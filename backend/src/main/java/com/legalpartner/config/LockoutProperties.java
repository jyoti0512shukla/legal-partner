package com.legalpartner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "legalpartner.lockout")
public class LockoutProperties {

    private int maxAttempts = 5;
    private int lockDurationMinutes = 15;

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getLockDurationMinutes() { return lockDurationMinutes; }
    public void setLockDurationMinutes(int lockDurationMinutes) { this.lockDurationMinutes = lockDurationMinutes; }
}
