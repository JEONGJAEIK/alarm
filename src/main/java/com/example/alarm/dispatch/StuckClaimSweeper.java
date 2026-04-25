package com.example.alarm.dispatch;

import com.example.alarm.config.DispatchProperties;
import com.example.alarm.domain.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * visibility timeout을 초과한 IN_PROGRESS 알림을 PENDING으로 복귀시키는 스위퍼.
 *
 * <p>워커가 채널 호출 중 OOM/SIGKILL/네트워크 단절로 사망한 경우에 행을
 * 복구한다. 복구 시 attempts는 증가시키지 않는다(실제 채널 실패가 아니므로).
 *
 * <p>다중 인스턴스에서 동시 실행되어도 single bulk UPDATE가 InnoDB row lock으로
 * 직렬화된다. 같은 행을 두 번 PENDING으로 되돌려도 idempotent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StuckClaimSweeper {

    private final NotificationRepository repo;
    private final DispatchProperties props;

    /**
     * 스케줄러가 호출하는 진입점. 어떤 예외도 다음 sweep을 막지 않도록 흡수.
     */
    @Scheduled(fixedDelayString = "${alarm.dispatch.sweep-interval-ms}")
    public void scheduledSweep() {
        try {
            int released = sweep();
            if (released > 0) {
                log.info("released {} stuck claims", released);
            }
        } catch (Exception e) {
            log.error("sweep failed", e);
        }
    }

    /**
     * stuck-claim 한 회차 실행. 직접 호출 가능하도록 public + Transactional.
     *
     * @return 이번 회차에 PENDING으로 되돌린 행 수
     */
    @Transactional
    public int sweep() {
        Instant now = Instant.now(Clock.systemUTC());
        Instant cutoff = now.minus(props.visibilityTimeout());
        return repo.releaseStuckClaims(cutoff, now);
    }
}
