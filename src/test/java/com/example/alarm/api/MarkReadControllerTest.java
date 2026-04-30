package com.example.alarm.api;

import com.example.alarm.domain.DedupKeys;
import com.example.alarm.domain.NotificationChannelType;
import com.example.alarm.domain.NotificationRepository;
import com.example.alarm.support.AbstractMysqlIntegrationTest;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 알림 읽음 처리 PATCH 엔드포인트 통합 테스트.
 */
@AutoConfigureMockMvc
class MarkReadControllerTest extends AbstractMysqlIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired NotificationRepository repo;

    @Test
    void PATCH_read는_읽음_상태로_전이시키고_멱등하다() throws Exception {
        var body = json.writeValueAsString(Map.of(
                "recipientId", "u-mr",
                "type", "ENROLLMENT_COMPLETED",
                "channel", "IN_APP",
                "eventId", "evt-mr-1",
                "referenceData", Map.of()));
        mvc.perform(post("/api/notifications")
                        .header("X-User-Id", "system")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        String id = repo.findByDedupKey(DedupKeys.derive("evt-mr-1", NotificationChannelType.IN_APP))
                .orElseThrow().getExternalId();

        mvc.perform(patch("/api/notifications/" + id + "/read")
                        .header("X-User-Id", "u-mr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));

        mvc.perform(patch("/api/notifications/" + id + "/read")
                        .header("X-User-Id", "u-mr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));
    }

    @Test
    void PATCH_read를_본인이_아닌_사용자가_호출하면_403이다() throws Exception {
        var body = json.writeValueAsString(Map.of(
                "recipientId", "u-mr",
                "type", "ENROLLMENT_COMPLETED",
                "channel", "IN_APP",
                "eventId", "evt-mr-2",
                "referenceData", Map.of()));
        mvc.perform(post("/api/notifications")
                        .header("X-User-Id", "system")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        String id = repo.findByDedupKey(DedupKeys.derive("evt-mr-2", NotificationChannelType.IN_APP))
                .orElseThrow().getExternalId();

        mvc.perform(patch("/api/notifications/" + id + "/read")
                        .header("X-User-Id", "u-other"))
                .andExpect(status().isForbidden());
    }
}
