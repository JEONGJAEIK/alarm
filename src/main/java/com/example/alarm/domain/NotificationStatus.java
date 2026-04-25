package com.example.alarm.domain;

/**
 * 알림의 생명주기 상태를 나타내는 열거형.
 *
 * <p>상태 전이: {@code PENDING} → {@code IN_PROGRESS} → {@code SUCCEEDED} 또는
 * {@code DEAD_LETTER}. {@code DEAD_LETTER}는 {@link Notification#revive(java.time.Instant)}
 * 호출로 {@code PENDING}으로 복원할 수 있다.
 */
public enum NotificationStatus {
    PENDING,
    IN_PROGRESS,
    SUCCEEDED,
    DEAD_LETTER;

    /**
     * 더 이상 재처리되지 않는 최종 상태인지 확인한다.
     *
     * @return {@code SUCCEEDED} 또는 {@code DEAD_LETTER}이면 {@code true}
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == DEAD_LETTER;
    }
}
