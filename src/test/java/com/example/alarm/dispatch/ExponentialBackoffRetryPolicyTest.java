package com.example.alarm.dispatch;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ExponentialBackoffRetryPolicy} 단위 테스트.
 *
 * <p>지수 백오프 지연 계산, 포기 조건, 유효하지 않은 인자 거부를 검증한다.
 */
class ExponentialBackoffRetryPolicyTest {

    @Test
    void delay는_max에_도달할_때까지_2배씩_증가한다() {
        var p = new ExponentialBackoffRetryPolicy(
                Duration.ofMillis(100), Duration.ofSeconds(10), 0.0, 5);
        Instant t = Instant.parse("2026-04-25T00:00:00Z");

        assertEquals(t.plusMillis(100), p.nextAttemptAt(1, t));
        assertEquals(t.plusMillis(200), p.nextAttemptAt(2, t));
        assertEquals(t.plusMillis(400), p.nextAttemptAt(3, t));
        assertEquals(t.plusMillis(800), p.nextAttemptAt(4, t));
        assertEquals(t.plusSeconds(10), p.nextAttemptAt(20, t));
    }

    @Test
    void maxAttempts에_도달하면_shouldGiveUp이_true이다() {
        var p = new ExponentialBackoffRetryPolicy(
                Duration.ofMillis(100), Duration.ofSeconds(10), 0.0, 3);

        assertFalse(p.shouldGiveUp(2));
        assertTrue(p.shouldGiveUp(3));
        assertTrue(p.shouldGiveUp(4));
    }

    @Test
    void 잘못된_인자는_생성자에서_거부된다() {
        assertThrows(IllegalArgumentException.class, () ->
                new ExponentialBackoffRetryPolicy(Duration.ZERO, Duration.ofSeconds(1), 0, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new ExponentialBackoffRetryPolicy(Duration.ofSeconds(2), Duration.ofSeconds(1), 0, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new ExponentialBackoffRetryPolicy(Duration.ofMillis(10), Duration.ofSeconds(1), 1.5, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new ExponentialBackoffRetryPolicy(Duration.ofMillis(10), Duration.ofSeconds(1), 0, 0));
    }
}
