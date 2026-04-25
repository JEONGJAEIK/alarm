package com.example.alarm.channel;

import com.example.alarm.domain.Notification;
import com.example.alarm.domain.NotificationChannelType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 인앱 알림 발송을 {@code in_app_message} 테이블에 INSERT로 구현한 채널.
 *
 * <p>현재 단계에서는 알림 타입을 그대로 title로, referenceData 문자열을 body로 사용한다.
 * 템플릿 도입 시 렌더링된 결과로 교체 예정.
 */
@RequiredArgsConstructor
@Component
public class InAppChannel implements NotificationChannel {

    private final InAppMessageRepository repo;
    private final Clock clock;

    @Override
    public NotificationChannelType type() {
        return NotificationChannelType.IN_APP;
    }

    @Override
    @Transactional
    public void deliver(Notification n) {
        String title = n.getType().name();
        String body = n.getReferenceData() == null ? "" : n.getReferenceData().toString();
        repo.save(InAppMessage.of(n.getRecipientId(), n.getId(), title, body, Instant.now(clock)));
    }
}
