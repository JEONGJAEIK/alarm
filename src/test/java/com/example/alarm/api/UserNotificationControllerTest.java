package com.example.alarm.api;

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
 * {@link UserNotificationController}의 사용자 알림 목록 엔드포인트 통합 테스트.
 */
@AutoConfigureMockMvc
class UserNotificationControllerTest extends AbstractMysqlIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void 사용자_알림_목록은_본인의_알림만_반환한다() throws Exception {
        postNotification("u-7", "evt-A", "EMAIL");
        postNotification("u-7", "evt-B", "IN_APP");
        postNotification("u-other", "evt-C", "EMAIL");

        mvc.perform(get("/api/users/u-7/notifications").header("X-User-Id", "u-7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void 다른_사용자의_알림_목록을_조회하면_403이다() throws Exception {
        mvc.perform(get("/api/users/u-7/notifications").header("X-User-Id", "u-other"))
                .andExpect(status().isForbidden());
    }

    @Test
    void read_파라미터로_읽음_여부_필터가_적용된다() throws Exception {
        postNotification("u-8", "evt-D", "IN_APP");

        mvc.perform(get("/api/users/u-8/notifications")
                        .header("X-User-Id", "u-8")
                        .param("read", "false"))
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(get("/api/users/u-8/notifications")
                        .header("X-User-Id", "u-8")
                        .param("read", "true"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    private void postNotification(String userId, String eventId, String channel) throws Exception {
        var body = json.writeValueAsString(Map.of(
                "recipientId", userId,
                "type", "PAYMENT_CONFIRMED",
                "channel", channel,
                "eventId", eventId,
                "referenceData", Map.of()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/notifications")
                .header("X-User-Id", "system")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
