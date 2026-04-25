package com.example.alarm.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Notification} 도메인 엔티티 단위 테스트.
 *
 * <p>상태 머신 전이, 클레임/해제, 읽음 처리, revive 등 핵심 비즈니스 메서드를 검증한다.
 */
class NotificationTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");

    @Test
    void create는_PENDING과_attempts_0으로_초기화한다() {
        Notification n = Notification.create("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-1", Map.of("a", 1), T0);

        assertEquals(NotificationStatus.PENDING, n.getStatus());
        assertEquals(0, n.getAttempts());
        assertEquals("evt-1::EMAIL", n.getDedupKey());
        assertEquals(T0, n.getNextAttemptAt());
        assertNull(n.getClaimedAt());
        assertFalse(n.isRead());
    }

    @Test
    void 미래_scheduledAt이_있으면_nextAttemptAt에_반영된다() {
        Instant future = T0.plusSeconds(3600);
        Notification n = Notification.create("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-2", Map.of(), T0, future);

        assertEquals(future, n.getNextAttemptAt());
    }

    @Test
    void claim은_PENDING을_IN_PROGRESS로_전이시킨다() {
        Notification n = Notification.create("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-3", Map.of(), T0);
        Instant t1 = T0.plusSeconds(1);

        n.claim("worker-A", t1);

        assertEquals(NotificationStatus.IN_PROGRESS, n.getStatus());
        assertEquals(t1, n.getClaimedAt());
        assertEquals("worker-A", n.getClaimedBy());
    }

    @Test
    void claim은_PENDING이_아닌_상태에서_거부된다() {
        Notification n = Notification.create("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-4", Map.of(), T0);
        n.claim("worker-A", T0);

        assertThrows(IllegalStateException.class, () -> n.claim("worker-B", T0));
    }

    @Test
    void markSucceeded는_claim_정보를_정리한다() {
        Notification n = Notification.create("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-5", Map.of(), T0);
        n.claim("worker-A", T0);

        n.markSucceeded(T0.plusSeconds(2));

        assertEquals(NotificationStatus.SUCCEEDED, n.getStatus());
        assertNull(n.getClaimedAt());
        assertNull(n.getClaimedBy());
    }

    @Test
    void scheduleRetry는_attempts를_증가시키고_실패_사유를_저장한다() {
        Notification n = Notification.create("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-6", Map.of(), T0);
        n.claim("worker-A", T0);

        n.scheduleRetry(T0.plusSeconds(10), "smtp 503", T0.plusSeconds(2));

        assertEquals(NotificationStatus.PENDING, n.getStatus());
        assertEquals(1, n.getAttempts());
        assertEquals("smtp 503", n.getLastFailureReason());
        assertEquals(T0.plusSeconds(10), n.getNextAttemptAt());
    }

    @Test
    void markDeadLetter는_DEAD_LETTER로_전이시킨다() {
        Notification n = Notification.create("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-7", Map.of(), T0);
        n.claim("worker-A", T0);

        n.markDeadLetter("permanent", T0.plusSeconds(2));

        assertEquals(NotificationStatus.DEAD_LETTER, n.getStatus());
        assertEquals(1, n.getAttempts());
        assertEquals("permanent", n.getLastFailureReason());
    }

    @Test
    void releaseStuckClaim은_PENDING으로_복귀하면서_attempts를_증가시키지_않는다() {
        Notification n = Notification.create("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-8", Map.of(), T0);
        n.claim("worker-A", T0);

        n.releaseStuckClaim(T0.plusSeconds(120));

        assertEquals(NotificationStatus.PENDING, n.getStatus());
        assertEquals(0, n.getAttempts());
        assertNull(n.getClaimedAt());
    }

    @Test
    void markRead는_읽음_플래그와_시각을_한_번만_기록한다() {
        Notification n = Notification.create("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.IN_APP, "evt-9", Map.of(), T0);

        n.markRead(T0.plusSeconds(1));
        Instant firstReadAt = n.getReadAt();

        n.markRead(T0.plusSeconds(2));

        assertTrue(n.isRead());
        assertEquals(firstReadAt, n.getReadAt());
    }

    @Test
    void revive는_attempts와_status를_초기화한다() {
        Notification n = Notification.create("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-10", Map.of(), T0);
        n.claim("worker-A", T0);
        n.markDeadLetter("nope", T0);

        n.revive(T0.plusSeconds(3600));

        assertEquals(NotificationStatus.PENDING, n.getStatus());
        assertEquals(0, n.getAttempts());
        assertNull(n.getLastFailureReason());
    }

    @Test
    void revive는_DEAD_LETTER가_아닌_상태에서_거부된다() {
        Notification n = Notification.create("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-11", Map.of(), T0);

        assertThrows(IllegalStateException.class, () -> n.revive(T0));
    }
}
