package com.example.alarm.api.auth;

import java.lang.annotation.*;

/**
 * 컨트롤러 메서드 파라미터에 붙여 {@code X-User-Id} 헤더 값을 자동 주입받는 어노테이션.
 *
 * <p>{@link XUserIdArgumentResolver}가 처리.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}
