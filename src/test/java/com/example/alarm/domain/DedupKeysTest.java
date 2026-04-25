package com.example.alarm.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DedupKeysTest {

    @Test
    void deriveCombinesEventAndChannel() {
        assertEquals("evt-1::EMAIL",
                DedupKeys.derive("evt-1", NotificationChannelType.EMAIL));
    }

    @Test
    void deriveRejectsBlankEventId() {
        assertThrows(IllegalArgumentException.class,
                () -> DedupKeys.derive("  ", NotificationChannelType.EMAIL));
    }

    @Test
    void deriveRejectsNullChannel() {
        assertThrows(IllegalArgumentException.class,
                () -> DedupKeys.derive("evt-1", null));
    }

    @Test
    void differentChannelsProduceDifferentKeys() {
        assertNotEquals(
                DedupKeys.derive("evt-1", NotificationChannelType.EMAIL),
                DedupKeys.derive("evt-1", NotificationChannelType.IN_APP));
    }
}
