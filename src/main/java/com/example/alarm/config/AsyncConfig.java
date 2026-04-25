package com.example.alarm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 알림 디스패치 워커가 사용할 전용 스레드 풀을 제공.
 *
 * <p>HTTP 요청 처리 스레드와 분리되어, 채널 호출이 느려져도 톰캣 스레드를 점유하지 않는다.
 * graceful shutdown 시 30초 대기로 진행 중인 작업 완료를 보장.
 */
@Configuration
public class AsyncConfig {

    /**
     * 디스패처 전용 {@link ThreadPoolTaskExecutor}. 코어 4 / 최대 8 / 큐 64,
     * 종료 시 30초 동안 진행 중 작업을 기다린다.
     */
    @Bean(name = "dispatcherExecutor")
    public Executor dispatcherExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(8);
        exec.setQueueCapacity(64);
        exec.setThreadNamePrefix("alarm-dispatch-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        return exec;
    }

}
