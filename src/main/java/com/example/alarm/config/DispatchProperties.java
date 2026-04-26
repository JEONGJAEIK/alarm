package com.example.alarm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code alarm.dispatch.*} 네임스페이스의 발송 설정 프로퍼티.
 *
 * <p>폴링 주기, 배치 크기, 재시도 정책 파라미터 등 발송 워커의 동작을 제어한다.
 * Spring Boot {@code @ConfigurationProperties}로 바인딩되므로 {@code application.yml}에서
 * 외부 설정 가능하다.
 */
@Getter
@Setter
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
    private long backoffMultiplier = 2;
    private int executorAwaitSeconds = 30;

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
