package com.example.alarm.dispatch;

import java.time.Instant;

public interface RetryPolicy {
    Instant nextAttemptAt(int attemptsAfterFailure, Instant now);
    boolean shouldGiveUp(int attemptsAfterFailure);
}
