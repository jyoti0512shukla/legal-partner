package com.legalpartner.service;

import com.legalpartner.model.dto.DealSpec;
import com.legalpartner.service.ClauseRuleEngine.RuleResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Fix engine for clause rule violations. Applies an escalation chain:
 *
 *   1. RETRY          — re-generate with full failure feedback
 *   2. TARGETED_RETRY — surgical "fix only X, don't change Y" prompt
 *   3. INJECT         — deterministically append text from inject_template
 *   4. FLAG           — no auto-fix, surface as warning
 *
 * Design principles:
 *   - Deterministic injection is preferred for concrete deal values (amounts, dates)
 *     because LLMs may still hallucinate numbers even on retry.
 *   - Retry/targeted retry is used for structural and semantic issues where the
 *     LLM needs to rewrite prose.
 *   - All fixes are audited: every modification is tracked with the rule that
 *     triggered it and the fix type applied.
 */
@Service
@Slf4j
public class FixEngine {

    private final ChatLanguageModel chatModel;

    public FixEngine(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    // ── Public records ──────────────────────────────────────────────────

    public record AuditEntry(
            String ruleId,
            String issue,
            String fixApplied,
            /** RETRY, TARGETED_RETRY, INJECT, FLAG */
            String fixType,
            /** HIGH = deterministic, MEDIUM = LLM retry, LOW = flagged only */
            String confidence
    ) {}

    public record FixResult(
            String fixedHtml,
            List<AuditEntry> auditTrail,
            boolean wasModified
    ) {}

    // ── Main fix pipeline ───────────────────────────────────────────────

    /**
     * Fix a clause based on rule violations. Groups failures by fix strategy
     * and applies them in order: INJECT first (deterministic), then RETRY/TARGETED_RETRY,
     * finally FLAG (no-op, just audit).
     *
     * @param clauseHtml    the original generated clause HTML
     * @param failures      rule results that failed validation
     * @param dealSpec      structured deal terms for template resolution
     * @param systemPrompt  the original system prompt (for RETRY context)
     * @param userPrompt    the original user prompt (for RETRY context)
     * @return FixResult with fixed HTML and audit trail
     */
    public FixResult fix(String clauseHtml, List<RuleResult> failures,
                         DealSpec dealSpec, String systemPrompt, String userPrompt) {

        if (failures == null || failures.isEmpty()) {
            return new FixResult(clauseHtml, List.of(), false);
        }

        List<AuditEntry> audit = new ArrayList<>();
        String currentHtml = clauseHtml;
        boolean modified = false;

        // Partition failures by strategy
        List<RuleResult> injectFailures = new ArrayList<>();
        List<RuleResult> retryFailures = new ArrayList<>();
        List<RuleResult> targetedRetryFailures = new ArrayList<>();
        List<RuleResult> flagFailures = new ArrayList<>();

        for (RuleResult f : failures) {
            if (f.passed()) continue; // skip passed rules

            String action = f.fixAction();
            if (action == null) action = "FLAG";

            switch (action) {
                case "INJECT" -> injectFailures.add(f);
                case "RETRY" -> retryFailures.add(f);
                case "TARGETED_RETRY" -> targetedRetryFailures.add(f);
                default -> flagFailures.add(f);
            }
        }

        // Phase 1: Deterministic injections (highest confidence)
        for (RuleResult f : injectFailures) {
            String injectTemplate = f.rule().injectTemplate();
            if (injectTemplate != null && !injectTemplate.isBlank()) {
                String before = currentHtml;
                currentHtml = injectDeterministicText(currentHtml, f, dealSpec);
                if (!currentHtml.equals(before)) {
                    modified = true;
                    String resolvedText = resolveTemplate(injectTemplate, dealSpec);
                    audit.add(new AuditEntry(
                            f.rule().id(),
                            f.message(),
                            "Injected: " + truncate(resolvedText, 120),
                            "INJECT",
                            "HIGH"
                    ));
                    log.debug("FixEngine INJECT [{}]: {}", f.rule().id(), truncate(resolvedText, 80));
                }
            }
        }

        // Phase 2: Targeted retry (fix specific issues without rewriting everything)
        if (!targetedRetryFailures.isEmpty()) {
            String targetedPrompt = buildTargetedRetryPrompt(currentHtml, targetedRetryFailures, dealSpec);
            String fixedHtml = executeRetry(currentHtml, targetedPrompt, systemPrompt);
            if (fixedHtml != null && !fixedHtml.equals(currentHtml)) {
                currentHtml = fixedHtml;
                modified = true;
                for (RuleResult f : targetedRetryFailures) {
                    audit.add(new AuditEntry(
                            f.rule().id(),
                            f.message(),
                            "Targeted retry applied",
                            "TARGETED_RETRY",
                            "MEDIUM"
                    ));
                }
                log.debug("FixEngine TARGETED_RETRY: fixed {} issues", targetedRetryFailures.size());
            }
        }

        // Phase 3: Full retry (if there are still structural issues)
        if (!retryFailures.isEmpty()) {
            String retryPrompt = buildFullRetryPrompt(currentHtml, retryFailures, dealSpec, userPrompt);
            String fixedHtml = executeRetry(currentHtml, retryPrompt, systemPrompt);
            if (fixedHtml != null && !fixedHtml.equals(currentHtml)) {
                currentHtml = fixedHtml;
                modified = true;
                for (RuleResult f : retryFailures) {
                    audit.add(new AuditEntry(
                            f.rule().id(),
                            f.message(),
                            "Full retry applied",
                            "RETRY",
                            "MEDIUM"
                    ));
                }
                log.debug("FixEngine RETRY: re-generated clause for {} issues", retryFailures.size());
            }
        }

        // Phase 4: Flags (no fix, just record)
        for (RuleResult f : flagFailures) {
            audit.add(new AuditEntry(
                    f.rule().id(),
                    f.message(),
                    f.rule().riskMessage() != null ? f.rule().riskMessage() : "Flagged for review",
                    "FLAG",
                    "LOW"
            ));
            log.debug("FixEngine FLAG [{}]: {}", f.rule().id(),
                    f.rule().riskMessage() != null ? f.rule().riskMessage() : f.message());
        }

        log.info("FixEngine complete: {} injections, {} targeted retries, {} full retries, {} flags",
                injectFailures.size(), targetedRetryFailures.size(), retryFailures.size(), flagFailures.size());

        return new FixResult(currentHtml, audit, modified);
    }

    // ── Targeted retry prompt ───────────────────────────────────────────

    /**
     * Build a targeted retry prompt that specifies exactly what to fix
     * and what NOT to change.
     */
    private String buildTargetedRetryPrompt(String originalClause, List<RuleResult> failures,
                                             DealSpec dealSpec) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are fixing a legal contract clause. The clause below has specific issues.\n");
        sb.append("Fix ONLY the listed issues. DO NOT change any other part of the clause.\n");
        sb.append("Return the complete fixed clause in the same HTML format.\n\n");

        sb.append("ISSUES TO FIX:\n");
        for (RuleResult f : failures) {
            sb.append("- [").append(f.rule().id()).append("] ").append(f.message()).append("\n");
            if (f.rule().fixHint() != null) {
                sb.append("  Fix: ").append(f.rule().fixHint()).append("\n");
            }
        }

        // Add deal values for context
        if (dealSpec != null) {
            String dealContext = buildDealContextForFix(dealSpec);
            if (!dealContext.isEmpty()) {
                sb.append("\nDEAL VALUES TO USE:\n").append(dealContext);
            }
        }

        sb.append("\nDO NOT CHANGE:\n");
        sb.append("- Existing sub-clause numbering and structure\n");
        sb.append("- Correct provisions that are already present\n");
        sb.append("- HTML formatting and tag structure\n\n");

        sb.append("ORIGINAL CLAUSE:\n").append(originalClause);

        return sb.toString();
    }

    /**
     * Build a full retry prompt for structural issues that need a rewrite.
     */
    private String buildFullRetryPrompt(String originalClause, List<RuleResult> failures,
                                         DealSpec dealSpec, String originalUserPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("You previously generated this clause, but it failed quality validation.\n");
        sb.append("Re-generate the clause fixing ALL of the following issues.\n\n");

        sb.append("FAILED REQUIREMENTS:\n");
        for (RuleResult f : failures) {
            sb.append("- ").append(f.message()).append("\n");
            if (f.rule().fixHint() != null) {
                sb.append("  Required: ").append(f.rule().fixHint()).append("\n");
            }
        }

        if (dealSpec != null) {
            String dealContext = buildDealContextForFix(dealSpec);
            if (!dealContext.isEmpty()) {
                sb.append("\nDEAL VALUES — use these exact values:\n").append(dealContext);
            }
        }

        if (originalUserPrompt != null && !originalUserPrompt.isBlank()) {
            sb.append("\nORIGINAL INSTRUCTIONS:\n").append(originalUserPrompt).append("\n");
        }

        sb.append("\nPREVIOUS (REJECTED) OUTPUT:\n").append(originalClause);
        sb.append("\n\nGenerate a corrected version that passes all requirements above.");
        sb.append(" Output ONLY the HTML clause, no explanation.");

        return sb.toString();
    }

    // ── LLM retry execution ────────────────────────────────────────────

    /**
     * Execute a retry call to the LLM and extract the HTML response.
     */
    private String executeRetry(String currentHtml, String fixPrompt, String systemPrompt) {
        try {
            String sys = systemPrompt != null ? systemPrompt
                    : "You are a legal contract drafting assistant. Fix the clause as instructed. Output ONLY valid HTML.";

            AiMessage response = chatModel.generate(
                    SystemMessage.from(sys),
                    UserMessage.from(fixPrompt)
            ).content();

            String text = response.text();
            if (text == null || text.isBlank()) return null;

            // Extract HTML if wrapped in code fences
            text = text.trim();
            if (text.startsWith("```")) {
                int start = text.indexOf('\n');
                int end = text.lastIndexOf("```");
                if (start > 0 && end > start) {
                    text = text.substring(start + 1, end).trim();
                }
            }

            // Sanity check: result should contain HTML
            if (!text.contains("<") || text.length() < 100) {
                log.warn("FixEngine retry returned non-HTML or too-short response ({} chars)", text.length());
                return null;
            }

            return text;
        } catch (Exception e) {
            log.warn("FixEngine retry failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Deterministic injection ─────────────────────────────────────────

    /**
     * Deterministic injection — resolve template placeholders from DealSpec and
     * append as a new sub-clause paragraph in the clause HTML.
     */
    private String injectDeterministicText(String clauseHtml, RuleResult failure, DealSpec dealSpec) {
        String template = failure.rule().injectTemplate();
        if (template == null || template.isBlank()) return clauseHtml;

        String resolvedText = resolveTemplate(template, dealSpec);
        if (resolvedText.contains("{{")) {
            // Unresolved placeholders remain — skip injection to avoid broken text
            log.debug("Skipping injection for [{}]: unresolved placeholders in '{}'",
                    failure.rule().id(), resolvedText);
            return clauseHtml;
        }

        // Build the injection paragraph
        String injectionHtml = "\n<p class=\"clause-sub\">" + escapeHtml(resolvedText) + "</p>";

        // Insert before the closing </div> or </section> of the clause, or append at end
        String insertionPoint = findInsertionPoint(clauseHtml);
        if (insertionPoint != null) {
            int idx = clauseHtml.lastIndexOf(insertionPoint);
            if (idx >= 0) {
                return clauseHtml.substring(0, idx) + injectionHtml + "\n" + clauseHtml.substring(idx);
            }
        }

        // Fallback: append at end
        return clauseHtml + injectionHtml;
    }

    /**
     * Resolve {{field}} and {{field_formatted}} placeholders from DealSpec.
     */
    private String resolveTemplate(String template, DealSpec dealSpec) {
        if (dealSpec == null) return template;

        String result = template;

        // Find all {{...}} placeholders
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{([^}]+)}}").matcher(template);
        while (m.find()) {
            String placeholder = m.group(1).trim();
            String replacement = null;

            if (placeholder.endsWith("_formatted")) {
                // Use formatted resolver
                String fieldPath = placeholder.replace("_formatted", "");
                replacement = dealSpec.resolveFieldFormatted(fieldPath);
            } else {
                // Use raw resolver
                Object val = dealSpec.resolveField(placeholder);
                if (val != null) {
                    replacement = val.toString();
                }
            }

            if (replacement != null) {
                result = result.replace("{{" + placeholder + "}}", replacement);
            }
        }

        return result;
    }

    /**
     * Find the best insertion point — before the last closing tag of the clause body.
     */
    private String findInsertionPoint(String html) {
        String lower = html.toLowerCase();
        // Try common closing tags in order of preference
        for (String tag : List.of("</div>", "</section>", "</article>")) {
            if (lower.contains(tag)) return tag;
        }
        return null;
    }

    // ── Deal context helper ─────────────────────────────────────────────

    /**
     * Build a concise deal-values context block for fix prompts.
     */
    private String buildDealContextForFix(DealSpec spec) {
        StringBuilder sb = new StringBuilder();
        if (spec.getFees() != null) {
            appendIfPresent(sb, "License fee", spec.resolveFieldFormatted("fees.licenseFee"));
            appendIfPresent(sb, "Maintenance fee", spec.resolveFieldFormatted("fees.maintenanceFee"));
            appendIfPresent(sb, "Billing cycle", spec.resolveFieldFormatted("fees.billingCycle"));
            appendIfPresent(sb, "Payment terms", spec.resolveFieldFormatted("fees.paymentTerms"));
        }
        if (spec.getLicense() != null) {
            appendIfPresent(sb, "License type", spec.resolveFieldFormatted("license.type"));
            appendIfPresent(sb, "Users", spec.resolveFieldFormatted("license.users"));
            appendIfPresent(sb, "Deployment", spec.resolveFieldFormatted("license.deployment"));
        }
        if (spec.getSupport() != null) {
            appendIfPresent(sb, "SLA response", spec.resolveFieldFormatted("support.slaResponseHours"));
            appendIfPresent(sb, "Uptime", spec.resolveFieldFormatted("support.uptimeSla"));
        }
        if (spec.getLegal() != null) {
            appendIfPresent(sb, "Jurisdiction", spec.resolveFieldFormatted("legal.jurisdiction"));
            appendIfPresent(sb, "Arbitration", spec.resolveFieldFormatted("legal.arbitration"));
            appendIfPresent(sb, "Liability cap", spec.resolveFieldFormatted("legal.liabilityCap"));
            appendIfPresent(sb, "Notice period", spec.resolveFieldFormatted("legal.noticeDays"));
        }
        return sb.toString();
    }

    private void appendIfPresent(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("- ").append(label).append(": ").append(value).append("\n");
        }
    }

    // ── Utilities ───────────────────────────────────────────────────────

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
