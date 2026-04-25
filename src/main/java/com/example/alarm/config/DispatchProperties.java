package com.example.alarm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code alarm.dispatch.*} 네임스페이스의 발송 설정 프로퍼티.
 *
 * <p>폴링 주기, 배치 크기, 재시도 정책 파라미터 등 발송 워커의 동작을 제어한다.
 * Spring Boot {@code @ConfigurationProperties}로 바인딩되므로 {@code application.yml}에서
 * 외부 설정 가능하다.
 */
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

    /**
     * 지수 백오프 기본 지연 시간을 {@link Duration}으로 반환한다.
     *
     * @return {@code backoffBaseMs} 기반 Duration
     */
    public Duration backoffBase() { return Duration.ofMillis(backoffBaseMs); }

    /**
     * 지수 백오프 최대 지연 시간을 {@link Duration}으로 반환한다.
     *
     * @return {@code backoffMaxMs} 기반 Duration
     */
    public Duration backoffMax() { return Duration.ofMillis(backoffMaxMs); }

    /**
     * 클레임 가시성 타임아웃을 {@link Duration}으로 반환한다.
     *
     * @return {@code visibilityTimeoutSeconds} 기반 Duration
     */
    public Duration visibilityTimeout() { return Duration.ofSeconds(visibilityTimeoutSeconds); }
}
