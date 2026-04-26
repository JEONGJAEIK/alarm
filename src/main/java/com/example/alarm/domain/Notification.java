package com.example.alarm.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 알림 발송 단위를 나타내는 JPA 엔티티.
 *
 * <p>한 알림은 수신자, 채널, 비즈니스 이벤트로 특정되며 {@link NotificationStatus} 상태 머신을
 * 따라 생명주기가 관리된다. 낙관적 락({@code @Version})으로 동시 수정을 방지하며,
 * dedup_key 유니크 인덱스로 중복 생성을 차단한다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "recipient_id", nullable = false, length = 100)
    private String recipientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannelType channel;

    @Column(name = "dedup_key", nullable = false, length = 200)
    private String dedupKey;

    @Convert(converter = ReferenceDataConverter.class)
    @Column(name = "reference_data", columnDefinition = "JSON")
    private Map<String, Object> referenceData;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "claimed_by", length = 100)
    private String claimedBy;

    @Column(name = "last_failure_reason", length = 1000)
    private String lastFailureReason;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "read_at")
    private Instant readAt;

    @Version
    private long version;

    /**
     * 즉시 발송 대상 알림을 생성한다. {@code nextAttemptAt = now}로 설정되어
     * 다음 워커 폴링 틱에 클레임된다.
     *
     * @param recipientId 수신자 식별자
     * @param type        알림 유형
     * @param channel     발송 채널
     * @param eventId     외부 이벤트 식별자 (dedup 키 파생에 사용)
     * @param referenceData 템플릿 렌더링용 참조 데이터 (null 허용)
     * @param now         생성 시각 (UTC)
     * @return 초기 상태({@code PENDING})의 알림 인스턴스
     */
    public static Notification createImmediate(String recipientId,
                                               NotificationType type,
                                               NotificationChannelType channel,
                                               String eventId,
                                               Map<String, Object> referenceData,
                                               Instant now) {
        return create(recipientId, type, channel, eventId, referenceData, now, null);
    }

    /**
     * 예약 발송 시각을 지정하여 알림을 생성한다. {@code nextAttemptAt = scheduledAt}로
     * 설정되어 해당 시각 이후의 워커 폴링 틱부터 클레임 대상이 된다.
     *
     * @param recipientId  수신자 식별자
     * @param type         알림 유형
     * @param channel      발송 채널
     * @param eventId      외부 이벤트 식별자 (dedup 키 파생에 사용)
     * @param referenceData 템플릿 렌더링용 참조 데이터 (null 허용)
     * @param now          생성 시각 (UTC)
     * @param scheduledAt  예약 발송 시각 (반드시 {@code now}보다 미래)
     * @return 초기 상태({@code PENDING})의 알림 인스턴스
     * @throws IllegalArgumentException scheduledAt이 null이거나 now 이전·동일할 때
     */
    public static Notification createScheduled(String recipientId,
                                               NotificationType type,
                                               NotificationChannelType channel,
                                               String eventId,
                                               Map<String, Object> referenceData,
                                               Instant now,
                                               Instant scheduledAt) {
        if (scheduledAt == null || !scheduledAt.isAfter(now)) {
            throw new IllegalArgumentException("scheduledAt은 now보다 미래여야 합니다");
        }
        return create(recipientId, type, channel, eventId, referenceData, now, scheduledAt);
    }

    private static Notification create(String recipientId,
                                       NotificationType type,
                                       NotificationChannelType channel,
                                       String eventId,
                                       Map<String, Object> referenceData,
                                       Instant now,
                                       Instant scheduledAt) {
        Notification n = new Notification();
        n.id = UUID.randomUUID().toString();
        n.recipientId = recipientId;
        n.type = type;
        n.channel = channel;
        n.dedupKey = DedupKeys.derive(eventId, channel);
        n.referenceData = referenceData == null ? Map.of() : Map.copyOf(referenceData);
        n.status = NotificationStatus.PENDING;
        n.attempts = 0;
        n.createdAt = now;
        n.updatedAt = now;
        n.nextAttemptAt = (scheduledAt != null && scheduledAt.isAfter(now)) ? scheduledAt : now;
        n.read = false;
        return n;
    }

    /**
     * 알림을 {@code IN_PROGRESS}로 클레임한다.
     *
     * <p>워커가 발송 직전 호출. {@code @Transactional} 안에서 호출되어야 다중 인스턴스
     * 환경에서 row lock으로 동시 클레임이 차단된다.
     *
     * @param workerId 클레임 시도 워커 식별자 ({@code hostname-pid-uuid8} 형식)
     * @param now      클레임 시각 (UTC)
     * @throws IllegalStateException PENDING 상태가 아닐 때
     */
    public void claim(String workerId, Instant now) {
        if (status != NotificationStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태에서만 클레임할 수 있습니다 (현재: " + status + ")");
        }
        this.status = NotificationStatus.IN_PROGRESS;
        this.claimedAt = now;
        this.claimedBy = workerId;
        this.updatedAt = now;
    }

    /**
     * 알림 발송 성공 처리를 수행한다.
     *
     * <p>클레임 정보를 초기화하고 상태를 {@code SUCCEEDED}로 전이시킨다.
     *
     * @param now 성공 처리 시각 (UTC)
     * @throws IllegalStateException IN_PROGRESS 상태가 아닐 때
     */
    public void markSucceeded(Instant now) {
        if (status != NotificationStatus.IN_PROGRESS) {
            throw new IllegalStateException("IN_PROGRESS 상태에서만 성공 처리할 수 있습니다 (현재: " + status + ")");
        }
        this.status = NotificationStatus.SUCCEEDED;
        this.claimedAt = null;
        this.claimedBy = null;
        this.updatedAt = now;
    }

    /**
     * 발송 실패 후 재시도를 예약한다.
     *
     * <p>attempts를 1 증가시키고, 클레임을 해제하며, 다음 시도 시각과 실패 사유를 기록한다.
     *
     * @param nextAttemptAt 다음 발송 시도 시각 (UTC)
     * @param failureReason 실패 사유 (1000자 초과 시 잘림)
     * @param now           현재 시각 (UTC)
     * @throws IllegalStateException IN_PROGRESS 상태가 아닐 때
     */
    public void scheduleRetry(Instant nextAttemptAt, String failureReason, Instant now) {
        if (status != NotificationStatus.IN_PROGRESS) {
            throw new IllegalStateException("IN_PROGRESS 상태에서만 재시도 예약할 수 있습니다 (현재: " + status + ")");
        }
        this.status = NotificationStatus.PENDING;
        this.attempts = this.attempts + 1;
        this.nextAttemptAt = nextAttemptAt;
        this.claimedAt = null;
        this.claimedBy = null;
        this.lastFailureReason = truncate(failureReason);
        this.lastFailureAt = now;
        this.updatedAt = now;
    }

    /**
     * 알림을 {@code DEAD_LETTER} 상태로 전이시킨다.
     *
     * <p>최대 재시도 횟수 초과 시 호출. attempts를 1 증가시키고 클레임을 해제한다.
     *
     * @param failureReason 최종 실패 사유 (1000자 초과 시 잘림)
     * @param now           처리 시각 (UTC)
     * @throws IllegalStateException IN_PROGRESS 상태가 아닐 때
     */
    public void markDeadLetter(String failureReason, Instant now) {
        if (status != NotificationStatus.IN_PROGRESS) {
            throw new IllegalStateException("IN_PROGRESS 상태에서만 DEAD_LETTER로 전이할 수 있습니다 (현재: " + status + ")");
        }
        this.status = NotificationStatus.DEAD_LETTER;
        this.attempts = this.attempts + 1;
        this.claimedAt = null;
        this.claimedBy = null;
        this.lastFailureReason = truncate(failureReason);
        this.lastFailureAt = now;
        this.updatedAt = now;
    }

    /**
     * 타임아웃된 클레임을 해제하고 알림을 {@code PENDING}으로 복귀시킨다.
     *
     * <p>클레임 잠금 해소용. attempts는 증가하지 않는다 — 워커가 실제로 처리를
     * 시도했는지 확인할 수 없기 때문이다.
     *
     * @param now 처리 시각 (UTC)
     */
    public void releaseStuckClaim(Instant now) {
        this.status = NotificationStatus.PENDING;
        this.claimedAt = null;
        this.claimedBy = null;
        this.updatedAt = now;
    }

    /**
     * 알림을 읽음 처리한다.
     *
     * <p>이미 읽음 상태라면 멱등하게 무시한다 — readAt이 변경되지 않는다.
     *
     * @param now 읽음 처리 시각 (UTC)
     */
    public void markRead(Instant now) {
        if (this.read) return;
        this.read = true;
        this.readAt = now;
        this.updatedAt = now;
    }

    /**
     * {@code DEAD_LETTER} 알림을 {@code PENDING}으로 복원한다.
     *
     * <p>운영자가 수동으로 재처리를 지시할 때 사용. attempts와 실패 정보를 초기화한다.
     *
     * @param now 복원 시각 (UTC)
     * @throws IllegalStateException DEAD_LETTER 상태가 아닐 때
     */
    public void revive(Instant now) {
        if (this.status != NotificationStatus.DEAD_LETTER) {
            throw new IllegalStateException("DEAD_LETTER 상태에서만 revive할 수 있습니다 (현재: " + this.status + ")");
        }
        this.status = NotificationStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.lastFailureReason = null;
        this.lastFailureAt = null;
        this.updatedAt = now;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
