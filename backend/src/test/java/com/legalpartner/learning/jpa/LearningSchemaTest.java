package com.legalpartner.learning.jpa;

import com.legalpartner.model.entity.learning.ClauseEdit;
import com.legalpartner.model.entity.learning.FirmClause;
import com.legalpartner.model.entity.learning.QuestionCalibration;
import com.legalpartner.repository.learning.ClauseEditRepository;
import com.legalpartner.repository.learning.FirmClauseRepository;
import com.legalpartner.repository.learning.QuestionCalibrationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real V35 migration on H2 (PostgreSQL mode) and lets Hibernate validate every
 * learning entity against it — the same {@code ddl-auto: validate} production uses.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/migration/V35__learning_loop.sql,classpath:db/migration/V36__review_answer_log.sql",
        "spring.datasource.url=jdbc:h2:mem:learning;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class LearningSchemaTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.legalpartner.model.entity.learning")
    @EnableJpaRepositories("com.legalpartner.repository.learning")
    static class Config {}

    @Autowired FirmClauseRepository firmClauses;
    @Autowired ClauseEditRepository edits;
    @Autowired QuestionCalibrationRepository calibration;

    @Test
    void entitiesMatchMigrationAndRoundTrip() {
        FirmClause fc = firmClauses.save(FirmClause.builder().clauseKey("LIABILITY").contractType("MSA")
                .textEnc("x").fingerprint("abc").supportCount(3).executedCount(1).build());
        assertThat(firmClauses.findByClauseKeyAndContractTypeAndStatus("LIABILITY", "MSA", "CANDIDATE"))
                .extracting(FirmClause::getId).containsExactly(fc.getId());

        UUID doc = UUID.randomUUID();
        edits.save(ClauseEdit.builder().documentId(doc).clauseKey("LIABILITY").article(3).editRatio(0.4).build());
        assertThat(edits.findByDocumentIdAndClauseKey(doc, "LIABILITY")).get()
                .extracting(ClauseEdit::getEditRatio).isEqualTo(0.4);

        calibration.save(QuestionCalibration.builder().questionId("liability_cap_exists").contractType("_ALL").answered(4).build());
        assertThat(calibration.findByQuestionIdAndContractType("liability_cap_exists", "_ALL")).isPresent();
    }
}
