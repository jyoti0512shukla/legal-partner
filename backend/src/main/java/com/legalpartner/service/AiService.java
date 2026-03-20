package com.legalpartner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.config.LegalSystemConfig;
import com.legalpartner.model.dto.*;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.rag.*;
import com.legalpartner.rag.DocumentFullTextRetriever;
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
    private final DocumentFullTextRetriever fullTextRetriever;
    private final VllmGuidedClient vllmClient;
    private final LegalSystemConfig legalSystemConfig;
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
            ConversationStore conversationStore,
            DocumentFullTextRetriever fullTextRetriever,
            VllmGuidedClient vllmClient,
            LegalSystemConfig legalSystemConfig) {
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
        this.fullTextRetriever = fullTextRetriever;
        this.vllmClient = vllmClient;
        this.legalSystemConfig = legalSystemConfig;
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

        // Step 7: Generate — merge system into user (Mistral/SaulLM rejects separate system role)
        AiMessage response = chatModel.generate(
                UserMessage.from(legalSystemConfig.localize(PromptTemplates.QUERY_SYSTEM) + "\n\n" + prompt)
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

        // Use plain chatModel — compare prompt expects pipe-delimited text, not JSON
        AiMessage response = chatModel.generate(
                UserMessage.from(legalSystemConfig.localize(PromptTemplates.COMPARE_SYSTEM) + "\n\n" + prompt)
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

        // Full document context — not just top-K chunks. A missing clause (e.g. no
        // force majeure) can only be detected by seeing the whole contract.
        String context = fullTextRetriever.retrieveFullText(documentId);

        if (context.isBlank()) {
            return new RiskAssessmentResult("UNKNOWN", List.of(new RiskCategory(
                    "Not Ready", "UNKNOWN",
                    "Document has not been indexed yet. Wait for INDEXED status.", ""
            )));
        }

        String guidedPrompt = String.format(PromptTemplates.RISK_USER_GUIDED, context);

        // Primary: guided_json — vLLM+Outlines physically constrains tokens to schema.
        com.fasterxml.jackson.databind.JsonNode json = vllmClient.generateStructured(
                legalSystemConfig.localize(PromptTemplates.RISK_SYSTEM_GUIDED), guidedPrompt, StructuredSchemas.RISK_SCHEMA, 800);

        log.info("[prompt={}] guided_json node: {}",
                PromptTemplates.PROMPT_VERSION,
                json.has("overall_risk") ? "overall_risk+categories" : (json.size() > 0 ? json.fieldNames().next() : "empty"));

        RiskAssessmentResult guidedResult = parseRiskJson(json);
        if (!guidedResult.categories().isEmpty()) return guidedResult;

        // guided_json returned empty — fall back to raw-completions CSV
        log.info("[prompt={}] guided_json returned no categories — falling back to CSV completions", PromptTemplates.PROMPT_VERSION);
        String csvPrompt = String.format(PromptTemplates.RISK_USER, documentId, context);
        String rawText = stripResponsePrefix(
                vllmClient.generateText(legalSystemConfig.localize(PromptTemplates.RISK_SYSTEM), csvPrompt, "OVERALL=", 150));
        log.info("[prompt={}] CSV fallback raw length={}, preview={}", PromptTemplates.PROMPT_VERSION,
                rawText.length(), rawText.substring(0, Math.min(200, rawText.length())).replace('\n', ' '));
        RiskAssessmentResult csvResult = parseRiskCsv(rawText);
        if (!csvResult.categories().isEmpty()) return csvResult;

        RiskAssessmentResult lineResult = parseRiskLines(rawText);
        if (!lineResult.categories().isEmpty()) return lineResult;

        // Last resort: ask for prose analysis and run through proximity scanner
        log.info("[prompt={}] all structured formats failed — trying prose fallback", PromptTemplates.PROMPT_VERSION);
        String prosePrompt = "Analyze the risk in this contract. For each of these categories — " +
                "Liability, Indemnity, Termination, IP Rights, Confidentiality, Governing Law, Force Majeure — " +
                "state whether the risk is HIGH, MEDIUM, or LOW and briefly explain why.\n\nContract:\n" + context;
        String prose = stripResponsePrefix(vllmClient.generateProse(legalSystemConfig.localize(PromptTemplates.RISK_SYSTEM), prosePrompt, 400));
        log.info("[prompt={}] prose fallback length={}, preview={}", PromptTemplates.PROMPT_VERSION,
                prose.length(), prose.substring(0, Math.min(200, prose.length())).replace('\n', ' '));
        return parseRiskLines(prose);
    }

    // Maps LABEL= keys to display names
    private static final java.util.Map<String, String> RISK_LABEL_NAMES = java.util.Map.of(
            "OVERALL",         "Overall",
            "LIABILITY",       "Liability",
            "INDEMNITY",       "Indemnity",
            "TERMINATION",     "Termination",
            "IP_RIGHTS",       "IP Rights",
            "CONFIDENTIALITY", "Confidentiality",
            "GOVERNING_LAW",   "Governing Law",
            "FORCE_MAJEURE",   "Force Majeure"
    );

    /** Parse guided_json response: {"overall_risk":"HIGH","categories":[{"name":"...","rating":"HIGH",...}]} */
    private RiskAssessmentResult parseRiskJson(com.fasterxml.jackson.databind.JsonNode root) {
        if (root == null || root.isMissingNode() || !root.has("overall_risk")) {
            return new RiskAssessmentResult("MEDIUM", List.of());
        }
        String overallRisk = root.path("overall_risk").asText("MEDIUM");
        List<RiskCategory> categories = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode cat : root.path("categories")) {
            categories.add(new RiskCategory(
                    cat.path("name").asText("Unknown"),
                    cat.path("rating").asText("MEDIUM"),
                    cat.path("justification").asText(""),
                    cat.path("section_ref").asText("See contract")
            ));
        }
        log.info("[prompt={}] guided_json parsed: overall={}, categories={}", PromptTemplates.PROMPT_VERSION, overallRisk, categories.size());
        return new RiskAssessmentResult(overallRisk, categories);
    }

    /** Parse CSV format: OVERALL=HIGH,LIABILITY=MEDIUM,... */
    private RiskAssessmentResult parseRiskCsv(String raw) {
        if (raw == null || raw.isBlank()) return new RiskAssessmentResult("MEDIUM", List.of());
        String overallRisk = "MEDIUM";
        List<RiskCategory> categories = new ArrayList<>();

        // Find the CSV line — could have prose before/after
        java.util.regex.Matcher csvLine = java.util.regex.Pattern
                .compile("OVERALL=[A-Z]+(,[A-Z_]+=[A-Z]+)+", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(raw);
        String csv = csvLine.find() ? csvLine.group() : raw;

        for (String entry : csv.split(",")) {
            String[] kv = entry.trim().split("=", 2);
            if (kv.length < 2) continue;
            String key = kv[0].trim().toUpperCase().replace(' ', '_');
            String val = kv[1].trim().toUpperCase();
            if (!val.matches("HIGH|MEDIUM|LOW")) continue;
            if ("OVERALL".equals(key)) {
                overallRisk = val;
            } else if (RISK_LABEL_NAMES.containsKey(key)) {
                categories.add(new RiskCategory(RISK_LABEL_NAMES.get(key), val, "", "See contract"));
            }
        }
        if (!categories.isEmpty()) {
            log.info("CSV risk parser: overall={}, categories={}", overallRisk, categories.size());
        }
        return new RiskAssessmentResult(overallRisk, categories);
    }

    public ExtractionResult extractKeyTerms(UUID documentId, String username) {
        documentRepository.findById(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));

        // Full document — party names, dates, values can appear anywhere in the contract
        String context = fullTextRetriever.retrieveFullText(documentId);
        if (context.isBlank()) {
            return new ExtractionResult(null, null, null, null, null, null, null, null, null);
        }

        AiMessage response = chatModel.generate(
                UserMessage.from(legalSystemConfig.localize(PromptTemplates.EXTRACTION_SYSTEM) + "\n\n" + String.format(PromptTemplates.EXTRACTION_USER, context))
        ).content();

        String rawText = stripResponsePrefix(response.text());
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

    // Prose phrases that imply HIGH risk when no explicit rating found (model often skips the word)
    private static final List<String> HIGH_RISK_PHRASES = List.of(
        "no ", "not found", "not defined", "not present", "missing", "absent",
        "no cap", "unlimited", "one-sided", "unilateral", "undefined", "does not",
        "no provision", "not addressed"
    );
    // Prose phrases that imply LOW risk
    private static final List<String> LOW_RISK_PHRASES = List.of(
        "clear", "balanced", "mutual", "standard", "well-defined", "explicitly",
        "both parties", "adequate", "comprehensive"
    );

    private void proximityFallbackScan(String flat, java.util.Set<String> seen, List<RiskCategory> categories) {
        String[] segments = flat.split("(?<=[.!?])\\s+|\\n|\\d+\\.\\s+");
        for (String seg : segments) {
            String lower = seg.toLowerCase();
            for (var entry : PROXIMITY_KEYWORDS.entrySet()) {
                String keyword = entry.getKey();
                String canonical = entry.getValue();
                if (!lower.contains(keyword)) continue;
                if (!seen.add(canonical)) continue;

                // Try explicit HIGH/MEDIUM/LOW first
                java.util.regex.Matcher rm = PROX_RATING.matcher(seg);
                String rating = rm.find() ? rm.group(1).toUpperCase() : null;

                // If no explicit rating, infer from prose
                if (rating == null) {
                    boolean impliesHigh = HIGH_RISK_PHRASES.stream().anyMatch(lower::contains);
                    boolean impliesLow  = LOW_RISK_PHRASES.stream().anyMatch(lower::contains);
                    if (impliesHigh) rating = "HIGH";
                    else if (impliesLow) rating = "LOW";
                    else rating = "MEDIUM"; // default for known category without rating
                }

                String justification = seg.trim();
                if (justification.length() > 150) justification = justification.substring(0, 150).trim();

                String name = switch (canonical) {
                    case "IP_RIGHTS"       -> "IP Rights";
                    case "GOVERNING_LAW"   -> "Governing Law";
                    case "FORCE_MAJEURE"   -> "Force Majeure";
                    case "CONFIDENTIALITY" -> "Confidentiality";
                    case "TERMINATION"     -> "Termination";
                    case "INDEMNITY"       -> "Indemnity";
                    case "LIABILITY"       -> "Liability";
                    default -> canonical.charAt(0) + canonical.substring(1).toLowerCase();
                };
                categories.add(new RiskCategory(name, rating,
                        justification.isBlank() ? "See contract for details." : justification,
                        "See contract"));
            }
        }
    }

    /**
     * Strip the "Response:" prefix that AALAP/Mistral models add automatically.
     * Also strips [/INST] echoes that appear when contract text confuses the template.
     */
    private String stripResponsePrefix(String raw) {
        if (raw == null) return "";
        // Remove everything up to and including [/INST] if present (prompt leak)
        int instEnd = raw.lastIndexOf("[/INST]");
        if (instEnd >= 0) raw = raw.substring(instEnd + 7);
        // Strip leading "Response:" or "Response :" with optional whitespace
        return raw.replaceAll("(?i)^\\s*Response\\s*:\\s*", "").trim();
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
                UserMessage.from(legalSystemConfig.localize(PromptTemplates.REFINE_CLAUSE_SYSTEM) + "\n\n" + prompt)
        ).content();

        return parseRefineResponse(response.text());
    }

    private static final java.util.regex.Pattern REFINE_IMPROVED =
            java.util.regex.Pattern.compile("(?:^|\\n)IMPROVED:\\s*(.+)", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern REFINE_REASONING =
            java.util.regex.Pattern.compile("(?:^|\\n)REASONING:\\s*(.+)", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern DRILLDOWN_RISK =
            java.util.regex.Pattern.compile("(?:^|\\n)RISK:\\s*(.+?)(?=\\nIMPACT:|\\nFIX:|\\nLANGUAGE:|$)", java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern DRILLDOWN_IMPACT =
            java.util.regex.Pattern.compile("(?:^|\\n)IMPACT:\\s*(.+?)(?=\\nFIX:|\\nLANGUAGE:|$)", java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern DRILLDOWN_FIX =
            java.util.regex.Pattern.compile("(?:^|\\n)FIX:\\s*(.+?)(?=\\nLANGUAGE:|$)", java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern DRILLDOWN_LANGUAGE =
            java.util.regex.Pattern.compile("(?:^|\\n)LANGUAGE:\\s*(.+)", java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);

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

    public RiskDrilldownResult riskDrilldown(UUID documentId, RiskDrilldownRequest request, String username) {
        String context = fullTextRetriever.retrieveFullText(documentId);
        if (context.isBlank()) {
            return new RiskDrilldownResult(request.categoryName(), request.rating(),
                    "Document has not been indexed yet.", null, null, null);
        }
        // Cap context — drilldown is focused, doesn't need the full document
        if (context.length() > 5000) context = context.substring(0, 5000);

        String prompt = String.format(PromptTemplates.RISK_DRILLDOWN_USER,
                context,
                request.categoryName(),
                request.rating(),
                request.justification() != null ? request.justification() : "No initial justification.",
                request.sectionRef() != null && !request.sectionRef().isBlank() ? request.sectionRef() : "Not found");

        AiMessage response = chatModel.generate(
                UserMessage.from(legalSystemConfig.localize(PromptTemplates.RISK_DRILLDOWN_SYSTEM) + "\n\n" + prompt)
        ).content();

        return parseDrilldownResponse(request.categoryName(), request.rating(), response.text());
    }

    private RiskDrilldownResult parseDrilldownResponse(String categoryName, String rating, String raw) {
        if (raw == null) raw = "";
        String cleaned = raw.replaceAll("(?s)<style[^>]*>.*?</style>", "").replaceAll("<[^>]+>", "").trim();

        java.util.regex.Matcher rm = DRILLDOWN_RISK.matcher(cleaned);
        java.util.regex.Matcher im = DRILLDOWN_IMPACT.matcher(cleaned);
        java.util.regex.Matcher fm = DRILLDOWN_FIX.matcher(cleaned);
        java.util.regex.Matcher lm = DRILLDOWN_LANGUAGE.matcher(cleaned);

        String detailedRisk     = rm.find() ? rm.group(1).trim() : null;
        String businessImpact   = im.find() ? im.group(1).trim() : null;
        String howToFix         = fm.find() ? fm.group(1).trim() : null;
        String suggestedLanguage = lm.find() ? lm.group(1).trim() : null;

        if (detailedRisk == null || detailedRisk.isBlank()) {
            log.warn("Risk drilldown: could not parse structured response for {}", categoryName);
            detailedRisk = cleaned.isBlank() ? "Analysis unavailable." : cleaned;
        }

        return new RiskDrilldownResult(categoryName, rating, detailedRisk, businessImpact, howToFix, suggestedLanguage);
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
                    UserMessage.from(legalSystemConfig.localize(PromptTemplates.SUMMARY_SYSTEM) + "\n\n" + String.format(PromptTemplates.SUMMARY_USER, content))
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

    // ── Workflow step: Executive Summary ──────────────────────────────────────

    public ExecutiveSummaryResult generateWorkflowSummary(UUID documentId, Map<String, Object> priorResults, String username) {
        return generateWorkflowSummary(documentId, priorResults, username, null);
    }

    public ExecutiveSummaryResult generateWorkflowSummary(UUID documentId, Map<String, Object> priorResults, String username, String draftedText) {
        if (documentId != null) {
            documentRepository.findById(documentId)
                    .orElseThrow(() -> new java.util.NoSuchElementException("Document not found: " + documentId));
        }

        String priorJson;
        try {
            priorJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(priorResults);
        } catch (Exception e) {
            priorJson = priorResults.toString();
        }
        // Limit to avoid exceeding context window
        if (priorJson.length() > 6000) priorJson = priorJson.substring(0, 6000) + "\n[...truncated]";

        String userPrompt = String.format(PromptTemplates.SUMMARY_USER_GUIDED, priorJson);
        com.fasterxml.jackson.databind.JsonNode json = vllmClient.generateStructured(
                legalSystemConfig.localize(PromptTemplates.SUMMARY_SYSTEM_GUIDED), userPrompt, StructuredSchemas.SUMMARY_SCHEMA, 800);

        return parseSummaryJson(json);
    }

    private ExecutiveSummaryResult parseSummaryJson(com.fasterxml.jackson.databind.JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return ExecutiveSummaryResult.builder()
                    .executiveSummary("Summary could not be generated.")
                    .overallRisk("MEDIUM")
                    .topConcerns(List.of())
                    .recommendations(List.of())
                    .redFlags(List.of())
                    .build();
        }
        List<String> topConcerns = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        List<String> redFlags = new ArrayList<>();
        root.path("top_concerns").forEach(n -> topConcerns.add(n.asText()));
        root.path("recommendations").forEach(n -> recommendations.add(n.asText()));
        root.path("red_flags").forEach(n -> redFlags.add(n.asText()));
        return ExecutiveSummaryResult.builder()
                .executiveSummary(root.path("executive_summary").asText(""))
                .overallRisk(root.path("overall_risk").asText("MEDIUM"))
                .topConcerns(topConcerns)
                .recommendations(recommendations)
                .redFlags(redFlags)
                .build();
    }

    // ── Workflow step: Redline Suggestions ────────────────────────────────────

    public RedlineSuggestionsResult generateRedlines(UUID documentId, Map<String, Object> priorResults, String username) {
        documentRepository.findById(documentId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Document not found: " + documentId));

        // Build clause issues list from prior checklist results (if available)
        String clauseIssues = buildClauseIssuesList(priorResults);
        if (clauseIssues.isBlank()) {
            return RedlineSuggestionsResult.builder().suggestions(List.of()).build();
        }

        String userPrompt = String.format(PromptTemplates.REDLINE_USER_GUIDED, clauseIssues);
        com.fasterxml.jackson.databind.JsonNode json = vllmClient.generateStructured(
                legalSystemConfig.localize(PromptTemplates.REDLINE_SYSTEM_GUIDED), userPrompt, StructuredSchemas.REDLINE_SCHEMA, 1200);

        return parseRedlineJson(json);
    }

    @SuppressWarnings("unchecked")
    private String buildClauseIssuesList(Map<String, Object> priorResults) {
        // Try to get clause checklist results
        Object checklistRaw = priorResults.get("CLAUSE_CHECKLIST");
        if (checklistRaw == null) return "";
        try {
            String json = objectMapper.writeValueAsString(checklistRaw);
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);
            StringBuilder sb = new StringBuilder();
            node.path("clauses").forEach(clause -> {
                String status = clause.path("status").asText("");
                if ("WEAK".equals(status) || "MISSING".equals(status)) {
                    sb.append("- ").append(clause.path("clauseName").asText(clause.path("clause_id").asText("Unknown")))
                      .append(" [").append(status).append("]: ")
                      .append(clause.path("assessment").asText(clause.path("finding").asText("")))
                      .append("\n");
                }
            });
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to extract clause issues for redlines: {}", e.getMessage());
            return "";
        }
    }

    /** Builds redline issues from RISK_ASSESSMENT categories (for draft-only runs with no checklist). */
    private String buildClauseIssuesFromRisk(Map<String, Object> priorResults) {
        Object riskRaw = priorResults.get("RISK_ASSESSMENT");
        if (riskRaw == null) return "";
        try {
            String json = objectMapper.writeValueAsString(riskRaw);
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);
            StringBuilder sb = new StringBuilder();
            node.path("categories").forEach(cat -> {
                String rating = cat.path("rating").asText("");
                if ("HIGH".equals(rating) || "MEDIUM".equals(rating)) {
                    sb.append("- ").append(cat.path("name").asText("Clause"))
                      .append(" [").append(rating).append(" RISK]: ")
                      .append(cat.path("justification").asText("")).append("\n");
                }
            });
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private RedlineSuggestionsResult parseRedlineJson(com.fasterxml.jackson.databind.JsonNode root) {
        List<RedlineSuggestionsResult.RedlineSuggestion> suggestions = new ArrayList<>();
        if (root != null && root.has("suggestions")) {
            root.path("suggestions").forEach(s -> suggestions.add(
                    RedlineSuggestionsResult.RedlineSuggestion.builder()
                            .clauseName(s.path("clause_name").asText(""))
                            .issue(s.path("issue").asText(""))
                            .suggestedLanguage(s.path("suggested_language").asText(""))
                            .rationale(s.path("rationale").asText(""))
                            .build()
            ));
        }
        return RedlineSuggestionsResult.builder().suggestions(suggestions).build();
    }

    // ── Workflow: RAG-aware risk assessment ───────────────────────────────────

    /**
     * Risk assessment with optional corpus benchmark context and feedback from prior iteration.
     * ragContext: precedent clauses from similar contracts (from WorkflowContextService)
     * feedbackContext: quality gaps from previous attempt (drives refinement loop)
     */
    public RiskAssessmentResult assessRiskWithContext(UUID documentId, String username,
                                                      String ragContext, String feedbackContext) {
        return assessRiskWithContext(documentId, username, ragContext, feedbackContext, null);
    }

    public RiskAssessmentResult assessRiskWithContext(UUID documentId, String username,
                                                      String ragContext, String feedbackContext,
                                                      String draftedText) {
        String contractText;
        if (documentId != null) {
            documentRepository.findById(documentId)
                    .orElseThrow(() -> new java.util.NoSuchElementException("Document not found: " + documentId));
            contractText = fullTextRetriever.retrieveFullText(documentId);
        } else {
            contractText = draftedText != null ? draftedText : "";
        }
        if (contractText.isBlank()) {
            return new RiskAssessmentResult("UNKNOWN", List.of(new RiskCategory(
                    "Not Ready", "UNKNOWN", "No contract text available.", "")));
        }

        // Build enriched context: RAG benchmarks + contract + optional refinement feedback
        StringBuilder fullContext = new StringBuilder();
        if (ragContext != null && !ragContext.isBlank()) {
            fullContext.append(ragContext);
        }
        fullContext.append("=== CONTRACT TO ANALYZE ===\n").append(contractText);
        if (feedbackContext != null && !feedbackContext.isBlank()) {
            fullContext.append("\n\n").append(feedbackContext);
        }

        String guidedPrompt = String.format(PromptTemplates.RISK_USER_GUIDED,
                fullContext.toString().substring(0, Math.min(fullContext.length(), 8000)));

        com.fasterxml.jackson.databind.JsonNode json = vllmClient.generateStructured(
                legalSystemConfig.localize(PromptTemplates.RISK_SYSTEM_GUIDED),
                guidedPrompt, StructuredSchemas.RISK_SCHEMA, 900);

        RiskAssessmentResult result = parseRiskJson(json);
        if (!result.categories().isEmpty()) return result;

        // Fallback to CSV then prose
        String csvPrompt = String.format(PromptTemplates.RISK_USER, documentId, contractText);
        String rawText = stripResponsePrefix(
                vllmClient.generateText(legalSystemConfig.localize(PromptTemplates.RISK_SYSTEM), csvPrompt, "OVERALL=", 150));
        result = parseRiskCsv(rawText);
        return result.categories().isEmpty() ? parseRiskLines(rawText) : result;
    }

    // ── Workflow: RAG-aware redlines ──────────────────────────────────────────

    /**
     * Redline suggestions grounded in firm's clause library (golden clauses) and corpus precedents.
     * ragContext: golden library clauses + similar contract text (from WorkflowContextService)
     * feedbackContext: quality gaps from previous attempt
     */
    public RedlineSuggestionsResult generateRedlinesWithContext(UUID documentId,
                                                                Map<String, Object> priorResults,
                                                                String username,
                                                                String ragContext,
                                                                String feedbackContext) {
        return generateRedlinesWithContext(documentId, priorResults, username, ragContext, feedbackContext, null);
    }

    public RedlineSuggestionsResult generateRedlinesWithContext(UUID documentId,
                                                                Map<String, Object> priorResults,
                                                                String username,
                                                                String ragContext,
                                                                String feedbackContext,
                                                                String draftedText) {
        if (documentId != null) {
            documentRepository.findById(documentId)
                    .orElseThrow(() -> new java.util.NoSuchElementException("Document not found: " + documentId));
        }

        String clauseIssues = buildClauseIssuesList(priorResults);
        // For draft-only runs, fall back to risk categories as clause issues
        if (clauseIssues.isBlank() && draftedText != null) {
            clauseIssues = buildClauseIssuesFromRisk(priorResults);
        }
        if (clauseIssues.isBlank()) return RedlineSuggestionsResult.builder().suggestions(List.of()).build();

        StringBuilder userPrompt = new StringBuilder();
        if (ragContext != null && !ragContext.isBlank()) {
            userPrompt.append(ragContext);
        }
        userPrompt.append("The following clause issues were identified in the contract:\n").append(clauseIssues);
        if (feedbackContext != null && !feedbackContext.isBlank()) {
            userPrompt.append("\n\n").append(feedbackContext);
        }

        String prompt = userPrompt.toString();
        if (prompt.length() > 6000) prompt = prompt.substring(0, 6000) + "\n[...truncated]";

        com.fasterxml.jackson.databind.JsonNode json = vllmClient.generateStructured(
                legalSystemConfig.localize(PromptTemplates.REDLINE_SYSTEM_GUIDED),
                prompt, StructuredSchemas.REDLINE_SCHEMA, 1400);
        return parseRedlineJson(json);
    }

    // ── Workflow: Draft clause step ───────────────────────────────────────────

    /**
     * Drafts a specific clause type as a workflow step.
     * Uses the document's deal context (parties, dates) + RAG corpus + clause library.
     * Returns a Map<String,Object> with "clauseType" and "content" keys.
     */
    public Map<String, Object> draftClauseForWorkflow(UUID documentId,
                                                       Map<String, String> params,
                                                       String username,
                                                       String ragContext,
                                                       String feedbackContext) {
        String clauseType   = params != null ? params.getOrDefault("clauseType",   "LIABILITY") : "LIABILITY";
        String partyA       = params != null ? params.getOrDefault("partyA",       "") : "";
        String partyB       = params != null ? params.getOrDefault("partyB",       "") : "";
        String jurisdiction = params != null ? params.getOrDefault("jurisdiction", "") : "";
        String dealBrief    = params != null ? params.getOrDefault("dealBrief",    "") : "";

        String contractText = "";
        String contractTypeName = "Commercial";
        if (documentId != null) {
            DocumentMetadata meta = documentRepository.findById(documentId)
                    .orElseThrow(() -> new java.util.NoSuchElementException("Document not found: " + documentId));
            contractText = fullTextRetriever.retrieveFullText(documentId);
            contractTypeName = meta.getDocumentType() != null ? meta.getDocumentType().name() : "Commercial";
        }

        // Build system prompt — inject parties and jurisdiction directly so model can't override them
        String partyALabel = partyA.isBlank() ? "the Service Provider" : partyA;
        String partyBLabel = partyB.isBlank() ? "the Client" : partyB;
        String jurisdictionLabel = jurisdiction.isBlank() ? "" : ", governed by the laws of " + jurisdiction;
        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append("You are a senior commercial contracts lawyer.\n");
        systemPrompt.append("Draft a complete, enforceable ").append(clauseType)
                    .append(" clause for a ").append(contractTypeName).append(" agreement")
                    .append(jurisdictionLabel).append(".\n");
        systemPrompt.append("STRICT RULES:\n");
        systemPrompt.append("- Do NOT use placeholders like [Party Name], [DATE], [INSERT], [***], [__].\n");
        systemPrompt.append("- Use \"").append(partyALabel).append("\" and \"").append(partyBLabel)
                    .append("\" as the party names throughout. Do not substitute other names.\n");
        systemPrompt.append("- Write at least 3 numbered sub-clauses with full legal text.\n");
        systemPrompt.append("- Base your language on the PRECEDENT CONTEXT provided.\n");
        if (!jurisdiction.isBlank()) {
            systemPrompt.append("- Governing law is ").append(jurisdiction)
                        .append(". Do NOT reference any other jurisdiction.\n");
        }

        StringBuilder userPrompt = new StringBuilder();
        if (ragContext != null && !ragContext.isBlank()) {
            userPrompt.append(ragContext);
        }
        // Inject deal context: user-supplied brief first, then document text if available
        if (!dealBrief.isBlank()) {
            userPrompt.append("=== DEAL CONTEXT ===\n").append(dealBrief).append("\n=== END DEAL CONTEXT ===\n\n");
        } else if (!contractText.isBlank()) {
            String snip = contractText.length() > 2000 ? contractText.substring(0, 2000) : contractText;
            userPrompt.append("=== DEAL CONTEXT (from the contract being processed) ===\n")
                      .append(snip).append("\n=== END DEAL CONTEXT ===\n\n");
        }
        userPrompt.append("Parties: ").append(partyALabel).append(" and ").append(partyBLabel).append(".\n");
        if (!jurisdiction.isBlank()) userPrompt.append("Governing law: ").append(jurisdiction).append(".\n");
        userPrompt.append("Draft a complete ").append(clauseType).append(" clause for this agreement.");
        if (feedbackContext != null && !feedbackContext.isBlank()) {
            userPrompt.append("\n\n").append(feedbackContext);
        }

        AiMessage response = chatModel.generate(
                SystemMessage.from(legalSystemConfig.localizeForJurisdiction(systemPrompt.toString(), jurisdiction)),
                UserMessage.from(userPrompt.toString())
        ).content();

        String content = stripResponsePrefix(response.text());
        return Map.of(
                "clauseType", clauseType,
                "content", content,
                "contractType", contractTypeName
        );
    }
}
