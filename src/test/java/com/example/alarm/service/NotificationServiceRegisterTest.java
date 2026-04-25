package com.example.alarm.service;

import com.example.alarm.domain.*;
import com.example.alarm.support.AbstractMysqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NotificationService#register} 멱등 등록 검증.
 */
class NotificationServiceRegisterTest extends AbstractMysqlIntegrationTest {

    @Autowired NotificationService service;
    @Autowired NotificationRepository repo;

    @BeforeEach
    void clean() { repo.deleteAll(); }

    @Test
    void register는_PENDING_상태로_저장한다() {
        Notification n = service.register(new NotificationService.RegisterCommand(
                "u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-1", Map.of("k", "v"), null));

        assertEquals(NotificationStatus.PENDING, n.getStatus());
        assertNotNull(repo.findById(n.getId()).orElse(null));
    }

    @Test
    void register는_같은_eventId와_channel에_대해_멱등하다() {
        var cmd = new NotificationService.RegisterCommand("u1",
                NotificationType.PAYMENT_CONFIRMED, NotificationChannelType.EMAIL,
                "evt-2", Map.of(), null);

        Notification first = service.register(cmd);
        Notification second = service.register(cmd);

        assertEquals(first.getId(), second.getId());
    }

    @Test
    void register는_동시_중복_요청에도_단일_행만_생성한다() throws Exception {
        var cmd = new NotificationService.RegisterCommand("u1",
                NotificationType.PAYMENT_CONFIRMED, NotificationChannelType.EMAIL,
                "evt-3", Map.of(), null);

        int threadCount = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        try {
            var futures = IntStream.range(0, threadCount).mapToObj(i -> pool.submit(() -> {
                try {
                    barrier.await(); // 모든 스레드가 동시에 출발
                    return service.register(cmd).getId();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            })).toList();

            var ids = futures.stream()
                    .map(f -> {
                        try { return f.get(5, TimeUnit.SECONDS); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    })
                    .distinct()
                    .toList();
            assertEquals(1, ids.size(), "동시 중복 요청은 단일 id로 수렴해야 한다");
            assertEquals(1, repo.count(), "DB에 단 1개 행만 존재해야 한다");
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void register는_미래_scheduledAt을_nextAttemptAt에_반영한다() {
        Instant future = Instant.now().plusSeconds(3600);
        Notification n = service.register(new NotificationService.RegisterCommand(
                "u1", NotificationType.COURSE_START_D1, NotificationChannelType.EMAIL,
                "evt-sched", Map.of(), future));

        assertTrue(n.getNextAttemptAt().isAfter(Instant.now().plusSeconds(60)));
    }
}
