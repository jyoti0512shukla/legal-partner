package com.legalpartner.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import com.legalpartner.rag.DocumentFullTextRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class QaSuggestionServiceTest {

    @Mock
    private ChatLanguageModel chatModel;
    @Mock
    private DocumentFullTextRetriever retriever;

    private QaSuggestionService service;

    @BeforeEach
    void setUp() {
        service = new QaSuggestionService(chatModel, retriever);
        service.init();
    }

    @Test
    void defaultSuggestionsReturnedForUnknownType() {
        List<String> suggestions = service.getSuggestions("UNKNOWN_TYPE");
        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions.size()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void ndaSuggestionsAreNdaSpecific() {
        List<String> suggestions = service.getSuggestions("NDA");
        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions).anyMatch(s -> s.toLowerCase().contains("confidential") || s.toLowerCase().contains("nda") || s.toLowerCase().contains("mutual"));
    }

    @Test
    void saasSuggestionsAreSaasSpecific() {
        List<String> suggestions = service.getSuggestions("SAAS");
        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions).anyMatch(s -> s.toLowerCase().contains("sla") || s.toLowerCase().contains("uptime") || s.toLowerCase().contains("data"));
    }

    @Test
    void employmentSuggestionsAreEmploymentSpecific() {
        List<String> suggestions = service.getSuggestions("EMPLOYMENT");
        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions).anyMatch(s -> s.toLowerCase().contains("non-compete") || s.toLowerCase().contains("salary") || s.toLowerCase().contains("probation"));
    }

    @Test
    void availableTypesIncludesExpected() {
        var types = service.getAvailableTypes();
        assertThat(types).contains("NDA", "SAAS", "SOFTWARE_LICENSE", "EMPLOYMENT", "MSA", "_default");
    }

    @Test
    void allTypesHaveAtLeast5Questions() {
        for (String type : service.getAvailableTypes()) {
            List<String> suggestions = service.getSuggestions(type);
            assertThat(suggestions).as("Type " + type + " should have >= 5 suggestions")
                    .hasSizeGreaterThanOrEqualTo(5);
        }
    }
}
