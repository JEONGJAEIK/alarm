package com.example.alarm.dispatch;

import com.example.alarm.channel.ChannelDeliveryException;
import com.example.alarm.channel.ChannelRegistry;
import com.example.alarm.config.DispatchProperties;
import com.example.alarm.domain.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DB 폴링 기반 디스패처. 한 번의 {@link #runOnce(String)} 호출에서
 * 1) 짧은 트랜잭션으로 PENDING 행을 클레임 → 2) 트랜잭션 밖에서 채널 호출
 * → 3) 별도 짧은 트랜잭션으로 finalize한다.
 *
 * <p>채널 호출 동안 row lock을 보유하지 않으므로 외부 시스템 응답이 느려져도
 * DB 부하가 누적되지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PollingNotificationDispatcher implements NotificationDispatcher {

    private final ChannelRegistry channels;
    private final DispatchUnitOfWork uow;
    private final DispatchProperties props;

    @Override
    public int runOnce(String workerId) {
        List<Notification> claimed;
        try {
            claimed = uow.claimBatch(workerId, props.getBatchSize());
        } catch (Exception e) {
            log.error("claim batch failed", e);
            return 0;
        }
        int processed = 0;
        for (Notification n : claimed) {
            try {
                processOne(n);
                processed++;
            } catch (Exception e) {
                log.warn("processOne unexpected error id={}", n.getId(), e);
            }
        }
        return processed;
    }

    private void processOne(Notification n) {
        try {
            channels.resolve(n.getChannel()).deliver(n);
            uow.finalizeSuccess(n.getId());
        } catch (ChannelDeliveryException ex) {
            uow.finalizeFailure(n.getId(), ex.getMessage(), ex.isRetryable());
        } catch (Exception ex) {
            log.warn("delivery threw unexpected exception id={}", n.getId(), ex);
            uow.finalizeFailure(n.getId(),
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(), true);
        }
    }
}
