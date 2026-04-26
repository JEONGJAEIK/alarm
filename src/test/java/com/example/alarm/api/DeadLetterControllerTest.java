package com.example.alarm.api;

import com.example.alarm.domain.*;
import com.example.alarm.support.AbstractMysqlIntegrationTest;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 관리자용 데드레터 목록 + 수동 재시도 엔드포인트 통합 테스트.
 */
@AutoConfigureMockMvc
class DeadLetterControllerTest extends AbstractMysqlIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired NotificationRepository repo;
    @Autowired ObjectMapper json;

    @BeforeEach
    void clean() { repo.deleteAll(); }

    @Test
    void GET은_X_User_Id_헤더가_없으면_401이다() throws Exception {
        mvc.perform(get("/api/admin/dead-letters")
                        .header("X-Admin", "true"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void GET은_X_Admin_헤더가_없으면_403이다() throws Exception {
        mvc.perform(get("/api/admin/dead-letters")
                        .header("X-User-Id", "admin-1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void GET은_DEAD_LETTER_상태_알림만_반환한다() throws Exception {
        Notification dead = Notification.createImmediate("u-dl", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-dl-1", Map.of(), Instant.now());
        // claim → IN_PROGRESS 후 markDeadLetter
        dead.claim("worker-x", Instant.now());
        dead.markDeadLetter("permanent", Instant.now());
        repo.save(dead);

        mvc.perform(get("/api/admin/dead-letters")
                        .header("X-User-Id", "admin-1")
                        .header("X-Admin", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("DEAD_LETTER"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void POST_retry는_DEAD_LETTER를_PENDING으로_되살린다() throws Exception {
        Notification dead = Notification.createImmediate("u-dl", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-dl-2", Map.of(), Instant.now());
        dead.claim("worker-x", Instant.now());
        dead.markDeadLetter("permanent", Instant.now());
        repo.save(dead);

        mvc.perform(post("/api/admin/dead-letters/" + dead.getExternalId() + "/retry")
                        .header("X-User-Id", "admin-1")
                        .header("X-Admin", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0));

        Notification after = repo.findById(dead.getId()).orElseThrow();
        assertEquals(NotificationStatus.PENDING, after.getStatus());
        assertEquals(0, after.getAttempts());
    }
}
