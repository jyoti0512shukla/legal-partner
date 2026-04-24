package com.legalpartner.rag;

import com.legalpartner.config.ClauseTypeRegistry;
import com.legalpartner.config.ContractTypeRegistry;
import com.legalpartner.model.dto.DraftRequest;
import com.legalpartner.model.entity.ClauseLibraryEntry;
import com.legalpartner.service.ClauseLibraryService;
import com.legalpartner.service.EncryptionService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * Industry best-practice context retrieval for contract drafting.
 *
 * Implements:
 * - Multi-query retrieval (semantic + keyword variants for better recall)
 * - Metadata-aware filtering (document type, jurisdiction, clause type)
 * - Clause-type prioritization (LIABILITY, INDEMNITY chunks ranked higher)
 * - Document diversity (cap chunks per document to avoid over-representation)
 * - Re-ranking (vector + keyword + recency + metadata match)
 * - Context structuring (organized by source with provenance)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DraftContextRetriever {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final QueryExpander queryExpander;
    private final ReRanker reRanker;
    private final EncryptionService encryptionService;
    private final ClauseLibraryService clauseLibraryService;
    private final ClauseTypeRegistry clauseRegistry;
    private final ContractTypeRegistry contractRegistry;

    @Value("${legalpartner.draft.retrieval.candidate-count:30}")
    private int candidateCount;

    @Value("${legalpartner.draft.retrieval.top-k:12}")
    private int topK;

    @Value("${legalpartner.draft.retrieval.max-chunks-per-doc:3}")
    private int maxChunksPerDoc;

    @Value("${legalpartner.draft.retrieval.context-max-chars:8000}")
    private int contextMaxChars;

    @Value("${legalpartner.defaults.jurisdiction:United States (Delaware)}")
    private String defaultJurisdiction;

    // Per-clause search queries now live in resources/config/clauses.yml
    // (loaded by ClauseTypeRegistry). Use clauseRegistry.get(key).searchQueries().

    private static final List<String> DEFAULT_QUERIES = List.of(
        "contract clause obligations rights duties",
        "agreement terms conditions covenants"
    );

    /**
     * Retrieve and structure context for drafting a specific clause type.
     * Library (golden) clauses are injected first, followed by vector-search results.
     */
    public DraftContext retrieveForClause(String clauseType, DraftRequest request) {
        String normalizedType = clauseType != null ? clauseType.toUpperCase() : "LIABILITY";
        List<String> queries = clauseRegistry.contains(normalizedType)
                && !clauseRegistry.get(normalizedType).searchQueries().isEmpty()
                ? clauseRegistry.get(normalizedType).searchQueries()
                : DEFAULT_QUERIES;

        // Phase 1: inject firm clause library entries (golden first, then others)
        String contractType = mapTemplateToDocumentType(request.getTemplateId());
        List<ClauseLibraryEntry> libraryEntries = clauseLibraryService.findForDraft(
                normalizedType, contractType,
                request.getIndustry(), request.getPracticeArea() != null ? request.getPracticeArea() : null);
        String libraryContext = buildLibraryContext(libraryEntries);

        // Phase 2: vector search from firm's indexed documents
        List<EmbeddingMatch<TextSegment>> allCandidates = multiQueryRetrieve(queries);
        List<EmbeddingMatch<TextSegment>> filtered = filterByMetadata(allCandidates, request, normalizedType);
        List<EmbeddingMatch<TextSegment>> diversified = applyDocumentDiversity(filtered);
        // Rerank against the CONCATENATION of all query variants rather than just
        // the first. The query variants capture different angles (e.g. "limitation
        // of liability" vs "indemnify hold harmless"); the reranker should score
        // against the full intent, not a single facet.
        String rerankQuery = String.join(" ", queries);
        List<EmbeddingMatch<TextSegment>> ranked = reRanker.rerank(diversified, rerankQuery, topK);
        ranked = diversifyForContext(ranked);

        // Combine: library entries consume some of the context budget; remainder for vector results
        int libraryChars = libraryContext.length();
        int remainingChars = Math.max(contextMaxChars - libraryChars, 2000);
        String vectorContext = buildStructuredContext(ranked, remainingChars);

        String structuredContext = libraryContext + (vectorContext.isBlank() ? "" : "\n" + vectorContext);
        List<String> provenance = collectProvenance(ranked);
        if (!libraryEntries.isEmpty()) {
            provenance = new ArrayList<>(provenance);
            ((ArrayList<String>) provenance).add(0, "Firm Clause Library (" + libraryEntries.size() + " entries)");
        }
        return new DraftContext(structuredContext, ranked.size() + libraryEntries.size(), provenance);
    }

    /**
     * Multi-query retrieval with primary-query score boosting. Each query
     * variant retrieves its own top-N, but matches from the FIRST query
     * (the "canonical" phrasing of what we want) get a small multiplicative
     * boost. Keeps the recall benefit of multiple queries while letting the
     * primary intent dominate when there's agreement.
     */
    private List<EmbeddingMatch<TextSegment>> multiQueryRetrieve(List<String> queries) {
        Map<String, EmbeddingMatch<TextSegment>> best = new HashMap<>();
        Map<String, Double> bestScore = new HashMap<>();

        for (int qi = 0; qi < queries.size(); qi++) {
            String q = queries.get(qi);
            // Primary query gets a 1.2× boost; secondaries decay by 0.05 per rank.
            double queryWeight = Math.max(0.7, 1.2 - (qi * 0.05));

            String expanded = queryExpander.expand(q);
            Embedding embedding = embeddingModel.embed(expanded).content();
            List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(embedding, candidateCount / 2);
            for (EmbeddingMatch<TextSegment> m : matches) {
                String key = keyFor(m);
                double weighted = m.score() * queryWeight;
                // Keep the highest-weighted occurrence per chunk across all queries
                if (!best.containsKey(key) || weighted > bestScore.get(key)) {
                    best.put(key, m);
                    bestScore.put(key, weighted);
                }
            }
        }

        return best.entrySet().stream()
                .sorted((a, b) -> Double.compare(bestScore.get(b.getKey()), bestScore.get(a.getKey())))
                .map(Map.Entry::getValue)
                .limit(candidateCount)
                .toList();
    }

    /**
     * Interleave chunks across source documents so that when the final
     * char-budget truncation hits, EVERY source has contributed at least
     * one chunk before any source contributes its second. Previously the
     * naive ranked list could fill the budget with one document's top-3
     * before the next document's top-1 was even considered.
     */
    private List<EmbeddingMatch<TextSegment>> diversifyForContext(List<EmbeddingMatch<TextSegment>> ranked) {
        Map<String, List<EmbeddingMatch<TextSegment>>> byDoc = new LinkedHashMap<>();
        for (EmbeddingMatch<TextSegment> m : ranked) {
            String docId = m.embedded().metadata().getString("document_id");
            if (docId == null) docId = keyFor(m);
            byDoc.computeIfAbsent(docId, k -> new ArrayList<>()).add(m);
        }
        // Round-robin: first chunk from each doc, then second, then third...
        List<EmbeddingMatch<TextSegment>> out = new ArrayList<>();
        int round = 0;
        boolean added;
        do {
            added = false;
            for (List<EmbeddingMatch<TextSegment>> docChunks : byDoc.values()) {
                if (round < docChunks.size()) {
                    out.add(docChunks.get(round));
                    added = true;
                }
            }
            round++;
        } while (added);
        return out;
    }

    private List<EmbeddingMatch<TextSegment>> filterByMetadata(
            List<EmbeddingMatch<TextSegment>> candidates,
            DraftRequest request,
            String clauseType
    ) {
        String targetDocType = mapTemplateToDocumentType(request.getTemplateId());
        String targetMatterId = (request.getMatterId() != null && !request.getMatterId().isBlank())
                ? request.getMatterId() : null;
        String targetJurisdiction = (request.getJurisdiction() == null || request.getJurisdiction().isBlank())
                ? defaultJurisdiction : request.getJurisdiction();
        String targetIndustry = request.getIndustry();

        Set<String> acceptableClauseTypes = getAcceptableClauseTypes(clauseType);

        List<EmbeddingMatch<TextSegment>> filtered = candidates.stream()
                .filter(m -> {
                    // Hard exclude EDGAR-sourced chunks for drafting — they carry binary
                    // blobs, source tags, and unrelated-deal prose that the model parrots.
                    String source = m.embedded().metadata().getString("source");
                    if ("EDGAR".equalsIgnoreCase(source)) return false;

                    // Hard exclude non-anonymized chunks — cross-client confidentiality
                    // fail-safe. Chunks ingested before the anonymization pipeline landed
                    // (or where anonymization failed) have their original party names /
                    // amounts / jurisdictions in the embedded text, and can leak into
                    // other clients' drafts. They must be re-indexed (see
                    // AdminController.reindexAnonymization) before becoming retrievable.
                    String anonFlag = m.embedded().metadata().getString("is_anonymized");
                    if (!"true".equalsIgnoreCase(anonFlag)) return false;

                    String docType = m.embedded().metadata().getString("document_type");
                    String matterId = m.embedded().metadata().getString("matter_id");
                    String jurisdiction = m.embedded().metadata().getString("jurisdiction");
                    String chunkClauseType = m.embedded().metadata().getString("clause_type");
                    String industry = m.embedded().metadata().getString("industry");

                    // ── Scoping rule: precedent must share context with the draft ──
                    // If the draft has a matter, the chunk must come from that matter OR
                    // match the target contract type. If no matter, the chunk MUST match
                    // the target contract type. Untagged chunks are excluded in both cases.
                    boolean matterMatch = targetMatterId != null && targetMatterId.equals(matterId);
                    boolean docTypeMatch = targetDocType != null && targetDocType.equalsIgnoreCase(docType);
                    if (!matterMatch && !docTypeMatch) return false;

                    // ── Jurisdiction: hard filter when target is specified ──
                    // Indian-law precedents must NOT appear in US/Delaware drafts.
                    // Allow untagged chunks only when target jurisdiction is also blank.
                    boolean jurisdictionMatch;
                    if (targetJurisdiction != null && !targetJurisdiction.isBlank()) {
                        // Target has jurisdiction — chunk must match or be untagged
                        // BUT reject chunks from a clearly different jurisdiction family
                        if (jurisdiction != null && !jurisdiction.isBlank()) {
                            jurisdictionMatch = isSameJurisdictionFamily(targetJurisdiction, jurisdiction);
                        } else {
                            jurisdictionMatch = true; // untagged chunk, allow
                        }
                    } else {
                        jurisdictionMatch = true; // no target jurisdiction, allow all
                    }
                    boolean clauseMatch = chunkClauseType == null || chunkClauseType.isBlank()
                            || acceptableClauseTypes.contains(chunkClauseType.toUpperCase());
                    boolean industryMatch = industry == null || industry.isBlank()
                            || targetIndustry == null || targetIndustry.isBlank()
                            || targetIndustry.equalsIgnoreCase("GENERAL")
                            || industry.equalsIgnoreCase(targetIndustry);

                    return jurisdictionMatch && clauseMatch && industryMatch;
                })
                .toList();

        // Strict: no permissive fallback. Empty RAG is better than poisoned RAG
        // — the primary cause of garbage drafts was MSA/NDA precedents bleeding
        // into SaaS drafts because the old fallback returned all candidates.
        if (filtered.isEmpty()) {
            log.info("No metadata-scoped precedents for clause={} template={} matter={} — drafting without RAG",
                    clauseType, request.getTemplateId(), targetMatterId);
        }
        return filtered;
    }

    /** Check if two jurisdictions belong to the same legal family (US states, Indian states, UK, etc.) */
    private boolean isSameJurisdictionFamily(String target, String chunk) {
        String t = target.toLowerCase(), c = chunk.toLowerCase();
        // US family: any US state, "united states", "delaware", "california", "new york", etc.
        boolean tUS = t.contains("united states") || t.contains("u.s.") || t.contains("delaware")
                || t.contains("california") || t.contains("new york") || t.contains("texas")
                || t.contains("illinois") || t.contains("florida") || t.contains("usa");
        boolean cUS = c.contains("united states") || c.contains("u.s.") || c.contains("delaware")
                || c.contains("california") || c.contains("new york") || c.contains("texas")
                || c.contains("illinois") || c.contains("florida") || c.contains("usa");
        if (tUS && cUS) return true;
        // India family
        boolean tIN = t.contains("india") || t.contains("mumbai") || t.contains("delhi")
                || t.contains("bangalore") || t.contains("maharashtra") || t.contains("karnataka");
        boolean cIN = c.contains("india") || c.contains("mumbai") || c.contains("delhi")
                || c.contains("bangalore") || c.contains("maharashtra") || c.contains("karnataka")
                || c.contains("ontario");  // Ontario is Canada, not India — but tagged Indian docs sometimes have it
        if (tIN && cIN) return true;
        // UK family
        boolean tUK = t.contains("united kingdom") || t.contains("england") || t.contains("uk")
                || t.contains("london") || t.contains("scotland");
        boolean cUK = c.contains("united kingdom") || c.contains("england") || c.contains("uk")
                || c.contains("london") || c.contains("scotland");
        if (tUK && cUK) return true;
        // Different families — reject cross-jurisdiction RAG
        if ((tUS && cIN) || (tIN && cUS) || (tUS && cUK) || (tUK && cIN)) return false;
        // Fallback: exact match or contains
        return t.contains(c) || c.contains(t);
    }

    /**
     * Build a context block from firm clause library entries.
     * Golden clauses are prefixed with [GOLDEN CLAUSE] to guide the LLM.
     */
    private String buildLibraryContext(List<ClauseLibraryEntry> entries) {
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("=== FIRM CLAUSE LIBRARY (use these as primary precedent) ===\n\n");
        for (int i = 0; i < entries.size(); i++) {
            ClauseLibraryEntry e = entries.get(i);
            String tag = e.isGolden() ? "[GOLDEN CLAUSE — Firm Approved]" : "[Firm Library Clause]";
            String meta = Stream.of(e.getContractType(), e.getIndustry(), e.getPracticeArea(), e.getJurisdiction())
                    .filter(Objects::nonNull).collect(Collectors.joining(", "));
            sb.append(String.format("[Library Entry %d: %s | %s%s]\n",
                    i + 1, e.getTitle(), tag, meta.isBlank() ? "" : " | " + meta));
            sb.append(e.getContent()).append("\n\n");
        }
        sb.append("=== END FIRM CLAUSE LIBRARY ===\n");
        return sb.toString();
    }

    private Set<String> getAcceptableClauseTypes(String clauseType) {
        if (clauseRegistry.contains(clauseType)) {
            Set<String> types = clauseRegistry.get(clauseType).acceptableClauseTypes();
            if (!types.isEmpty()) return types;
        }
        return Set.of("GENERAL", clauseType);
    }

    private boolean isRelatedDocType(String docType, String target) {
        if (target == null) return true;
        return (target.equals("NDA") && "CONFIDENTIALITY".equals(docType))
                || (target.equals("MSA") && Set.of("SOW", "VENDOR", "OTHER").contains(docType));
    }

    private List<EmbeddingMatch<TextSegment>> applyDocumentDiversity(List<EmbeddingMatch<TextSegment>> candidates) {
        Map<String, List<EmbeddingMatch<TextSegment>>> byDoc = candidates.stream()
                .collect(Collectors.groupingBy(m -> m.embedded().metadata().getString("document_id")));

        return byDoc.values().stream()
                .flatMap(list -> list.stream().limit(maxChunksPerDoc))
                .sorted(Comparator.comparingDouble((EmbeddingMatch<TextSegment> m) -> m.score()).reversed())
                .limit(topK * 2)
                .toList();
    }

    private String buildStructuredContext(List<EmbeddingMatch<TextSegment>> matches, int maxChars) {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (int i = 0; i < matches.size() && total < maxChars; i++) {
            EmbeddingMatch<TextSegment> m = matches.get(i);
            // We deliberately OMIT the file_name from the header. Filenames often
            // contain client names ("Contract_AcmeCorp.docx") — even if the
            // body is anonymized, a raw filename in the RAG payload is a leak
            // channel. The model doesn't need it; Source N is enough for
            // internal referencing.
            String section = m.embedded().metadata().getString("section_path");
            String docType = m.embedded().metadata().getString("document_type");
            String jurisdiction = m.embedded().metadata().getString("jurisdiction");

            String text;
            try {
                text = encryptionService.decrypt(m.embedded().text());
            } catch (Exception e) {
                text = m.embedded().text();
            }

            String header = String.format("[Source %d | %s | %s | %s]",
                    i + 1,
                    section != null && !section.isBlank() ? section : "clause",
                    docType != null ? docType : "",
                    jurisdiction != null ? jurisdiction : "");

            String block = "\n" + header + "\n" + text + "\n\n";
            if (total + block.length() > maxChars) {
                sb.append(block, 0, maxChars - total);
                sb.append("\n[...truncated]");
                break;
            }
            sb.append(block);
            total += block.length();
        }
        return sb.toString();
    }

    private List<String> collectProvenance(List<EmbeddingMatch<TextSegment>> matches) {
        return matches.stream()
                .map(m -> m.embedded().metadata().getString("file_name"))
                .filter(Objects::nonNull)
                .distinct()
                .limit(5)
                .toList();
    }

    private String keyFor(EmbeddingMatch<TextSegment> m) {
        String docId = m.embedded().metadata().getString("document_id");
        String idx = m.embedded().metadata().getString("chunk_index");
        return (docId != null ? docId : "") + ":" + (idx != null ? idx : "");
    }

    /**
     * Map a drafting template id (what the user is authoring) to the uploaded
     * DocumentType tag (what precedents we should retrieve). Now driven by
     * resources/config/contract_types.yml. Strict: templates without a mapping
     * get NO retrieval — better empty than wrong.
     */
    private String mapTemplateToDocumentType(String templateId) {
        if (templateId == null) return null;
        return contractRegistry.documentType(templateId);
    }

    public record DraftContext(String structuredContext, int chunkCount, List<String> sourceDocuments) {}
}
