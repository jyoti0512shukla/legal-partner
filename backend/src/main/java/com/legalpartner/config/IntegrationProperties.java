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
    private NetDocuments netDocuments = new NetDocuments();
    private IManage iManage = new IManage();
    private MicrosoftTeams microsoftTeams = new MicrosoftTeams();

    @Data
    public static class Docusign {
        private boolean enabled = false;
        private String clientId = "";
        private String clientSecret = "";
        /** demo or production */
        private String environment = "demo";
        /** HMAC secret for webhook signature verification (from DocuSign Connect settings) */
        private String webhookSecret = "";
    }

    @Data
    public static class Slack {
        private boolean enabled = false;
    }

    @Data
    public static class NetDocuments {
        private boolean enabled = false;
        private String clientId = "";
        private String clientSecret = "";
    }

    @Data
    public static class IManage {
        private boolean enabled = false;
        private String clientId = "";
        private String clientSecret = "";
        private String server = "";  // e.g. cloudimanage.com or firm's own server
    }

    @Data
    public static class MicrosoftTeams {
        private boolean enabled = false;
    }
}
