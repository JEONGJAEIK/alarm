package com.example.alarm.api.dto;

import com.example.alarm.domain.DeadLetter;
import com.example.alarm.domain.FailureEntry;
import com.example.alarm.domain.Notification;
import com.example.alarm.domain.NotificationChannelType;
import com.example.alarm.domain.NotificationStatus;
import com.example.alarm.domain.NotificationType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 관리자용 DLQ 단건 응답 DTO. revive 응답에도 사용된다.
 *
 * <p>본 DTO는 {@code /api/admin/dead-letters/**} 엔드포인트에서만 사용되며,
 * 사용자 응답에는 절대 노출되지 않아야 한다(BOPLA 방어). {@code failureHistory}를
 * 노출하는 유일한 응답 DTO이다.
 */
public record AdminDeadLetterResponse(
        String notificationExternalId,
        String recipientId,
        NotificationType type,
        NotificationChannelType channel,
        NotificationStatus status,
        int attempts,
        int reviveCount,
        Map<String, Object> referenceData,
        Instant firstFailedAt,
        Instant lastUpdatedAt,
        Instant nextAttemptAt,
        Instant lastRevivedAt,
        String lastRevivedBy,
        List<FailureEntry> failureHistory) {

    /**
     * 도메인 엔티티 두 개를 결합하여 관리자 DLQ 단건 응답 DTO로 변환.
     */
    public static AdminDeadLetterResponse from(Notification n, DeadLetter d) {
        return new AdminDeadLetterResponse(
                n.getExternalId(), n.getRecipientId(), n.getType(), n.getChannel(),
                n.getStatus(), n.getAttempts(), d.getReviveCount(),
                n.getReferenceData(),
                d.getCreatedAt(), d.getUpdatedAt(), n.getNextAttemptAt(),
                d.getLastRevivedAt(), d.getLastRevivedBy(),
                d.getFailureHistory());
    }
}
