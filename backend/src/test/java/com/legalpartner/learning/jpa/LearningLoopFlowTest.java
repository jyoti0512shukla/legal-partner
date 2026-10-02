package com.legalpartner.learning.jpa;

import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.enums.DocumentType;
import com.legalpartner.rag.DocumentFullTextRetriever;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.repository.learning.*;
import com.legalpartner.service.AnonymizationService;
import com.legalpartner.service.EncryptionService;
import com.legalpartner.service.learning.*;
import com.legalpartner.testsupport.ConfigFixtures;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.jasypt.encryption.StringEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The learning loop end to end on the real V35 schema: every step a lawyer or event
 * triggers, with only the LLM, file store and document table faked.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/migration/V35__learning_loop.sql,classpath:db/migration/V36__review_answer_log.sql",
        "spring.datasource.url=jdbc:h2:mem:learningflow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class LearningLoopFlowTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.legalpartner.model.entity.learning")
    @EnableJpaRepositories("com.legalpartner.repository.learning")
    static class Config {}

    @Autowired DraftClauseExposureRepository exposureRepo;
    @Autowired ClauseEditRepository editRepo;
    @Autowired ClauseObservationRepository observationRepo;
    @Autowired FirmClauseRepository firmClauseRepo;
    @Autowired DraftingInsightRepository insightRepo;
    @Autowired ReviewDisputeRepository disputeRepo;
    @Autowired QuestionCalibrationRepository calibrationRepo;
    @Autowired ReviewAnswerLogRepository answerLogRepo;

    static final String AI_CAP = "The aggregate liability of each Party under this Agreement shall not exceed "
            + "the fees paid in the twelve months preceding the claim.";
    static final String LAWYER_CAP = "The aggregate liability of each Party under this Agreement shall not exceed "
            + "two times the fees paid or payable in the twelve months preceding the event giving rise to the claim, "
            + "save that this cap shall not apply to breach of confidentiality or data protection obligations.";
    static final String RULE = "Carve out breach of confidentiality and data protection from the liability cap";

    final LearningConfig config = ConfigFixtures.learning();
    final EncryptionService encryption = new EncryptionService(new StringEncryptor() {
        public String encrypt(String s) { return s; }
        public String decrypt(String s) { return s; }
    });
    final DocumentMetadataRepository documents = mock(DocumentMetadataRepository.class);
    final DocumentFullTextRetriever fullText = mock(DocumentFullTextRetriever.class);
    final DocumentTextReader textReader = mock(DocumentTextReader.class);
    final ChatLanguageModel reflector = messages ->
            Response.from(AiMessage.from("{\"preferences\": [\"" + RULE + "\"]}"));

    ExposureService exposures;
    InsightService insights;
    EditCaptureService editCapture;
    FirmClauseBankService clauseBank;
    ReviewCalibrationService calibration;
    LearningMetricsService metrics;

    @BeforeEach
    void wire() {
        exposures = new ExposureService(exposureRepo, encryption, config);
        insights = new InsightService(insightRepo, reflector, ConfigFixtures.prompts(), config);
        editCapture = new EditCaptureService(exposures, editRepo, textReader, encryption, insights, config);
        var vocab = ConfigFixtures.vocabulary();
        clauseBank = new FirmClauseBankService(observationRepo, firmClauseRepo, documents, fullText, textReader, encryption,
                new AnonymizationService(reflector, vocab), ConfigFixtures.riskQuestions(), ConfigFixtures.clauseSpecs(),
                config, vocab);
        calibration = new ReviewCalibrationService(calibrationRepo, disputeRepo, answerLogRepo, config, ConfigFixtures.prompts());
        metrics = new LearningMetricsService(config, exposureRepo, editRepo, firmClauseRepo, insightRepo, disputeRepo, calibration);
    }

    @Test
    void lawyerEditsBecomeActivePreferencesInTheNextPrompt() {
        int docs = config.getInsightAutoActivateDocs();
        for (int i = 0; i < docs; i++) {
            UUID doc = UUID.randomUUID();
            exposures.record(doc, "msa", "MSA", List.of(new ExposureService.Exposure(
                    "LIABILITY", 3, "LIMITATION OF LIABILITY", ExposureService.LLM, null, List.of(), AI_CAP)));
            when(textReader.read("v2-" + doc)).thenReturn("ARTICLE 3 — LIMITATION OF LIABILITY\n" + LAWYER_CAP);
            var captured = editCapture.capture(doc, "v2-" + doc, 2, false);
            assertThat(captured).singleElement().satisfies(c -> {
                assertThat(c.editRatio()).isGreaterThan(config.getSignificantEditRatio());
                assertThat(c.reflected()).isTrue();
            });
        }
        // One insight, merged across documents, auto-activated after enough distinct documents.
        assertThat(insightRepo.findAll()).singleElement().satisfies(i -> {
            assertThat(i.getStatus()).isEqualTo(InsightService.ACTIVE);
            assertThat(i.getEvidenceCount()).isEqualTo(docs);
        });
        var active = insights.activeFor("LIABILITY", "MSA");
        assertThat(insights.promptBlock(active)).contains("FIRM DRAFTING PREFERENCES").contains(RULE);
        assertThat(insights.activeFor("LIABILITY", "NDA")).isEmpty(); // scoped to the contract type it came from

        // Signed with light editing → the insight that was in the prompt is credited.
        UUID signed = UUID.randomUUID();
        exposures.record(signed, "msa", "MSA", List.of(new ExposureService.Exposure(
                "LIABILITY", 3, "LIMITATION OF LIABILITY", ExposureService.LLM, null,
                active.stream().map(x -> x.getId()).toList(), LAWYER_CAP)));
        when(textReader.read("final")).thenReturn("ARTICLE 3 — LIMITATION OF LIABILITY\n" + LAWYER_CAP);
        editCapture.capture(signed, "final", null, true);
        assertThat(insightRepo.findById(active.get(0).getId())).get().extracting(x -> x.getHelpfulCount()).isEqualTo(1);

        var m = metrics.compute();
        assertThat(m.editsBySource()).containsKey(ExposureService.LLM);
        assertThat(m.llmEditsByInsightUse()).containsKeys("WITH_INSIGHTS", "WITHOUT_INSIGHTS");
        assertThat(m.insights()).containsEntry(InsightService.ACTIVE, 1L);
    }

    @Test
    void recurringFirmWordingIsMinedApprovedAndSelectedForDrafting() {
        String[][] parties = {{"Northwind Systems, Inc.", "Contoso Retail LLC"}, {"Fabrikam Ltd", "Tailspin Toys Inc."},
                {"Litware Corp", "Wingtip Traders LLC"}};
        for (String[] p : parties) {
            UUID id = UUID.randomUUID();
            DocumentMetadata doc = DocumentMetadata.builder().id(id).documentType(DocumentType.MSA).source("USER")
                    .partyA(p[0]).partyB(p[1]).fileName(id + ".pdf").build();
            when(documents.findById(id)).thenReturn(Optional.of(doc));
            when(fullText.retrieveFullTextUncapped(id)).thenReturn(
                    "MASTER SERVICES AGREEMENT between " + p[0] + " and " + p[1] + ".\n\n"
                            + "ARTICLE 9 — LIMITATION OF LIABILITY\n"
                            + "9.1 Except for liability arising from gross negligence or wilful misconduct, the aggregate "
                            + "liability of " + p[0] + " to " + p[1] + " under or in connection with this Agreement shall not "
                            + "exceed the total fees paid in the twelve months preceding the event giving rise to the claim.\n"
                            + "9.2 Neither party shall be liable for any indirect, incidental, special or consequential loss, "
                            + "including loss of profits, revenue or goodwill, even if advised of the possibility of such loss.\n\n"
                            + "ARTICLE 10 — GOVERNING LAW\nThis Agreement is governed by the laws of England and Wales.");
            assertThat(clauseBank.observeDocument(id)).isPositive();
        }

        var report = clauseBank.mine();
        assertThat(report.created()).isPositive();
        var candidate = clauseBank.list(FirmClauseBankService.CANDIDATE).stream()
                .filter(c -> c.clauseKey().equals("LIABILITY")).findFirst().orElseThrow();
        assertThat(candidate.supportCount()).isEqualTo(parties.length);
        assertThat(candidate.text()).contains(config.getPartyAPlaceholder()).doesNotContain("Northwind", "Fabrikam", "Litware");

        // Nothing is used for drafting until a lawyer approves it.
        assertThat(clauseBank.selectForDraft("LIABILITY", "MSA", "BALANCED")).isEmpty();
        clauseBank.approve(candidate.id(), null, config.defaultPosition(), "partner@firm.test");
        var selected = clauseBank.selectForDraft("LIABILITY", "MSA", "BALANCED").orElseThrow();
        String rendered = clauseBank.render(selected.text(), "Acme Corp", "Beta LLC");
        assertThat(rendered).contains("Acme Corp").contains("Beta LLC").doesNotContain("{{");

        // Re-mining keeps the lawyer's approved text.
        clauseBank.mine();
        assertThat(clauseBank.list(FirmClauseBankService.APPROVED)).extracting(FirmClauseBankService.FirmClauseView::id)
                .contains(candidate.id());
        assertThat(metrics.compute().firmClauses()).containsEntry(FirmClauseBankService.APPROVED, 1L);
    }

    @Test
    void disputesLowerQuestionWeightAndBecomeFewShotCorrections() {
        String q = "liability_cap_exists";
        for (int i = 0; i < 20; i++) calibration.recordAnswers(UUID.randomUUID(), "MSA", List.of(q, "other_question"));
        for (int i = 0; i < 9; i++) { // 9/20 wrong → precision 0.55, below needs_rewrite.max_precision
            calibration.dispute(UUID.randomUUID(), "MSA", new ReviewCalibrationService.DisputeRequest(
                    q, "LIABILITY", "NO", "YES", "shall not exceed the fees paid", "cap is in 9.1"), "associate@firm.test");
        }
        Map<String, Double> w = calibration.multipliers("MSA");
        assertThat(w.get(q)).isLessThan(1.0).isGreaterThanOrEqualTo(config.getCalibrationMinMultiplier());
        assertThat(w.get("other_question")).isEqualTo(1.0);
        assertThat(calibration.correctionsFor(q)).contains("corrected earlier answers").contains("PRESENT")
                .contains("shall not exceed the fees paid");
        assertThat(calibration.correctionsFor("other_question")).isEmpty();
        assertThat(calibration.needsRewrite()).extracting(ReviewCalibrationService.QuestionHealth::questionId).containsExactly(q);
    }

    @Test
    void deletingADocumentForgetsWhatWasLearnedFromIt() {
        UUID doc = UUID.randomUUID();
        exposures.record(doc, "msa", "MSA", List.of(new ExposureService.Exposure(
                "LIABILITY", 3, "LIMITATION OF LIABILITY", ExposureService.LLM, null, List.of(), AI_CAP)));
        when(textReader.read(any())).thenReturn("ARTICLE 3 — LIMITATION OF LIABILITY\n" + LAWYER_CAP);
        editCapture.capture(doc, "v2", 2, false);
        calibration.dispute(doc, "MSA", new ReviewCalibrationService.DisputeRequest(
                "q", "LIABILITY", "NO", "YES", null, null), "a@firm.test");

        editCapture.forgetDocument(doc);
        exposures.forgetDocument(doc);
        calibration.forgetDocument(doc);
        assertThat(editRepo.findByDocumentId(doc)).isEmpty();
        assertThat(exposureRepo.findByDocumentIdOrderByArticle(doc)).isEmpty();
        assertThat(disputeRepo.count()).isZero();
    }

    @Test
    void reReviewingADocumentCountsItsAnswersOnceAndDeleteReversesCounts() {
        UUID doc = UUID.randomUUID();
        String q = "termination_notice";
        for (int i = 0; i < 3; i++) calibration.recordAnswers(doc, "MSA", List.of(q));   // reviewed three times
        var dispute = new ReviewCalibrationService.DisputeRequest(q, "TERMINATION", "NO", "YES", null, "see 12.2");
        calibration.dispute(doc, "MSA", dispute, "a@firm.test");
        calibration.dispute(doc, "MSA", dispute, "a@firm.test");                           // same correction twice
        var all = calibrationRepo.findByQuestionIdAndContractType(q, ReviewCalibrationService.ALL).orElseThrow();
        assertThat(all.getAnswered()).isEqualTo(1);
        assertThat(all.getDisputed()).isEqualTo(1);

        calibration.forgetDocument(doc);
        var after = calibrationRepo.findByQuestionIdAndContractType(q, "MSA").orElseThrow();
        assertThat(after.getAnswered()).isZero();
        assertThat(after.getDisputed()).isZero();
        assertThat(answerLogRepo.findByDocumentId(doc)).isEmpty();
    }

    @Test
    void signedDraftsExportAsEvalBriefsWithSignedReferenceText() {
        UUID id = UUID.randomUUID();
        DocumentMetadata doc = DocumentMetadata.builder().id(id).source("DRAFT_ASYNC").documentType(DocumentType.MSA)
                .partyA("Northwind Systems, Inc.").partyB("Contoso Retail LLC").build();
        var manifests = mock(com.legalpartner.service.review.DraftManifestStore.class);
        var manifest = new com.legalpartner.service.review.DraftManifest(1, "msa", "MSA", "PARTY_A", "BALANCED", "Delaware",
                List.of(new com.legalpartner.service.review.DraftManifest.Section("LIABILITY", "LIMITATION OF LIABILITY", 3,
                        List.of("LIABILITY"), AI_CAP)), "Northwind provides services to Contoso; cap 2x fees.");
        when(manifests.read(id)).thenReturn(Optional.of(manifest));
        when(documents.findBySourceAndContractStatusIn(org.mockito.ArgumentMatchers.eq("DRAFT_ASYNC"), any())).thenReturn(List.of(doc));
        exposures.record(id, "msa", "MSA", List.of(new ExposureService.Exposure(
                "LIABILITY", 3, "LIMITATION OF LIABILITY", ExposureService.LLM, null, List.of(), AI_CAP)));
        when(textReader.read("signed")).thenReturn("ARTICLE 3 — LIMITATION OF LIABILITY\n" + LAWYER_CAP);
        editCapture.capture(id, "signed", null, true);

        ChatLanguageModel anonymizer = messages -> Response.from(AiMessage.from(
                "{\"entities\": ["
                        + "{\"type\": \"ORG\", \"original\": \"Northwind Systems, Inc.\", \"synthetic\": \"Acme Corp\"},"
                        + "{\"type\": \"ORG\", \"original\": \"Contoso Retail LLC\", \"synthetic\": \"Beta LLC\"},"
                        + "{\"type\": \"ORG\", \"original\": \"Northwind\", \"synthetic\": \"Acme\"},"
                        + "{\"type\": \"ORG\", \"original\": \"Contoso\", \"synthetic\": \"Beta\"}]}"));
        var exporter = new EvalCaseExporter(config, documents, manifests, editRepo, encryption,
                new AnonymizationService(anonymizer, ConfigFixtures.vocabulary()));

        @SuppressWarnings("unchecked")
        var raw = (List<Map<String, Object>>) exporter.export(false, 10).get("briefs");
        assertThat(raw).singleElement().satisfies(c -> {
            assertThat(c).containsEntry("templateId", "msa").containsEntry("clientPosition", "PARTY_A");
            assertThat((Map<String, String>) c.get("reference_clauses")).containsEntry("LIABILITY", LAWYER_CAP);
        });
        var anonymized = exporter.export(true, 10);
        assertThat((List<?>) anonymized.get("briefs")).hasSize(1);
        assertThat(anonymized.toString()).doesNotContain("Northwind").doesNotContain("Contoso").contains("Acme Corp");

        // Anonymization failing must never export raw client data as "anonymized".
        ChatLanguageModel broken = messages -> Response.from(AiMessage.from("sorry"));
        var failClosed = new EvalCaseExporter(config, documents, manifests, editRepo, encryption,
                new AnonymizationService(broken, ConfigFixtures.vocabulary())).export(true, 10);
        assertThat((List<?>) failClosed.get("briefs")).isEmpty();
        assertThat(failClosed.get("skipped")).isEqualTo(1);
    }
}
