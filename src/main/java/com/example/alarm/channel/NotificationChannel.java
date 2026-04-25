package com.example.alarm.channel;

import com.example.alarm.domain.Notification;
import com.example.alarm.domain.NotificationChannelType;

/**
 * 단일 알림 채널의 발송 책임을 정의하는 SPI.
 *
 * <p>구현체는 {@link #type()}으로 자신이 다루는 채널 타입을 식별하며,
 * {@link #deliver(Notification)}에서 발송 동작을 수행한다.
 *
 * <p>일시 장애로 재시도가 가능한 실패는 {@link ChannelDeliveryException}을 던지며
 * {@code retryable=true}로 표시. 영구 실패는 {@code retryable=false}.
 */
public interface NotificationChannel {
    NotificationChannelType type();
    void deliver(Notification notification) throws ChannelDeliveryException;
}
