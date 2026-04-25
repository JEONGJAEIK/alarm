package com.example.alarm.channel;

import com.example.alarm.domain.Notification;
import com.example.alarm.domain.NotificationChannelType;
import com.example.alarm.template.TemplateRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 인앱 알림 발송을 {@code in_app_message} 테이블 INSERT로 구현한 채널.
 *
 * <p>{@link TemplateRenderer}로 렌더된 title/body를 inbox에 저장한다.
 * 템플릿이 등록 안 된 타입은 fallback이 사용된다.
 */
@Component
@RequiredArgsConstructor
public class InAppChannel implements NotificationChannel {

    private final InAppMessageRepository repo;
    private final TemplateRenderer renderer;
    private final Clock clock;

    @Override
    public NotificationChannelType type() {
        return NotificationChannelType.IN_APP;
    }

    @Override
    @Transactional
    public void deliver(Notification n) {
        var rendered = renderer.render(n.getType(), n.getChannel(), n.getReferenceData());
        repo.save(InAppMessage.of(n.getRecipientId(), n.getId(),
                rendered.title(), rendered.body(), Instant.now(clock)));
    }
}
