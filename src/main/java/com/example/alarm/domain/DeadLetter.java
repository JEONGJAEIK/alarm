package com.example.alarm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 알림이 자동 재시도를 모두 소진하여 DEAD_LETTER로 전이된 후 운영자가 다루는 별도 엔티티.
 *
 * <p>한 {@link Notification}당 최대 1 row만 존재한다(notification_id UNIQUE).
 * 자동 재시도 종료 시 INSERT, 운영자가 revive 후 또 자동 재시도 소진 시 같은 row를 UPDATE한다.
 * {@code @Version} 낙관적 락으로 failure_history JSON 동시 수정 race를 방어한다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "notification_dlq")
public class DeadLetter extends BaseTimeEntity {

    private static final int HISTORY_LIMIT = 10;
    private static final int REASON_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notification_id", nullable = false, updatable = false)
    private Long notificationId;

    @Convert(converter = FailureHistoryConverter.class)
    @Column(name = "failure_history", nullable = false, columnDefinition = "JSON")
    private List<FailureEntry> failureHistory;

    @Column(name = "revive_count", nullable = false)
    private int reviveCount;

    @Column(name = "last_revived_at")
    private Instant lastRevivedAt;

    @Column(name = "last_revived_by", length = 100)
    private String lastRevivedBy;

    @Version
    private long version;

    /**
     * 자동 재시도 N회 소진 시 첫 DLQ 진입 entity를 생성한다.
     *
     * @param notificationId 대상 알림 internal id
     * @param detailReason   관리자 노출용 구체 실패 사유 ({@value #REASON_MAX_LENGTH}자 초과 시 truncate)
     * @param now            진입 시각 (UTC)
     * @return 초기 상태({@code reviveCount=0}, history 1건)의 DeadLetter 인스턴스
     */
    public static DeadLetter create(Long notificationId, String detailReason, Instant now) {
        DeadLetter d = new DeadLetter();
        d.notificationId = notificationId;
        d.failureHistory = new ArrayList<>();
        d.failureHistory.add(new FailureEntry(now, truncate(detailReason), 0));
        d.reviveCount = 0;
        return d;
    }

    /**
     * Revive 후 자동 재시도가 또 N회 실패했을 때 호출.
     *
     * <p>history에 새 항목을 push하며, 길이가 {@value #HISTORY_LIMIT}을 초과하면 가장 오래된
     * 항목을 drop한다. revive_count는 변경하지 않는다 — 본 메서드는 실패 이벤트이지
     * revive 이벤트가 아니다.
     *
     * @param detailReason 관리자 노출용 구체 실패 사유 ({@value #REASON_MAX_LENGTH}자 초과 시 truncate)
     * @param now          push 시각 (UTC)
     */
    public void appendFailure(String detailReason, Instant now) {
        List<FailureEntry> next = new ArrayList<>(failureHistory);
        next.add(new FailureEntry(now, truncate(detailReason), reviveCount));
        if (next.size() > HISTORY_LIMIT) {
            next = new ArrayList<>(next.subList(next.size() - HISTORY_LIMIT, next.size()));
        }
        this.failureHistory = next;
    }

    /**
     * 관리자 수동 재시도 명령을 받았음을 기록한다.
     *
     * <p>revive_count를 1 증가시키고 last_revived_* 필드를 갱신한다. failure_history는
     * 건드리지 않는다 — revive 자체는 실패 이벤트가 아니다.
     *
     * @param adminId 수동 재시도 명령을 발행한 관리자 식별자
     * @param now     명령 시각 (UTC)
     */
    public void markRevived(String adminId, Instant now) {
        this.reviveCount = this.reviveCount + 1;
        this.lastRevivedAt = now;
        this.lastRevivedBy = adminId;
    }

    /**
     * failure_history는 외부에서 수정 불가능하도록 unmodifiable view로 노출한다.
     */
    public List<FailureEntry> getFailureHistory() {
        return Collections.unmodifiableList(failureHistory);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > REASON_MAX_LENGTH ? s.substring(0, REASON_MAX_LENGTH) : s;
    }
}
