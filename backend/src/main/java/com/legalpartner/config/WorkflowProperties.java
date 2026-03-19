package com.legalpartner.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "legalpartner.workflows")
@Data
public class WorkflowProperties {
    private boolean enabled = true;
}
