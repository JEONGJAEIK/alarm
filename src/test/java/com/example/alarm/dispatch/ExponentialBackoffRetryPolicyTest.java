package com.example.alarm.dispatch;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ExponentialBackoffRetryPolicyTest {

    @Test
    void doublesDelayUpToCap() {
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
    void shouldGiveUpOnReachingMax() {
        var p = new ExponentialBackoffRetryPolicy(
                Duration.ofMillis(100), Duration.ofSeconds(10), 0.0, 3);

        assertFalse(p.shouldGiveUp(2));
        assertTrue(p.shouldGiveUp(3));
        assertTrue(p.shouldGiveUp(4));
    }

    @Test
    void rejectsInvalidArgs() {
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
