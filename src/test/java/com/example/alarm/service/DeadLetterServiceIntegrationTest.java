package com.example.alarm.service;

import com.example.alarm.config.DispatchProperties;
import com.example.alarm.domain.DeadLetter;
import com.example.alarm.domain.DeadLetterRepository;
import com.example.alarm.domain.Notification;
import com.example.alarm.domain.NotificationChannelType;
import com.example.alarm.domain.NotificationRepository;
import com.example.alarm.domain.NotificationStatus;
import com.example.alarm.domain.NotificationType;
import com.example.alarm.dispatch.DispatchUnitOfWork;
import com.example.alarm.support.AbstractMysqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DeadLetterService}의 자동 DLQ 진입·revive·재진입 통합 시나리오 검증.
 *
 * <p>Testcontainers MySQL과 Spring Boot 컨텍스트를 사용하여 {@code DispatchUnitOfWork.finalizeFailure}부터
 * {@code DeadLetterService.revive}까지 두 엔티티({@link Notification}, {@link DeadLetter})가
 * 같은 트랜잭션에서 일관되게 갱신되는지 end-to-end로 확인한다.
 */
class DeadLetterServiceIntegrationTest extends AbstractMysqlIntegrationTest {

    @Autowired NotificationRepository notifRepo;
    @Autowired DeadLetterRepository dlqRepo;
    @Autowired DispatchUnitOfWork uow;
    @Autowired DeadLetterService service;
    @Autowired Clock clock;
    @Autowired DispatchProperties dispatchProps;

    private Long notifId;

    @BeforeEach
    void setUp() {
        // FK cascade 순서: dlq → notification 순으로 비운다.
        dlqRepo.deleteAllInBatch();
        notifRepo.deleteAllInBatch();
        Notification n = Notification.createImmediate(
                "user-1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL,
                "evt-" + System.nanoTime(), null, Instant.now(clock));
        notifId = notifRepo.saveAndFlush(n).getId();
    }

    @Test
    void 자동_재시도_소진_시_dlq에_1_row가_INSERT되고_history는_1건() {
        runUntilDeadLetter("SMTP 504 timeout");

        Notification n = notifRepo.findById(notifId).orElseThrow();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        assertThat(n.getAttempts()).isEqualTo(dispatchProps.getMaxAttempts());

        DeadLetter d = dlqRepo.findByNotificationId(notifId).orElseThrow();
        assertThat(d.getReviveCount()).isZero();
        assertThat(d.getFailureHistory()).hasSize(1);
        assertThat(d.getFailureHistory().get(0).reason()).isEqualTo("SMTP 504 timeout");
        assertThat(d.getFailureHistory().get(0).reviveCount()).isZero();
    }

    @Test
    void revive는_notification을_PENDING_attempts_0으로_되돌리고_dlq의_revive_count를_증가시킨다() {
        runUntilDeadLetter("first");

        Notification n = notifRepo.findById(notifId).orElseThrow();
        service.revive(n.getExternalId(), "admin1");

        Notification refreshed = notifRepo.findById(notifId).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(refreshed.getAttempts()).isZero();

        DeadLetter d = dlqRepo.findByNotificationId(notifId).orElseThrow();
        assertThat(d.getReviveCount()).isEqualTo(1);
        assertThat(d.getLastRevivedBy()).isEqualTo("admin1");
        assertThat(d.getFailureHistory()).hasSize(1); // 보존
    }

    @Test
    void revive_후_또_재시도_소진하면_같은_dlq_row가_UPDATE되고_history가_2건이_된다() {
        runUntilDeadLetter("first");
        Notification n = notifRepo.findById(notifId).orElseThrow();
        service.revive(n.getExternalId(), "admin1");

        runUntilDeadLetter("second");

        DeadLetter d = dlqRepo.findByNotificationId(notifId).orElseThrow();
        assertThat(d.getFailureHistory()).hasSize(2);
        assertThat(d.getFailureHistory().get(0).reason()).isEqualTo("first");
        assertThat(d.getFailureHistory().get(0).reviveCount()).isZero();
        assertThat(d.getFailureHistory().get(1).reason()).isEqualTo("second");
        assertThat(d.getFailureHistory().get(1).reviveCount()).isEqualTo(1);
        assertThat(dlqRepo.count()).isEqualTo(1L);
    }

    @Test
    void revive는_DEAD_LETTER가_아닌_알림에_대해_IllegalStateException() {
        Notification n = notifRepo.findById(notifId).orElseThrow();

        assertThatThrownBy(() -> service.revive(n.getExternalId(), "admin1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void list는_updated_at_내림차순으로_dlq_페이지를_반환한다() {
        runUntilDeadLetter("only");

        var page = service.list(PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).deadLetter().getNotificationId()).isEqualTo(notifId);
    }

    /**
     * IN_PROGRESS로 클레임 → finalizeFailure(retryable=true)를 maxAttempts번 반복한다.
     * 마지막 호출에서 retryPolicy.shouldGiveUp이 true가 되어 markDeadLetter + dlq 진입.
     *
     * @param reason 매 실패마다 dlq에 누적될 사유 (마지막 호출이 dlq에 영속화됨)
     */
    private void runUntilDeadLetter(String reason) {
        int max = dispatchProps.getMaxAttempts();
        for (int i = 0; i < max; i++) {
            Notification n = notifRepo.findById(notifId).orElseThrow();
            n.claim("worker-test", Instant.now(clock));
            notifRepo.saveAndFlush(n);
            uow.finalizeFailure(notifId, reason, true);
        }
    }
}
