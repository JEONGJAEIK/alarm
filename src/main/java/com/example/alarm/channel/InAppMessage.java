package com.example.alarm.channel;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 인앱 알림 inbox의 단일 메시지 행. {@link InAppChannel#deliver}가 INSERT한다.
 */
@Entity
@Table(name = "in_app_message")
public class InAppMessage {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "recipient_id", nullable = false, length = 100)
    private String recipientId;

    @Column(name = "notification_id", nullable = false, length = 36)
    private String notificationId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected InAppMessage() {}

    /**
     * 신규 인앱 메시지 생성. UUID PK + 현재 시각이 부여된다.
     */
    public static InAppMessage of(String recipientId, String notificationId,
                                  String title, String body, Instant now) {
        InAppMessage m = new InAppMessage();
        m.id = UUID.randomUUID().toString();
        m.recipientId = recipientId;
        m.notificationId = notificationId;
        m.title = title;
        m.body = body;
        m.createdAt = now;
        return m;
    }

    public String getId() { return id; }
    public String getRecipientId() { return recipientId; }
    public String getNotificationId() { return notificationId; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
}
