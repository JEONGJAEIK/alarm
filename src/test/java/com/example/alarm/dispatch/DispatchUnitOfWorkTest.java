package com.example.alarm.dispatch;

import com.example.alarm.domain.*;
import com.example.alarm.support.AbstractMysqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
}
