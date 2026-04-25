package com.example.alarm.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NotificationStatusTest {

    @Test
    void terminalStatesAreSucceededAndDeadLetter() {
        assertTrue(NotificationStatus.SUCCEEDED.isTerminal());
        assertTrue(NotificationStatus.DEAD_LETTER.isTerminal());
        assertFalse(NotificationStatus.PENDING.isTerminal());
        assertFalse(NotificationStatus.IN_PROGRESS.isTerminal());
    }
}
