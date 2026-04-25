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
 * {@link NotificationController}의 POST/GET 엔드포인트 통합 테스트.
 */
@AutoConfigureMockMvc
class NotificationControllerTest extends AbstractMysqlIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void POST는_202와_PENDING으로_응답한다() throws Exception {
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
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0));
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
    void POST_중복_요청은_같은_id를_반환한다() throws Exception {
        var body = json.writeValueAsString(Map.of(
                "recipientId", "u-42",
                "type", "PAYMENT_CONFIRMED",
                "channel", "EMAIL",
                "eventId", "evt-202",
                "referenceData", Map.of()));

        var first = mvc.perform(post("/api/notifications")
                        .header("X-User-Id", "system")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        var second = mvc.perform(post("/api/notifications")
                        .header("X-User-Id", "system")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertEquals(
                json.readTree(first).get("id").asText(),
                json.readTree(second).get("id").asText());
    }

    @Test
    void GET은_현재_상태를_반환한다() throws Exception {
        var body = json.writeValueAsString(Map.of(
                "recipientId", "u-42",
                "type", "PAYMENT_CONFIRMED",
                "channel", "EMAIL",
                "eventId", "evt-203",
                "referenceData", Map.of()));

        var posted = mvc.perform(post("/api/notifications")
                        .header("X-User-Id", "system")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(posted).get("id").asText();

        mvc.perform(get("/api/notifications/" + id)
                        .header("X-User-Id", "u-42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void GET을_본인이_아닌_사용자가_호출하면_403이다() throws Exception {
        var body = json.writeValueAsString(Map.of(
                "recipientId", "u-42",
                "type", "PAYMENT_CONFIRMED",
                "channel", "EMAIL",
                "eventId", "evt-204",
                "referenceData", Map.of()));

        var posted = mvc.perform(post("/api/notifications")
                        .header("X-User-Id", "system")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(posted).get("id").asText();

        mvc.perform(get("/api/notifications/" + id)
                        .header("X-User-Id", "u-other"))
                .andExpect(status().isForbidden());
    }
}
