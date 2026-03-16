package com.legalpartner.service;

import com.legalpartner.model.dto.TemplateInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
@Slf4j
public class TemplateService {

    private static final List<TemplateInfo> TEMPLATES = List.of(
            TemplateInfo.builder().id("nda").name("Non-Disclosure Agreement").description("Mutual NDA for business discussions").build(),
            TemplateInfo.builder().id("msa").name("Master Services Agreement").description("Framework agreement for ongoing services").build()
    );

    public List<TemplateInfo> listTemplates() {
        return TEMPLATES;
    }

    public String loadTemplate(String templateId) {
        String path = "templates/" + templateId + ".html";
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load template {}: {}", templateId, e.getMessage());
            throw new IllegalArgumentException("Template not found: " + templateId);
        }
    }
}
