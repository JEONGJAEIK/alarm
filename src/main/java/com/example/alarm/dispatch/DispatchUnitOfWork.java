package com.example.alarm.dispatch;

import com.example.alarm.domain.Notification;
import com.example.alarm.domain.NotificationRepository;
import com.example.alarm.domain.NotificationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 디스패처가 사용하는 짧은 트랜잭션 단위(claim, finalize)를 별도 빈으로 격리.
 *
 * <p>{@code @Transactional(REQUIRES_NEW)}는 self-invocation에서 작동하지 않으므로,
 * 호출자와 다른 빈으로 분리한다.
 *
 * <p>finalize 메서드는 {@link NotificationRepository#lockById}로 행 락을 잡고
 * 상태가 IN_PROGRESS인지 다시 확인한다. sweeper가 이미 PENDING으로 되돌렸거나
 * 다른 워커가 재클레임한 경우 조용히 스킵한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DispatchUnitOfWork {

    private final NotificationRepository repo;
    private final RetryPolicy retryPolicy;
    private final Clock clock;

    /**
     * due한 PENDING 행을 batchSize만큼 클레임해 IN_PROGRESS로 전이.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 기반이므로 다중 워커가 동시 호출해도
     * 서로 다른 행만 가져간다.
     *
     * @param workerId  클레임 시도 워커 식별자
     * @param batchSize 한 번에 가져올 최대 행 수
     * @return 클레임된 알림들. REQUIRES_NEW 트랜잭션 commit 후 반환되므로 detached 상태이며,
     *         단순 필드(id, channel, recipientId 등) 읽기만 안전하다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Notification> claimBatch(String workerId, int batchSize) {
        Instant now = Instant.now(clock);
        List<Notification> due = repo.findDuePending(now, batchSize);
        for (Notification n : due) {
            n.claim(workerId, now);
        }
        repo.flush();
        return due;
    }

    /**
     * 발송 성공한 알림을 SUCCEEDED로 전이.
     *
     * <p>락 후 상태가 IN_PROGRESS가 아니면 다른 경로(sweeper, 다른 워커)가 이미 처리한 것으로
     * 간주하고 조용히 스킵.
     *
     * @param id 알림 식별자
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeSuccess(Long id) {
        Notification n = repo.lockById(id).orElseThrow(
                () -> new IllegalStateException("알림이 존재하지 않습니다: " + id));
        if (n.getStatus() != NotificationStatus.IN_PROGRESS) {
            log.warn("finalizeSuccess 건너뜀 — id={} status={} (sweeper 경합 또는 중복 호출)",
                    id, n.getStatus());
            return;
        }
        n.markSucceeded(Instant.now(clock));
        repo.saveAndFlush(n);
    }

    /**
     * 발송 실패한 알림을 retryable 여부와 attempts에 따라 PENDING(재시도) 또는 DEAD_LETTER로 전이.
     *
     * <p>락 후 상태가 IN_PROGRESS가 아니면 조용히 스킵. 실패 사유는 본 메서드에서
     * 영속화하지 않고 로그로만 남긴다 — 상세 사유 영속화는 후속 단계에서 별도
     * {@code DeadLetter} 엔티티가 담당한다.
     *
     * @param id       알림 식별자
     * @param reason   실패 사유 (로그 출력용)
     * @param retryable true면 재시도 정책에 따라 다음 시도 예약, false면 즉시 DEAD_LETTER
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeFailure(Long id, String reason, boolean retryable) {
        Instant now = Instant.now(clock);
        Notification n = repo.lockById(id).orElseThrow(
                () -> new IllegalStateException("알림이 존재하지 않습니다: " + id));
        if (n.getStatus() != NotificationStatus.IN_PROGRESS) {
            log.warn("finalizeFailure 건너뜀 — id={} status={} reason={}", id, n.getStatus(), reason);
            return;
        }
        int nextAttempts = n.getAttempts() + 1;
        if (!retryable || retryPolicy.shouldGiveUp(nextAttempts)) {
            n.markDeadLetter(now);
            log.warn("notification dead-letter id={} reason={}", id, reason);
        } else {
            Instant nextAt = retryPolicy.nextAttemptAt(nextAttempts, now);
            n.scheduleRetry(nextAt, now);
            log.warn("notification retry scheduled id={} attempts={} reason={}",
                    id, nextAttempts, reason);
        }
        repo.saveAndFlush(n);
    }
}
