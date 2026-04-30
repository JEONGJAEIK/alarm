package com.example.alarm.channel;

import com.example.alarm.domain.*;
import com.example.alarm.support.AbstractMysqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InAppChannel}이 인앱 inbox 행을 영속화하는지 검증.
 */
class InAppChannelTest extends AbstractMysqlIntegrationTest {

    @Autowired InAppChannel channel;
    @Autowired InAppMessageRepository inboxRepo;
    @Autowired NotificationRepository notificationRepo;

    @BeforeEach
    void clean() {
        inboxRepo.deleteAll();
        notificationRepo.deleteAll();
    }

    @Test
    void deliver는_인앱_inbox에_행을_저장한다() {
        Notification n = Notification.createImmediate("u-99", NotificationType.ENROLLMENT_COMPLETED,
                NotificationChannelType.IN_APP, "evt-i1", Map.of("courseId", "c-9"),
                Instant.parse("2026-04-25T00:00:00Z"));
        notificationRepo.saveAndFlush(n);

        channel.deliver(n);

        var rows = inboxRepo.findByRecipientIdOrderByCreatedAtDesc("u-99");
        assertEquals(1, rows.size());
        assertEquals(n.getId(), rows.get(0).getNotificationId());
    }
}
