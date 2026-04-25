package com.example.alarm.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, String> {

    Optional<Notification> findByDedupKey(String dedupKey);

    @Query(value = """
        SELECT * FROM notification
        WHERE status = 'PENDING' AND next_attempt_at <= :now
        ORDER BY next_attempt_at
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Notification> findDuePending(@Param("now") Instant now,
                                      @Param("batchSize") int batchSize);

    @Query(value = """
        SELECT id FROM notification
        WHERE status = 'IN_PROGRESS' AND claimed_at < :cutoff
        ORDER BY claimed_at
        LIMIT :limit
        """, nativeQuery = true)
    List<String> findStuckClaimedIds(@Param("cutoff") Instant cutoff,
                                     @Param("limit") int limit);

    @Modifying
    @Query(value = """
        UPDATE notification
           SET status = 'PENDING', claimed_at = NULL, claimed_by = NULL, updated_at = :now
         WHERE status = 'IN_PROGRESS' AND claimed_at < :cutoff
        """, nativeQuery = true)
    int releaseStuckClaims(@Param("cutoff") Instant cutoff,
                           @Param("now") Instant now);

    List<Notification> findByRecipientIdOrderByCreatedAtDesc(String recipientId, Pageable pageable);

    List<Notification> findByRecipientIdAndReadOrderByCreatedAtDesc(String recipientId,
                                                                   boolean read,
                                                                   Pageable pageable);

    List<Notification> findByStatusOrderByUpdatedAtDesc(NotificationStatus status, Pageable pageable);
}
