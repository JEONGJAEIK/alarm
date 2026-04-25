package com.example.alarm.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@code /api/admin/**} 경로에 대해 {@code X-Admin: true} 헤더를 검증하는 인터셉터.
 *
 * <p>헤더가 없거나 {@code true}가 아니면 403 Forbidden으로 응답.
 */
@Component
public class AdminHeaderInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Admin";

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        String value = request.getHeader(HEADER);
        if (!"true".equalsIgnoreCase(value)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 헤더가 필요합니다");
        }
        return true;
    }
}
