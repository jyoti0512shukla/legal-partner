package com.legalpartner.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "legalpartner.mail")
@Data
public class MailProperties {
    /** Set to true once SMTP credentials are configured */
    private boolean enabled = false;
    /** From address used in outgoing emails */
    private String from = "noreply@legalpartner.local";
    /** Optional: base URL for deep links in emails */
    private String appUrl = "http://localhost:5173";
}
