package com.example.alarm.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * MySQL Testcontainer 기반 통합 테스트 기반 클래스.
 *
 * <p>모든 MySQL 연동 통합 테스트가 이 클래스를 상속함으로써 컨테이너 기동,
 * Spring Boot 컨텍스트 로드, {@code test} 프로파일 활성화가 공통 적용된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
public abstract class AbstractMysqlIntegrationTest {
}
