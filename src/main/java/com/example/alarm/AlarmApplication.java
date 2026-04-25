package com.example.alarm;

import com.example.alarm.config.DispatchProperties;
import com.example.alarm.dispatch.ExponentialBackoffRetryPolicy;
import com.example.alarm.dispatch.RetryPolicy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(DispatchProperties.class)
public class AlarmApplication {
    public static void main(String[] args) {
        SpringApplication.run(AlarmApplication.class, args);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RetryPolicy retryPolicy(DispatchProperties props) {
        return new ExponentialBackoffRetryPolicy(
                props.backoffBase(), props.backoffMax(),
                props.getBackoffJitterRatio(), props.getMaxAttempts());
    }
}
