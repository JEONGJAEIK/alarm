package com.example.alarm.service;

import com.example.alarm.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 알림 등록·조회·읽음 처리의 단일 진입점.
 *
 * <p>register는 dedup_key UNIQUE 제약을 권위로 멱등 보장. 동시 중복 INSERT 시 패자(loser)는
 * {@link DuplicateNotificationException}을 받아 409 Conflict로 변환된다. 호출자는 별도의
 * 조회·목록 API로 알림 상태를 확인한다.
 *
 * <p>DLQ(데드레터) 운영 작업은 {@link DeadLetterService}로 분리되어 있다.
 */
@RequiredArgsConstructor
@Service
public class NotificationService {

    private final NotificationRepository repo;
    private final Clock clock;

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
     * <p>dedup_key UNIQUE 제약이 멱등성의 권위다. 동일 {@code (eventId, channel)} 조합에
     * 대한 두 번째 호출은 {@link DuplicateNotificationException}을 던져 API에서 409 Conflict로
     * 변환된다. 호출자는 별도의 조회·목록 API로 알림 상태를 확인한다.
     *
     * <p><b>트랜잭션 의도:</b> {@code saveAndFlush} 도중 unique 위반이 발생하면 Hibernate가
     * 세션을 rollback-only로 마킹하지만, 본 메서드는 catch 즉시 예외를 다시 throw하므로
     * Spring AOP가 정상적으로 rollback을 수행한다(commit 시도 없음). 따라서
     * {@code UnexpectedRollbackException} 위험이 없다.
     *
     * <p><b>호출자 트랜잭션 전파:</b> 호출자가 자신의 {@code @Transactional} 안에서 register를
     * 호출하면 동일 트랜잭션에 합류한다. 호출자 트랜잭션이 롤백되면 알림 등록도 함께
     * 롤백되므로, 비즈니스 트랜잭션 커밋 후에만 알림을 발생시키려면
     * {@code @TransactionalEventListener(phase=AFTER_COMMIT)} 패턴을 사용한다.
     *
     * @param cmd 등록 요청 데이터
     * @throws DuplicateNotificationException 동일 {@code (eventId, channel)} 조합이 이미 존재할 때
     */
    @Transactional
    public void register(RegisterCommand cmd) {
        Instant now = Instant.now(clock);
        Notification n = (cmd.scheduledAt() != null && cmd.scheduledAt().isAfter(now))
                ? Notification.createScheduled(cmd.recipientId(), cmd.type(), cmd.channel(),
                        cmd.eventId(), cmd.referenceData(), now, cmd.scheduledAt())
                : Notification.createImmediate(cmd.recipientId(), cmd.type(), cmd.channel(),
                        cmd.eventId(), cmd.referenceData(), now);
        try {
            repo.saveAndFlush(n);
        } catch (DataIntegrityViolationException dup) {
            throw new DuplicateNotificationException(n.getDedupKey());
        }
    }

    /**
     * 외부 노출 ID(external_id)로 알림을 단건 조회. 본인 또는 관리자만 접근 가능.
     *
     * @param externalId  외부 노출 ID
     * @param requesterId 호출자 사용자 ID
     * @param isAdmin     관리자 헤더 여부
     * @return 조회된 알림
     * @throws NotificationNotFoundException 알림이 없을 때
     * @throws ForbiddenException            본인이 아니고 관리자도 아닐 때
     */
    @Transactional(readOnly = true)
    public Notification findByExternalId(String externalId, String requesterId, boolean isAdmin) {
        Notification n = repo.findByExternalId(externalId).orElseThrow(() -> new NotificationNotFoundException(externalId));
        if (!isAdmin && !n.isOwnedBy(requesterId)) {
            throw new ForbiddenException("본인의 알림만 조회할 수 있습니다");
        }
        return n;
    }

    /**
     * 수신자 ID로 알림 목록을 페이지로 조회한다. 정렬은 Repository 메서드명의 {@code createdAt DESC}.
     *
     * <p>요청자({@code requesterId})가 {@code recipientId}와 다르면 본인이 아닌 알림 조회로
     * 간주해 {@link ForbiddenException}을 던진다. {@code pageable.getPageSize()}는 1~200으로
     * 클램프되며, 그 외의 page index와 sort는 그대로 사용한다.
     *
     * @param recipientId 수신자 ID
     * @param requesterId 호출자 사용자 ID (본인 검증)
     * @param read null이면 전체, true/false면 해당 read 상태로 필터
     * @param pageable 페이지 정보 (size 1~200으로 클램프됨)
     * @return 알림 엔티티 리스트 (가장 최근 등록 순)
     * @throws ForbiddenException 호출자가 수신자가 아닐 때
     */
    @Transactional(readOnly = true)
    public List<Notification> listForRecipient(String recipientId, String requesterId, Boolean read, Pageable pageable) {
        if (!recipientId.equals(requesterId)) {
            throw new ForbiddenException("본인의 알림만 조회할 수 있습니다");
        }
        int safeSize = Math.min(Math.max(pageable.getPageSize(), 1), 200);
        Pageable safe = PageRequest.of(pageable.getPageNumber(), safeSize, pageable.getSort());
        if (read == null) {
            return repo.findByRecipientIdOrderByCreatedAtDesc(recipientId, safe);
        }
        return repo.findByRecipientIdAndReadOrderByCreatedAtDesc(recipientId, read, safe);
    }

    /**
     * 알림을 읽음 상태로 전이. 이미 읽음 상태면 즉시 반환.
     *
     * <p>다중 디바이스에서 동시 호출 시 {@code @Version} 낙관적 락 충돌이 발생할 수 있다.
     * 충돌 시 1회 재조회하면 이미 읽음 상태로 정착되어 있으므로 멱등.
     *
     * <p><b>트랜잭션 경계 없음:</b> {@code saveAndFlush}의 자체 트랜잭션이 낙관적 락을
     * 처리하므로, 외부 {@code @Transactional}이 없어야 catch 후 재조회가 안전하게 동작한다.
     * {@code @Transactional}을 붙이면 충돌 시 트랜잭션이 rollback-only로 마킹되어
     * {@link org.springframework.transaction.UnexpectedRollbackException}이 전파된다.
     *
     * @param id internal Long ID
     * @return 읽음 처리된 알림 (또는 이미 읽음 상태였던 알림)
     * @throws NotificationNotFoundException 해당 ID가 없을 때
     */
    public Notification markRead(Long id) {
        Notification n = repo.findById(id).orElseThrow(() -> new NotificationNotFoundException(String.valueOf(id)));
        if (n.isRead()) return n;
        try {
            n.markRead(Instant.now(clock));
            return repo.saveAndFlush(n);
        } catch (ObjectOptimisticLockingFailureException race) {
            return repo.findById(id).orElseThrow(() -> new NotificationNotFoundException(String.valueOf(id)));
        }
    }

    /**
     * 본인 검증 후 알림을 읽음 처리. 권한 검증은 트랜잭션 밖에서 수행.
     *
     * @param externalId  외부 노출 ID
     * @param requesterId 호출자 사용자 ID
     * @return 읽음 처리된 알림
     * @throws NotificationNotFoundException 해당 ID가 없을 때
     * @throws ForbiddenException            호출자가 알림의 수신자가 아닐 때
     */
    public Notification markReadByOwner(String externalId, String requesterId) {
        Notification n = repo.findByExternalId(externalId).orElseThrow(() -> new NotificationNotFoundException(externalId));
        if (!n.isOwnedBy(requesterId)) {
            throw new ForbiddenException("본인의 알림만 읽음 처리할 수 있습니다");
        }
        return markRead(n.getId());
    }

}
