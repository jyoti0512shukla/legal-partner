package com.legalpartner.service;

import com.legalpartner.model.dto.DealSpec;
import com.legalpartner.service.ClauseRuleEngine.RuleResult;
import com.legalpartner.service.FixEngine.AuditEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes decomposed coverage and risk scores from clause validation results.
 *
 * Produces a {@link CoverageReport} with:
 *   - Per-clause coverage (% of rules passed)
 *   - Overall weighted coverage
 *   - Risk level derived from severity of failures
 *   - List of risk drivers, missing terms, applied fixes, and blockers
 *
 * Severity weights for overall risk:
 *   CRITICAL = 10, HIGH = 5, MEDIUM = 2, LOW = 1
 */
@Service
@Slf4j
public class DealCoverageScore {

    // Severity weights for risk scoring
    private static final Map<String, Integer> SEVERITY_WEIGHT = Map.of(
            "CRITICAL", 10,
            "HIGH", 5,
            "MEDIUM", 2,
            "LOW", 1
    );

    // ── Public record ───────────────────────────────────────────────────

    public record CoverageReport(
            /** Overall coverage: weighted average of clause coverage [0.0 - 1.0] */
            double overallCoverage,
            /** Per-clause coverage: clauseType -> fraction of rules passed */
            Map<String, Double> clauseCoverage,
            /** Overall risk level: HIGH, MEDIUM, LOW */
            String overallRisk,
            /** Risk drivers: human-readable strings describing risk sources */
            List<String> riskDrivers,
            /** Deal terms that were required but missing from the generated clauses */
            List<String> missingTerms,
            /** Fixes that were applied by the FixEngine */
            List<AuditEntry> fixesApplied,
            /** CRITICAL failures that were not fixed — these block approval */
            List<String> blockers
    ) {}

    // ── Main computation ────────────────────────────────────────────────

    /**
     * Compute decomposed coverage and risk scores from all clause validation results.
     *
     * @param resultsByClause map of clauseType -> list of RuleResults
     * @param fixes           audit entries from the FixEngine (may be empty)
     * @param dealSpec        the deal specification (for context in reporting)
     * @return CoverageReport with all metrics
     */
    public CoverageReport compute(Map<String, List<RuleResult>> resultsByClause,
                                   List<AuditEntry> fixes,
                                   DealSpec dealSpec) {

        Map<String, Double> clauseCoverage = new LinkedHashMap<>();
        List<String> riskDrivers = new ArrayList<>();
        List<String> missingTerms = new ArrayList<>();
        List<String> blockers = new ArrayList<>();

        int totalWeightedRules = 0;
        int totalWeightedPassed = 0;
        int riskScore = 0;

        Set<String> fixedRuleIds = fixes != null
                ? fixes.stream()
                    .filter(a -> !"FLAG".equals(a.fixType()))
                    .map(AuditEntry::ruleId)
                    .collect(Collectors.toSet())
                : Set.of();

        for (Map.Entry<String, List<RuleResult>> entry : resultsByClause.entrySet()) {
            String clauseType = entry.getKey();
            List<RuleResult> results = entry.getValue();

            if (results == null || results.isEmpty()) {
                clauseCoverage.put(clauseType, 1.0);
                continue;
            }

            int passed = 0;
            int total = results.size();

            for (RuleResult r : results) {
                int weight = SEVERITY_WEIGHT.getOrDefault(
                        r.rule().severity() != null ? r.rule().severity() : "LOW", 1);

                totalWeightedRules += weight;

                if (r.passed()) {
                    passed++;
                    totalWeightedPassed += weight;
                } else {
                    // Check if this was fixed
                    boolean wasFixed = fixedRuleIds.contains(r.rule().id());

                    if (wasFixed) {
                        totalWeightedPassed += weight; // count fixed rules as passed
                    } else {
                        // Unfixed failure — contributes to risk
                        riskScore += weight;

                        // Categorise the failure
                        if ("CRITICAL".equals(r.rule().severity())) {
                            blockers.add("[" + clauseType + "] " + r.message());
                        }

                        if ("FLAG".equals(r.fixAction())) {
                            String riskMsg = r.rule().riskMessage() != null
                                    ? r.rule().riskMessage()
                                    : r.message();
                            riskDrivers.add("[" + clauseType + "] " + riskMsg);
                        }

                        // Track missing deal values specifically
                        if (r.rule().conditions() != null) {
                            r.rule().conditions().stream()
                                    .filter(c -> "must_include_deal_value".equals(c.type()))
                                    .filter(c -> c.field() != null)
                                    .forEach(c -> missingTerms.add(
                                            clauseType + ": " + humaniseField(c.field())));
                        }
                    }
                }
            }

            double coverage = total > 0 ? (double) passed / total : 1.0;
            // Adjust for fixed rules
            long fixedForClause = fixes != null
                    ? fixes.stream()
                        .filter(a -> !"FLAG".equals(a.fixType()))
                        .filter(a -> results.stream().anyMatch(r -> r.rule().id().equals(a.ruleId())))
                        .count()
                    : 0;
            double adjustedCoverage = total > 0
                    ? (double) (passed + fixedForClause) / total
                    : 1.0;
            clauseCoverage.put(clauseType, Math.min(adjustedCoverage, 1.0));
        }

        // Overall coverage: weighted average
        double overallCoverage = totalWeightedRules > 0
                ? (double) totalWeightedPassed / totalWeightedRules
                : 1.0;

        // Overall risk level from cumulative risk score
        String overallRisk = computeRiskLevel(riskScore, totalWeightedRules);

        // Deduplicate
        List<String> dedupedMissing = missingTerms.stream().distinct().collect(Collectors.toList());
        List<String> dedupedDrivers = riskDrivers.stream().distinct().collect(Collectors.toList());

        List<AuditEntry> appliedFixes = fixes != null
                ? fixes.stream()
                    .filter(a -> !"FLAG".equals(a.fixType()))
                    .collect(Collectors.toList())
                : List.of();

        log.info("DealCoverageScore: overall={}%, risk={}, clauses={}, blockers={}, fixes={}",
                String.format("%.1f", overallCoverage * 100), overallRisk, clauseCoverage.size(),
                blockers.size(), appliedFixes.size());

        return new CoverageReport(
                Math.round(overallCoverage * 1000.0) / 1000.0,
                clauseCoverage,
                overallRisk,
                dedupedDrivers,
                dedupedMissing,
                appliedFixes,
                blockers
        );
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Derive overall risk level from the cumulative risk score.
     * Thresholds are relative to total weighted rules to normalise across
     * different contract sizes.
     */
    private String computeRiskLevel(int riskScore, int totalWeightedRules) {
        if (totalWeightedRules == 0) return "LOW";

        double riskRatio = (double) riskScore / totalWeightedRules;

        if (riskRatio >= 0.3) return "HIGH";
        if (riskRatio >= 0.1) return "MEDIUM";
        return "LOW";
    }

    /**
     * Convert a DealSpec field path to a human-readable label.
     */
    private String humaniseField(String fieldPath) {
        if (fieldPath == null) return "unknown";
        return switch (fieldPath) {
            case "fees.licenseFee" -> "license fee amount";
            case "fees.maintenanceFee" -> "maintenance fee amount";
            case "fees.billingCycle" -> "billing cycle";
            case "license.users" -> "authorized user count";
            case "license.locations" -> "authorized locations";
            case "license.type" -> "license type";
            case "license.deployment" -> "deployment model";
            case "legal.jurisdiction" -> "governing jurisdiction";
            case "legal.arbitration" -> "arbitration body";
            case "legal.liabilityCap" -> "liability cap";
            case "legal.noticeDays" -> "notice period";
            case "support.slaResponseHours" -> "SLA response time";
            case "support.uptimeSla" -> "uptime SLA";
            case "security.escrow" -> "source code escrow";
            case "security.soc2" -> "SOC 2 compliance";
            case "security.iso27001" -> "ISO 27001 certification";
            default -> fieldPath.replace('.', ' ');
        };
    }
}
