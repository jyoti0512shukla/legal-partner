package com.legalpartner.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorsOriginsTest {

    @Test
    void acceptsSeveralExactOrigins() {
        var cors = new CorsOrigins(" https://legal.example.com/, https://1.2.3.4.nip.io ,https://legal.example.com", "http://ignored");
        assertThat(cors.origins()).containsExactly("https://legal.example.com", "https://1.2.3.4.nip.io");
    }

    @Test
    void fallsBackToTheFrontendUrlWhenUnset() {
        assertThat(new CorsOrigins("", "https://legal.example.com").origins()).containsExactly("https://legal.example.com");
        assertThat(new CorsOrigins(null, "https://legal.example.com").origins()).containsExactly("https://legal.example.com");
    }

    @Test
    void rejectsWildcardsAndNonUrls() {
        assertThatThrownBy(() -> new CorsOrigins("https://*.example.com", "x")).hasMessageContaining("wildcards");
        assertThatThrownBy(() -> new CorsOrigins("*", "x")).hasMessageContaining("wildcards");
        assertThatThrownBy(() -> new CorsOrigins("legal.example.com", "x")).hasMessageContaining("http");
    }
}
