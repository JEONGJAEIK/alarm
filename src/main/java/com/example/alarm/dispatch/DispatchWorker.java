package com.example.alarm.dispatch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * {@link NotificationDispatcher#runOnce}를 일정 주기로 호출하는 스케줄링 드라이버.
 *
 * <p>워커 ID는 {@code hostname-pid-uuid8} 형식이며, claimed_by 컬럼 길이(100자) 초과 방지를 위해
 * 100자 초과 시 우측을 잘라 보존한다.
 */
@Component
@Slf4j
public class DispatchWorker {

    private static final int MAX_WORKER_ID_LENGTH = 100;

    private final NotificationDispatcher dispatcher;
    private final String workerId;

    public DispatchWorker(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        this.workerId = buildWorkerId();
    }

    private static String buildWorkerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        long pid = ProcessHandle.current().pid();
        String uuid8 = UUID.randomUUID().toString().substring(0, 8);
        String id = host + "-" + pid + "-" + uuid8;
        return id.length() > MAX_WORKER_ID_LENGTH
                ? id.substring(id.length() - MAX_WORKER_ID_LENGTH) : id;
    }

    /**
     * 폴링 주기마다 한 번의 디스패치 tick 실행. 어떤 예외도 다음 tick을 막지 않도록 흡수한다.
     */
    @Scheduled(fixedDelayString = "${alarm.dispatch.poll-interval-ms}")
    public void tick() {
        try {
            int processed = dispatcher.runOnce(workerId);
            if (processed > 0) {
                log.debug("worker={} processed={}", workerId, processed);
            }
        } catch (Exception e) {
            log.error("dispatch tick failed", e);
        }
    }

    public String workerId() { return workerId; }
}
