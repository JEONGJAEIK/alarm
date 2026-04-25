package com.example.alarm.dispatch;

import com.example.alarm.domain.*;
import com.example.alarm.support.AbstractMysqlIntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 미래 {@code scheduledAt} 값으로 등록한 알림이 시각이 도래할 때까지
 * 발송되지 않는지 검증.
 */
@TestPropertySource(properties = "alarm.dispatch.poll-interval-ms=50")
class ScheduledDeliveryTest extends AbstractMysqlIntegrationTest {

    @Autowired NotificationRepository repo;

    @BeforeEach
    void clean() { repo.deleteAll(); }

    @Test
    void scheduledAt이_미래면_즉시_발송되지_않고_도래_후_처리된다() throws Exception {
        Instant now = Instant.now();
        Notification future = Notification.create(
                "u-sched", NotificationType.COURSE_START_D1, NotificationChannelType.EMAIL,
                "evt-sched-1", Map.of(), now, now.plusMillis(800));
        repo.save(future);

        // 도래 전: 워커가 깨어나도 처리하지 않아야 함
        Thread.sleep(300);
        assertEquals(NotificationStatus.PENDING,
                repo.findById(future.getId()).orElseThrow().getStatus());

        // 도래 후: 워커가 픽업해 SUCCEEDED로 전이
        Awaitility.await()
                .atMost(Duration.ofSeconds(4))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertEquals(NotificationStatus.SUCCEEDED,
                        repo.findById(future.getId()).orElseThrow().getStatus()));
    }
}
