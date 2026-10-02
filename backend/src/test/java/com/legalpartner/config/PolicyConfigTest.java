package com.legalpartner.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.model.dto.WorkflowStepConfig;
import com.legalpartner.model.enums.ContractStatus;
import com.legalpartner.model.enums.WorkflowStepType;
import com.legalpartner.service.WorkflowQualityScorer;
import com.legalpartner.service.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Lifecycle, workflow and workflow-quality YAML are consistent with the enums and each other. */
class PolicyConfigTest {

    @Test
    void lifecycleIsAReachableStateMachine() {
        ContractLifecycleConfig c = new ContractLifecycleConfig();
        ReflectionTestUtils.invokeMethod(c, "load");
        assertThat(c.nextStatuses(null)).containsExactly(c.initial());
        // every status reachable from the initial one
        Set<ContractStatus> seen = new HashSet<>(Set.of(c.initial()));
        List<ContractStatus> frontier = new java.util.ArrayList<>(seen);
        while (!frontier.isEmpty()) {
            for (ContractStatus n : c.nextStatuses(frontier.remove(0))) if (seen.add(n)) frontier.add(n);
        }
        assertThat(seen).containsExactlyInAnyOrder(ContractStatus.values());
        assertThat(c.locks(c.finalizeTo())).isTrue();
        assertThat(c.canFinalizeFrom(ContractStatus.EXECUTED)).isFalse();
    }

    @Test
    void predefinedWorkflowsAreValid() {
        var workflows = WorkflowService.loadPredefined(new ObjectMapper());
        assertThat(workflows).hasSizeGreaterThanOrEqualTo(8);
        assertThat(workflows).extracting(WorkflowService.PredefinedWorkflow::name).doesNotHaveDuplicates();
        for (var w : workflows) {
            assertThat(w.steps()).as(w.name()).isNotEmpty();
            Set<String> earlier = new HashSet<>();
            for (WorkflowStepConfig s : w.steps()) {
                assertThat(s.getType()).as(w.name()).isNotNull();
                assertThat(s.getMaxIterations()).isPositive();
                if (s.getCondition() != null) {
                    String step = s.getCondition().getField().split("\\.")[0];
                    assertThat(earlier).as(w.name() + ": condition must reference an earlier step").contains(step);
                    assertThat(s.getCondition().getOp()).isIn("eq", "neq", "in");
                }
                earlier.add(s.getType().name());
            }
        }
        var draftLoop = workflows.stream().filter(w -> w.name().equals("Draft & Assess Loop")).findFirst().orElseThrow();
        assertThat(draftLoop.steps().get(0).getParams()).isEqualTo(Map.of("clauseType", "LIABILITY"));
        assertThat(draftLoop.steps().get(3).getMaxIterations()).isEqualTo(1);
    }

    @Test
    void everyStepTypeHasAQualityRubric() {
        WorkflowQualityScorer s = new WorkflowQualityScorer();
        ReflectionTestUtils.invokeMethod(s, "load");
        assertThat(s.ruledTypes()).containsExactlyInAnyOrder(WorkflowStepType.values());
        var om = new ObjectMapper();
        var q = s.score(WorkflowStepType.EXTRACT_KEY_TERMS, Map.of("partyA", "Acme", "partyB", "N/A"), om);
        assertThat(q.score()).isEqualTo(50);
        assertThat(q.gaps()).singleElement().asString().contains("partyB, effectiveDate, governingLaw");
        assertThat(s.score(WorkflowStepType.APPROVAL_GATE, Map.of(), om).score()).isEqualTo(100);
    }
}
