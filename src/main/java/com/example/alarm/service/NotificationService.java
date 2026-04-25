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
 */
@Service
public class NotificationService {

    private final NotificationRepository repo;
    private final Clock clock;

    public NotificationService(NotificationRepository repo, Clock clock) {
        this.repo = repo;
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
     * <p><b>경고: 이 메서드에 절대 {@code @Transactional}을 추가하지 마세요.</b>
     * 이유는 두 가지:
     * <ul>
     *   <li>{@code @Transactional}을 붙이면 REPEATABLE READ snapshot이 1차 조회 시점에 고정되어,
     *       {@link DataIntegrityViolationException} catch 후 재조회가 승자(winner) 행을 보지 못함.</li>
     *   <li>{@code @Transactional(REQUIRES_NEW)}을 붙이면 {@code saveAndFlush} unique 위반이
     *       해당 트랜잭션을 rollback-only로 마킹해 같은 트랜잭션의 catch 후 재조회가 막힘.</li>
     * </ul>
     * 현재 디자인은 {@code register} 자체에 트랜잭션 경계가 없고,
     * Spring Data JPA의 {@code saveAndFlush}가 메서드 레벨 {@code @Transactional}로
     * 독립 트랜잭션을 잠깐 열어 INSERT만 처리하므로 catch 후 재조회가 안전합니다.
     *
     * <p><b>호출자 트랜잭션과 독립:</b> 이 메서드는 호출자의 {@code @Transactional} 트랜잭션과
     * 무관하게 알림 행을 커밋합니다(saveAndFlush의 자체 트랜잭션). 호출자 롤백이 알림 생성을
     * 취소하지 않으므로, 비즈니스 트랜잭션의 사이드이펙트로 알림을 등록할 때는
     * {@code @TransactionalEventListener(phase=AFTER_COMMIT)} 패턴 사용 권장.
     *
     * @param cmd 등록 요청 데이터
     * @return 새로 생성됐거나 기존에 존재하던 알림 엔티티
     */
    public Notification register(RegisterCommand cmd) {
        Instant now = Instant.now(clock);
        String dedupKey = DedupKeys.derive(cmd.eventId(), cmd.channel());
        return repo.findByDedupKey(dedupKey).orElseGet(() -> insertNew(cmd, now));
    }

    private Notification insertNew(RegisterCommand cmd, Instant now) {
        Notification n = Notification.create(
                cmd.recipientId(), cmd.type(), cmd.channel(),
                cmd.eventId(), cmd.referenceData(), now, cmd.scheduledAt());
        try {
            return repo.saveAndFlush(n);
        } catch (DataIntegrityViolationException dup) {
            return repo.findByDedupKey(n.getDedupKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "dedup_key=" + n.getDedupKey() + " unique 위반 후 승자 행 재조회 실패", dup));
        }
    }

    /**
     * 알림 ID로 단건 조회. 본인 또는 관리자만 접근 가능.
     *
     * @param id          알림 ID
     * @param requesterId 호출자 사용자 ID
     * @param isAdmin     관리자 헤더 여부
     * @return 조회된 알림
     * @throws NotificationNotFoundException 알림이 없을 때
     * @throws ForbiddenException            본인이 아니고 관리자도 아닐 때
     */
    @Transactional(readOnly = true)
    public Notification findById(String id, String requesterId, boolean isAdmin) {
        Notification n = repo.findById(id).orElseThrow(() -> new NotificationNotFoundException(id));
        if (!isAdmin && !n.getRecipientId().equals(requesterId)) {
            throw new ForbiddenException("본인의 알림만 조회할 수 있습니다");
        }
        return n;
    }

    /**
     * 수신자 ID로 알림 목록을 페이지로 조회한다. 정렬은 {@code createdAt DESC}.
     *
     * @param recipientId 수신자 ID
     * @param read null이면 전체, true/false면 해당 read 상태로 필터
     * @param limit 1~200 사이로 클램프됨
     * @return 알림 엔티티 리스트 (가장 최근 등록 순)
     */
    @Transactional(readOnly = true)
    public java.util.List<Notification> listForRecipient(String recipientId, Boolean read, int limit) {
        int safe = Math.min(Math.max(limit, 1), 200);
        var page = org.springframework.data.domain.PageRequest.of(0, safe);
        if (read == null) {
            return repo.findByRecipientIdOrderByCreatedAtDesc(recipientId, page);
        }
        return repo.findByRecipientIdAndReadOrderByCreatedAtDesc(recipientId, read, page);
    }
}
