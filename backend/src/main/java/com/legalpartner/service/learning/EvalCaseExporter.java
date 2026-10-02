package com.legalpartner.service.learning;

import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.entity.learning.ClauseEdit;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.repository.learning.ClauseEditRepository;
import com.legalpartner.service.AnonymizationService;
import com.legalpartner.service.EncryptionService;
import com.legalpartner.service.review.DraftManifest;
import com.legalpartner.service.review.DraftManifestStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns this firm's signed AI drafts into eval cases for {@code eval/drafting} — the briefs the
 * firm actually drafts, with the text it actually signed as reference. The export uses the
 * harness's brief format, so it can be passed straight to {@code --briefs}.
 *
 * Client data: cases are real deals. {@code anonymize=true} rewrites names, amounts, dates and
 * places with consistent synthetic values (one LLM call per case) — required before the
 * harness is pointed at a hosted model.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvalCaseExporter {

    private final LearningConfig config;
    private final DocumentMetadataRepository documents;
    private final DraftManifestStore manifests;
    private final ClauseEditRepository edits;
    private final EncryptionService encryption;
    private final AnonymizationService anonymization;

    public Map<String, Object> export(boolean anonymize, int limit) {
        List<Map<String, Object>> briefs = new ArrayList<>();
        int skipped = 0;
        for (DocumentMetadata doc : signedDrafts()) {
            if (briefs.size() >= limit) break;
            DraftManifest m = manifests.read(doc.getId()).orElse(null);
            if (m == null || m.dealBrief() == null || m.dealBrief().isBlank()) { skipped++; continue; }
            try {
                briefs.add(toCase(doc, m, anonymize));
            } catch (Exception e) {
                skipped++;
                log.warn("Eval export: {} skipped: {}", doc.getId(), e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("note", anonymize
                ? "Exported from signed drafts, anonymized. Review before sharing."
                : "Exported from signed drafts — CONTAINS CLIENT DATA. Keep on firm infrastructure; never use with hosted models.");
        out.put("skipped", skipped);
        out.put("briefs", briefs);
        return out;
    }

    private List<DocumentMetadata> signedDrafts() {
        List<DocumentMetadata> out = new ArrayList<>();
        for (String source : config.getAiGeneratedSources()) {
            out.addAll(documents.findBySourceAndContractStatusIn(source, config.getSignedStatuses()));
        }
        out.sort(Comparator.comparing(DocumentMetadata::getUploadDate, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    Map<String, Object> toCase(DocumentMetadata doc, DraftManifest m, boolean anonymize) {
        Map<String, String> reference = new LinkedHashMap<>();
        for (ClauseEdit e : edits.findByDocumentId(doc.getId())) {
            if (e.isFinal() && e.getFinalText() != null) reference.put(e.getClauseKey(), encryption.decrypt(e.getFinalText()));
        }
        String brief = m.dealBrief();
        String partyA = doc.getPartyA();
        String partyB = doc.getPartyB();
        String jurisdiction = m.jurisdiction();

        if (anonymize) {
            var result = anonymization.anonymize(brief);
            Map<String, String> map = result.entityMap();
            // Fail closed: anonymization returns the original text when it fails.
            if (map.isEmpty()) throw new IllegalStateException("anonymization produced no substitutions");
            brief = result.anonymizedText();
            partyA = apply(map, partyA);
            partyB = apply(map, partyB);
            jurisdiction = apply(map, jurisdiction);
            reference.replaceAll((k, v) -> apply(map, v));
            String all = String.join("\n", brief, String.valueOf(partyA), String.valueOf(partyB), String.join("\n", reference.values()));
            for (String raw : new String[]{doc.getPartyA(), doc.getPartyB(), doc.getClientName()}) {
                if (raw != null && raw.length() >= 3 && all.contains(raw)) {
                    throw new IllegalStateException("a client name survived anonymization");
                }
            }
        }

        List<String> mustContain = new ArrayList<>();
        if (partyA != null && !partyA.isBlank()) mustContain.add(partyA);
        if (partyB != null && !partyB.isBlank()) mustContain.add(partyB);

        Map<String, Object> expect = new LinkedHashMap<>();
        expect.put("terms_present", mustContain);
        expect.put("terms_absent", List.of());
        expect.put("min_articles", Math.max(1, m.sections().size() - 1));

        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", "firm-" + doc.getId().toString().substring(0, 8));
        c.put("templateId", m.templateId());
        c.put("clientPosition", m.clientPosition());
        c.put("draftStance", m.draftStance());
        c.put("jurisdiction", jurisdiction);
        if (partyA != null) c.put("partyA", partyA);
        if (partyB != null) c.put("partyB", partyB);
        c.put("dealBrief", brief);
        c.put("expect", expect);
        c.put("reference_clauses", reference); // signed text per clause, for the judge
        return c;
    }

    private static String apply(Map<String, String> map, String text) {
        if (text == null) return null;
        String out = text;
        List<String> keys = new ArrayList<>(map.keySet());
        keys.sort(Comparator.comparingInt(String::length).reversed());
        for (String k : keys) if (k != null && !k.isBlank()) out = out.replace(k, map.get(k));
        return out;
    }
}
