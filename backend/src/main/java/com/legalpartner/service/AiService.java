package com.legalpartner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.model.dto.*;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.rag.*;
import com.legalpartner.repository.DocumentMetadataRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AiService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ChatLanguageModel chatModel;
    private final ChatLanguageModel jsonChatModel;
    private final QueryExpander queryExpander;
    private final ReRanker reRanker;
    private final CitationExtractor citationExtractor;
    private final ResponseValidator responseValidator;
    private final EncryptionService encryptionService;
    private final DocumentMetadataRepository documentRepository;
    private final ConversationStore conversationStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${legalpartner.rag.candidate-count:20}")
    private int candidateCount;

    @Value("${legalpartner.rag.top-k:5}")
    private int topK;

    @Value("${legalpartner.rag.context-max-chars:6000}")
    private int contextMaxChars;

    @Value("${legalpartner.rag.compare-context-chars:3000}")
    private int compareContextChars;

    @Value("${legalpartner.rag.retrieve-candidates:200}")
    private int retrieveCandidates;

    // Context budget parameters
    @Value("${legalpartner.context.model-window-tokens:8192}")
    private int modelWindowTokens;

    @Value("${legalpartner.context.answer-headroom-tokens:512}")
    private int answerHeadroomTokens;

    @Value("${legalpartner.context.system-prompt-tokens:250}")
    private int systemPromptTokens;

    // Conversation compression parameters
    @Value("${legalpartner.conversation.summarize-threshold-tokens:800}")
    private int summarizeThresholdTokens;

    @Value("${legalpartner.conversation.keep-verbatim-turns:2}")
    private int keepVerbatimTurns;

    @Value("${legalpartner.conversation.semantic-relevance-threshold:0.35}")
    private double semanticRelevanceThreshold;

    public AiService(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            ChatLanguageModel openAiChatModel,
            @Qualifier("jsonChatModel") ChatLanguageModel jsonChatModel,
            QueryExpander queryExpander,
            ReRanker reRanker,
            CitationExtractor citationExtractor,
            ResponseValidator responseValidator,
            EncryptionService encryptionService,
            DocumentMetadataRepository documentRepository,
            ConversationStore conversationStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.chatModel = openAiChatModel;
        this.jsonChatModel = jsonChatModel;
        this.queryExpander = queryExpander;
        this.reRanker = reRanker;
        this.citationExtractor = citationExtractor;
        this.responseValidator = responseValidator;
        this.encryptionService = encryptionService;
        this.documentRepository = documentRepository;
        this.conversationStore = conversationStore;
    }

    public QueryResult query(QueryRequest request, String username) {
        // Step 1: Embed ORIGINAL query first — used for both retrieval and semantic history pruning
        Embedding queryEmbedding = embeddingModel.embed(request.query()).content();

        // Step 2: Build smart history context (semantic pruning + rolling summary)
        String historyContext = buildHistoryContext(request.conversationId(), queryEmbedding.vector());
        int historyTokens = ConversationStore.estimateTokens(historyContext);

        // Step 3: Dynamic RAG context budget
        int queryTokens = ConversationStore.estimateTokens(request.query());
        int usedTokens = systemPromptTokens + historyTokens + queryTokens + answerHeadroomTokens;
        int remainingTokens = Math.max(500, modelWindowTokens - usedTokens);
        int contextBudgetChars = Math.min(contextMaxChars, remainingTokens * 4);

        log.debug("Context budget: {}t history, {}t query, {}t system → {}t remaining → {} chars for RAG",
                historyTokens, queryTokens, systemPromptTokens, remainingTokens, contextBudgetChars);

        // Step 4: Multi-query retrieval with deduplication
        String expanded = queryExpander.expand(request.query());
        List<EmbeddingMatch<TextSegment>> primaryCandidates =
                embeddingStore.findRelevant(queryEmbedding, candidateCount);
        List<EmbeddingMatch<TextSegment>> expandedCandidates = expanded.equals(request.query())
                ? List.of()
                : embeddingStore.findRelevant(embeddingModel.embed(expanded).content(), candidateCount / 2);
        List<EmbeddingMatch<TextSegment>> merged = mergeAndDeduplicate(primaryCandidates, expandedCandidates);

        // Matter-scoped filter: if matterId provided, keep only chunks from that matter's documents
        List<EmbeddingMatch<TextSegment>> scoped = merged;
        if (request.matterId() != null && !request.matterId().isBlank()) {
            Set<String> matterDocIds = new HashSet<>(
                    documentRepository.findIdStringsByMatterUuid(UUID.fromString(request.matterId())));
            if (!matterDocIds.isEmpty()) {
                scoped = merged.stream()
                        .filter(m -> matterDocIds.contains(m.embedded().metadata().getString("document_id")))
                        .toList();
                log.debug("Matter filter: {} → {} chunks from {} docs", merged.size(), scoped.size(), matterDocIds.size());
            }
        }
        List<EmbeddingMatch<TextSegment>> ranked = reRanker.rerank(scoped, request.query(), topK);

        // Step 5: Assemble RAG context within dynamic budget
        String ragContext = assembleContext(ranked, contextBudgetChars);

        // Step 6: Compose prompt — history block then RAG context then question
        String contextBlock = historyContext.isEmpty()
                ? ragContext
                : historyContext + "\n[Current Document Context]\n" + ragContext;
        String prompt = String.format(PromptTemplates.QUERY_USER, contextBlock, request.query());

        // Step 7: Generate
        AiMessage response = chatModel.generate(
                SystemMessage.from(PromptTemplates.QUERY_SYSTEM),
                UserMessage.from(prompt)
        ).content();

        String rawAnswer = response.text();
        JsonNode parsed = responseValidator.parseAndValidate(rawAnswer);

        String answer;
        List<String> keyClauses;
        if (parsed != null && parsed.has("answer")) {
            answer = parsed.get("answer").asText();
            keyClauses = new ArrayList<>();
            if (parsed.has("key_clauses")) {
                parsed.get("key_clauses").forEach(n -> keyClauses.add(n.asText()));
            }
        } else {
            answer = rawAnswer;
            keyClauses = List.of();
        }

        List<Citation> citations = citationExtractor.extract(answer, ranked);
        long verified = citations.stream().filter(Citation::verified).count();
        String confidence = responseValidator.calibrateConfidence(answer, ranked, verified, citations.size());
        List<String> warnings = responseValidator.checkFaithfulness(answer, ranked);

        // Step 8: Store turn with embedding, then maybe compress history
        String conversationId = (request.conversationId() != null && !request.conversationId().isBlank())
                ? request.conversationId()
                : UUID.randomUUID().toString();
        conversationStore.add(conversationId, request.query(), answer, queryEmbedding.vector());
        maybeCompressHistory(conversationId);

        return new QueryResult(answer, confidence, keyClauses, citations, warnings, conversationId);
    }

    public CompareResult compare(CompareRequest request, String username) {
        DocumentMetadata doc1 = documentRepository.findById(request.documentId1())
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + request.documentId1()));
        DocumentMetadata doc2 = documentRepository.findById(request.documentId2())
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + request.documentId2()));

        String context1 = truncateForModel(retrieveContextForDocument(doc1.getId()), compareContextChars);
        String context2 = truncateForModel(retrieveContextForDocument(doc2.getId()), compareContextChars);

        if (context1.isBlank() || context2.isBlank()) {
            String which;
            if (context1.isBlank() && context2.isBlank()) which = "Neither document has indexed content.";
            else if (context1.isBlank()) which = doc1.getFileName() + " has no indexed content.";
            else which = doc2.getFileName() + " has no indexed content.";
            return new CompareResult(List.of(new ComparisonDimension(
                    "Documents not ready", which,
                    "Go to Documents → re-upload and wait for INDEXED status.",
                    "neutral",
                    "Processing may still be running, or upload may have failed earlier."
            )));
        }

        String prompt = String.format(PromptTemplates.COMPARE_USER,
                doc1.getFileName(), context1, doc2.getFileName(), context2);

        AiMessage response = jsonChatModel.generate(
                SystemMessage.from(PromptTemplates.COMPARE_SYSTEM),
                UserMessage.from(prompt)
        ).content();

        List<ComparisonDimension> dimensions = parseCompareResponse(response.text());

        if (dimensions.isEmpty()) {
            log.warn("Compare produced no dimensions; raw length={}, preview={}",
                    response.text().length(),
                    response.text().substring(0, Math.min(200, response.text().length())).replace('\n', ' '));
            dimensions.add(new ComparisonDimension(
                    "Analysis", "Insufficient structured output from model.",
                    "Try comparing contracts with clearer clause structure.",
                    "neutral", "Model returned unstructured response."));
        }
        return new CompareResult(dimensions);
    }

    private static final java.util.Set<String> COMPARE_DIMENSIONS = java.util.Set.of(
            "liability", "indemnity", "termination", "confidentiality",
            "governing law", "force majeure", "ip rights");

    private List<ComparisonDimension> parseCompareResponse(String raw) {
        if (raw == null) return List.of();
        List<ComparisonDimension> results = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        for (String line : raw.split("\\r?\\n")) {
            line = line.trim();
            if (line.isBlank()) continue;
            String[] parts = line.split("\\|", -1);
            if (parts.length < 4) continue;

            String name = parts[0].trim();
            // Validate it's one of our 7 known dimensions (case-insensitive)
            if (!COMPARE_DIMENSIONS.contains(name.toLowerCase())) continue;
            if (!seen.add(name.toLowerCase())) continue; // dedup

            String doc1Summary  = parts[1].trim();
            String doc2Summary  = parts.length > 2 ? parts[2].trim() : "";
            String favorable    = parts.length > 3 ? parts[3].trim().toLowerCase() : "neutral";
            if (!favorable.matches("doc1|doc2|neutral")) favorable = "neutral";
            String reasoning    = parts.length > 4 ? parts[4].trim() : "";

            results.add(new ComparisonDimension(name, doc1Summary, doc2Summary, favorable, reasoning));
        }
        return results;
    }

    public RiskAssessmentResult assessRisk(UUID documentId, String username) {
        documentRepository.findById(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));

        String context = retrieveContextForDocument(documentId);

        if (context.isBlank()) {
            return new RiskAssessmentResult("UNKNOWN", List.of(new RiskCategory(
                    "Not Ready", "UNKNOWN",
                    "Document has not been indexed yet. Wait for INDEXED status.", ""
            )));
        }

        String prompt = String.format(PromptTemplates.RISK_USER, documentId, context);
        // Use plain chatModel — smaller models generate pipe-delimited text far more reliably than nested JSON
        AiMessage response = chatModel.generate(
                SystemMessage.from(PromptTemplates.RISK_SYSTEM),
                UserMessage.from(prompt)
        ).content();

        String rawText = response.text();
        log.info("Risk assessment raw response length={}, preview={}",
                rawText.length(), rawText.substring(0, Math.min(300, rawText.length())).replace('\n', ' '));

        return parseRiskLines(rawText);
    }

    public ExtractionResult extractKeyTerms(UUID documentId, String username) {
        documentRepository.findById(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));

        String context = retrieveContextForDocument(documentId);
        if (context.isBlank()) {
            return new ExtractionResult(null, null, null, null, null, null, null, null, null);
        }

        AiMessage response = chatModel.generate(
                SystemMessage.from(PromptTemplates.EXTRACTION_SYSTEM),
                UserMessage.from(String.format(PromptTemplates.EXTRACTION_USER, context))
        ).content();

        String rawText = response.text();
        log.info("Extraction raw response length={}, preview={}",
                rawText.length(), rawText.substring(0, Math.min(400, rawText.length())).replace('\n', ' '));
        return parseExtractionLines(rawText);
    }

    private static final java.util.regex.Pattern EXTRACTION_LINE =
            java.util.regex.Pattern.compile("^(PARTY_A|PARTY_B|EFFECTIVE_DATE|EXPIRY_DATE|CONTRACT_VALUE|LIABILITY_CAP|GOVERNING_LAW|NOTICE_PERIOD_DAYS|ARBITRATION_VENUE)\\s*:\\s*(.+)$",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private ExtractionResult parseExtractionLines(String raw) {
        Map<String, String> fields = new HashMap<>();
        for (String line : raw.split("\\r?\\n")) {
            java.util.regex.Matcher m = EXTRACTION_LINE.matcher(line.trim());
            if (!m.matches()) continue;
            String value = m.group(2).trim();
            if (!value.equalsIgnoreCase("null") && !value.isEmpty()) {
                fields.put(m.group(1).toUpperCase(), value);
            }
        }
        return new ExtractionResult(
                fields.get("PARTY_A"),
                fields.get("PARTY_B"),
                fields.get("EFFECTIVE_DATE"),
                fields.get("EXPIRY_DATE"),
                fields.get("CONTRACT_VALUE"),
                fields.get("LIABILITY_CAP"),
                fields.get("GOVERNING_LAW"),
                fields.get("NOTICE_PERIOD_DAYS"),
                fields.get("ARBITRATION_VENUE")
        );
    }

    // Maps prose label variants → canonical label used in parseRiskLines
    private static final java.util.regex.Pattern RISK_TOKEN =
            java.util.regex.Pattern.compile(
                    // Canonical underscored labels
                    "(OVERALL|LIABILITY|INDEMNITY|INDEMNIFICATION|TERMINATION" +
                    "|IP_RIGHTS|IP RIGHTS|INTELLECTUAL PROPERTY" +
                    "|CONFIDENTIALITY|CONFIDENTIAL" +
                    "|GOVERNING_LAW|GOVERNING LAW" +
                    "|FORCE_MAJEURE|FORCE MAJEURE)" +
                    "(?:[^:\\n]{0,50})?:\\s*(HIGH|MEDIUM|LOW)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    // Optional pipe-delimited justification/ref after rating
    private static final java.util.regex.Pattern PIPE_PARTS =
            java.util.regex.Pattern.compile("[^|]*\\|\\s*(.+?)\\s*\\|\\s*(.+)$");

    private RiskAssessmentResult parseRiskLines(String raw) {
        String overallRisk = "MEDIUM";
        List<RiskCategory> categories = new ArrayList<>();
        // Seen labels (dedup — model sometimes repeats labels)
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();

        // Flatten to single line for global scan, then also try per-line for pipe-format
        String flat = raw.replace('\n', ' ').replace('\r', ' ');
        java.util.regex.Matcher m = RISK_TOKEN.matcher(flat);

        // Collect all (label, rating, startPos, endPos)
        record Hit(String label, String rating, int end) {}
        List<Hit> hits = new ArrayList<>();
        while (m.find()) {
            hits.add(new Hit(m.group(1).toUpperCase(), m.group(2).toUpperCase(), m.end()));
        }

        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            // Normalise for dedup (e.g. INDEMNIFICATION == INDEMNITY)
            String dedupKey = switch (hit.label().toUpperCase().replace(' ', '_')) {
                case "INDEMNIFICATION" -> "INDEMNITY";
                case "IP_RIGHTS", "INTELLECTUAL_PROPERTY" -> "IP_RIGHTS";
                case "GOVERNING_LAW" -> "GOVERNING_LAW";
                case "FORCE_MAJEURE" -> "FORCE_MAJEURE";
                case "CONFIDENTIAL" -> "CONFIDENTIALITY";
                default -> hit.label().toUpperCase().replace(' ', '_');
            };
            if (!seen.add(dedupKey)) continue; // skip duplicate labels

            // Text between this hit's end and the next hit's start = possible justification
            int nextStart = (i + 1 < hits.size())
                    ? flat.lastIndexOf(hits.get(i + 1).label(), flat.length()) // rough
                    : flat.length();
            // Get the text after the rating up to ~120 chars or next label
            String after = flat.substring(hit.end(), Math.min(hit.end() + 150, flat.length())).trim();

            // Try to find pipe-separated parts in "after"
            java.util.regex.Matcher pm = PIPE_PARTS.matcher(after);
            String justification = "";
            String ref = "";
            if (pm.find()) {
                justification = pm.group(1).trim();
                ref = pm.group(2).trim();
            } else {
                // Clean up "after" — strip leading connector words and section refs
                // e.g. "RATING HIGH MISSING: LOW" → take text before next label keyword
                String clean = after.replaceAll("(?i)\\s*(OVERALL|LIABILITY|INDEMNITY|TERMINATION|IP_RIGHTS|CONFIDENTIALITY|GOVERNING_LAW|FORCE_MAJEURE).*", "").trim();
                // Look for section reference pattern
                java.util.regex.Matcher secMatcher = java.util.regex.Pattern
                        .compile("(?i)(section|clause|article)\\s*[\\d.]+")
                        .matcher(clean);
                if (secMatcher.find()) {
                    ref = secMatcher.group().trim();
                    justification = clean.substring(0, secMatcher.start()).trim();
                } else if (clean.length() > 3) {
                    justification = clean;
                }
                if (ref.isBlank()) ref = "See contract";
            }

            // Normalise label variants to canonical form
            String canonicalLabel = switch (hit.label().toUpperCase().replace(' ', '_')) {
                case "INDEMNIFICATION"   -> "INDEMNITY";
                case "IP_RIGHTS",
                     "INTELLECTUAL_PROPERTY" -> "IP_RIGHTS";
                case "GOVERNING_LAW"    -> "GOVERNING_LAW";
                case "FORCE_MAJEURE"    -> "FORCE_MAJEURE";
                case "CONFIDENTIAL"     -> "CONFIDENTIALITY";
                default                 -> hit.label().toUpperCase().replace(' ', '_');
            };

            if ("OVERALL".equals(canonicalLabel)) {
                overallRisk = hit.rating();
            } else {
                String name = switch (canonicalLabel) {
                    case "IP_RIGHTS"      -> "IP Rights";
                    case "GOVERNING_LAW"  -> "Governing Law";
                    case "FORCE_MAJEURE"  -> "Force Majeure";
                    case "INDEMNITY"      -> "Indemnity";
                    case "CONFIDENTIALITY"-> "Confidentiality";
                    case "TERMINATION"    -> "Termination";
                    case "LIABILITY"      -> "Liability";
                    default -> canonicalLabel.charAt(0) + canonicalLabel.substring(1).toLowerCase();
                };
                categories.add(new RiskCategory(name, hit.rating(),
                        justification.isBlank() ? "See contract for details." : justification, ref));
            }
        }

        // If we got < 3 categories, try a proximity fallback: scan sentences for
        // category keyword + risk level within a ~250-char window.
        if (categories.size() < 3) {
            log.info("Risk token scan found {} categories — trying proximity fallback", categories.size());
            proximityFallbackScan(flat, seen, categories);
        }

        if (categories.isEmpty()) {
            log.warn("Risk assessment: could not parse any labels from response");
        } else {
            log.info("Risk assessment parsed: overall={}, categories={}", overallRisk, categories.size());
        }

        return new RiskAssessmentResult(overallRisk, categories);
    }

    // Maps prose keywords → canonical category labels
    private static final java.util.Map<String, String> PROXIMITY_KEYWORDS = java.util.Map.of(
        "liabilit",       "LIABILITY",
        "indemnit",       "INDEMNITY",
        "terminat",       "TERMINATION",
        "intellectual",   "IP_RIGHTS",
        "ip rights",      "IP_RIGHTS",
        "confidential",   "CONFIDENTIALITY",
        "governing law",  "GOVERNING_LAW",
        "force majeure",  "FORCE_MAJEURE"
    );

    private static final java.util.regex.Pattern PROX_RATING =
            java.util.regex.Pattern.compile("\\b(HIGH|MEDIUM|LOW)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    private void proximityFallbackScan(String flat, java.util.Set<String> seen, List<RiskCategory> categories) {
        // Split into sentence-like segments (~250 chars around each period/newline)
        String[] segments = flat.split("(?<=[.!?])\\s+|\\s{2,}");
        for (String seg : segments) {
            String lower = seg.toLowerCase();
            for (var entry : PROXIMITY_KEYWORDS.entrySet()) {
                String keyword = entry.getKey();
                String canonical = entry.getValue();
                if (!lower.contains(keyword)) continue;
                if (!seen.add(canonical)) continue; // already have this label

                java.util.regex.Matcher rm = PROX_RATING.matcher(seg);
                if (!rm.find()) continue;
                String rating = rm.group(1).toUpperCase();

                // Extract trailing text as justification (strip the keyword lead-in)
                int kwIdx = lower.indexOf(keyword);
                String justification = seg.substring(Math.max(0, kwIdx)).trim();
                if (justification.length() > 150) justification = justification.substring(0, 150).trim();

                String name = switch (canonical) {
                    case "IP_RIGHTS"      -> "IP Rights";
                    case "GOVERNING_LAW"  -> "Governing Law";
                    case "FORCE_MAJEURE"  -> "Force Majeure";
                    default -> canonical.charAt(0) + canonical.substring(1).toLowerCase();
                };
                categories.add(new RiskCategory(name, rating,
                        justification.isBlank() ? "See contract for details." : justification,
                        "See contract"));
            }
        }
    }

    public RefineClauseResponse refineClause(RefineClauseRequest request, String username) {
        String context = request.getDocumentContext() != null && !request.getDocumentContext().isBlank()
                ? request.getDocumentContext().substring(0, Math.min(3000, request.getDocumentContext().length()))
                : "(No surrounding context provided)";
        String instruction = request.getInstruction() != null
                ? request.getInstruction()
                : "Improve for clarity and legal precision.";

        String prompt = String.format(PromptTemplates.REFINE_CLAUSE_USER,
                context, request.getSelectedText(), instruction);
        AiMessage response = jsonChatModel.generate(
                SystemMessage.from(PromptTemplates.REFINE_CLAUSE_SYSTEM),
                UserMessage.from(prompt)
        ).content();

        return parseRefineResponse(response.text());
    }

    private static final java.util.regex.Pattern REFINE_IMPROVED =
            java.util.regex.Pattern.compile("(?:^|\\n)IMPROVED:\\s*(.+)", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern REFINE_REASONING =
            java.util.regex.Pattern.compile("(?:^|\\n)REASONING:\\s*(.+)", java.util.regex.Pattern.CASE_INSENSITIVE);

    private RefineClauseResponse parseRefineResponse(String raw) {
        if (raw == null) raw = "";
        // Strip any CSS / HTML that leaked in (template styles sometimes injected)
        String cleaned = raw.replaceAll("(?s)<style[^>]*>.*?</style>", "")
                            .replaceAll("<[^>]+>", "")
                            .trim();

        java.util.regex.Matcher im = REFINE_IMPROVED.matcher(cleaned);
        java.util.regex.Matcher rm = REFINE_REASONING.matcher(cleaned);

        String improvedText = im.find() ? im.group(1).trim() : null;
        String reasoning    = rm.find() ? rm.group(1).trim() : null;

        // Fallback: try JSON "improved_text" field
        if (improvedText == null) {
            JsonNode parsed = responseValidator.parseAndValidate(cleaned);
            if (parsed != null && parsed.has("improved_text")) {
                improvedText = parsed.path("improved_text").asText(null);
                reasoning    = parsed.has("reasoning") ? parsed.path("reasoning").asText(null) : null;
            }
        }

        // Last resort: return whatever text was generated, stripped of obvious noise
        if (improvedText == null || improvedText.isBlank()) {
            // Remove lines that look like system prompt echo or CSS
            String stripped = Arrays.stream(cleaned.split("\\n"))
                    .filter(l -> !l.matches("(?i).*\\{.*\\}.*") && !l.isBlank())
                    .collect(Collectors.joining(" "))
                    .trim();
            improvedText = stripped.isBlank() ? raw.trim() : stripped;
            reasoning    = "Model returned unstructured response — text extracted as-is.";
            log.warn("Refine clause: could not parse IMPROVED/REASONING labels from response length={}", raw.length());
        }

        return RefineClauseResponse.builder()
                .improvedText(improvedText)
                .reasoning(reasoning)
                .build();
    }

    // ── Context assembly ───────────────────────────────────────────────────────

    private String assembleContext(List<EmbeddingMatch<TextSegment>> matches, int maxChars) {
        StringBuilder sb = new StringBuilder();
        int totalChars = 0;

        // Sort by document order within each document (chunk_index), group by document
        List<EmbeddingMatch<TextSegment>> ordered = matches.stream()
                .sorted(Comparator.comparing(m -> {
                    String idx = m.embedded().metadata().getString("chunk_index");
                    return idx != null ? Integer.parseInt(idx) : 0;
                }))
                .toList();

        for (EmbeddingMatch<TextSegment> m : ordered) {
            String decrypted;
            try {
                decrypted = encryptionService.decrypt(m.embedded().text());
            } catch (Exception e) {
                log.debug("Decryption fallback for chunk");
                decrypted = m.embedded().text();
            }
            String fileName = m.embedded().metadata().getString("file_name");
            String section = m.embedded().metadata().getString("section_path");
            String block = String.format("[Source: %s | %s]\n%s\n\n---\n\n",
                    fileName != null ? fileName : "Unknown",
                    section != null ? section : "",
                    decrypted);

            if (totalChars + block.length() > maxChars) {
                int remaining = maxChars - totalChars;
                if (remaining > 100) sb.append(block, 0, remaining).append("\n[...truncated]");
                break;
            }
            sb.append(block);
            totalChars += block.length();
        }
        return sb.toString();
    }

    private String retrieveContextForDocument(UUID docId) {
        List<String> queries = List.of(
                "liability termination indemnity confidentiality governing law",
                "contract agreement obligations breach consideration sections",
                "warranties representations covenants conditions",
                "payment fees schedule annexure"
        );
        Set<String> seen = new HashSet<>();
        List<EmbeddingMatch<TextSegment>> forDoc = new ArrayList<>();

        for (String searchQuery : queries) {
            if (forDoc.size() >= 8) break;
            Embedding embedding = embeddingModel.embed(searchQuery).content();
            List<EmbeddingMatch<TextSegment>> all = embeddingStore.findRelevant(embedding, retrieveCandidates);
            for (EmbeddingMatch<TextSegment> match : all) {
                if (!docId.toString().equals(match.embedded().metadata().getString("document_id"))) continue;
                String key = match.embedded().metadata().getString("chunk_index")
                        + ":" + match.embedded().text().hashCode();
                if (seen.add(key)) {
                    forDoc.add(match);
                    if (forDoc.size() >= 8) break;
                }
            }
        }
        return assembleContext(forDoc.stream().limit(8).toList(), compareContextChars);
    }

    // ── Conversation history management ───────────────────────────────────────

    /**
     * Build the history context block for the current query.
     * Includes rolling summary (if exists) + semantically relevant verbatim turns.
     */
    private String buildHistoryContext(String conversationId, float[] currentQueryEmbedding) {
        if (conversationId == null || conversationId.isBlank()) return "";
        ConversationStore.ConversationSession session = conversationStore.getSession(conversationId);
        if (session == null) return "";

        StringBuilder sb = new StringBuilder();

        // Include rolling summary first (most compressed representation of older history)
        if (session.summary() != null && !session.summary().isBlank()) {
            sb.append("[Session Summary]\n").append(session.summary()).append("\n\n");
        }

        // Include semantically relevant verbatim turns
        List<ConversationStore.ConversationTurn> turns = session.allTurns();
        if (!turns.isEmpty()) {
            List<ConversationStore.ConversationTurn> relevant = filterRelevantTurns(turns, currentQueryEmbedding);
            if (!relevant.isEmpty()) {
                sb.append("[Recent Exchanges]\n");
                for (ConversationStore.ConversationTurn turn : relevant) {
                    sb.append("Q: ").append(turn.question()).append("\n");
                    sb.append("A: ").append(turn.answer()).append("\n\n");
                }
            }
        }

        return sb.toString().trim();
    }

    /**
     * Filter turns by semantic relevance to the current query.
     * Always keeps the most recent turn (immediate context).
     * For older turns: only include if cosine similarity >= threshold.
     */
    private List<ConversationStore.ConversationTurn> filterRelevantTurns(
            List<ConversationStore.ConversationTurn> turns,
            float[] currentQueryEmbedding
    ) {
        if (turns.isEmpty()) return List.of();

        List<ConversationStore.ConversationTurn> result = new ArrayList<>();
        for (int i = 0; i < turns.size(); i++) {
            ConversationStore.ConversationTurn turn = turns.get(i);
            boolean isLastTurn = (i == turns.size() - 1);

            if (isLastTurn) {
                // Always include the immediately preceding turn for conversational coherence
                result.add(turn);
            } else if (turn.questionEmbedding() != null && currentQueryEmbedding != null) {
                double similarity = cosineSimilarity(currentQueryEmbedding, turn.questionEmbedding());
                if (similarity >= semanticRelevanceThreshold) {
                    result.add(turn);
                }
                log.debug("Turn similarity: {} — {}", similarity,
                        turn.question().substring(0, Math.min(50, turn.question().length())));
            } else {
                // No embedding available: include all turns (safe fallback)
                result.add(turn);
            }
        }
        return result;
    }

    /**
     * Trigger rolling summarization if total history tokens exceed the threshold.
     * Compresses all-but-last-N verbatim turns into a merged summary.
     * Called synchronously after each turn so next turn benefits immediately.
     */
    private void maybeCompressHistory(String conversationId) {
        int totalTokens = conversationStore.getTotalHistoryTokens(conversationId);
        if (totalTokens <= summarizeThresholdTokens) return;

        ConversationStore.ConversationSession session = conversationStore.getSession(conversationId);
        if (session == null) return;

        List<ConversationStore.ConversationTurn> allTurns = session.allTurns();
        if (allTurns.size() <= keepVerbatimTurns) return; // not enough turns to compress

        // Turns to summarize: everything except the last keepVerbatimTurns
        List<ConversationStore.ConversationTurn> toSummarize =
                allTurns.subList(0, allTurns.size() - keepVerbatimTurns);

        log.info("Compressing conversation {}: summarizing {} turns (keeping {} verbatim), total was {}t",
                conversationId, toSummarize.size(), keepVerbatimTurns, totalTokens);

        String newSummary = summarizeTurns(toSummarize, session.summary());
        if (newSummary != null && !newSummary.isBlank()) {
            conversationStore.compressHistory(conversationId, newSummary, keepVerbatimTurns);
            log.info("Compressed conversation {}: new summary ~{}t",
                    conversationId, ConversationStore.estimateTokens(newSummary));
        }
    }

    /**
     * Call the LLM to produce a rolling merged summary of conversation turns.
     * Merges with any existing summary so no information is lost.
     */
    private String summarizeTurns(List<ConversationStore.ConversationTurn> turns, String existingSummary) {
        StringBuilder content = new StringBuilder();
        if (existingSummary != null && !existingSummary.isBlank()) {
            content.append("[Previous Summary]\n").append(existingSummary).append("\n\n");
        }
        content.append("[Exchanges to Summarize]\n");
        for (ConversationStore.ConversationTurn t : turns) {
            content.append("Q: ").append(t.question()).append("\n");
            content.append("A: ").append(t.answer()).append("\n\n");
        }
        try {
            AiMessage summary = chatModel.generate(
                    SystemMessage.from(PromptTemplates.SUMMARY_SYSTEM),
                    UserMessage.from(String.format(PromptTemplates.SUMMARY_USER, content))
            ).content();
            return summary.text().trim();
        } catch (Exception e) {
            log.warn("Failed to summarize conversation history, keeping existing: {}", e.getMessage());
            return existingSummary;
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private List<EmbeddingMatch<TextSegment>> mergeAndDeduplicate(
            List<EmbeddingMatch<TextSegment>> primary,
            List<EmbeddingMatch<TextSegment>> secondary
    ) {
        Set<String> seen = new HashSet<>();
        List<EmbeddingMatch<TextSegment>> merged = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> m : primary) {
            if (seen.add(chunkKey(m))) merged.add(m);
        }
        for (EmbeddingMatch<TextSegment> m : secondary) {
            if (seen.add(chunkKey(m))) merged.add(m);
        }
        return merged;
    }

    private String chunkKey(EmbeddingMatch<TextSegment> m) {
        String docId = m.embedded().metadata().getString("document_id");
        String idx = m.embedded().metadata().getString("chunk_index");
        return (docId != null ? docId : "") + ":" + (idx != null ? idx : m.embedded().text().hashCode());
    }

    private String truncateForModel(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text != null ? text : "";
        return text.substring(0, maxChars) + "\n[...truncated]";
    }

    /** Cosine similarity between two float vectors. Returns 0.0 on null/length mismatch. */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom < 1e-10 ? 0.0 : dot / denom;
    }
}
