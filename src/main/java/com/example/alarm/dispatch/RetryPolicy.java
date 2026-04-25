package com.example.alarm.dispatch;

import java.time.Instant;

/**
 * 알림 발송 재시도 정책 인터페이스.
 *
 * <p>구현체는 발송 실패 횟수를 기반으로 다음 시도 시각을 계산하고,
 * 재시도 포기 여부를 결정한다.
 */
public interface RetryPolicy {

    /**
     * 발송 실패 후 다음 시도 시각을 계산한다.
     *
     * @param attemptsAfterFailure 현재까지 누적된 실패 횟수 (1부터 시작)
     * @param now                  현재 시각 (UTC)
     * @return 다음 발송 시도 시각
     */
    Instant nextAttemptAt(int attemptsAfterFailure, Instant now);

    /**
     * 더 이상 재시도하지 않아야 하는지 판단한다.
     *
     * @param attemptsAfterFailure 현재까지 누적된 실패 횟수
     * @return 재시도 포기 여부; {@code true}이면 {@code DEAD_LETTER}로 전이해야 함
     */
    boolean shouldGiveUp(int attemptsAfterFailure);
}
