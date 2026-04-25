package com.example.alarm.channel;

import com.example.alarm.domain.Notification;
import com.example.alarm.domain.NotificationChannelType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 이메일 발송을 모킹한 채널. 실제 SMTP 호출 없이 INFO 로그만 출력한다.
 */
@Slf4j
@Component
public class EmailChannel implements NotificationChannel {

    @Override
    public NotificationChannelType type() {
        return NotificationChannelType.EMAIL;
    }

    @Override
    public void deliver(Notification n) {
        log.info("[email-mock] to={} type={} dedupKey={} ref={}",
                n.getRecipientId(), n.getType(), n.getDedupKey(), n.getReferenceData());
    }
}
