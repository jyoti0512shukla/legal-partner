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
            TemplateInfo.builder().id("nda").name("Non-Disclosure Agreement").description("Mutual NDA for protecting confidential information during business discussions").build(),
            TemplateInfo.builder().id("msa").name("Master Services Agreement").description("Framework agreement for ongoing professional services engagements").build(),
            TemplateInfo.builder().id("saas").name("SaaS Subscription Agreement").description("Cloud software subscription with SLAs, data processing, and usage terms").build(),
            TemplateInfo.builder().id("software_license").name("Software License Agreement").description("Commercial software license — perpetual or term, with support and IP ownership").build(),
            TemplateInfo.builder().id("vendor").name("Vendor Agreement").description("Standard vendor terms for procurement of goods or services").build(),
            TemplateInfo.builder().id("supply").name("Supply Agreement").description("Manufacturing or supply chain contract with delivery, quality, and force majeure terms").build(),
            TemplateInfo.builder().id("employment").name("Employment Agreement").description("Executive or senior employee contract with compensation, IP assignment, and restrictive covenants").build(),
            TemplateInfo.builder().id("ip_license").name("IP License Agreement").description("License of patents, trademarks, or copyrights with royalty and sublicense terms").build(),
            TemplateInfo.builder().id("clinical_services").name("Clinical Services Agreement").description("Pharma / healthcare services contract with regulatory, data privacy, and compliance terms").build(),
            TemplateInfo.builder().id("fintech_msa").name("Fintech Master Services Agreement").description("Financial services MSA with regulatory references, data protection, and audit rights").build(),
            TemplateInfo.builder().id("custom").name("Custom Contract").description("Enter your own contract type — AI drafts appropriate clauses based on your description").build()
    );

    public List<TemplateInfo> listTemplates() {
        return TEMPLATES;
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
