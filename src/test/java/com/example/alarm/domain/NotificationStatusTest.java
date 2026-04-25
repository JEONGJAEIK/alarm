package com.example.alarm.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NotificationStatus} 단위 테스트.
 *
 * <p>terminal 상태 판별 로직이 올바르게 동작하는지 검증한다.
 */
class NotificationStatusTest {

    @Test
    void SUCCEEDED와_DEAD_LETTER만_terminal_상태이다() {
        assertTrue(NotificationStatus.SUCCEEDED.isTerminal());
        assertTrue(NotificationStatus.DEAD_LETTER.isTerminal());
        assertFalse(NotificationStatus.PENDING.isTerminal());
        assertFalse(NotificationStatus.IN_PROGRESS.isTerminal());
    }
}
