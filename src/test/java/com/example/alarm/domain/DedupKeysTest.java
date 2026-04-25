package com.example.alarm.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DedupKeys} 단위 테스트.
 *
 * <p>dedup 키 파생 로직과 입력 검증이 올바르게 동작하는지 검증한다.
 */
class DedupKeysTest {

    @Test
    void derive는_eventId와_channel을_조합한다() {
        assertEquals("evt-1::EMAIL",
                DedupKeys.derive("evt-1", NotificationChannelType.EMAIL));
    }

    @Test
    void derive는_빈_eventId를_거부한다() {
        assertThrows(IllegalArgumentException.class,
                () -> DedupKeys.derive("  ", NotificationChannelType.EMAIL));
    }

    @Test
    void derive는_null_channel을_거부한다() {
        assertThrows(IllegalArgumentException.class,
                () -> DedupKeys.derive("evt-1", null));
    }

    @Test
    void 서로_다른_channel은_서로_다른_dedup_key를_만든다() {
        assertNotEquals(
                DedupKeys.derive("evt-1", NotificationChannelType.EMAIL),
                DedupKeys.derive("evt-1", NotificationChannelType.IN_APP));
    }
}
