package com.legalpartner.config;

import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Provides jurisdiction-specific legal references for all LLM prompts.
 * Controlled by legalpartner.legal-system (INDIA | USA).
 */
@Component
@ConfigurationProperties(prefix = "legalpartner")
@Setter
public class LegalSystemConfig {

    private String legalSystem = "INDIA";

    public String getLegalSystem() { return legalSystem; }

    public boolean isUSA() { return "USA".equalsIgnoreCase(legalSystem); }

    // ── Role descriptions ──────────────────────────────────────────────────────

    public String legalAnalystExpertise() {
        return isUSA()
            ? "US legal analyst with expertise in contract law, Uniform Commercial Code (UCC), and US corporate law"
            : "Indian legal analyst with expertise in contract law, Indian Contract Act 1872, and Indian corporate law";
    }

    public String legalDraftsman() {
        return isUSA() ? "US legal draftsman" : "Indian legal draftsman";
    }

    public String legalRiskAnalyst() {
        return isUSA() ? "US legal risk analyst" : "Indian legal risk analyst";
    }

    public String legalRiskConsultant() {
        return isUSA() ? "US legal risk consultant" : "Indian legal risk consultant";
    }

    public String legalEditor() {
        return isUSA() ? "US legal editor" : "Indian legal editor";
    }

    public String legalReviewer() {
        return isUSA() ? "senior US legal reviewer" : "senior Indian legal reviewer";
    }

    // ── Law system ─────────────────────────────────────────────────────────────

    public String country() { return isUSA() ? "United States" : "India"; }

    public String lawSystem() { return isUSA() ? "US law" : "Indian law"; }

    public String lawComplianceContext() {
        return isUSA()
            ? "US law compliance (UCC and applicable federal/state statutes)"
            : "Indian law compliance (Indian Contract Act 1872 and related statutes)";
    }

    // ── Contract act references ────────────────────────────────────────────────

    public String contractAct() {
        return isUSA()
            ? "Uniform Commercial Code (UCC) and applicable common law"
            : "Indian Contract Act 1872";
    }

    public String contractActLiabilitySections() {
        return isUSA()
            ? "UCC § 2-719 and applicable state law"
            : "Indian Contract Act 1872, Sections 73-74";
    }

    public String contractActRepudiation() {
        return isUSA()
            ? "applicable anticipatory breach doctrine under common law"
            : "Indian Contract Act 1872, Section 39 (repudiation)";
    }

    public String contractActMisrepresentation() {
        return isUSA()
            ? "Restatement (Second) of Contracts §§ 159-164"
            : "Indian Contract Act 1872 Section 18 (misrepresentation) and Section 17 (fraud)";
    }

    public String contractActLawfulObject() {
        return isUSA()
            ? "applicable common law principles of lawful consideration"
            : "Indian Contract Act 1872 Section 10 (lawful object)";
    }

    // ── Dispute resolution ─────────────────────────────────────────────────────

    public String arbitrationAct() {
        return isUSA()
            ? "Federal Arbitration Act (9 U.S.C.) and applicable state arbitration rules"
            : "Arbitration and Conciliation Act 1996";
    }

    public String arbitrationSeat() {
        return isUSA() ? "New York, New York, United States" : "Mumbai, Maharashtra, India";
    }

    public String courtSeat() {
        return isUSA() ? "New York, New York" : "Mumbai, Maharashtra";
    }

    public String governingLawStatement() {
        return isUSA()
            ? "Governing law — laws of the State of New York, United States."
            : "Governing law — laws of India, Indian Contract Act 1872.";
    }

    // ── IP / Data / Tax ────────────────────────────────────────────────────────

    public String ipLaws() {
        return isUSA()
            ? "Copyright Act of 1976 (17 U.S.C.) and Patent Act (35 U.S.C.)"
            : "Copyright Act 1957 and Patents Act 1970";
    }

    public String moralRightsRef() {
        return isUSA()
            ? "moral rights (17 U.S.C. § 106A, where applicable)"
            : "moral rights (Copyright Act 1957)";
    }

    public String dataProtectionLaws() {
        return isUSA()
            ? "CCPA, COPPA, and applicable state privacy laws"
            : "Digital Personal Data Protection Act 2023 (DPDPA) and IT Act 2000";
    }

    public String dataProtectionLawsAbbrev() {
        return isUSA() ? "CCPA / applicable state privacy laws" : "IT Act 2000 / DPDPA";
    }

    public String taxRef() {
        return isUSA() ? "applicable federal and state taxes" : "GST/taxes";
    }

    public String msmeRef() {
        return isUSA()
            ? "applicable small business payment regulations"
            : "MSMED Act for MSMEs if applicable";
    }

    // ── Localization ───────────────────────────────────────────────────────────

    /**
     * Replace all %MARKER% placeholders in a prompt with jurisdiction-specific values.
     * Call this on every system prompt before sending to the LLM.
     */
    public String localize(String prompt) {
        return prompt
            .replace("%COUNTRY%",                        country())
            .replace("%LEGAL_ANALYST_EXPERTISE%",        legalAnalystExpertise())
            .replace("%LEGAL_DRAFTSMAN%",                legalDraftsman())
            .replace("%LEGAL_RISK_ANALYST%",             legalRiskAnalyst())
            .replace("%LEGAL_RISK_CONSULTANT%",          legalRiskConsultant())
            .replace("%LEGAL_EDITOR%",                   legalEditor())
            .replace("%LEGAL_REVIEWER%",                 legalReviewer())
            .replace("%LAW_SYSTEM%",                     lawSystem())
            .replace("%LAW_COMPLIANCE%",                 lawComplianceContext())
            .replace("%CONTRACT_ACT_LIABILITY_SECTIONS%",contractActLiabilitySections())
            .replace("%CONTRACT_ACT_REPUDIATION%",       contractActRepudiation())
            .replace("%CONTRACT_ACT_MISREPRESENTATION%", contractActMisrepresentation())
            .replace("%CONTRACT_ACT_LAWFUL_OBJECT%",     contractActLawfulObject())
            .replace("%CONTRACT_ACT%",                   contractAct())   // after specific variants
            .replace("%ARBITRATION_ACT%",                arbitrationAct())
            .replace("%ARBITRATION_SEAT%",               arbitrationSeat())
            .replace("%COURT_SEAT%",                     courtSeat())
            .replace("%GOVERNING_LAW_STATEMENT%",        governingLawStatement())
            .replace("%IP_LAWS%",                        ipLaws())
            .replace("%MORAL_RIGHTS_REF%",               moralRightsRef())
            .replace("%DATA_PROTECTION_LAWS%",           dataProtectionLaws())
            .replace("%DATA_PROTECTION_LAWS_ABBREV%",    dataProtectionLawsAbbrev())
            .replace("%TAX_REF%",                        taxRef())
            .replace("%MSME_REF%",                       msmeRef());
    }
}
