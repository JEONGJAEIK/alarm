package com.example.alarm.dispatch;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ExponentialBackoffRetryPolicy} 단위 테스트.
 *
 * <p>multiplier 4 기반 30s/2m/8m/15m 곡선 검증, 포기 조건, 지터 분포 범위를 확인한다.
 */
class ExponentialBackoffRetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-04-26T00:00:00Z");

    @Test
    void 곱수_4_jitter_0이면_30s_2m_8m_15m_곡선이_나온다() {
        var policy = new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(30), Duration.ofMinutes(15), 0.0, 4, 5);

        assertThat(policy.nextAttemptAt(1, NOW)).isEqualTo(NOW.plusSeconds(30));
        assertThat(policy.nextAttemptAt(2, NOW)).isEqualTo(NOW.plusSeconds(120));   // 2m
        assertThat(policy.nextAttemptAt(3, NOW)).isEqualTo(NOW.plusSeconds(480));   // 8m
        assertThat(policy.nextAttemptAt(4, NOW)).isEqualTo(NOW.plusSeconds(900));   // 15m (cap)
    }

    @Test
    void shouldGiveUp는_attempts가_maxAttempts에_도달하면_true() {
        var policy = new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(30), Duration.ofMinutes(15), 0.0, 4, 5);

        assertThat(policy.shouldGiveUp(4)).isFalse();
        assertThat(policy.shouldGiveUp(5)).isTrue();
        assertThat(policy.shouldGiveUp(6)).isTrue();
    }

    @Test
    void jitter_0_2이면_delay가_20퍼센트_범위_내에_분산된다() {
        var policy = new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(30), Duration.ofMinutes(15), 0.2, 4, 5);

        for (int i = 0; i < 100; i++) {
            Instant next = policy.nextAttemptAt(1, NOW);
            long delayMs = next.toEpochMilli() - NOW.toEpochMilli();
            assertThat(delayMs).isBetween(24_000L, 36_000L);
        }
    }

    @Test
    void 잘못된_인자는_생성자에서_거부된다() {
        // base가 0 이하
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(
                Duration.ZERO, Duration.ofMinutes(15), 0.2, 4, 5))
                .isInstanceOf(IllegalArgumentException.class);
        // max가 base 미만
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(
                Duration.ofMinutes(20), Duration.ofMinutes(15), 0.2, 4, 5))
                .isInstanceOf(IllegalArgumentException.class);
        // jitterRatio 범위 밖
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(30), Duration.ofMinutes(15), 1.5, 4, 5))
                .isInstanceOf(IllegalArgumentException.class);
        // multiplier < 2
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(30), Duration.ofMinutes(15), 0.2, 1, 5))
                .isInstanceOf(IllegalArgumentException.class);
        // maxAttempts < 1
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(30), Duration.ofMinutes(15), 0.2, 4, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
