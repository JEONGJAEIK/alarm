package com.example.alarm.domain;

import com.example.alarm.support.AbstractMysqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NotificationRepository} MySQL 통합 테스트.
 *
 * <p>Testcontainers로 실제 MySQL 인스턴스를 기동하여 쿼리 정확성을 검증한다.
 */
class NotificationRepositoryTest extends AbstractMysqlIntegrationTest {

    @Autowired NotificationRepository repo;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void clean() { repo.deleteAll(); }

    @Test
    void findByDedupKey는_저장된_알림을_반환한다() {
        Instant t = Instant.parse("2026-04-25T10:00:00Z");
        Notification n = Notification.createImmediate("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-1", Map.of(), t);
        repo.save(n);

        assertTrue(repo.findByDedupKey("evt-1::EMAIL").isPresent());
    }

    @Test
    void findDuePending은_due한_행만_nextAttemptAt_순으로_반환한다() {
        Instant now = Instant.parse("2026-04-25T10:00:00Z");
        Notification due = Notification.createImmediate("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-due", Map.of(), now.minusSeconds(5));
        Notification future = Notification.createScheduled("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-future", Map.of(), now, now.plusSeconds(60));
        repo.saveAll(List.of(due, future));

        List<Notification> result = tx.execute(s -> repo.findDuePending(now, 10));

        assertEquals(1, result.size());
        assertEquals(due.getId(), result.get(0).getId());
    }

    @Test
    void findStuckClaimedIds는_cutoff_이전_IN_PROGRESS_행을_반환한다() {
        Instant now = Instant.parse("2026-04-25T10:00:00Z");
        Notification stuck = Notification.createImmediate("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-stuck", Map.of(), now);
        stuck.claim("worker-X", now.minusSeconds(120));
        repo.save(stuck);

        Notification fresh = Notification.createImmediate("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-fresh", Map.of(), now);
        fresh.claim("worker-Y", now);
        repo.save(fresh);

        List<String> ids = repo.findStuckClaimedIds(now.minusSeconds(60), 50);

        assertEquals(List.of(stuck.getId()), ids);
    }

    @Test
    void 사용자별_조회는_read_플래그로_필터링된다() {
        Instant t = Instant.parse("2026-04-25T10:00:00Z");
        Notification unread = Notification.createImmediate("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.IN_APP, "evt-a", Map.of(), t);
        Notification read = Notification.createImmediate("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.IN_APP, "evt-b", Map.of(), t);
        read.markRead(t);
        repo.saveAll(List.of(unread, read));

        var page = PageRequest.of(0, 50);
        assertEquals(1, repo.findByRecipientIdAndReadOrderByCreatedAtDesc("u1", false, page).size());
        assertEquals(1, repo.findByRecipientIdAndReadOrderByCreatedAtDesc("u1", true, page).size());
        assertEquals(2, repo.findByRecipientIdOrderByCreatedAtDesc("u1", page).size());
    }
}
