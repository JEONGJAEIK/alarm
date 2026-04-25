package com.example.alarm.channel;

import com.example.alarm.domain.Notification;
import com.example.alarm.domain.NotificationChannelType;
import com.example.alarm.template.TemplateRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 이메일 발송을 모킹한 채널. 실제 SMTP 호출 없이 INFO 로그로 렌더된 title/body를 출력한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailChannel implements NotificationChannel {

    private final TemplateRenderer renderer;

    @Override
    public NotificationChannelType type() {
        return NotificationChannelType.EMAIL;
    }

    @Override
    public void deliver(Notification n) {
        var rendered = renderer.render(n.getType(), n.getChannel(), n.getReferenceData());
        log.info("[email-mock] to={} title=\"{}\" body=\"{}\"",
                n.getRecipientId(), rendered.title(), rendered.body());
    }
}
