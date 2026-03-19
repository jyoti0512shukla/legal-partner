package com.legalpartner.rag;

import java.util.List;
import java.util.Map;

/**
 * JSON Schemas for vLLM guided_json constrained decoding.
 *
 * Each schema is passed to VllmGuidedClient which forwards it to vLLM as guided_json.
 * vLLM uses Outlines to mask invalid tokens — enum fields can only produce their
 * allowed values, required fields must appear, arrays are bounded. The model physically
 * cannot deviate from the schema regardless of what the underlying prompt says.
 */
public final class StructuredSchemas {

    private StructuredSchemas() {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Map<String, Object> strProp() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> nullableStr() {
        return Map.of("type", List.of("string", "null"));
    }

    private static Map<String, Object> enumProp(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    // ── Risk Assessment ───────────────────────────────────────────────────────
    //
    // {
    //   "overall_risk": "HIGH|MEDIUM|LOW",
    //   "categories": [
    //     { "name": "...", "rating": "HIGH|MEDIUM|LOW", "justification": "...", "section_ref": "..." }
    //   ]
    // }

    public static final Map<String, Object> RISK_SCHEMA = Map.of(
            "type", "object",
            "required", List.of("overall_risk", "categories"),
            "properties", Map.of(
                    "overall_risk", enumProp("HIGH", "MEDIUM", "LOW"),
                    "categories", Map.of(
                            "type", "array",
                            "minItems", 7,
                            "maxItems", 7,
                            "items", Map.of(
                                    "type", "object",
                                    "required", List.of("name", "rating", "justification", "section_ref"),
                                    "properties", Map.of(
                                            "name", strProp(),
                                            "rating", enumProp("HIGH", "MEDIUM", "LOW"),
                                            "justification", strProp(),
                                            "section_ref", strProp()
                                    )
                            )
                    )
            )
    );

    // ── Document Comparison ───────────────────────────────────────────────────
    //
    // {
    //   "dimensions": [
    //     { "name": "Liability", "doc1_summary": "...", "doc2_summary": "...",
    //       "favorable": "doc1|doc2|neutral", "reasoning": "..." }
    //   ]
    // }

    public static final Map<String, Object> COMPARE_SCHEMA = Map.of(
            "type", "object",
            "required", List.of("dimensions"),
            "properties", Map.of(
                    "dimensions", Map.of(
                            "type", "array",
                            "minItems", 7,
                            "maxItems", 7,
                            "items", Map.of(
                                    "type", "object",
                                    "required", List.of("name", "doc1_summary", "doc2_summary", "favorable", "reasoning"),
                                    "properties", Map.of(
                                            "name", strProp(),
                                            "doc1_summary", strProp(),
                                            "doc2_summary", strProp(),
                                            "favorable", enumProp("doc1", "doc2", "neutral"),
                                            "reasoning", strProp()
                                    )
                            )
                    )
            )
    );

    // ── Key Terms Extraction ──────────────────────────────────────────────────

    public static final Map<String, Object> EXTRACTION_SCHEMA = Map.ofEntries(
            Map.entry("type", "object"),
            Map.entry("properties", Map.ofEntries(
                    Map.entry("party_a",            nullableStr()),
                    Map.entry("party_b",            nullableStr()),
                    Map.entry("effective_date",     nullableStr()),
                    Map.entry("expiry_date",        nullableStr()),
                    Map.entry("contract_value",     nullableStr()),
                    Map.entry("liability_cap",      nullableStr()),
                    Map.entry("governing_law",      nullableStr()),
                    Map.entry("notice_period_days", nullableStr()),
                    Map.entry("arbitration_venue",  nullableStr())
            ))
    );

    // ── Refine Clause ─────────────────────────────────────────────────────────

    public static final Map<String, Object> REFINE_SCHEMA = Map.of(
            "type", "object",
            "required", List.of("improved_text", "reasoning"),
            "properties", Map.of(
                    "improved_text", strProp(),
                    "reasoning",     strProp()
            )
    );

    // ── Contract Review Checklist ─────────────────────────────────────────────
    //
    // Exactly 12 clauses with fixed clause_id enum. The enum constraint means
    // the model cannot invent new clause IDs or use prose variants.

    public static final Map<String, Object> CHECKLIST_SCHEMA = Map.of(
            "type", "object",
            "required", List.of("clauses"),
            "properties", Map.of(
                    "clauses", Map.of(
                            "type", "array",
                            "minItems", 12,
                            "maxItems", 12,
                            "items", Map.of(
                                    "type", "object",
                                    "required", List.of("clause_id", "status", "risk_level", "section_ref", "finding"),
                                    "properties", Map.ofEntries(
                                            Map.entry("clause_id", enumProp(
                                                    "LIABILITY_LIMIT", "INDEMNITY",
                                                    "TERMINATION_CONVENIENCE", "TERMINATION_CAUSE",
                                                    "FORCE_MAJEURE", "CONFIDENTIALITY",
                                                    "GOVERNING_LAW", "DISPUTE_RESOLUTION",
                                                    "IP_OWNERSHIP", "DATA_PROTECTION",
                                                    "PAYMENT_TERMS", "ASSIGNMENT"
                                            )),
                                            Map.entry("status",         enumProp("PRESENT", "WEAK", "MISSING")),
                                            Map.entry("risk_level",     enumProp("HIGH", "MEDIUM", "LOW")),
                                            Map.entry("section_ref",    strProp()),
                                            Map.entry("finding",        strProp()),
                                            Map.entry("recommendation", nullableStr())
                                    )
                            )
                    )
            )
    );
}
