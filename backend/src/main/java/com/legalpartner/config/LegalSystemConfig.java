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
     * Replace all %MARKER% placeholders using the server-level legal system config.
     * Call this on prompts that are NOT per-request (e.g. risk/review analysis).
     */
    public String localize(String prompt) {
        return localizeForJurisdiction(prompt, null);
    }

    /**
     * Replace all %MARKER% placeholders using a per-request jurisdiction string.
     * This overrides the server-level legalSystem setting for drafting prompts.
     * Examples: "California, United States", "England and Wales", "India (Maharashtra)"
     */
    public String localizeForJurisdiction(String prompt, String jurisdiction) {
        JurisdictionContext ctx = detectJurisdiction(jurisdiction);
        return prompt
            .replace("%COUNTRY%",                        ctx.country)
            .replace("%LEGAL_ANALYST_EXPERTISE%",        ctx.legalAnalystExpertise)
            .replace("%LEGAL_DRAFTSMAN%",                ctx.legalDraftsman)
            .replace("%LEGAL_RISK_ANALYST%",             ctx.legalRiskAnalyst)
            .replace("%LEGAL_RISK_CONSULTANT%",          ctx.legalRiskConsultant)
            .replace("%LEGAL_EDITOR%",                   ctx.legalEditor)
            .replace("%LEGAL_REVIEWER%",                 ctx.legalReviewer)
            .replace("%LAW_SYSTEM%",                     ctx.lawSystem)
            .replace("%LAW_COMPLIANCE%",                 ctx.lawCompliance)
            .replace("%CONTRACT_ACT_LIABILITY_SECTIONS%",ctx.contractActLiabilitySections)
            .replace("%CONTRACT_ACT_REPUDIATION%",       ctx.contractActRepudiation)
            .replace("%CONTRACT_ACT_MISREPRESENTATION%", ctx.contractActMisrepresentation)
            .replace("%CONTRACT_ACT_LAWFUL_OBJECT%",     ctx.contractActLawfulObject)
            .replace("%CONTRACT_ACT%",                   ctx.contractAct)
            .replace("%ARBITRATION_ACT%",                ctx.arbitrationAct)
            .replace("%ARBITRATION_SEAT%",               ctx.arbitrationSeat)
            .replace("%COURT_SEAT%",                     ctx.courtSeat)
            .replace("%GOVERNING_LAW_STATEMENT%",        ctx.governingLawStatement)
            .replace("%DISPUTE_RESOLUTION_CLAUSE%",      ctx.disputeResolutionClause)
            .replace("%IP_LAWS%",                        ctx.ipLaws)
            .replace("%MORAL_RIGHTS_REF%",               ctx.moralRightsRef)
            .replace("%DATA_PROTECTION_LAWS%",           ctx.dataProtectionLaws)
            .replace("%DATA_PROTECTION_LAWS_ABBREV%",    ctx.dataProtectionLawsAbbrev)
            .replace("%TAX_REF%",                        ctx.taxRef)
            .replace("%MSME_REF%",                       ctx.msmeRef);
    }

    /** Detects legal system from a jurisdiction string and returns all legal references. */
    private JurisdictionContext detectJurisdiction(String jurisdiction) {
        if (jurisdiction == null || jurisdiction.isBlank()) {
            // Fall back to server-level config
            return isUSA() ? usaContext("the applicable state") : indiaContext();
        }
        String j = jurisdiction.toLowerCase();
        if (j.contains("california"))       return usaContext("California");
        if (j.contains("new york"))         return usaContext("New York");
        if (j.contains("delaware"))         return usaContext("Delaware");
        if (j.contains("united states") || j.contains("u.s.")) return usaContext("the applicable state");
        if (j.contains("england") || j.contains("wales"))      return ukContext();
        if (j.contains("singapore"))        return singaporeContext();
        if (j.contains("germany"))          return genericEUContext("Germany", "German law (BGB)");
        if (j.contains("france"))           return genericEUContext("France", "French law (Code Civil)");
        if (j.contains("australia"))        return australiaContext(jurisdiction);
        // India and everything else
        return indiaContext();
    }

    private JurisdictionContext usaContext(String state) {
        String courtCity = state.equals("California") ? "San Francisco, California"
                         : state.equals("New York")   ? "New York, New York"
                         : state.equals("Delaware")   ? "Wilmington, Delaware"
                         : "the applicable state court";
        return new JurisdictionContext(
            "United States",
            "US legal analyst with expertise in contract law, UCC, and US corporate law",
            "US legal draftsman",
            "US legal risk analyst", "US legal risk consultant",
            "US legal editor", "senior US legal reviewer",
            "US law", "US law compliance (UCC and applicable federal/state statutes)",
            "UCC § 2-719 and applicable state law",
            "applicable anticipatory breach doctrine under common law",
            "Restatement (Second) of Contracts §§ 159-164",
            "applicable common law principles of lawful consideration",
            "Uniform Commercial Code (UCC) and applicable common law",
            "Federal Arbitration Act (9 U.S.C.) and applicable state arbitration rules",
            courtCity, courtCity,
            "Governing law — laws of the State of " + state + ", United States.",
            // Courts-only dispute resolution — no arbitration for US
            "Sub-clause 3: Courts — each Party irrevocably submits to the exclusive jurisdiction of the state and federal courts sitting in " + courtCity + " for resolution of any dispute arising under this Agreement. Each Party waives any objection to venue or inconvenient forum.",
            "Copyright Act of 1976 (17 U.S.C.) and Patent Act (35 U.S.C.)",
            "moral rights (17 U.S.C. § 106A, where applicable)",
            "CCPA, COPPA, and applicable state privacy laws",
            "CCPA / applicable state privacy laws",
            "applicable federal and state taxes",
            "applicable small business payment regulations"
        );
    }

    private JurisdictionContext ukContext() {
        return new JurisdictionContext(
            "England and Wales",
            "English legal analyst with expertise in contract law and English common law",
            "English legal draftsman",
            "English legal risk analyst", "English legal risk consultant",
            "English legal editor", "senior English legal reviewer",
            "English law", "English law compliance",
            "applicable provisions of the Contracts (Rights of Third Parties) Act 1999",
            "applicable anticipatory breach doctrine under English common law",
            "Misrepresentation Act 1967",
            "applicable common law principles of lawful consideration",
            "English common law and applicable statutes",
            "Arbitration Act 1996", "London, England", "London, England",
            "Governing law — laws of England and Wales.",
            "Sub-clause 3: Courts — each Party irrevocably submits to the exclusive jurisdiction of the courts of England and Wales.",
            "Copyright, Designs and Patents Act 1988 and Patents Act 1977",
            "moral rights (Copyright, Designs and Patents Act 1988, ss. 77-89)",
            "UK GDPR and Data Protection Act 2018",
            "UK GDPR / DPA 2018",
            "applicable VAT and taxes",
            "applicable small business regulations"
        );
    }

    private JurisdictionContext singaporeContext() {
        return new JurisdictionContext(
            "Singapore",
            "Singapore legal analyst with expertise in contract law and Singapore law",
            "Singapore legal draftsman",
            "Singapore legal risk analyst", "Singapore legal risk consultant",
            "Singapore legal editor", "senior Singapore legal reviewer",
            "Singapore law", "Singapore law compliance",
            "applicable provisions of the Contracts (Rights of Third Parties) Act (Cap. 53B)",
            "applicable anticipatory breach doctrine under Singapore common law",
            "Misrepresentation Act (Cap. 390)",
            "applicable common law principles of lawful consideration",
            "Singapore common law and applicable statutes",
            "International Arbitration Act (Cap. 143A) and SIAC Rules",
            "Singapore", "Singapore",
            "Governing law — laws of Singapore.",
            "Sub-clause 3: Arbitration — disputes shall be resolved by arbitration administered by the Singapore International Arbitration Centre (SIAC) under the SIAC Rules, with seat at Singapore.",
            "Copyright Act 2021 and Patents Act (Cap. 221)",
            "moral rights (Copyright Act 2021)",
            "Personal Data Protection Act 2012 (PDPA)",
            "PDPA",
            "applicable GST and taxes",
            "applicable small business regulations"
        );
    }

    private JurisdictionContext australiaContext(String jurisdiction) {
        String state = jurisdiction.contains("Victoria") ? "Victoria"
                     : jurisdiction.contains("Queensland") ? "Queensland"
                     : "New South Wales";
        return new JurisdictionContext(
            "Australia", "Australian legal analyst", "Australian legal draftsman",
            "Australian legal risk analyst", "Australian legal risk consultant",
            "Australian legal editor", "senior Australian legal reviewer",
            "Australian law", "Australian law compliance (Australian Consumer Law and applicable statutes)",
            "applicable provisions of the Australian Consumer Law",
            "applicable anticipatory breach doctrine under Australian common law",
            "Australian Consumer Law (Schedule 2, Competition and Consumer Act 2010)",
            "applicable common law principles of lawful consideration",
            "Australian common law and applicable statutes",
            "International Arbitration Act 1974 (Cth)", "Sydney, New South Wales", "Sydney, New South Wales",
            "Governing law — laws of " + state + ", Australia.",
            "Sub-clause 3: Courts — each Party submits to the exclusive jurisdiction of the courts of " + state + ", Australia.",
            "Copyright Act 1968 (Cth) and Patents Act 1990 (Cth)",
            "moral rights (Copyright Act 1968 (Cth), Part IX)",
            "Privacy Act 1988 (Cth) and Australian Privacy Principles",
            "Privacy Act 1988 / APPs",
            "applicable GST and taxes",
            "applicable small business regulations"
        );
    }

    private JurisdictionContext genericEUContext(String country, String lawRef) {
        return new JurisdictionContext(
            country, country + " legal analyst", country + " legal draftsman",
            country + " legal risk analyst", country + " legal risk consultant",
            country + " legal editor", "senior " + country + " legal reviewer",
            country + " law", country + " law compliance",
            "applicable statutory provisions", "applicable anticipatory breach doctrine",
            "applicable misrepresentation provisions", "applicable principles of lawful consideration",
            lawRef, "ICC Arbitration Rules", country, country,
            "Governing law — " + lawRef + ".",
            "Sub-clause 3: Courts — each Party submits to the exclusive jurisdiction of the courts of " + country + ".",
            "applicable IP laws", "applicable moral rights provisions",
            "GDPR and applicable national data protection law", "GDPR",
            "applicable VAT and taxes", "applicable small business regulations"
        );
    }

    private JurisdictionContext indiaContext() {
        return new JurisdictionContext(
            "India",
            "Indian legal analyst with expertise in contract law, Indian Contract Act 1872, and Indian corporate law",
            "Indian legal draftsman",
            "Indian legal risk analyst", "Indian legal risk consultant",
            "Indian legal editor", "senior Indian legal reviewer",
            "Indian law", "Indian law compliance (Indian Contract Act 1872 and related statutes)",
            "Indian Contract Act 1872, Sections 73-74",
            "Indian Contract Act 1872, Section 39 (repudiation)",
            "Indian Contract Act 1872 Section 18 (misrepresentation) and Section 17 (fraud)",
            "Indian Contract Act 1872 Section 10 (lawful object)",
            "Indian Contract Act 1872",
            "Arbitration and Conciliation Act 1996",
            "Mumbai, Maharashtra, India", "Mumbai, Maharashtra",
            "Governing law — laws of India, Indian Contract Act 1872.",
            "Sub-clause 3: Arbitration — disputes shall be referred to arbitration under the Arbitration and Conciliation Act 1996, with a sole arbitrator and seat at Mumbai, Maharashtra, India.",
            "Copyright Act 1957 and Patents Act 1970",
            "moral rights (Copyright Act 1957)",
            "Digital Personal Data Protection Act 2023 (DPDPA) and IT Act 2000",
            "IT Act 2000 / DPDPA",
            "GST/taxes",
            "MSMED Act for MSMEs if applicable"
        );
    }

    /** All jurisdiction-specific legal references resolved for a single request. */
    private record JurisdictionContext(
        String country, String legalAnalystExpertise, String legalDraftsman,
        String legalRiskAnalyst, String legalRiskConsultant,
        String legalEditor, String legalReviewer,
        String lawSystem, String lawCompliance,
        String contractActLiabilitySections, String contractActRepudiation,
        String contractActMisrepresentation, String contractActLawfulObject, String contractAct,
        String arbitrationAct, String arbitrationSeat, String courtSeat,
        String governingLawStatement, String disputeResolutionClause,
        String ipLaws, String moralRightsRef,
        String dataProtectionLaws, String dataProtectionLawsAbbrev,
        String taxRef, String msmeRef
    ) {}
}
