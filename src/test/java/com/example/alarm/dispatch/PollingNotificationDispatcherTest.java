package com.example.alarm.dispatch;

import com.example.alarm.channel.ChannelDeliveryException;
import com.example.alarm.channel.NotificationChannel;
import com.example.alarm.config.DispatchProperties;
import com.example.alarm.domain.*;
import com.example.alarm.support.AbstractMysqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link PollingNotificationDispatcher}의 클레임/발송/finalize 흐름 통합 테스트.
 */
class PollingNotificationDispatcherTest extends AbstractMysqlIntegrationTest {

    @Autowired NotificationRepository repo;
    @Autowired PollingNotificationDispatcher dispatcher;
    @Autowired DispatchProperties props;
    @MockitoSpyBean(name = "emailChannel") NotificationChannel emailChannel;

    @BeforeEach
    void clean() {
        repo.deleteAll();
        reset(emailChannel);
        when(emailChannel.type()).thenReturn(NotificationChannelType.EMAIL);
    }

    @Test
    void 정상_발송은_SUCCEEDED로_전이된다() {
        Notification n = repo.save(Notification.createImmediate("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-d1", Map.of(),
                Instant.now().minusSeconds(1)));
        doNothing().when(emailChannel).deliver(any());

        int processed = dispatcher.runOnce("worker-test");

        assertEquals(1, processed);
        assertEquals(NotificationStatus.SUCCEEDED,
                repo.findById(n.getId()).orElseThrow().getStatus());
    }

    @Test
    void retryable_실패는_PENDING_유지하고_attempts를_증가시킨다() {
        Notification n = repo.save(Notification.createImmediate("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-d2", Map.of(),
                Instant.now().minusSeconds(1)));
        doThrow(new ChannelDeliveryException("smtp 503", true)).when(emailChannel).deliver(any());

        dispatcher.runOnce("worker-test");

        Notification after = repo.findById(n.getId()).orElseThrow();
        assertEquals(NotificationStatus.PENDING, after.getStatus());
        assertEquals(1, after.getAttempts());
        assertTrue(after.getLastFailureReason().contains("smtp 503"));
    }

    @Test
    void nonRetryable_실패는_즉시_DEAD_LETTER로_전이된다() {
        Notification n = repo.save(Notification.createImmediate("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-d3", Map.of(),
                Instant.now().minusSeconds(1)));
        doThrow(new ChannelDeliveryException("invalid address", false)).when(emailChannel).deliver(any());

        dispatcher.runOnce("worker-test");

        assertEquals(NotificationStatus.DEAD_LETTER,
                repo.findById(n.getId()).orElseThrow().getStatus());
    }

    @Test
    void 예상하지_못한_예외는_retryable로_처리된다() {
        Notification n = repo.save(Notification.createImmediate("u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-d4", Map.of(),
                Instant.now().minusSeconds(1)));
        doThrow(new RuntimeException("kaboom")).when(emailChannel).deliver(any());

        dispatcher.runOnce("worker-test");

        Notification after = repo.findById(n.getId()).orElseThrow();
        assertEquals(NotificationStatus.PENDING, after.getStatus());
        assertEquals(1, after.getAttempts());
    }
}
