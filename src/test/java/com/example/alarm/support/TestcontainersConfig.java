package com.example.alarm.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;

/**
 * 통합 테스트용 MySQL Testcontainer 설정.
 *
 * <p>{@code @ServiceConnection}으로 Spring Boot의 데이터소스 자동 구성에 컨테이너 정보를
 * 주입하므로 별도의 {@code DataSource} 빈 선언이 필요 없다. {@code withReuse(true)}로
 * 동일 JVM 내 여러 테스트 클래스가 컨테이너를 공유하여 기동 비용을 절감한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    /**
     * 통합 테스트용 MySQL 컨테이너 빈을 등록한다.
     *
     * @return 재사용 가능한 MySQL 8.0.36 컨테이너
     */
    @Bean
    @ServiceConnection
    public MySQLContainer<?> mysqlContainer() {
        return new MySQLContainer<>("mysql:8.0.36")
                .withDatabaseName("alarm_test")
                .withUsername("test")
                .withPassword("test")
                .withReuse(true);
    }
}
