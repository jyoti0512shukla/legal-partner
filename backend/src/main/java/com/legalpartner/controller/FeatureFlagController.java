package com.legalpartner.controller;

import com.legalpartner.config.WorkflowProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/config/features")
@RequiredArgsConstructor
public class FeatureFlagController {

    private final WorkflowProperties workflowProperties;

    @GetMapping
    public Map<String, Boolean> features() {
        return Map.of("workflowsEnabled", workflowProperties.isEnabled());
    }
}
