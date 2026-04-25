package com.example.alarm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "alarm.dispatch")
public class DispatchProperties {
    private long pollIntervalMs = 500;
    private int batchSize = 20;
    private int maxAttempts = 5;
    private long visibilityTimeoutSeconds = 60;
    private long sweepIntervalMs = 10_000;
    private long backoffBaseMs = 1_000;
    private long backoffMaxMs = 30L * 60 * 1000;
    private double backoffJitterRatio = 0.2;

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long v) { this.pollIntervalMs = v; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int v) { this.batchSize = v; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int v) { this.maxAttempts = v; }
    public long getVisibilityTimeoutSeconds() { return visibilityTimeoutSeconds; }
    public void setVisibilityTimeoutSeconds(long v) { this.visibilityTimeoutSeconds = v; }
    public long getSweepIntervalMs() { return sweepIntervalMs; }
    public void setSweepIntervalMs(long v) { this.sweepIntervalMs = v; }
    public long getBackoffBaseMs() { return backoffBaseMs; }
    public void setBackoffBaseMs(long v) { this.backoffBaseMs = v; }
    public long getBackoffMaxMs() { return backoffMaxMs; }
    public void setBackoffMaxMs(long v) { this.backoffMaxMs = v; }
    public double getBackoffJitterRatio() { return backoffJitterRatio; }
    public void setBackoffJitterRatio(double v) { this.backoffJitterRatio = v; }

    public Duration backoffBase() { return Duration.ofMillis(backoffBaseMs); }
    public Duration backoffMax() { return Duration.ofMillis(backoffMaxMs); }
    public Duration visibilityTimeout() { return Duration.ofSeconds(visibilityTimeoutSeconds); }
}
