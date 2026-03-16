package com.legalpartner.rag;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class QueryExpander {

    // Keys are ROOT STEMS (prefix match) so "terminat" matches "terminate", "termination", "terminating"
    private static final Map<String, List<String>> STEM_SYNONYMS = Map.ofEntries(
            Map.entry("terminat", List.of("exit clause", "right to terminate", "notice of termination", "expiry", "end of contract")),
            Map.entry("liabilit", List.of("limitation of liability", "cap on liability", "damages", "liable", "indemnit")),
            Map.entry("indemnit", List.of("indemnification", "hold harmless", "indemnify", "defend claims")),
            Map.entry("govern", List.of("jurisdiction", "applicable law", "choice of law", "governing law")),
            Map.entry("confidential", List.of("non-disclosure", "NDA", "proprietary information", "trade secret")),
            Map.entry("force majeure", List.of("act of god", "unforeseen circumstances", "beyond control", "vis major")),
            Map.entry("warrant", List.of("representation", "represents and warrants", "warranties", "guarantee")),
            Map.entry("intellectual property", List.of("IP rights", "patent", "copyright", "trademark", "work product")),
            Map.entry("ip right", List.of("intellectual property", "patent", "copyright", "trademark")),
            Map.entry("payment", List.of("compensation", "fees", "invoice", "remuneration", "consideration")),
            Map.entry("arbitrat", List.of("dispute resolution", "mediation", "conciliation", "ICADR", "ICC")),
            Map.entry("breach", List.of("default", "non-performance", "violation", "non-compliance")),
            Map.entry("assign", List.of("transfer of rights", "novation", "delegation", "assignment")),
            Map.entry("notice", List.of("written notice", "notification", "notice period", "cure period")),
            Map.entry("represent", List.of("warranties", "covenants", "undertakings", "representations")),
            Map.entry("non-compet", List.of("restraint of trade", "non-solicitation", "exclusivity")),
            Map.entry("data protect", List.of("PDPB", "GDPR", "personal data", "data processing", "privacy")),
            Map.entry("subcontract", List.of("outsourcing", "third party", "vendor", "sub-vendor"))
    );

    // Legal acronyms that should be expanded when seen as standalone terms
    private static final Map<String, String> ACRONYM_EXPANSIONS = Map.of(
            " nda ", " non-disclosure agreement confidentiality ",
            " msa ", " master services agreement ",
            " sow ", " statement of work ",
            " sla ", " service level agreement ",
            " mou ", " memorandum of understanding ",
            " loi ", " letter of intent ",
            " ica ", " Indian Contract Act 1872 "
    );

    public String expand(String query) {
        String lowerQuery = query.toLowerCase();
        StringBuilder expanded = new StringBuilder(query);

        // Acronym expansion (whole-word)
        String withAcronyms = " " + lowerQuery + " ";
        for (Map.Entry<String, String> entry : ACRONYM_EXPANSIONS.entrySet()) {
            if (withAcronyms.contains(entry.getKey())) {
                expanded.append(" ").append(entry.getValue().trim());
            }
        }

        // Stem-based synonym expansion
        for (Map.Entry<String, List<String>> entry : STEM_SYNONYMS.entrySet()) {
            if (lowerQuery.contains(entry.getKey())) {
                for (String synonym : entry.getValue()) {
                    if (!lowerQuery.contains(synonym.toLowerCase())) {
                        expanded.append(" ").append(synonym);
                    }
                }
            }
        }
        return expanded.toString();
    }
}
