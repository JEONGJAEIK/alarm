package com.example.alarm.service;

import com.example.alarm.domain.*;
import com.example.alarm.support.AbstractMysqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
        service.register(new NotificationService.RegisterCommand(
                "u1", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.EMAIL, "evt-1", Map.of("k", "v"), null));

        String dedupKey = DedupKeys.derive("evt-1", NotificationChannelType.EMAIL);
        Notification n = repo.findByDedupKey(dedupKey).orElseThrow();
        assertEquals(NotificationStatus.PENDING, n.getStatus());
    }

    @Test
    void register는_같은_eventId와_channel_재호출_시_DuplicateNotificationException을_던진다() {
        var cmd = new NotificationService.RegisterCommand("u1",
                NotificationType.PAYMENT_CONFIRMED, NotificationChannelType.EMAIL,
                "evt-2", Map.of(), null);

        service.register(cmd);
        assertThrows(DuplicateNotificationException.class, () -> service.register(cmd));
        assertEquals(1, repo.count(), "DB에 단 1개 행만 존재해야 한다");
    }

    @Test
    void register는_동시_중복_요청에서_단일_INSERT만_성공하고_나머지는_DuplicateNotificationException() throws Exception {
        var cmd = new NotificationService.RegisterCommand("u1",
                NotificationType.PAYMENT_CONFIRMED, NotificationChannelType.EMAIL,
                "evt-3", Map.of(), null);

        int threadCount = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threadCount).mapToObj(i -> pool.submit(() -> {
                try {
                    barrier.await();
                    service.register(cmd);
                    successes.incrementAndGet();
                } catch (DuplicateNotificationException dup) {
                    duplicates.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            })).toList();

            for (var f : futures) f.get(5, TimeUnit.SECONDS);

            assertEquals(1, successes.get(), "단일 thread만 INSERT에 성공해야 한다");
            assertEquals(threadCount - 1, duplicates.get(),
                    "나머지는 DuplicateNotificationException을 받아야 한다");
            assertEquals(1, repo.count(), "DB에 단 1개 행만 존재해야 한다");
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void register는_미래_scheduledAt을_nextAttemptAt에_반영한다() {
        Instant future = Instant.now().plusSeconds(3600);
        service.register(new NotificationService.RegisterCommand(
                "u1", NotificationType.COURSE_START_D1, NotificationChannelType.EMAIL,
                "evt-sched", Map.of(), future));

        String dedupKey = DedupKeys.derive("evt-sched", NotificationChannelType.EMAIL);
        Notification n = repo.findByDedupKey(dedupKey).orElseThrow();
        assertTrue(n.getNextAttemptAt().isAfter(Instant.now().plusSeconds(60)));
    }
}
