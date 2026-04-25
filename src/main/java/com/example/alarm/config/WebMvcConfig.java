package com.example.alarm.config;

import com.example.alarm.api.auth.AdminHeaderInterceptor;
import com.example.alarm.api.auth.XUserIdArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 인증 인프라(헤더 기반 사용자 ID 주입 + 관리자 헤더 검증)를 Spring MVC에 등록.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final XUserIdArgumentResolver userIdResolver;
    private final AdminHeaderInterceptor adminInterceptor;

    public WebMvcConfig(XUserIdArgumentResolver userIdResolver,
                        AdminHeaderInterceptor adminInterceptor) {
        this.userIdResolver = userIdResolver;
        this.adminInterceptor = adminInterceptor;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(userIdResolver);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/api/admin/**");
    }
}
