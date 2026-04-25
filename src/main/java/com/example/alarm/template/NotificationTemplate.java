package com.example.alarm.template;

import com.example.alarm.domain.NotificationChannelType;
import com.example.alarm.domain.NotificationType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 알림 타입과 채널 조합별 템플릿. {@code title_template}/{@code body_template}에
 * {@code {{key}}} 형식 placeholder를 작성하면 발송 시 referenceData 값으로 치환된다.
 */
@Entity
@Table(name = "notification_template")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationTemplate {

    @Id
    @Column(length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannelType channel;

    @Column(name = "title_template", length = 200)
    private String titleTemplate;

    @Column(name = "body_template", columnDefinition = "TEXT")
    private String bodyTemplate;

    /**
     * 새 템플릿을 생성. UUID PK가 부여된다.
     */
    public static NotificationTemplate of(NotificationType type, NotificationChannelType channel,
                                          String title, String body) {
        NotificationTemplate t = new NotificationTemplate();
        t.id = UUID.randomUUID().toString();
        t.type = type;
        t.channel = channel;
        t.titleTemplate = title;
        t.bodyTemplate = body;
        return t;
    }
}
