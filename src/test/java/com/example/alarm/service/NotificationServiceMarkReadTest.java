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
 * {@link NotificationService#markRead}의 다중 디바이스 동시 호출 안전성 검증.
 */
class NotificationServiceMarkReadTest extends AbstractMysqlIntegrationTest {

    @Autowired NotificationService service;
    @Autowired NotificationRepository repo;

    @BeforeEach
    void clean() { repo.deleteAll(); }

    @Test
    void 동시_읽음_처리는_멱등하게_수렴한다() throws Exception {
        Notification n = repo.save(Notification.create("u-r", NotificationType.PAYMENT_CONFIRMED,
                NotificationChannelType.IN_APP, "evt-r1", Map.of(), Instant.now()));

        int threadCount = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        try {
            var futures = IntStream.range(0, threadCount).mapToObj(i -> pool.submit(() -> {
                try {
                    barrier.await();
                    return service.markRead(n.getId());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            })).toList();
            for (var f : futures) f.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }

        Notification after = repo.findById(n.getId()).orElseThrow();
        assertTrue(after.isRead());
        assertNotNull(after.getReadAt());
    }
}
