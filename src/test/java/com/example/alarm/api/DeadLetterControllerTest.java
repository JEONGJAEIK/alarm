package com.example.alarm.api;

import com.example.alarm.domain.DeadLetter;
import com.example.alarm.domain.DeadLetterRepository;
import com.example.alarm.domain.Notification;
import com.example.alarm.domain.NotificationChannelType;
import com.example.alarm.domain.NotificationRepository;
import com.example.alarm.domain.NotificationType;
import com.example.alarm.support.AbstractMysqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link DeadLetterController}의 관리자용 DLQ 엔드포인트 통합 테스트.
 *
 * <p>알림을 직접 DEAD_LETTER 상태로 전이시키고 DLQ row를 함께 영속화하여
 * 목록·재시도 시나리오를 검증한다. sweeper와의 락 경합을 피하기 위해
 * sweep 주기는 충분히 길게 설정한다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "alarm.dispatch.sweep-interval-ms=3600000"
})
class DeadLetterControllerTest extends AbstractMysqlIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired NotificationRepository notifRepo;
    @Autowired DeadLetterRepository dlqRepo;
    @Autowired TransactionTemplate tx;
    @Autowired Clock clock;

    private String externalId;

    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(s -> {
            dlqRepo.deleteAllInBatch();
            notifRepo.deleteAllInBatch();
        });
        externalId = tx.execute(s -> {
            Instant now = Instant.now(clock);
            Notification n = Notification.createImmediate(
                    "user-1",
                    NotificationType.PAYMENT_CONFIRMED,
                    NotificationChannelType.EMAIL,
                    "evt-" + System.nanoTime(), null, now);
            n.claim("worker-test", now);
            n.markDeadLetter(now);
            Notification saved = notifRepo.saveAndFlush(n);
            dlqRepo.saveAndFlush(DeadLetter.create(saved.getId(), "SMTP 504", now));
            return saved.getExternalId();
        });
    }

    @Test
    void GET_admin_dead_letters는_failureHistory를_포함하는_페이지를_반환한다() throws Exception {
        mvc.perform(get("/api/admin/dead-letters")
                        .header("X-User-Id", "admin42")
                        .header("X-Admin", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.content[0].failureHistory").isArray())
                .andExpect(jsonPath("$.content[0].failureHistory[0].reason", equalTo("SMTP 504")))
                .andExpect(jsonPath("$.content[0].reviveCount", equalTo(0)));
    }

    @Test
    void POST_retry는_X_Admin_Id_헤더를_받아_last_revived_by에_기록한다() throws Exception {
        mvc.perform(post("/api/admin/dead-letters/{id}/retry", externalId)
                        .header("X-User-Id", "admin42")
                        .header("X-Admin", "true")
                        .header("X-Admin-Id", "admin42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("PENDING")))
                .andExpect(jsonPath("$.attempts", equalTo(0)))
                .andExpect(jsonPath("$.reviveCount", equalTo(1)))
                .andExpect(jsonPath("$.lastRevivedBy", equalTo("admin42")));
    }

    @Test
    void POST_retry는_X_Admin_없이_호출하면_차단된다() throws Exception {
        mvc.perform(post("/api/admin/dead-letters/{id}/retry", externalId)
                        .header("X-User-Id", "admin42")
                        .header("X-Admin-Id", "admin42"))
                .andExpect(status().isForbidden());
    }

    @Test
    void POST_retry는_없는_externalId에_대해_404() throws Exception {
        mvc.perform(post("/api/admin/dead-letters/{id}/retry", "nonexistentABCD")
                        .header("X-User-Id", "admin42")
                        .header("X-Admin", "true")
                        .header("X-Admin-Id", "admin42"))
                .andExpect(status().isNotFound());
    }
}
