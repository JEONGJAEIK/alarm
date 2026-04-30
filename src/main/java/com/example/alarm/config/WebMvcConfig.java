package com.example.alarm.config;

import com.example.alarm.api.auth.AdminHeaderInterceptor;
import com.example.alarm.api.auth.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 인증·권한 인터셉터를 등록.
 *
 * <p>{@link AuthInterceptor}는 모든 {@code /api/**}에서 X-User-Id 검증, 그 뒤로
 * {@link AdminHeaderInterceptor}가 {@code /api/admin/**}에서 X-Admin 검증을 추가로 수행.
 */
@RequiredArgsConstructor
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AdminHeaderInterceptor adminInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(adminInterceptor).addPathPatterns("/api/admin/**");
    }
}
