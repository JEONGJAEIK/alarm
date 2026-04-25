package com.example.alarm.domain;

public enum NotificationStatus {
    PENDING,
    IN_PROGRESS,
    SUCCEEDED,
    DEAD_LETTER;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == DEAD_LETTER;
    }
}
