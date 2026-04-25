package com.example.alarm.domain;

/**
 * 비즈니스 이벤트별 알림 유형.
 *
 * <p>각 값은 알림 템플릿 및 발송 채널 선택 전략과 연결된다.
 */
public enum NotificationType {
    ENROLLMENT_COMPLETED,
    PAYMENT_CONFIRMED,
    COURSE_START_D1,
    CANCELLATION
}
