package com.legalpartner.service.extraction;

import com.legalpartner.repository.AliasOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CanonicalMapperTest {

    @Mock
    private AliasOverrideRepository overrideRepo;

    private CanonicalMapper mapper;

    @BeforeEach
    void setUp() {
        when(overrideRepo.findAll()).thenReturn(List.of());
        mapper = new CanonicalMapper(overrideRepo);
        mapper.init();
    }

    @Test
    void exactCanonicalIdMatch() {
        var result = mapper.map("party_a");
        assertThat(result.canonicalField()).isEqualTo("party_a");
        assertThat(result.confidence()).isEqualTo("HIGH");
    }

    @Test
    void aliasMatch() {
        var result = mapper.map("Licensor");
        assertThat(result.canonicalField()).isEqualTo("party_a");
        assertThat(result.confidence()).isEqualTo("HIGH");
    }

    @Test
    void aliasMatchCaseInsensitive() {
        var result = mapper.map("LIMITATION OF LIABILITY");
        assertThat(result.canonicalField()).isEqualTo("liability_cap");
        assertThat(result.confidence()).isEqualTo("HIGH");
    }

    @Test
    void fuzzyMatch() {
        var result = mapper.map("liability_caps"); // close to liability_cap
        assertThat(result.canonicalField()).isNotNull();
        assertThat(result.confidence()).isIn("HIGH", "MEDIUM");
    }

    @Test
    void unknownFieldReturnsLow() {
        var result = mapper.map("some_random_custom_field");
        assertThat(result.confidence()).isEqualTo("LOW");
    }

    @Test
    void nullInputHandled() {
        var result = mapper.map(null);
        assertThat(result.confidence()).isEqualTo("LOW");
    }

    @Test
    void emptyInputHandled() {
        var result = mapper.map("");
        assertThat(result.confidence()).isEqualTo("LOW");
    }

    @Test
    void governingLawAlias() {
        var result = mapper.map("applicable law");
        assertThat(result.canonicalField()).isEqualTo("governing_law");
    }

    @Test
    void paymentTermsAlias() {
        var result = mapper.map("billing terms");
        assertThat(result.canonicalField()).isEqualTo("payment_terms");
    }
}
