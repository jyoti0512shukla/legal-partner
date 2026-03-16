package com.legalpartner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "legalpartner.password")
public class PasswordPolicyProperties {

    private int minLength = 12;
    private int maxLength = 128;
    private boolean requireUppercase = true;
    private boolean requireLowercase = true;
    private boolean requireDigit = true;
    private boolean requireSpecial = true;
    private boolean blockCommon = true;
    private int expiryDays = 90;
    private int historyCount = 5;

    public int getMinLength() { return minLength; }
    public void setMinLength(int minLength) { this.minLength = minLength; }
    public int getMaxLength() { return maxLength; }
    public void setMaxLength(int maxLength) { this.maxLength = maxLength; }
    public boolean isRequireUppercase() { return requireUppercase; }
    public void setRequireUppercase(boolean requireUppercase) { this.requireUppercase = requireUppercase; }
    public boolean isRequireLowercase() { return requireLowercase; }
    public void setRequireLowercase(boolean requireLowercase) { this.requireLowercase = requireLowercase; }
    public boolean isRequireDigit() { return requireDigit; }
    public void setRequireDigit(boolean requireDigit) { this.requireDigit = requireDigit; }
    public boolean isRequireSpecial() { return requireSpecial; }
    public void setRequireSpecial(boolean requireSpecial) { this.requireSpecial = requireSpecial; }
    public boolean isBlockCommon() { return blockCommon; }
    public void setBlockCommon(boolean blockCommon) { this.blockCommon = blockCommon; }
    public int getExpiryDays() { return expiryDays; }
    public void setExpiryDays(int expiryDays) { this.expiryDays = expiryDays; }
    public int getHistoryCount() { return historyCount; }
    public void setHistoryCount(int historyCount) { this.historyCount = historyCount; }
}
