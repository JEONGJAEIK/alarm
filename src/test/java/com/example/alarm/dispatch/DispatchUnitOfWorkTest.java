package com.example.alarm.dispatch;

import com.example.alarm.domain.*;
import com.example.alarm.support.AbstractMysqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DispatchUnitOfWork}의 짧은 트랜잭션 동작을 통합 테스트.
 */
class DispatchUnitOfWorkTest extends AbstractMysqlIntegrationTest {

    @Autowired DispatchUnitOfWork uow;
    @Autowired NotificationRepository repo;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void clean() { repo.deleteAll(); }

    @Test
    void claimBatch는_PENDING을_IN_PROGRESS로_전이시킨다() {
        Instant now = Instant.now();
        repo.save(Notification.create("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-c1", Map.of(), now.minusSeconds(5)));
        repo.save(Notification.create("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-c2", Map.of(), now.minusSeconds(3)));

        List<Notification> claimed = uow.claimBatch("worker-1", 10);

        assertEquals(2, claimed.size());
        for (Notification n : claimed) {
            assertEquals(NotificationStatus.IN_PROGRESS, repo.findById(n.getId()).orElseThrow().getStatus());
        }
    }

    @Test
    void claimBatch는_미래의_scheduledAt을_가진_행을_제외한다() {
        Instant now = Instant.now();
        Notification due = repo.save(Notification.create("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-c3", Map.of(), now.minusSeconds(5)));
        Notification later = repo.save(Notification.create("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-c4", Map.of(), now, now.plusSeconds(60)));

        List<Notification> claimed = uow.claimBatch("worker-1", 10);

        assertEquals(1, claimed.size());
        assertEquals(due.getId(), claimed.get(0).getId());
        assertEquals(NotificationStatus.PENDING, repo.findById(later.getId()).orElseThrow().getStatus());
    }

    @Test
    void finalizeSuccess는_SUCCEEDED로_전이시킨다() {
        Notification n = repo.save(Notification.create("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-c5", Map.of(), Instant.now().minusSeconds(5)));
        uow.claimBatch("worker-1", 10);

        uow.finalizeSuccess(n.getId());

        assertEquals(NotificationStatus.SUCCEEDED, repo.findById(n.getId()).orElseThrow().getStatus());
    }

    @Test
    void finalizeFailure는_retryable이면_PENDING으로_되돌린다() {
        Notification n = repo.save(Notification.create("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-c6", Map.of(), Instant.now().minusSeconds(5)));
        uow.claimBatch("worker-1", 10);

        uow.finalizeFailure(n.getId(), "smtp 503", true);

        Notification after = repo.findById(n.getId()).orElseThrow();
        assertEquals(NotificationStatus.PENDING, after.getStatus());
        assertEquals(1, after.getAttempts());
        assertTrue(after.getLastFailureReason().contains("smtp 503"));
    }

    @Test
    void finalizeFailure는_nonRetryable이면_DEAD_LETTER로_전이시킨다() {
        Notification n = repo.save(Notification.create("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-c7", Map.of(), Instant.now().minusSeconds(5)));
        uow.claimBatch("worker-1", 10);

        uow.finalizeFailure(n.getId(), "bad address", false);

        assertEquals(NotificationStatus.DEAD_LETTER, repo.findById(n.getId()).orElseThrow().getStatus());
    }

    @Test
    void finalizeSuccess는_sweeper가_PENDING으로_되돌린_경우_silent_skip한다() {
        Notification n = repo.save(Notification.create("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-race1", Map.of(), Instant.now().minusSeconds(5)));
        uow.claimBatch("worker-1", 10);

        // sweeper 시뮬레이션: bulk UPDATE로 PENDING 복귀 (트랜잭션 필요)
        tx.executeWithoutResult(s ->
                repo.releaseStuckClaims(Instant.now().plusSeconds(999), Instant.now()));

        // finalizeSuccess 시도 — 가드에 막혀 silent skip
        assertDoesNotThrow(() -> uow.finalizeSuccess(n.getId()));

        Notification after = repo.findById(n.getId()).orElseThrow();
        assertEquals(NotificationStatus.PENDING, after.getStatus());
        assertEquals(0, after.getAttempts(), "sweeper 복귀는 attempts를 증가시키지 않아야 한다");
    }

    @Test
    void finalizeFailure는_sweeper가_PENDING으로_되돌린_경우_silent_skip한다() {
        Notification n = repo.save(Notification.create("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-race2", Map.of(), Instant.now().minusSeconds(5)));
        uow.claimBatch("worker-1", 10);

        // sweeper 시뮬레이션: bulk UPDATE로 PENDING 복귀 (트랜잭션 필요)
        tx.executeWithoutResult(s ->
                repo.releaseStuckClaims(Instant.now().plusSeconds(999), Instant.now()));

        assertDoesNotThrow(() -> uow.finalizeFailure(n.getId(), "ignored", true));

        Notification after = repo.findById(n.getId()).orElseThrow();
        assertEquals(NotificationStatus.PENDING, after.getStatus());
        assertEquals(0, after.getAttempts(), "silent skip 시 attempts 증가 안 함");
    }
}
