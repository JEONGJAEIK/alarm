package com.example.alarm.service;

/**
 * 본인이 아닌 사용자가 다른 사용자의 알림에 접근하거나, 관리자 헤더 없이 관리자 API를 호출했을 때
 * 던지는 예외.
 *
 * <p>{@code ApiExceptionHandler}가 403 Forbidden으로 변환한다.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) { super(message); }
}
