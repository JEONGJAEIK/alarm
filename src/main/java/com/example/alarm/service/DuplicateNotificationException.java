package com.example.alarm.service;

/**
 * 동일 {@code (eventId, channel)} 조합으로 알림이 이미 등록되어 있을 때 던지는 예외.
 *
 * <p>API 계층에서 409 Conflict로 변환된다. dedup_key UNIQUE 제약이 멱등성의 권위이며,
 * 호출자는 별도의 조회 API로 알림 상태를 확인할 수 있다.
 */
public class DuplicateNotificationException extends RuntimeException {
    public DuplicateNotificationException(String dedupKey) {
        super("이미 발송된 알림입니다: " + dedupKey);
    }
}
