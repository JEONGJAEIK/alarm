package com.example.alarm;

import com.example.alarm.config.DispatchProperties;
import com.example.alarm.dispatch.ExponentialBackoffRetryPolicy;
import com.example.alarm.dispatch.RetryPolicy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * 알람 서비스 애플리케이션 진입점.
 *
 * <p>Spring Boot 자동 구성을 활성화하고, 비동기 처리({@code @Async}),
 * 스케줄링({@code @Scheduled}), 발송 설정 프로퍼티를 초기화한다.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(DispatchProperties.class)
@EnableJpaAuditing(dateTimeProviderRef = "auditDateTimeProvider")
public class AlarmApplication {

    /**
     * 애플리케이션 메인 메서드.
     *
     * @param args 커맨드라인 인수
     */
    public static void main(String[] args) {
        SpringApplication.run(AlarmApplication.class, args);
    }

    /**
     * UTC 기준 시스템 클록 빈을 등록한다.
     *
     * @return {@code Clock.systemUTC()}
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * 발송 재시도 정책 빈을 등록한다.
     *
     * <p>설정 프로퍼티({@link DispatchProperties})로부터 지수 백오프 파라미터를 읽어
     * {@link ExponentialBackoffRetryPolicy}를 생성한다.
     *
     * @param props 발송 설정 프로퍼티
     * @return 재시도 정책 인스턴스
     */
    @Bean
    public RetryPolicy retryPolicy(DispatchProperties props) {
        return new ExponentialBackoffRetryPolicy(
                props.backoffBase(), props.backoffMax(),
                props.getBackoffJitterRatio(), props.getMaxAttempts());
    }

    /**
     * JPA Auditing의 시간 소스를 {@link Clock}에 위임하는 {@link DateTimeProvider}.
     *
     * <p>{@code @CreatedDate}, {@code @LastModifiedDate}가 이 빈을 통해 시간을 가져오므로
     * 테스트에서 {@code Clock.fixed(...)}로 시간을 고정하면 감사 시간도 결정론적이다.
     */
    @Bean
    public DateTimeProvider auditDateTimeProvider(Clock clock) {
        return () -> Optional.of(Instant.now(clock));
    }
}
