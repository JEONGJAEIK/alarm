package com.example.alarm.domain;

public final class DedupKeys {
    private DedupKeys() {}

    public static String derive(String eventId, NotificationChannelType channel) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        if (channel == null) {
            throw new IllegalArgumentException("channel must not be null");
        }
        return eventId + "::" + channel.name();
    }
}
