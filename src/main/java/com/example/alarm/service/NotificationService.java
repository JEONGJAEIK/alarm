package com.example.alarm.service;

import com.example.alarm.domain.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * 알림 등록·조회·읽음 처리·데드레터 관리의 단일 진입점.
 *
 * <p>register는 dedup_key UNIQUE 제약을 권위로 멱등 보장. 동시 중복 INSERT 시 패자(loser)는
 * {@link DataIntegrityViolationException}을 받고 기존 행을 재조회한다.
 *
 * <p>INSERT 시도는 {@link NotificationInsertHelper}가 {@code REQUIRES_NEW} 트랜잭션으로
 * 격리하여, INSERT 실패 시 해당 트랜잭션만 롤백되고 호출자 트랜잭션이 오염되지 않는다.
 */
@Service
public class NotificationService {

    private final NotificationRepository repo;
    private final NotificationInsertHelper insertHelper;
    private final Clock clock;

    public NotificationService(NotificationRepository repo,
                                NotificationInsertHelper insertHelper,
                                Clock clock) {
        this.repo = repo;
        this.insertHelper = insertHelper;
        this.clock = clock;
    }

    /**
     * 알림 등록 요청.
     *
     * @param recipientId   수신자 식별자
     * @param type          알림 타입
     * @param channel       발송 채널
     * @param eventId       멱등 키 구성에 쓰이는 이벤트 식별자 (소문자 정규화됨)
     * @param referenceData 알림에 첨부할 자유 JSON 맵 (null/빈 맵 허용)
     * @param scheduledAt   미래 시각이면 예약 발송, null이면 즉시 처리 후보
     */
    public record RegisterCommand(
            String recipientId,
            NotificationType type,
            NotificationChannelType channel,
            String eventId,
            Map<String, Object> referenceData,
            Instant scheduledAt) {}

    /**
     * 알림을 멱등 등록한다.
     *
     * <p>동일 {@code (eventId, channel)} 조합에 대해 항상 같은 notification id를 반환한다.
     * 먼저 dedup_key로 기존 행을 조회하고, 없으면 INSERT를 시도한다. 동시 INSERT race가
     * 발생하면 unique 제약 위반을 catch한 뒤 재조회로 동일성을 보장한다.
     *
     * <p>INSERT는 {@link NotificationInsertHelper#insertAndFlush}가 별도 트랜잭션으로
     * 격리하므로, INSERT 실패 시 해당 트랜잭션만 롤백되어 이후 재조회가 가능하다.
     *
     * @param cmd 등록 요청 데이터
     * @return 새로 생성됐거나 기존에 존재하던 알림 엔티티
     */
    public Notification register(RegisterCommand cmd) {
        Instant now = Instant.now(clock);
        String dedupKey = DedupKeys.derive(cmd.eventId(), cmd.channel());

        // 1차: 이미 존재하는 행 반환
        var existing = repo.findByDedupKey(dedupKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 2차: INSERT 시도 (별도 REQUIRES_NEW 트랜잭션)
        Notification n = Notification.create(
                cmd.recipientId(), cmd.type(), cmd.channel(),
                cmd.eventId(), cmd.referenceData(), now, cmd.scheduledAt());
        try {
            return insertHelper.insertAndFlush(n);
        } catch (DataIntegrityViolationException dup) {
            // 동시 INSERT race — 승자(winner)의 행 반환
            return repo.findByDedupKey(dedupKey)
                    .orElseThrow(() -> dup);
        }
    }
}
