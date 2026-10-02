package com.legalpartner.service.review;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Structured record of a generated draft, written next to its HTML as
 * {@code <docId>.draft.json}. Review reads this instead of re-parsing HTML, so it sees
 * exactly the sections the drafter produced, under the right contract type and with
 * the party position the draft was written for.
 *
 * @param version        schema version (bump on incompatible changes)
 * @param templateId     drafting template (contract_types.yml key)
 * @param reviewType     review contract-type code (risk_questions.yml key)
 * @param clientPosition PARTY_A / PARTY_B / NEUTRAL / null
 * @param draftStance    FIRST_DRAFT / BALANCED / FINAL_OFFER / null
 * @param dealBrief      the brief the draft was generated from (learning: eval-case export)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DraftManifest(
        int version,
        String templateId,
        String reviewType,
        String clientPosition,
        String draftStance,
        String jurisdiction,
        List<Section> sections,
        String dealBrief
) {
    public static final int CURRENT_VERSION = 1;

    public DraftManifest(int version, String templateId, String reviewType, String clientPosition,
                         String draftStance, String jurisdiction, List<Section> sections) {
        this(version, templateId, reviewType, clientPosition, draftStance, jurisdiction, sections, null);
    }

    /**
     * @param key        drafting clause key (clauses.yml)
     * @param reviewKeys review clause keys this section is reviewed as
     * @param text       plain text of the section body (HTML stripped, line breaks kept)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Section(String key, String title, int article, List<String> reviewKeys, String text) {}
}
