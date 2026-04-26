package com.example.alarm.api;

import com.example.alarm.domain.Notification;
import com.example.alarm.domain.NotificationChannelType;
import com.example.alarm.domain.NotificationRepository;
import com.example.alarm.domain.NotificationType;
import com.example.alarm.support.AbstractMysqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사용자 응답 DTO에 운영자 전용 실패 상세 키가 노출되지 않음을 검증하는 BOPLA 방어 테스트.
 *
 * <p>{@code failureReason}, {@code failureHistory}, {@code lastFailureReason}는 관리자 전용
 * DTO({@code AdminDeadLetterResponse} 등)에서만 노출되며, 사용자 응답에서는 절대 등장해서는 안 된다.
 */
@AutoConfigureMockMvc
class UserNotificationResponseSchemaTest extends AbstractMysqlIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired NotificationRepository notifRepo;
    @Autowired Clock clock;

    private String externalId;

    @BeforeEach
    void setUp() {
        notifRepo.deleteAllInBatch();
        Notification n = Notification.createImmediate(
                "user-1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL,
                "evt-" + System.nanoTime(), null, Instant.now(clock));
        externalId = notifRepo.saveAndFlush(n).getExternalId();
    }

    @Test
    void 사용자_응답_JSON에는_failureReason_failureHistory_lastFailureReason_키가_없다() throws Exception {
        mvc.perform(get("/api/notifications/{id}", externalId)
                        .header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureReason").doesNotExist())
                .andExpect(jsonPath("$.failureHistory").doesNotExist())
                .andExpect(jsonPath("$.lastFailureReason").doesNotExist());
    }
}
