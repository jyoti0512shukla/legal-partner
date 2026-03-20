package com.legalpartner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.model.entity.ClauseLibraryEntry;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.enums.WorkflowStepType;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * Retrieves RAG context (corpus benchmarks + clause library) for workflow step execution.
 *
 * - RISK_ASSESSMENT: similar contract segments for benchmarking risk clauses
 * - REDLINE_SUGGESTIONS: golden library clauses for WEAK/MISSING types + corpus precedents
 * - CLAUSE_CHECKLIST: standard clause benchmarks from similar contracts
 * - DRAFT_CLAUSE: full clause library + corpus for the requested clause type
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowContextService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ClauseLibraryService clauseLibraryService;
    private final EncryptionService encryptionService;

    private static final int CANDIDATE_COUNT = 20;
    private static final int MAX_CONTEXT_CHARS = 4000;

    public String getContextForStep(WorkflowStepType type, DocumentMetadata docMeta,
                                    Map<String, Object> priorResults, ObjectMapper mapper) {
        try {
            return switch (type) {
                case RISK_ASSESSMENT     -> getRiskContext(docMeta);
                case REDLINE_SUGGESTIONS -> getRedlineContext(docMeta, priorResults, mapper);
                case CLAUSE_CHECKLIST    -> getChecklistContext(docMeta);
                case DRAFT_CLAUSE        -> getDraftContext(docMeta);
                default                  -> ""; // EXTRACT_KEY_TERMS, GENERATE_SUMMARY: no RAG needed
            };
        } catch (Exception e) {
            log.warn("WorkflowContextService: failed for {}: {}", type, e.getMessage());
            return "";
        }
    }

    // ── Step-specific retrieval ────────────────────────────────────────────────

    private String getRiskContext(DocumentMetadata docMeta) {
        String query = "limitation of liability cap indemnification termination for cause force majeure IP rights governing law risk clauses";
        List<EmbeddingMatch<TextSegment>> matches = retrieve(query, docMeta);
        if (matches.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("=== BENCHMARK CONTEXT: How similar contracts in your corpus handle risk ===\n");
        sb.append("(Compare this contract against these — note what is missing or deviating)\n\n");
        appendMatches(sb, matches, MAX_CONTEXT_CHARS);
        sb.append("=== END BENCHMARK ===\n\n");
        return sb.toString();
    }

    private String getRedlineContext(DocumentMetadata docMeta, Map<String, Object> priorResults, ObjectMapper mapper) {
        // Pull WEAK/MISSING clause types from the prior CLAUSE_CHECKLIST step
        List<String> weakTypes = extractWeakClauseTypes(priorResults, mapper);
        log.info("WorkflowContextService: weak clause types for redline context: {}", weakTypes);

        StringBuilder sb = new StringBuilder();
        sb.append("=== FIRM STANDARD CLAUSES (use as target language for redline suggestions) ===\n");
        sb.append("When suggesting improved language, base it on these firm-approved precedents:\n\n");

        // Golden clauses from library for each weak type
        boolean hasLibraryEntries = false;
        for (String clauseType : weakTypes) {
            List<ClauseLibraryEntry> entries = clauseLibraryService.findForDraft(
                    clauseType,
                    docMeta != null && docMeta.getDocumentType() != null ? docMeta.getDocumentType().name() : null,
                    docMeta != null ? docMeta.getIndustry() : null,
                    null
            );
            if (!entries.isEmpty()) {
                hasLibraryEntries = true;
                ClauseLibraryEntry best = entries.get(0); // golden-first ordering from repo
                String tag = best.isGolden() ? "[GOLDEN — Firm Approved]" : "[Firm Library]";
                sb.append(String.format("--- %s Clause Standard %s ---\n", clauseType, tag));
                sb.append(best.getContent()).append("\n\n");
                if (sb.length() >= MAX_CONTEXT_CHARS / 2) break;
            }
        }

        // Supplement with corpus precedents
        String corpusQuery = weakTypes.isEmpty()
                ? "contract clause improvement standard language"
                : String.join(" ", weakTypes.stream().limit(3).toList()) + " contract clause language";
        List<EmbeddingMatch<TextSegment>> corpusMatches = retrieve(corpusQuery, docMeta);
        if (!corpusMatches.isEmpty()) {
            if (hasLibraryEntries) sb.append("--- Additional corpus precedents ---\n");
            appendMatches(sb, corpusMatches, MAX_CONTEXT_CHARS - sb.length());
        }

        sb.append("=== END FIRM STANDARDS ===\n\n");
        return sb.toString();
    }

    private String getChecklistContext(DocumentMetadata docMeta) {
        String query = "standard contract clauses definitions confidentiality termination liability governing law force majeure warranty";
        List<EmbeddingMatch<TextSegment>> matches = retrieve(query, docMeta);
        if (matches.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("=== BENCHMARK: Standard clause coverage from similar contracts ===\n\n");
        appendMatches(sb, matches, MAX_CONTEXT_CHARS);
        sb.append("=== END BENCHMARK ===\n\n");
        return sb.toString();
    }

    private String getDraftContext(DocumentMetadata docMeta) {
        // Pull library first, then corpus
        StringBuilder sb = new StringBuilder();
        sb.append("=== PRECEDENT CONTEXT: Firm clause library and corpus ===\n\n");

        List<ClauseLibraryEntry> allGolden = clauseLibraryService.findForDraft(null, null, null, null)
                .stream()
                .filter(ClauseLibraryEntry::isGolden)
                .limit(3)
                .toList();
        for (ClauseLibraryEntry e : allGolden) {
            sb.append(String.format("[GOLDEN CLAUSE: %s | %s]\n", e.getTitle(), e.getClauseType()));
            sb.append(e.getContent()).append("\n\n");
            if (sb.length() >= MAX_CONTEXT_CHARS / 2) break;
        }

        List<EmbeddingMatch<TextSegment>> matches = retrieve("contract drafting clause obligations rights duties covenants", docMeta);
        appendMatches(sb, matches, MAX_CONTEXT_CHARS - sb.length());
        sb.append("=== END PRECEDENT ===\n\n");
        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<EmbeddingMatch<TextSegment>> retrieve(String query, DocumentMetadata docMeta) {
        Embedding emb = embeddingModel.embed(query).content();
        List<EmbeddingMatch<TextSegment>> all = embeddingStore.findRelevant(emb, CANDIDATE_COUNT);
        // Exclude the document being analyzed — we want external benchmarks
        if (docMeta != null) {
            String docId = docMeta.getId().toString();
            all = all.stream()
                    .filter(m -> !docId.equals(m.embedded().metadata().getString("document_id")))
                    .toList();
        }
        return all;
    }

    private void appendMatches(StringBuilder sb, List<EmbeddingMatch<TextSegment>> matches, int maxChars) {
        for (EmbeddingMatch<TextSegment> m : matches) {
            if (sb.length() >= maxChars) break;
            String text;
            try { text = encryptionService.decrypt(m.embedded().text()); }
            catch (Exception e) { text = m.embedded().text(); }
            String fileName = m.embedded().metadata().getString("file_name");
            String docType  = m.embedded().metadata().getString("document_type");
            String block = String.format("[From: %s (%s)]\n%s\n\n",
                    fileName != null ? fileName : "Unknown",
                    docType  != null ? docType  : "",
                    text);
            if (sb.length() + block.length() > maxChars) {
                sb.append(block, 0, Math.max(0, maxChars - sb.length()));
                break;
            }
            sb.append(block);
        }
    }

    private List<String> extractWeakClauseTypes(Map<String, Object> priorResults, ObjectMapper mapper) {
        List<String> types = new ArrayList<>();
        try {
            Object checklist = priorResults.get("CLAUSE_CHECKLIST");
            if (checklist == null) return types;
            JsonNode node = mapper.readTree(mapper.writeValueAsString(checklist));
            StreamSupport.stream(node.path("clauses").spliterator(), false)
                    .filter(c -> {
                        String status = c.path("status").asText("");
                        return "WEAK".equals(status) || "MISSING".equals(status);
                    })
                    .forEach(c -> {
                        String id = c.path("clauseId").asText(
                                c.path("clause_id").asText(
                                        c.path("clauseName").asText("")));
                        if (!id.isBlank()) types.add(id.toUpperCase().replace(" ", "_"));
                    });
        } catch (Exception e) {
            log.warn("Failed to extract weak clause types: {}", e.getMessage());
        }
        return types;
    }
}
