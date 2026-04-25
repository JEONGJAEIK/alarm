package com.example.alarm.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 알림 엔티티에 대한 데이터 접근 인터페이스.
 *
 * <p>Spring Data JPA가 런타임에 구현체를 생성한다. 발송 워커, 스케줄러, 사용자 조회 세
 * 가지 접근 패턴을 모두 지원한다.
 */
public interface NotificationRepository extends JpaRepository<Notification, String> {

    /**
     * dedup 키로 알림을 조회한다.
     *
     * @param dedupKey 중복 제거 키
     * @return 해당 키의 알림 (없으면 empty)
     */
    Optional<Notification> findByDedupKey(String dedupKey);

    /**
     * 현재 시각 기준으로 발송 대기 중인 {@code PENDING} 알림을 조회한다.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED}로 다중 워커 환경에서 동일 행의 중복 처리를 방지한다.
     * 반드시 트랜잭션 안에서 호출해야 한다.
     *
     * @param now       현재 시각 (UTC); {@code next_attempt_at <= now}인 행만 포함
     * @param batchSize 최대 반환 건수
     * @return 발송 대기 알림 목록 (nextAttemptAt 오름차순)
     */
    @Query(value = """
        SELECT * FROM notification
        WHERE status = 'PENDING' AND next_attempt_at <= :now
        ORDER BY next_attempt_at
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Notification> findDuePending(@Param("now") Instant now,
                                      @Param("batchSize") int batchSize);

    /**
     * 클레임 시각이 cutoff보다 오래된 {@code IN_PROGRESS} 알림의 ID를 조회한다.
     *
     * <p>워커 장애로 클레임이 해제되지 않은 알림을 감지하는 sweep 스케줄러용.
     *
     * @param cutoff 이 시각 이전에 클레임된 행만 반환
     * @param limit  최대 반환 건수
     * @return 타임아웃 클레임 알림 ID 목록
     */
    @Query(value = """
        SELECT id FROM notification
        WHERE status = 'IN_PROGRESS' AND claimed_at < :cutoff
        ORDER BY claimed_at
        LIMIT :limit
        """, nativeQuery = true)
    List<String> findStuckClaimedIds(@Param("cutoff") Instant cutoff,
                                     @Param("limit") int limit);

    /**
     * cutoff보다 오래된 클레임을 일괄 해제하여 {@code PENDING}으로 복귀시킨다.
     *
     * <p>벌크 업데이트이므로 1차 캐시가 갱신되지 않는다.
     * 호출 후 영속성 컨텍스트를 flush/clear하거나 새 트랜잭션에서 사용할 것.
     *
     * @param cutoff 이 시각 이전 클레임 행을 대상으로 함
     * @param now    updated_at에 기록할 현재 시각 (UTC)
     * @return 갱신된 행 수
     */
    @Modifying
    @Query(value = """
        UPDATE notification
           SET status = 'PENDING', claimed_at = NULL, claimed_by = NULL, updated_at = :now
         WHERE status = 'IN_PROGRESS' AND claimed_at < :cutoff
        """, nativeQuery = true)
    int releaseStuckClaims(@Param("cutoff") Instant cutoff,
                           @Param("now") Instant now);

    /**
     * 수신자의 알림을 생성일 내림차순으로 페이지 조회한다.
     *
     * @param recipientId 수신자 식별자
     * @param pageable    페이지 정보
     * @return 알림 목록
     */
    List<Notification> findByRecipientIdOrderByCreatedAtDesc(String recipientId, Pageable pageable);

    /**
     * 수신자의 알림을 읽음 여부로 필터링하여 생성일 내림차순으로 페이지 조회한다.
     *
     * @param recipientId 수신자 식별자
     * @param read        읽음 여부 필터
     * @param pageable    페이지 정보
     * @return 조건에 맞는 알림 목록
     */
    List<Notification> findByRecipientIdAndReadOrderByCreatedAtDesc(String recipientId,
                                                                   boolean read,
                                                                   Pageable pageable);

    /**
     * 특정 상태의 알림을 수정일 내림차순으로 페이지 조회한다.
     *
     * @param status   조회할 알림 상태
     * @param pageable 페이지 정보
     * @return 해당 상태 알림 목록
     */
    List<Notification> findByStatusOrderByUpdatedAtDesc(NotificationStatus status, Pageable pageable);
}
