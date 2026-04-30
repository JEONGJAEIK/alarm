package com.example.alarm;

import com.example.alarm.channel.ChannelDeliveryException;
import com.example.alarm.channel.NotificationChannel;
import com.example.alarm.domain.*;
import com.example.alarm.support.AbstractMysqlIntegrationTest;
import tools.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 알림 시스템 전체 흐름 (등록 → 워커 폴링 → 채널 호출 → 상태 전이) E2E 검증.
 *
 * <p>5가지 핵심 시나리오: 해피 패스, 재시도 후 성공, 재시도 소진 후 데드레터,
 * stuck-claim 복구, 동시 중복 등록 단일 행 수렴.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "alarm.dispatch.poll-interval-ms=50",
        "alarm.dispatch.sweep-interval-ms=200",
        "alarm.dispatch.executor-await-seconds=2",
        "spring.task.scheduling.shutdown.await-termination=true",
        "spring.task.scheduling.shutdown.await-termination-period=2s"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EndToEndOperationsTest extends AbstractMysqlIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired NotificationRepository repo;
    @MockitoSpyBean(name = "emailChannel") NotificationChannel emailChannel;

    @BeforeEach
    void clean() {
        repo.deleteAll();
        reset(emailChannel);
        when(emailChannel.type()).thenReturn(NotificationChannelType.EMAIL);
    }

    @Test
    void 해피_패스_POST_202_반환_후_워커가_SUCCEEDED로_전이시킨다() throws Exception {
        doNothing().when(emailChannel).deliver(any());
        var body = json.writeValueAsString(Map.of(
                "recipientId", "u-e2e",
                "type", "PAYMENT_CONFIRMED",
                "channel", "EMAIL",
                "eventId", "evt-e2e-1",
                "referenceData", Map.of()));

        mvc.perform(post("/api/notifications")
                        .header("X-User-Id", "system")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        String dedupKey = DedupKeys.derive("evt-e2e-1", NotificationChannelType.EMAIL);
        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertEquals(NotificationStatus.SUCCEEDED,
                        repo.findByDedupKey(dedupKey).orElseThrow().getStatus()));
    }

    @Test
    void 재시도_후_성공한다() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            if (calls.incrementAndGet() < 2)
                throw new ChannelDeliveryException("transient", true);
            return null;
        }).when(emailChannel).deliver(any());

        var body = json.writeValueAsString(Map.of(
                "recipientId", "u-e2e",
                "type", "PAYMENT_CONFIRMED",
                "channel", "EMAIL",
                "eventId", "evt-e2e-2",
                "referenceData", Map.of()));
        mvc.perform(post("/api/notifications")
                        .header("X-User-Id", "system")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        String dedupKey = DedupKeys.derive("evt-e2e-2", NotificationChannelType.EMAIL);
        Awaitility.await().atMost(Duration.ofSeconds(8))
                .untilAsserted(() -> assertEquals(NotificationStatus.SUCCEEDED,
                        repo.findByDedupKey(dedupKey).orElseThrow().getStatus()));
        assertTrue(repo.findByDedupKey(dedupKey).orElseThrow().getAttempts() >= 1);
    }

    @Test
    void 재시도_소진_후_DEAD_LETTER로_전이된다() throws Exception {
        doThrow(new ChannelDeliveryException("always fail", true))
                .when(emailChannel).deliver(any());

        var body = json.writeValueAsString(Map.of(
                "recipientId", "u-e2e",
                "type", "PAYMENT_CONFIRMED",
                "channel", "EMAIL",
                "eventId", "evt-e2e-3",
                "referenceData", Map.of()));
        mvc.perform(post("/api/notifications")
                        .header("X-User-Id", "system")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        String dedupKey = DedupKeys.derive("evt-e2e-3", NotificationChannelType.EMAIL);
        Awaitility.await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertEquals(NotificationStatus.DEAD_LETTER,
                        repo.findByDedupKey(dedupKey).orElseThrow().getStatus()));
    }

    @Test
    void stuck_claim은_sweeper가_복구하고_재처리된다() {
        Instant longAgo = Instant.now().minusSeconds(120);
        Notification stuck = Notification.createImmediate(
                "u-e2e", NotificationType.COURSE_START_D1, NotificationChannelType.EMAIL,
                "evt-e2e-4", Map.of(), longAgo);
        stuck.claim("dead-pid", longAgo);
        repo.save(stuck);

        doNothing().when(emailChannel).deliver(any());

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertEquals(NotificationStatus.SUCCEEDED,
                        repo.findById(stuck.getId()).orElseThrow().getStatus()));
    }

    @Test
    void 동시_중복_POST는_단일_행만_생성하고_나머지는_409를_반환한다() throws Exception {
        doNothing().when(emailChannel).deliver(any());

        var body = json.writeValueAsString(Map.of(
                "recipientId", "u-e2e",
                "type", "PAYMENT_CONFIRMED",
                "channel", "EMAIL",
                "eventId", "evt-e2e-5",
                "referenceData", Map.of()));

        int threadCount = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        try {
            var futures = java.util.stream.IntStream.range(0, threadCount)
                    .mapToObj(i -> pool.submit(() -> mvc.perform(post("/api/notifications")
                            .header("X-User-Id", "system")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                            .andReturn().getResponse().getStatus()))
                    .toList();
            for (var f : futures) {
                int status = f.get();
                if (status == 202) accepted.incrementAndGet();
                else if (status == 409) conflict.incrementAndGet();
            }
            assertEquals(1, accepted.get(), "단일 요청만 202 Accepted여야 한다");
            assertEquals(threadCount - 1, conflict.get(), "나머지는 409 Conflict여야 한다");
            String dedupKey = DedupKeys.derive("evt-e2e-5", NotificationChannelType.EMAIL);
            assertNotNull(repo.findByDedupKey(dedupKey).orElse(null));
            assertEquals(1, repo.count(), "DB에 단 1개 행만 존재해야 한다");
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
