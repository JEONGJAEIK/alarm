package com.example.alarm.dispatch;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 지수 백오프 + 랜덤 지터 방식의 재시도 정책 구현체.
 *
 * <p>delay = min(base * multiplier^(attempts-1), max), 지터 적용 후 {@code [0, max]}로 클램프.
 * 지터는 여러 워커가 동시에 재시도하여 서버를 과부하시키는 thundering herd 문제를 완화한다.
 * 인스턴스는 스레드 안전하다 — 모든 필드가 불변이고 난수 생성에 {@link ThreadLocalRandom}을 사용한다.
 */
public class ExponentialBackoffRetryPolicy implements RetryPolicy {

    private final long baseMs;
    private final long maxMs;
    private final double jitterRatio;
    private final long multiplier;
    private final int maxAttempts;

    /**
     * 재시도 정책을 생성한다.
     *
     * @param base         기본 지연 시간 (양수여야 함)
     * @param max          최대 지연 시간 (base 이상이어야 함)
     * @param jitterRatio  지터 비율; 0이면 지터 없음, 1이면 ±100% ({@code [0,1]} 범위)
     * @param multiplier   지수 곱수 (2 이상). 예: 2이면 base, 2*base, 4*base ...; 4이면 base, 4*base, 16*base ...
     * @param maxAttempts  최대 시도 횟수 (1 이상이어야 함)
     * @throws IllegalArgumentException 인자가 범위를 벗어날 때
     */
    public ExponentialBackoffRetryPolicy(Duration base, Duration max,
                                         double jitterRatio, long multiplier, int maxAttempts) {
        if (base.isNegative() || base.isZero()) throw new IllegalArgumentException("base는 0보다 커야 합니다");
        if (max.compareTo(base) < 0) throw new IllegalArgumentException("max는 base 이상이어야 합니다");
        if (jitterRatio < 0 || jitterRatio > 1) throw new IllegalArgumentException("jitterRatio는 [0,1] 범위여야 합니다");
        if (multiplier < 2) throw new IllegalArgumentException("multiplier는 2 이상이어야 합니다");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts는 1 이상이어야 합니다");
        this.baseMs = base.toMillis();
        this.maxMs = max.toMillis();
        this.jitterRatio = jitterRatio;
        this.multiplier = multiplier;
        this.maxAttempts = maxAttempts;
    }

    /**
     * 실패 횟수에 따른 다음 시도 시각을 계산한다.
     *
     * <p>delay가 max에 도달하면 그 이상으로 증가하지 않는다 (capped exponential backoff).
     *
     * @param attemptsAfterFailure 누적 실패 횟수 (1부터 시작; 0 이하이면 1로 처리)
     * @param now                  현재 시각 (UTC)
     * @return 다음 발송 시도 시각
     */
    @Override
    public Instant nextAttemptAt(int attemptsAfterFailure, Instant now) {
        int safe = Math.max(attemptsAfterFailure, 1);
        long pow = baseMs;
        for (int i = 1; i < safe && pow < maxMs; i++) {
            pow = Math.min(pow * multiplier, maxMs);
        }
        long delay = Math.min(pow, maxMs);
        if (jitterRatio > 0) {
            long jitter = (long) (delay * jitterRatio);
            long jittered = delay + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
            delay = Math.max(0, Math.min(jittered, maxMs));
        }
        return now.plusMillis(delay);
    }

    /**
     * 누적 실패 횟수가 maxAttempts에 도달하면 재시도를 포기한다.
     *
     * @param attemptsAfterFailure 누적 실패 횟수
     * @return {@code attemptsAfterFailure >= maxAttempts}이면 {@code true}
     */
    @Override
    public boolean shouldGiveUp(int attemptsAfterFailure) {
        return attemptsAfterFailure >= maxAttempts;
    }
}
