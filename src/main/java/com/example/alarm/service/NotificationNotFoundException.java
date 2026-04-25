package com.example.alarm.service;

/**
 * 요청한 알림 ID가 DB에 존재하지 않을 때 던지는 예외.
 *
 * <p>API 계층에서 404 Not Found로 변환된다.
 */
public class NotificationNotFoundException extends RuntimeException {
    public NotificationNotFoundException(String id) {
        super("알림을 찾을 수 없습니다: " + id);
    }
}
