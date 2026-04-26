package com.example.alarm.dispatch;

import com.example.alarm.domain.*;
import com.example.alarm.support.AbstractMysqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link StuckClaimSweeper}의 stuck-claim 복구 동작 통합 테스트.
 */
class StuckClaimSweeperTest extends AbstractMysqlIntegrationTest {

    @Autowired NotificationRepository repo;
    @Autowired StuckClaimSweeper sweeper;

    @BeforeEach
    void clean() { repo.deleteAll(); }

    @Test
    void visibility_timeout_초과한_IN_PROGRESS_행을_PENDING으로_되돌린다() {
        Instant longAgo = Instant.now().minusSeconds(120);
        Notification stuck = Notification.createImmediate("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-stuck-1", Map.of(), longAgo);
        stuck.claim("dead-worker", longAgo);
        repo.save(stuck);

        int released = sweeper.sweep();

        assertEquals(1, released);
        Notification reloaded = repo.findById(stuck.getId()).orElseThrow();
        assertEquals(NotificationStatus.PENDING, reloaded.getStatus());
        assertNull(reloaded.getClaimedAt());
        assertEquals(0, reloaded.getAttempts(), "sweeper 복귀는 attempts 증가시키지 않아야 한다");
    }

    @Test
    void 최근_클레임_행은_그대로_유지한다() {
        Notification fresh = Notification.createImmediate("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-stuck-2", Map.of(), Instant.now());
        fresh.claim("live-worker", Instant.now());
        repo.save(fresh);

        sweeper.sweep();

        assertEquals(NotificationStatus.IN_PROGRESS,
                repo.findById(fresh.getId()).orElseThrow().getStatus());
    }
}
