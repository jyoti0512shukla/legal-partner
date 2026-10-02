package com.legalpartner.config;

import com.legalpartner.service.TemplateService;
import com.legalpartner.testsupport.ConfigFixtures;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards for the config-over-code refactor: config that code depends on must exist and be wired. */
class HardcodingGuardsTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** A prompt id typo would silently yield an empty prompt at runtime. */
    @Test
    void everyPromptIdUsedInCodeExists() throws Exception {
        PromptRepository prompts = ConfigFixtures.prompts();
        Pattern get = Pattern.compile("prompts\\.get\\(\"([A-Z0-9_]+)\"\\)");
        Set<String> ids = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = get.matcher(Files.readString(f));
                while (m.find()) ids.add(m.group(1));
            }
        }
        assertThat(ids).as("prompt ids referenced in code").isNotEmpty();
        for (String id : ids) {
            assertThat(prompts.get(id)).as("prompt " + id).isNotBlank();
        }
    }

    @Test
    void templatePickerComesFromContractTypes() {
        var contractTypes = ConfigFixtures.contractTypes();
        var list = new TemplateService(contractTypes).listTemplates();
        assertThat(list).extracting(t -> t.getId()).containsExactlyElementsOf(contractTypes.allTemplateIds());
        assertThat(list).extracting(t -> t.getId()).contains("vendor", "custom");
        assertThat(list).allSatisfy(t -> {
            assertThat(t.getName()).isNotBlank();
            assertThat(t.getDescription()).isNotBlank();
        });
    }

    @Test
    void draftingDefaultsReplacePlaceholdersFromConfig() {
        DraftingDefaults d = new DraftingDefaults();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(d, "load");
        Map<String, String> vars = Map.of("PARTY_A", "Northwind", "PARTY_B", "Contoso",
                "JURISDICTION", "Delaware", "NOTICE_DAYS", "45", "TERM_YEARS", "2");
        String out = d.applyPlaceholderRules(
                "[Party A] shall give [X days] notice under [governing law] law for [insert duration]. Fee: TBD.", vars);
        // Regression: the old code produced "45 (thirty) days".
        assertThat(out).isEqualTo("Northwind shall give 45 days notice under Delaware law for 2 years. "
                + "Fee: as mutually agreed by the Parties in writing.");
        assertThat(d.form("NOTICE_DAYS")).isEqualTo("30");
        assertThat(d.styleFingerprint(Map.of("DRAFTING_REGISTER", "REG"))).startsWith("REG; sentences");
    }

    @Test
    void jurisdictionsDefineDraftingRegister() {
        LegalSystemConfig c = new LegalSystemConfig();
        c.setLegalSystem("USA");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(c, "load");
        assertThat(c.localizeForJurisdiction("%DRAFTING_REGISTER%", "India (Maharashtra)")).contains("Indian legal English");
        assertThat(c.localizeForJurisdiction("%DRAFTING_REGISTER%", "State of Delaware")).contains("governing law");
    }

    @Test
    void contractTypesCarryBannedTermsAndRefPrefix() {
        var ct = ConfigFixtures.contractTypes();
        assertThat(ct.get("saas").bannedTerms()).contains("landlord").doesNotContain("uptime");
        assertThat(ct.get("nda").bannedTerms()).contains("uptime");
        assertThat(ct.get("software_license").refPrefix()).isEqualTo("SLA");
        assertThat(ct.findByDisplayName("SaaS Subscription Agreement")).isPresent();
    }

    @Test
    void checklistIsOneListInYaml() {
        Map<String, String> checklist = ConfigFixtures.riskQuestions().getChecklistClauses();
        assertThat(checklist).hasSize(12).containsEntry("LIABILITY_LIMIT", "Limitation of Liability");
        List<String> ids = List.copyOf(checklist.keySet());
        String filled = com.legalpartner.service.ContractReviewServiceTestAccess.fill(
                ConfigFixtures.prompts().get("CHECKLIST_USER"), ids);
        assertThat(filled).contains("exactly 12 ratings").contains(ids.get(0) + "=?-?").doesNotContain("{{");
        @SuppressWarnings("unchecked")
        var schema = (Map<String, Object>) ((Map<String, Object>) com.legalpartner.rag.StructuredSchemas
                .checklistSchema(ids).get("properties")).get("clauses");
        assertThat(schema).containsEntry("minItems", 12).containsEntry("maxItems", 12);
    }

    @Test
    void vocabularyKeysAreValid() {
        var vocab = ConfigFixtures.vocabulary();
        for (String key : vocab.chunkClauseKeywords().keySet()) {
            assertThat(java.util.Arrays.stream(com.legalpartner.model.enums.ClauseType.values()).map(Enum::name))
                    .as("vocabulary.yml chunk_clause_keywords key " + key).contains(key);
        }
        assertThat(vocab.riskCategories()).hasSize(7);
        assertThat(vocab.riskCategoryByLabel("ip rights")).isNotNull();
        assertThat(vocab.typeSignals()).extracting(com.legalpartner.config.LegalVocabulary.TypeSignals::type).contains("NDA", "SAAS", "LEASE");
        assertThat(vocab.clauseHints().get(0).matches(Set.of("SLA", "DATA_PROTECTION"))).isTrue();
    }

    @Test
    void queryExpansionComesFromVocabulary() {
        var expander = new com.legalpartner.rag.QueryExpander(ConfigFixtures.vocabulary());
        String out = expander.expand("termination under the nda");
        assertThat(out).contains("non-disclosure agreement").contains("notice of termination");
    }

    @Test
    void everyIntakeFieldHasALabel() {
        var ct = ConfigFixtures.contractTypes();
        for (String t : ct.allTemplateIds()) {
            var c = ct.get(t);
            java.util.List<String> fields = new java.util.ArrayList<>(c.requiredFields());
            fields.addAll(c.recommendedFields());
            for (String f : fields) {
                assertThat(ct.fieldLabels()).as("contract_types.yml field_labels for " + f + " (" + t + ")").containsKey(f);
            }
        }
    }

    @Test
    void everyGoldenPlaceholderResolves() {
        var golden = ConfigFixtures.goldenClauses();
        Set<String> known = golden.knownPlaceholders();
        Pattern ph = Pattern.compile("\\{\\{\\s*([^#/}][^}]*)}}");
        List<String> unknown = new java.util.ArrayList<>();
        int checked = 0;
        for (var gc : golden.all()) {
            Matcher m = ph.matcher(gc.template());
            while (m.find()) {
                checked++;
                String name = m.group(1).trim();
                boolean ok = known.contains(name) || ConfigConsistencyTest.resolvesOnDealSpec(name)
                        || name.startsWith("partyA.") || name.startsWith("partyB.");
                if (!ok) unknown.add(gc.id() + ": {{" + name + "}}");
            }
        }
        assertThat(checked).as("placeholders inspected").isGreaterThan(50);
        assertThat(unknown).as("golden_clauses.yml placeholders with no alias/literal/role/DealSpec field").isEmpty();
    }

    @Test
    void unresolvedGoldenPlaceholdersRenderAsFillInFields() {
        String html = com.legalpartner.service.GoldenClauseLibrary.toHtml(
                "Notice of \u27E6FILL:notice_days\u27E7 days <b>& more</b>");
        assertThat(html).contains("<span class=\"placeholder\"").contains("[Notice days]")
                .contains("&lt;b&gt;&amp; more&lt;/b&gt;").doesNotContain("&lt;span");
    }

    @Test
    void terminologyMandateFormatsForEveryTemplate() {
        String mandate = ConfigFixtures.prompts().get("DRAFT_TERMINOLOGY_MANDATE");
        var ct = ConfigFixtures.contractTypes();
        for (String t : ct.allTemplateIds()) {
            var c = ct.get(t);
            String out = String.format(mandate, "Northwind", "Contoso", c.partyARole(), c.partyBRole(), "X, Y", "Z");
            assertThat(out).contains("(the " + c.partyARole() + ")").doesNotContain("%");
        }
        assertThat(mandate).doesNotContain("Vendor");
    }
}
