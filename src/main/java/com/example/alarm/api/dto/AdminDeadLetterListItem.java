package com.example.alarm.api.dto;

import com.example.alarm.domain.DeadLetter;
import com.example.alarm.domain.FailureEntry;
import com.example.alarm.domain.Notification;
import com.example.alarm.domain.NotificationChannelType;
import com.example.alarm.domain.NotificationType;

import java.time.Instant;
import java.util.List;

/**
 * 관리자용 DLQ 목록 항목 DTO. notification + dlq 결합 매핑.
 *
 * <p>본 DTO는 {@code /api/admin/dead-letters} 응답에서만 사용되며,
 * 사용자 응답에는 절대 노출되지 않아야 한다(BOPLA 방어).
 */
public record AdminDeadLetterListItem(
        String notificationExternalId,
        String recipientId,
        NotificationType type,
        NotificationChannelType channel,
        int attempts,
        int reviveCount,
        Instant firstFailedAt,
        Instant lastUpdatedAt,
        Instant lastRevivedAt,
        String lastRevivedBy,
        List<FailureEntry> failureHistory) {

    /**
     * 도메인 엔티티 두 개를 결합하여 관리자 DLQ 목록 항목 DTO로 변환.
     */
    public static AdminDeadLetterListItem from(Notification n, DeadLetter d) {
        return new AdminDeadLetterListItem(
                n.getExternalId(), n.getRecipientId(), n.getType(), n.getChannel(),
                n.getAttempts(), d.getReviveCount(),
                d.getCreatedAt(), d.getUpdatedAt(),
                d.getLastRevivedAt(), d.getLastRevivedBy(),
                d.getFailureHistory());
    }
}
