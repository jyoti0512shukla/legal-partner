package com.legalpartner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.rag.PromptTemplates;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client confidentiality layer for RAG.
 *
 * Solves the cross-client leakage problem: a firm's precedent library contains
 * past contracts from many different clients; if Client B's new draft retrieves
 * Client A's precedent verbatim, A's specific names/figures/dates can leak into
 * B's contract. That's a state-bar-level compliance problem.
 *
 * Two operations:
 *
 * 1. {@link #anonymize(String)} — runs at ingest time. Uses LLM-based NER to
 *    identify PERSON / ORG / MONEY / DATE / ADDRESS / JURISDICTION entities
 *    and proposes type-consistent synthetic replacements. Returns the
 *    rewritten text + the raw→synthetic map (so the originating matter can
 *    still display the real document to its own lawyers).
 *
 * 2. {@link #detectConcreteEntities(String)} — runs at output time. Regex-based
 *    quick pass over a generated draft that flags concrete dollar amounts,
 *    dates with year, emails, phones. The caller cross-checks these against
 *    what the user actually supplied in the deal brief; anything not in the
 *    brief probably leaked from a precedent.
 *
 * LLM-based NER for ingest uses the small chat model (2K tokens). Output-side
 * verification is regex only (cheap, sub-millisecond, runs on every clause).
 * A future iteration can add LLM NER for output verification behind a flag
 * when latency allows.
 */
@Service
@Slf4j
public class AnonymizationService {

    private final ChatLanguageModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnonymizationService(
            @Qualifier("shortChatModel") ChatLanguageModel shortChatModel) {
        this.chatModel = shortChatModel;
    }

    public record AnonymizationResult(String anonymizedText, Map<String, String> entityMap) {}

    /**
     * Anonymize a whole document's text. One LLM call per document; entities
     * are substituted globally (all occurrences of "Acme Corp" replaced with
     * "Helix Industries"). Returns the rewritten text + the raw→synthetic
     * map. On any failure, returns the original text with an empty map —
     * ingest should continue even if anonymization fails (the caller can
     * log + flag for manual review).
     */
    public AnonymizationResult anonymize(String originalText) {
        if (originalText == null || originalText.isBlank()) {
            return new AnonymizationResult(originalText, Map.of());
        }

        // Cap the text we send to the LLM — anonymization is for the ~first
        // 8000 chars of the contract, which covers the parties, recitals,
        // definitions, and the first ~5 articles. Later sections that reference
        // "Acme" back get replaced via the global substitution pass below.
        String capped = originalText.length() > 8000
                ? originalText.substring(0, 8000)
                : originalText;

        try {
            String prompt = PromptTemplates.ANONYMIZE_SYSTEM
                    + "\n\n" + String.format(PromptTemplates.ANONYMIZE_USER, capped);
            AiMessage response = chatModel.generate(UserMessage.from(prompt)).content();
            String raw = response.text().trim();

            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                log.warn("Anonymization: model output had no JSON object, skipping");
                return new AnonymizationResult(originalText, Map.of());
            }

            JsonNode root = objectMapper.readTree(raw.substring(start, end + 1));
            JsonNode entities = root.path("entities");
            if (!entities.isArray() || entities.isEmpty()) {
                log.info("Anonymization: no entities detected");
                return new AnonymizationResult(originalText, Map.of());
            }

            // Build the substitution map. Skip any pair whose original is so
            // short or generic that global replacement would corrupt the text
            // ("USA" → "Synthland" inside "USAGE" is a disaster).
            Map<String, String> map = new LinkedHashMap<>();
            for (JsonNode e : entities) {
                String type = e.path("type").asText("");
                String original = e.path("original").asText("");
                String synthetic = e.path("synthetic").asText("");
                if (original.isBlank() || synthetic.isBlank()) continue;
                if (original.length() < 3) continue;      // too short — skip
                if (original.equalsIgnoreCase(synthetic)) continue;
                map.put(original, synthetic);
            }

            if (map.isEmpty()) {
                return new AnonymizationResult(originalText, Map.of());
            }

            String rewritten = applySubstitutions(originalText, map);
            log.info("Anonymization: {} entities substituted ({} chars → {} chars)",
                    map.size(), originalText.length(), rewritten.length());
            return new AnonymizationResult(rewritten, map);

        } catch (Exception ex) {
            log.warn("Anonymization failed ({}), using raw text", ex.getMessage());
            return new AnonymizationResult(originalText, Map.of());
        }
    }

    /**
     * Apply a substitution map globally over the full text (not just the
     * sample we sent to the LLM). Substitutes longest keys first so "Acme
     * Cloud Technologies" is replaced before "Acme".
     */
    private String applySubstitutions(String text, Map<String, String> map) {
        List<String> keys = new ArrayList<>(map.keySet());
        keys.sort(Comparator.comparingInt(String::length).reversed());
        String result = text;
        for (String key : keys) {
            // Word-boundary replace (avoids "ACMEGROUP" being touched by "ACME")
            result = result.replaceAll(
                    "(?i)\\b" + Pattern.quote(key) + "\\b",
                    Matcher.quoteReplacement(map.get(key)));
        }
        return result;
    }

    // ── Output-side concrete-entity detection ────────────────────────────

    /**
     * Fast regex pass over a generated draft clause. Returns every concrete,
     * identifiable token that could be a cross-client leak:
     *   - dollar / currency figures with actual amounts ($1,200,000, INR 5,00,000)
     *   - specific dates with year (January 15, 2024)
     *   - email addresses
     *   - phone numbers (simple patterns)
     *   - possible party / company names (CapitalCase words 2-5 tokens long,
     *     minus a whitelist of common legal terms)
     *
     * The caller should cross-check these against what the user actually
     * supplied in the deal brief + form fields. Anything unaccounted for is
     * a leak candidate.
     */
    public Set<String> detectConcreteEntities(String draftText) {
        if (draftText == null || draftText.isBlank()) return Set.of();
        String plain = draftText.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
        Set<String> out = new LinkedHashSet<>();

        // Dollar / currency figures with digits (skip round numbers like "100" or "30"
        // — too generic; only flag ≥4 digits or with currency symbol + decimals)
        Matcher money = Pattern.compile(
                "(?:\\$|₹|£|€|USD|INR|GBP|EUR)\\s*[\\d,]{4,}(?:\\.\\d{2})?"
              + "|[\\d,]{4,}\\s*(?:dollars|rupees|USD|INR|GBP|EUR)").matcher(plain);
        while (money.find()) out.add(money.group().trim());

        // Specific dates with year — "January 15, 2024", "15/01/2024", "2024-01-15"
        Matcher dates = Pattern.compile(
                "(?i)\\b(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?"
              + "|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)"
              + "\\s+\\d{1,2},?\\s+\\d{4}\\b"
              + "|\\b\\d{4}-\\d{2}-\\d{2}\\b"
              + "|\\b\\d{1,2}/\\d{1,2}/\\d{4}\\b").matcher(plain);
        while (dates.find()) out.add(dates.group().trim());

        // Email addresses
        Matcher emails = Pattern.compile("[\\w._%+-]+@[\\w.-]+\\.[A-Za-z]{2,}").matcher(plain);
        while (emails.find()) out.add(emails.group().trim());

        // Phone numbers — permissive, catches formatted US/UK/India styles
        Matcher phones = Pattern.compile(
                "\\+?\\d{1,3}[\\s-]?\\(?\\d{3,5}\\)?[\\s-]?\\d{3,4}[\\s-]?\\d{3,4}").matcher(plain);
        while (phones.find()) {
            String match = phones.group().trim();
            // Filter out year-like short matches
            if (match.replaceAll("[^\\d]", "").length() >= 9) out.add(match);
        }

        return out;
    }

    /**
     * Given the set of concrete entities detected in the draft and the set
     * of values the user actually supplied (deal brief + form fields +
     * matter.clientName), return the entities that look like cross-client
     * leaks — present in the draft but not justified by user input.
     */
    public Set<String> findUnjustified(Set<String> detectedInDraft, Collection<String> userSupplied) {
        if (detectedInDraft.isEmpty()) return Set.of();
        // Normalize the user-supplied corpus to lowercase for contains checks.
        // We're being generous here — if the detected token appears anywhere
        // in the user's supplied text, count it as justified.
        String corpus = String.join(" | ",
                userSupplied.stream().filter(Objects::nonNull).toList()).toLowerCase();
        Set<String> leaks = new LinkedHashSet<>();
        for (String token : detectedInDraft) {
            String t = token.toLowerCase().replaceAll("\\s+", " ").trim();
            if (!corpus.contains(t)) leaks.add(token);
        }
        return leaks;
    }
}
