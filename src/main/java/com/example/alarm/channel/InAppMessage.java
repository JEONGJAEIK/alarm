package com.example.alarm.channel;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 인앱 알림 inbox의 단일 메시지 행. {@link InAppChannel#deliver}가 INSERT한다.
 *
 * <p>외부 노출이 없는 inbox 데이터이므로 PK는 {@code BIGINT AUTO_INCREMENT}.
 * sequential append로 InnoDB 클러스터 인덱스의 page split을 회피해 INSERT 효율이 높다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "in_app_message")
public class InAppMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_id", nullable = false, length = 100)
    private String recipientId;

    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 신규 인앱 메시지 생성. PK는 {@code repo.save} 시 DB의 IDENTITY가 부여한다.
     */
    public static InAppMessage of(String recipientId, Long notificationId,
                                  String title, String body, Instant now) {
        InAppMessage m = new InAppMessage();
        m.recipientId = recipientId;
        m.notificationId = notificationId;
        m.title = title;
        m.body = body;
        m.createdAt = now;
        return m;
    }

}
