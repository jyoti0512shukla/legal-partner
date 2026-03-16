package com.legalpartner.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "legalpartner.cloud")
public class CloudStorageProperties {

    /** Frontend URL for post-OAuth redirect (e.g. http://localhost:5173) */
    private String frontendUrl = "http://localhost:5173";
    /** Backend base URL for OAuth redirect_uri (e.g. http://localhost:8080) */
    private String backendUrl = "http://localhost:8080";

    private Google google = new Google();
    private Microsoft microsoft = new Microsoft();
    private Dropbox dropbox = new Dropbox();

    @Data
    public static class Google {
        private boolean enabled = false;
        private String clientId = "";
        private String clientSecret = "";
        private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
        private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
        private static final String SCOPES = "https://www.googleapis.com/auth/drive.readonly";
    }

    @Data
    public static class Microsoft {
        private boolean enabled = false;
        private String clientId = "";
        private String clientSecret = "";
        private String tenant = "common";
        private static final String AUTH_URL = "https://login.microsoftonline.com/%s/oauth2/v2.0/authorize";
        private static final String TOKEN_URL = "https://login.microsoftonline.com/%s/oauth2/v2.0/token";
        private static final String SCOPES = "offline_access Files.Read User.Read";
    }

    @Data
    public static class Dropbox {
        private boolean enabled = false;
        private String appKey = "";
        private String appSecret = "";
        private static final String AUTH_URL = "https://www.dropbox.com/oauth2/authorize";
        private static final String TOKEN_URL = "https://api.dropboxapi.com/oauth2/token";
        private static final String SCOPES = "";
    }
}
