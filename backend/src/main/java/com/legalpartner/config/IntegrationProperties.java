package com.legalpartner.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "legalpartner.integrations")
public class IntegrationProperties {

    private String frontendUrl = "http://localhost:5173";
    private String backendUrl = "http://localhost:8080";

    private Docusign docusign = new Docusign();
    private Slack slack = new Slack();

    @Data
    public static class Docusign {
        private boolean enabled = false;
        private String clientId = "";
        private String clientSecret = "";
        /** demo or production */
        private String environment = "demo";
    }

    @Data
    public static class Slack {
        private boolean enabled = false;
    }
}
