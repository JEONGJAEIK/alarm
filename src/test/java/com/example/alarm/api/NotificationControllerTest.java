package com.example.alarm.api;

import com.example.alarm.domain.DedupKeys;
import com.example.alarm.domain.Notification;
import com.example.alarm.domain.NotificationChannelType;
import com.example.alarm.domain.NotificationRepository;
import com.example.alarm.domain.NotificationStatus;
import com.example.alarm.support.AbstractMysqlIntegrationTest;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link NotificationController}의 POST/GET 엔드포인트 통합 테스트.
 */
@AutoConfigureMockMvc
class NotificationControllerTest extends AbstractMysqlIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired NotificationRepository repo;

    @Test
    void POST는_202와_빈_본문으로_응답하고_PENDING_행을_저장한다() throws Exception {
        var body = json.writeValueAsString(Map.of(
                "recipientId", "u-42",
                "type", "PAYMENT_CONFIRMED",
                "channel", "EMAIL",
                "eventId", "evt-200",
                "referenceData", Map.of("courseId", "c-1")));

        mvc.perform(post("/api/notifications")
                        .header("X-User-Id", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        String dedupKey = DedupKeys.derive("evt-200", NotificationChannelType.EMAIL);
        Notification n = repo.findByDedupKey(dedupKey).orElseThrow();
        assertEquals(NotificationStatus.PENDING, n.getStatus());
        assertEquals(0, n.getAttempts());
    }

    @Test
    void POST에_X_User_Id_헤더가_없으면_401이다() throws Exception {
        var body = json.writeValueAsString(Map.of(
                "recipientId", "u-42",
                "type", "PAYMENT_CONFIRMED",
                "channel", "EMAIL",
                "eventId", "evt-201",
                "referenceData", Map.of()));

        mvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void POST에_eventId가_없으면_400이다() throws Exception {
        var body = json.writeValueAsString(Map.of(
                "recipientId", "u-42",
                "type", "PAYMENT_CONFIRMED",
                "channel", "EMAIL",
                "referenceData", Map.of()));

        mvc.perform(post("/api/notifications")
                        .header("X-User-Id", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_중복_요청은_409를_반환한다() throws Exception {
        var body = json.writeValueAsString(Map.of(
                "recipientId", "u-42",
                "type", "PAYMENT_CONFIRMED",
                "channel", "EMAIL",
                "eventId", "evt-202",
                "referenceData", Map.of()));

        mvc.perform(post("/api/notifications")
                        .header("X-User-Id", "system")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        mvc.perform(post("/api/notifications")
                        .header("X-User-Id", "system")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("duplicate"));

        assertNotNull(repo.findByDedupKey(
                DedupKeys.derive("evt-202", NotificationChannelType.EMAIL))
                .orElse(null), "원본 행은 존재해야 한다");
    }

    @Test
    void GET은_현재_상태를_반환한다() throws Exception {
        var body = json.writeValueAsString(Map.of(
                "recipientId", "u-42",
                "type", "PAYMENT_CONFIRMED",
                "channel", "EMAIL",
                "eventId", "evt-203",
                "referenceData", Map.of()));

        mvc.perform(post("/api/notifications")
                        .header("X-User-Id", "system")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        String externalId = repo.findByDedupKey(
                DedupKeys.derive("evt-203", NotificationChannelType.EMAIL))
                .orElseThrow().getExternalId();
        assertNotNull(externalId);

        mvc.perform(get("/api/notifications/" + externalId)
                        .header("X-User-Id", "u-42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(externalId));
    }

    @Test
    void GET을_본인이_아닌_사용자가_호출하면_403이다() throws Exception {
        var body = json.writeValueAsString(Map.of(
                "recipientId", "u-42",
                "type", "PAYMENT_CONFIRMED",
                "channel", "EMAIL",
                "eventId", "evt-204",
                "referenceData", Map.of()));

        mvc.perform(post("/api/notifications")
                        .header("X-User-Id", "system")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        String externalId = repo.findByDedupKey(
                DedupKeys.derive("evt-204", NotificationChannelType.EMAIL))
                .orElseThrow().getExternalId();

        mvc.perform(get("/api/notifications/" + externalId)
                        .header("X-User-Id", "u-other"))
                .andExpect(status().isForbidden());
    }
}
