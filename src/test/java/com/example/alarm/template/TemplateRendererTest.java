package com.example.alarm.template;

import com.example.alarm.domain.NotificationChannelType;
import com.example.alarm.domain.NotificationType;
import com.example.alarm.support.AbstractMysqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TemplateRenderer}의 placeholder 치환 동작 통합 테스트.
 */
class TemplateRendererTest extends AbstractMysqlIntegrationTest {

    @Autowired NotificationTemplateRepository repo;
    @Autowired TemplateRenderer renderer;

    @BeforeEach
    void clean() { repo.deleteAll(); }

    @Test
    void placeholder는_referenceData_값으로_치환된다() {
        repo.save(NotificationTemplate.of(
                NotificationType.PAYMENT_CONFIRMED, NotificationChannelType.EMAIL,
                "결제 완료", "{{courseName}} 강의 결제가 완료됐어요."));

        var rendered = renderer.render(
                NotificationType.PAYMENT_CONFIRMED, NotificationChannelType.EMAIL,
                Map.of("courseName", "JPA 마스터"));

        assertEquals("결제 완료", rendered.title());
        assertEquals("JPA 마스터 강의 결제가 완료됐어요.", rendered.body());
    }

    @Test
    void 템플릿이_없으면_타입_이름과_referenceData를_fallback으로_사용한다() {
        var rendered = renderer.render(
                NotificationType.CANCELLATION, NotificationChannelType.IN_APP, Map.of());

        assertEquals("CANCELLATION", rendered.title());
        assertNotNull(rendered.body());
    }
}
