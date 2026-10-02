package com.legalpartner.service;

import com.legalpartner.config.ContractTypeRegistry;

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

    private final ContractTypeRegistry contractTypes;

    public TemplateService(ContractTypeRegistry contractTypes) {
        this.contractTypes = contractTypes;
    }

    /** Template picker entries — contract_types.yml is the single source (YAML order). */
    public List<TemplateInfo> listTemplates() {
        return contractTypes.allTemplateIds().stream()
                .map(contractTypes::get)
                .map(c -> TemplateInfo.builder().id(c.templateId()).name(c.displayName())
                        .description(c.description()).build())
                .toList();
    }

    public String loadTemplate(String templateId) {
        // Try specific template first, fall back to generic for new types and custom
        String path = "templates/" + templateId + ".html";
        ClassPathResource res = new ClassPathResource(path);
        if (res.exists()) {
            try { return res.getContentAsString(StandardCharsets.UTF_8); } catch (IOException ignored) {}
        }
        // Fallback to generic template
        try {
            return new ClassPathResource("templates/generic.html").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load template {}: {}", templateId, e.getMessage());
            throw new IllegalArgumentException("Template not found: " + templateId);
        }
    }
}
