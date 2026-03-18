package com.legalpartner.rag;

import com.legalpartner.model.dto.DraftRequest;
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

    @Value("${legalpartner.draft.retrieval.candidate-count:30}")
    private int candidateCount;

    @Value("${legalpartner.draft.retrieval.top-k:12}")
    private int topK;

    @Value("${legalpartner.draft.retrieval.max-chunks-per-doc:3}")
    private int maxChunksPerDoc;

    @Value("${legalpartner.draft.retrieval.context-max-chars:8000}")
    private int contextMaxChars;

    private static final Map<String, List<String>> CLAUSE_QUERIES = Map.ofEntries(
        Map.entry("LIABILITY", List.of(
            "liability limitation indemnity cap damages exclusion consequential",
            "limitation of liability aggregate cap direct damages indirect",
            "indemnify hold harmless defend claims breach",
            "Indian Contract Act Section 73 74 liquidated damages"
        )),
        Map.entry("TERMINATION", List.of(
            "termination notice period right to terminate exit clause",
            "termination for cause material breach cure period",
            "termination for convenience effects survival obligations",
            "Indian Contract Act Section 39 repudiation"
        )),
        Map.entry("CONFIDENTIALITY", List.of(
            "confidential information non-disclosure NDA proprietary",
            "confidentiality obligations exceptions public domain prior knowledge",
            "return destroy confidential information on termination survival",
            "IT Act trade secret data protection"
        )),
        Map.entry("GOVERNING_LAW", List.of(
            "governing law jurisdiction arbitration dispute resolution",
            "arbitration seat venue ICC SIAC LCIA Arbitration Act 1996",
            "mediation conciliation negotiation tiered dispute",
            "applicable law choice of law courts India"
        )),
        Map.entry("IP_RIGHTS", List.of(
            "intellectual property rights ownership work product license",
            "copyright patent trademark background IP foreground IP",
            "moral rights Copyright Act 1957 Patents Act assignment",
            "IP indemnification infringement third party claims"
        )),
        Map.entry("PAYMENT", List.of(
            "payment terms invoice due date late payment interest",
            "compensation fees charges GST tax MSMED Act",
            "payment schedule milestone billing disputed invoice",
            "consideration remuneration advance payment"
        )),
        Map.entry("SERVICES", List.of(
            "scope of services deliverables statement of work SOW",
            "service standards acceptance criteria change request",
            "subcontracting obligations performance service level",
            "Indian Contract Act Section 10 lawful object services"
        )),
        Map.entry("DEFINITIONS", List.of(
            "definitions interpretation meaning terms agreement",
            "confidential information disclosing receiving party purpose",
            "affiliate subsidiary intellectual property business day",
            "defined terms contract definitions schedule annexure"
        )),
        Map.entry("GENERAL_PROVISIONS", List.of(
            "entire agreement amendment modification waiver severability",
            "notices assignment subcontracting independent contractor",
            "counterparts force majeure survival governing law general",
            "boilerplate provisions miscellaneous general terms"
        ))
    );

    private static final List<String> DEFAULT_QUERIES = List.of(
        "contract clause obligations rights duties",
        "agreement terms conditions covenants"
    );

    /**
     * Retrieve and structure context for drafting a specific clause type.
     */
    public DraftContext retrieveForClause(String clauseType, DraftRequest request) {
        String normalizedType = clauseType != null ? clauseType.toUpperCase() : "LIABILITY";
        List<String> queries = CLAUSE_QUERIES.getOrDefault(normalizedType, DEFAULT_QUERIES);

        List<EmbeddingMatch<TextSegment>> allCandidates = multiQueryRetrieve(queries);
        List<EmbeddingMatch<TextSegment>> filtered = filterByMetadata(allCandidates, request, normalizedType);
        List<EmbeddingMatch<TextSegment>> diversified = applyDocumentDiversity(filtered);
        List<EmbeddingMatch<TextSegment>> ranked = reRanker.rerank(diversified, queries.get(0), topK);
        String structuredContext = buildStructuredContext(ranked, contextMaxChars);
        return new DraftContext(structuredContext, ranked.size(), collectProvenance(ranked));
    }

    private List<EmbeddingMatch<TextSegment>> multiQueryRetrieve(List<String> queries) {
        Set<String> seen = new HashSet<>();
        List<EmbeddingMatch<TextSegment>> merged = new ArrayList<>();

        for (String q : queries) {
            String expanded = queryExpander.expand(q);
            Embedding embedding = embeddingModel.embed(expanded).content();
            List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(embedding, candidateCount / 2);
            for (EmbeddingMatch<TextSegment> m : matches) {
                String key = keyFor(m);
                if (seen.add(key)) {
                    merged.add(m);
                }
            }
        }

        return merged.stream()
                .sorted(Comparator.comparingDouble((EmbeddingMatch<TextSegment> m) -> m.score()).reversed())
                .limit(candidateCount)
                .toList();
    }

    private List<EmbeddingMatch<TextSegment>> filterByMetadata(
            List<EmbeddingMatch<TextSegment>> candidates,
            DraftRequest request,
            String clauseType
    ) {
        String targetDocType = mapTemplateToDocumentType(request.getTemplateId());
        String targetJurisdiction = (request.getJurisdiction() == null || request.getJurisdiction().isBlank())
                ? "India" : request.getJurisdiction();

        // Determine acceptable clause types for this draft request
        Set<String> acceptableClauseTypes = getAcceptableClauseTypes(clauseType);

        List<EmbeddingMatch<TextSegment>> filtered = candidates.stream()
                .filter(m -> {
                    String docType = m.embedded().metadata().getString("document_type");
                    String jurisdiction = m.embedded().metadata().getString("jurisdiction");
                    String chunkClauseType = m.embedded().metadata().getString("clause_type");

                    boolean docTypeMatch = docType == null || targetDocType == null
                            || docType.equalsIgnoreCase(targetDocType)
                            || isRelatedDocType(docType, targetDocType);
                    boolean jurisdictionMatch = jurisdiction == null || jurisdiction.isBlank()
                            || targetJurisdiction.equalsIgnoreCase(jurisdiction)
                            || jurisdiction.toLowerCase().contains("india");
                    boolean clauseMatch = chunkClauseType == null || chunkClauseType.isBlank()
                            || acceptableClauseTypes.contains(chunkClauseType.toUpperCase());

                    return docTypeMatch && jurisdictionMatch && clauseMatch;
                })
                .toList();

        if (filtered.size() < 3) {
            log.debug("Few metadata-filtered matches ({}), falling back to top candidates", filtered.size());
            return candidates.stream().limit(candidateCount).toList();
        }
        return filtered;
    }

    private Set<String> getAcceptableClauseTypes(String clauseType) {
        return switch (clauseType) {
            case "LIABILITY" -> Set.of("LIABILITY", "INDEMNITY", "GENERAL");
            case "TERMINATION" -> Set.of("TERMINATION", "GENERAL");
            case "CONFIDENTIALITY" -> Set.of("CONFIDENTIALITY", "GENERAL");
            case "GOVERNING_LAW" -> Set.of("GOVERNING_LAW", "GENERAL");
            case "IP_RIGHTS" -> Set.of("IP_RIGHTS", "GENERAL");
            case "PAYMENT" -> Set.of("PAYMENT", "GENERAL");
            case "SERVICES" -> Set.of("SERVICES", "GENERAL");
            case "DEFINITIONS" -> Set.of("DEFINITIONS", "GENERAL");
            case "GENERAL_PROVISIONS" -> Set.of("GENERAL");
            default -> Set.of("GENERAL", clauseType);
        };
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
            String fileName = m.embedded().metadata().getString("file_name");
            String section = m.embedded().metadata().getString("section_path");
            String docType = m.embedded().metadata().getString("document_type");
            String jurisdiction = m.embedded().metadata().getString("jurisdiction");

            String text;
            try {
                text = encryptionService.decrypt(m.embedded().text());
            } catch (Exception e) {
                text = m.embedded().text();
            }

            String header = String.format("[Source %d: %s | %s | %s | %s]",
                    i + 1,
                    fileName != null ? fileName : "Unknown",
                    section != null ? section : "",
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

    private String mapTemplateToDocumentType(String templateId) {
        if (templateId == null) return null;
        return switch (templateId.toLowerCase()) {
            case "nda" -> "NDA";
            case "msa" -> "MSA";
            default -> null;
        };
    }

    public record DraftContext(String structuredContext, int chunkCount, List<String> sourceDocuments) {}
}
