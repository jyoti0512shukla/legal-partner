package com.legalpartner.service.learning;

import com.legalpartner.model.entity.learning.ClauseEdit;
import com.legalpartner.model.entity.learning.DraftClauseExposure;
import com.legalpartner.repository.learning.ClauseEditRepository;
import com.legalpartner.repository.learning.DraftClauseExposureRepository;
import com.legalpartner.repository.learning.DraftingInsightRepository;
import com.legalpartner.repository.learning.FirmClauseRepository;
import com.legalpartner.repository.learning.ReviewDisputeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Is the firm learning loop helping? The headline number is how much lawyers edit drafted
 * clauses, split by where the clause came from (firm bank vs shipped golden vs LLM) — if
 * learned clauses and preferences work, FIRM_BANK and insight-guided LLM clauses need less editing.
 */
@Service
@RequiredArgsConstructor
public class LearningMetricsService {

    private final LearningConfig config;
    private final DraftClauseExposureRepository exposures;
    private final ClauseEditRepository edits;
    private final FirmClauseRepository firmClauses;
    private final DraftingInsightRepository insights;
    private final ReviewDisputeRepository disputes;
    private final ReviewCalibrationService calibration;

    /** Mean edit ratio over {@code n} edited clauses (null when none). */
    public record EditStat(int n, Double meanEditRatio) {}

    public record Metrics(int windowDays,
                          Map<String, Long> firmClauses,
                          Map<String, Long> insights,
                          Map<String, Long> draftedClausesBySource,
                          Map<String, EditStat> editsBySource,
                          Map<String, EditStat> signedEditsBySource,
                          Map<String, EditStat> llmEditsByInsightUse,
                          long reviewAnswers, long reviewDisputes, long disputesInWindow,
                          Double reviewAgreement,
                          List<ReviewCalibrationService.QuestionHealth> questionsNeedingRewrite) {}

    public Metrics compute() {
        int days = config.getMetricsWindowDays();
        Instant since = Instant.now().minus(Duration.ofDays(days));

        List<DraftClauseExposure> exposed = exposures.findByCreatedAtAfter(since);
        Map<String, DraftClauseExposure> byDocClause = exposed.stream()
                .collect(Collectors.toMap(e -> key(e.getDocumentId(), e.getClauseKey()), e -> e, (a, b) -> b));
        List<ClauseEdit> edited = edits.findByCapturedAtAfter(since).stream()
                .filter(e -> byDocClause.containsKey(key(e.getDocumentId(), e.getClauseKey()))).toList();

        long answered = calibration.totalAnswered();
        long disputed = calibration.totalDisputed();
        return new Metrics(days,
                counts(List.of(FirmClauseBankService.CANDIDATE, FirmClauseBankService.APPROVED,
                        FirmClauseBankService.REJECTED, FirmClauseBankService.RETIRED), firmClauses::countByStatus),
                counts(List.of(InsightService.PROPOSED, InsightService.ACTIVE, InsightService.RETIRED), insights::countByStatus),
                exposed.stream().collect(Collectors.groupingBy(DraftClauseExposure::getProvenance, LinkedHashMap::new, Collectors.counting())),
                editStats(edited, e -> provenance(e, byDocClause)),
                editStats(edited.stream().filter(ClauseEdit::isFinal).toList(), e -> provenance(e, byDocClause)),
                editStats(edited.stream().filter(e -> ExposureService.LLM.equals(provenance(e, byDocClause))).toList(),
                        e -> hasInsights(byDocClause.get(key(e.getDocumentId(), e.getClauseKey()))) ? "WITH_INSIGHTS" : "WITHOUT_INSIGHTS"),
                answered, disputed, disputes.countByCreatedAtAfter(since),
                answered == 0 ? null : 1.0 - (double) disputed / answered,
                calibration.needsRewrite());
    }

    static Map<String, EditStat> editStats(List<ClauseEdit> list, java.util.function.Function<ClauseEdit, String> group) {
        Map<String, List<ClauseEdit>> grouped = list.stream().collect(Collectors.groupingBy(group, LinkedHashMap::new, Collectors.toList()));
        Map<String, EditStat> out = new LinkedHashMap<>();
        grouped.forEach((k, v) -> out.put(k, new EditStat(v.size(),
                v.stream().mapToDouble(ClauseEdit::getEditRatio).average().orElse(Double.NaN))));
        return out;
    }

    private static String provenance(ClauseEdit e, Map<String, DraftClauseExposure> byDocClause) {
        DraftClauseExposure x = byDocClause.get(key(e.getDocumentId(), e.getClauseKey()));
        return x == null ? "UNKNOWN" : x.getProvenance();
    }

    private static boolean hasInsights(DraftClauseExposure e) {
        return e != null && e.getInsightIds() != null && !e.getInsightIds().isBlank();
    }

    private static Map<String, Long> counts(List<String> statuses, java.util.function.ToLongFunction<String> count) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String s : statuses) out.put(s, count.applyAsLong(s));
        return out;
    }

    private static String key(UUID doc, String clause) {
        return doc + "|" + clause;
    }
}
