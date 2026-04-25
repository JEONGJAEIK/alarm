package com.example.alarm.dispatch;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

public class ExponentialBackoffRetryPolicy implements RetryPolicy {

    private final long baseMs;
    private final long maxMs;
    private final double jitterRatio;
    private final int maxAttempts;

    public ExponentialBackoffRetryPolicy(Duration base, Duration max, double jitterRatio, int maxAttempts) {
        if (base.isNegative() || base.isZero()) throw new IllegalArgumentException("base must be > 0");
        if (max.compareTo(base) < 0) throw new IllegalArgumentException("max must be >= base");
        if (jitterRatio < 0 || jitterRatio > 1) throw new IllegalArgumentException("jitterRatio in [0,1]");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        this.baseMs = base.toMillis();
        this.maxMs = max.toMillis();
        this.jitterRatio = jitterRatio;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public Instant nextAttemptAt(int attemptsAfterFailure, Instant now) {
        int safe = Math.max(attemptsAfterFailure, 1);
        long pow = baseMs;
        for (int i = 1; i < safe && pow < maxMs; i++) {
            pow = Math.min(pow * 2, maxMs);
        }
        long delay = Math.min(pow, maxMs);
        if (jitterRatio > 0) {
            long jitter = (long) (delay * jitterRatio);
            delay = Math.max(0, delay + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1));
        }
        return now.plusMillis(delay);
    }

    @Override
    public boolean shouldGiveUp(int attemptsAfterFailure) {
        return attemptsAfterFailure >= maxAttempts;
    }
}
