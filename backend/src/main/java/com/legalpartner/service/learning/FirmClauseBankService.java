package com.legalpartner.service.learning;

import com.legalpartner.config.LegalVocabulary;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.entity.learning.ClauseObservation;
import com.legalpartner.model.entity.learning.FirmClause;
import com.legalpartner.rag.DocumentFullTextRetriever;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.repository.learning.ClauseObservationRepository;
import com.legalpartner.repository.learning.FirmClauseRepository;
import com.legalpartner.service.AnonymizationService;
import com.legalpartner.service.EncryptionService;
import com.legalpartner.service.RiskQuestionEngine;
import com.legalpartner.service.review.ClauseInventory;
import com.legalpartner.service.review.ClauseSpecRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Firm clause bank: the firm's own recurring wording, mined from its documents and approved
 * by a lawyer before the drafter may use it.
 *
 * Observe → mine → approve → draft:
 * <ol>
 *   <li><b>Observe</b>: segment each firm document into clauses (review inventory, headings
 *       first), map to drafting clause keys, store encrypted text + MinHash signature.</li>
 *   <li><b>Mine</b>: per (clause key, contract type), MinHash LSH + union-find clusters of
 *       near-duplicate wording; clusters used in ≥ min-support documents become CANDIDATE
 *       clauses (medoid text, templated so no client names or deal values remain).</li>
 *   <li><b>Approve</b>: a lawyer approves (optionally editing the text and choosing a position),
 *       rejects or retires. Only APPROVED clauses are ever used.</li>
 *   <li><b>Draft</b>: {@link #selectForDraft} picks the approved clause for the stance — never by
 *       past usage, so a clause's popularity cannot amplify itself.</li>
 * </ol>
 */
@Service
@Slf4j
public class FirmClauseBankService {

    public static final String CANDIDATE = "CANDIDATE";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String RETIRED = "RETIRED";

    private final ClauseObservationRepository observations;
    private final FirmClauseRepository firmClauses;
    private final DocumentMetadataRepository documents;
    private final DocumentFullTextRetriever fullText;
    private final DocumentTextReader textReader;
    private final EncryptionService encryption;
    private final AnonymizationService anonymization;
    private final RiskQuestionEngine riskQuestions;
    private final ClauseSpecRegistry spec;
    private final LearningConfig config;
    private final MinHasher minHasher;
    private final ClauseTemplater templater;

    public FirmClauseBankService(ClauseObservationRepository observations, FirmClauseRepository firmClauses,
                                 DocumentMetadataRepository documents, DocumentFullTextRetriever fullText,
                                 DocumentTextReader textReader, EncryptionService encryption,
                                 AnonymizationService anonymization, RiskQuestionEngine riskQuestions,
                                 ClauseSpecRegistry spec, LearningConfig config, LegalVocabulary vocabulary) {
        this.observations = observations;
        this.firmClauses = firmClauses;
        this.documents = documents;
        this.fullText = fullText;
        this.textReader = textReader;
        this.encryption = encryption;
        this.anonymization = anonymization;
        this.riskQuestions = riskQuestions;
        this.spec = spec;
        this.config = config;
        this.minHasher = MinHasher.from(config);
        this.templater = ClauseTemplater.from(config, vocabulary);
    }

    public record FirmClauseView(UUID id, String clauseKey, String contractType, String status, String position,
                                 String text, int supportCount, int executedCount, int timesUsed,
                                 String approvedBy, Instant approvedAt, Instant updatedAt) {}

    // ── Observe ────────────────────────────────────────────────────────────────

    /** Which documents feed the bank (learning.yml sources / signed_statuses). */
    boolean isMineable(DocumentMetadata doc) {
        return doc != null && doc.getDocumentType() != null
                && config.isFirmPrecedent(doc.getSource(), doc.getContractStatus());
    }

    /** Segment a document into clause observations (idempotent per document and clause). */
    @Transactional
    public int observeDocument(UUID documentId) {
        if (!config.isEnabled()) return 0;
        DocumentMetadata doc = documents.findById(documentId).orElse(null);
        if (!isMineable(doc)) return 0;
        String text = config.isAiGenerated(doc.getSource()) ? textReader.read(doc.getStoredPath()) : fullText.retrieveFullTextUncapped(documentId);
        if (text == null || text.isBlank()) return 0;

        String contractType = doc.getDocumentType().name();
        boolean executed = config.isSigned(doc.getContractStatus());
        Map<String, String> byReviewKey = ClauseInventory.inventory(text, riskQuestions.getClauseKeywords(), config.getMiningMaxClauseChars());
        int stored = 0;
        for (var e : byReviewKey.entrySet()) {
            String clauseText = e.getValue().strip();
            if (clauseText.length() < config.getMiningMinClauseChars()) continue;
            Optional<String> clauseKey = spec.draftingKeyFor(e.getKey());
            if (clauseKey.isEmpty()) continue;
            ClauseObservation obs = observations.findByDocumentIdAndClauseKey(documentId, clauseKey.get())
                    .orElseGet(() -> ClauseObservation.builder().documentId(documentId).clauseKey(clauseKey.get()).build());
            obs.setContractType(contractType);
            obs.setTextEnc(encryption.encrypt(clauseText));
            obs.setTextSha256(sha256(clauseText));
            obs.setMinhash(MinHasher.encode(minHasher.signature(clauseText)));
            obs.setExecuted(executed);
            obs.setObservedAt(Instant.now());
            observations.save(obs);
            stored++;
        }
        log.info("Clause bank: observed {} clause(s) in {} ({}, executed={})", stored, doc.getFileName(), contractType, executed);
        return stored;
    }

    // ── Mine ───────────────────────────────────────────────────────────────────

    public record MiningReport(int groups, int clusters, int created, int updated) {}

    @Scheduled(cron = "${legalpartner.learning.mining.cron:0 30 2 * * *}")
    public void scheduledMine() {
        if (config.isEnabled()) mine();
    }

    @Transactional
    public MiningReport mine() {
        int groups = 0, clustersSeen = 0, created = 0, updated = 0;
        for (Object[] g : observations.findGroups()) {
            String clauseKey = (String) g[0];
            String contractType = (String) g[1];
            List<ClauseObservation> obs = observations.findByClauseKeyAndContractType(clauseKey, contractType);
            if (obs.size() < config.getMiningMinSupport()) continue;
            groups++;
            List<int[]> sigs = obs.stream().map(o -> MinHasher.decode(o.getMinhash())).toList();
            for (List<Integer> cluster : minHasher.cluster(sigs, config.getMiningSimilarity())) {
                Set<String> docs = new LinkedHashSet<>();
                Set<String> signedDocs = new HashSet<>();
                for (int i : cluster) {
                    docs.add(obs.get(i).getDocumentId().toString());
                    if (obs.get(i).isExecuted()) signedDocs.add(obs.get(i).getDocumentId().toString());
                }
                if (docs.size() < config.getMiningMinSupport()) continue;
                clustersSeen++;
                ClauseObservation medoid = obs.get(MinHasher.medoid(cluster, sigs));
                boolean isNew = upsertCandidate(clauseKey, contractType, medoid, docs, signedDocs.size());
                if (isNew) created++; else updated++;
            }
        }
        log.info("Clause bank mining: {} groups, {} clusters, {} new candidates, {} updated", groups, clustersSeen, created, updated);
        return new MiningReport(groups, clustersSeen, created, updated);
    }

    /** Update the firm clause that already covers this cluster (mining.cluster_match_overlap) or create a candidate. */
    private boolean upsertCandidate(String clauseKey, String contractType, ClauseObservation medoid,
                                    Set<String> docs, int signed) {
        List<FirmClause> existing = new ArrayList<>();
        for (String status : List.of(CANDIDATE, APPROVED, REJECTED, RETIRED)) {
            existing.addAll(firmClauses.findByClauseKeyAndContractTypeAndStatus(clauseKey, contractType, status));
        }
        Optional<FirmClause> match = existing.stream().filter(f -> overlap(InsightService.parseIds(f.getSourceDocumentIds()), docs) >= config.getClusterMatchOverlap()).findFirst();
        FirmClause fc = match.orElseGet(() -> FirmClause.builder().clauseKey(clauseKey).contractType(contractType)
                .status(CANDIDATE).position(config.defaultPosition()).build());
        fc.setSupportCount(docs.size());
        fc.setExecutedCount(signed);
        fc.setSourceDocumentIds(String.join(",", docs));
        if (CANDIDATE.equals(fc.getStatus()) || fc.getTextEnc() == null) {
            // Only unapproved candidates track the latest medoid; approved text belongs to the lawyer.
            String templated = templatize(medoid);
            fc.setTextEnc(encryption.encrypt(templated));
            fc.setFingerprint(sha256(templated));
        }
        fc.setUpdatedAt(Instant.now());
        firmClauses.save(fc);
        return match.isEmpty();
    }

    private String templatize(ClauseObservation obs) {
        String text = encryption.decrypt(obs.getTextEnc());
        DocumentMetadata doc = documents.findById(obs.getDocumentId()).orElse(null);
        Set<String> entities = new LinkedHashSet<>(anonymization.detectConcreteEntities(text));
        if (doc != null && doc.getClientName() != null) entities.add(doc.getClientName());
        return templater.templatize(text, doc != null ? doc.getPartyA() : null,
                doc != null ? doc.getPartyB() : null, entities);
    }

    static double overlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        return (double) inter.size() / Math.min(a.size(), b.size());
    }

    // ── Approve ────────────────────────────────────────────────────────────────

    @Transactional
    public FirmClauseView approve(UUID id, String editedText, String position, String user) {
        FirmClause fc = get(id);
        if (editedText != null && !editedText.isBlank()) {
            fc.setTextEnc(encryption.encrypt(editedText.strip()));
            fc.setFingerprint(sha256(editedText.strip()));
        }
        if (position != null && !position.isBlank()) {
            if (!config.isPosition(position)) throw new IllegalArgumentException("position must be one of " + config.getPositions());
            fc.setPosition(position);
        }
        fc.setStatus(APPROVED);
        fc.setApprovedBy(user);
        fc.setApprovedAt(Instant.now());
        fc.setUpdatedAt(Instant.now());
        return view(firmClauses.save(fc));
    }

    @Transactional
    public FirmClauseView setStatus(UUID id, String status) {
        FirmClause fc = get(id);
        fc.setStatus(status);
        fc.setUpdatedAt(Instant.now());
        return view(firmClauses.save(fc));
    }

    public List<FirmClauseView> list(String status) {
        List<FirmClause> rows = status == null ? firmClauses.findAll() : firmClauses.findByStatusOrderByUpdatedAtDesc(status);
        return rows.stream().map(this::view).toList();
    }

    // ── Draft ──────────────────────────────────────────────────────────────────

    public record Selection(UUID firmClauseId, String text) {}

    /**
     * Approved firm clause for a drafted clause, or empty. The stance picks the position
     * via learning.yml firm_clauses.stance_position_order.
     */
    public Optional<Selection> selectForDraft(String clauseKey, String contractType, String stance) {
        if (!config.isEnabled() || contractType == null) return Optional.empty();
        List<FirmClause> approved = firmClauses.findByClauseKeyAndContractTypeAndStatus(clauseKey, contractType, APPROVED);
        return choose(approved, config.positionOrder(stance)).map(fc -> new Selection(fc.getId(), encryption.decrypt(fc.getTextEnc())));
    }

    static Optional<FirmClause> choose(List<FirmClause> approved, List<String> positionOrder) {
        if (approved == null || approved.isEmpty()) return Optional.empty();
        Map<String, FirmClause> byPosition = new LinkedHashMap<>();
        for (FirmClause fc : approved) {
            // Most recently approved wins within a position (a lawyer's latest decision).
            FirmClause current = byPosition.get(fc.getPosition());
            if (current == null || (fc.getApprovedAt() != null && current.getApprovedAt() != null
                    && fc.getApprovedAt().isAfter(current.getApprovedAt()))) {
                byPosition.put(fc.getPosition(), fc);
            }
        }
        for (String p : positionOrder) if (byPosition.containsKey(p)) return Optional.of(byPosition.get(p));
        return Optional.empty();
    }

    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

    /**
     * Firm clause text for a new deal: party placeholders become this deal's party names,
     * every other placeholder (amounts, dates, details) becomes a fill-in field for the lawyer.
     */
    public String render(String text, String partyA, String partyB) {
        String out = text;
        if (partyA != null && !partyA.isBlank()) out = out.replace(config.getPartyAPlaceholder(), partyA.trim());
        if (partyB != null && !partyB.isBlank()) out = out.replace(config.getPartyBPlaceholder(), partyB.trim());
        java.util.regex.Matcher m = PLACEHOLDER.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1).replace('.', '_');
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    com.legalpartner.service.GoldenClauseLibrary.fillMarker(name)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    @Transactional
    public void recordUse(UUID firmClauseId) {
        firmClauses.findById(firmClauseId).ifPresent(fc -> {
            fc.setTimesUsed(fc.getTimesUsed() + 1);
            firmClauses.save(fc);
        });
    }

    @Transactional
    public void forgetDocument(UUID documentId) {
        observations.deleteByDocumentId(documentId);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private FirmClause get(UUID id) {
        return firmClauses.findById(id).orElseThrow(() -> new java.util.NoSuchElementException("Firm clause not found"));
    }

    private FirmClauseView view(FirmClause fc) {
        String text;
        try { text = encryption.decrypt(fc.getTextEnc()); } catch (Exception e) { text = ""; }
        return new FirmClauseView(fc.getId(), fc.getClauseKey(), fc.getContractType(), fc.getStatus(), fc.getPosition(),
                text, fc.getSupportCount(), fc.getExecutedCount(), fc.getTimesUsed(), fc.getApprovedBy(),
                fc.getApprovedAt(), fc.getUpdatedAt());
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
