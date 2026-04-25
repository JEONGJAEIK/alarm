package com.example.alarm.channel;

import com.example.alarm.domain.NotificationChannelType;
import com.example.alarm.support.AbstractMysqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ChannelRegistry}의 채널 해결 동작 통합 테스트.
 */
class ChannelRegistryTest extends AbstractMysqlIntegrationTest {

    @Autowired ChannelRegistry registry;

    @Test
    void 등록된_채널_타입을_resolve로_가져올_수_있다() {
        assertNotNull(registry.resolve(NotificationChannelType.EMAIL));
        assertNotNull(registry.resolve(NotificationChannelType.IN_APP));
    }
}
