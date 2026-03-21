package com.legalpartner.integration;

import com.legalpartner.storage.TokenResponse;

public interface IntegrationProvider {

    String getProviderId();

    String getDisplayName();

    String getCategory();

    boolean isOAuth();

    String buildAuthorizationUrl(String redirectUri, String state);

    TokenResponse exchangeCodeForTokens(String code, String redirectUri);

    String refreshAccessToken(String refreshToken);
}
