package com.example.alarm.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 모든 {@code /api/**} 요청에서 {@code X-User-Id} 헤더 존재 여부를 검증.
 *
 * <p>헤더가 누락되거나 비어있으면 401 Unauthorized로 응답한다. 값 자체의 활용은
 * 컨트롤러의 {@code @RequestHeader("X-User-Id")} 파라미터에 위임.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-User-Id";

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        String userId = request.getHeader(HEADER);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-User-Id 헤더가 필요합니다");
        }
        return true;
    }
}
