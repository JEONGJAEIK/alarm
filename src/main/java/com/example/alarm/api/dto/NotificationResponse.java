package com.example.alarm.api.dto;

import com.example.alarm.domain.Notification;
import com.example.alarm.domain.NotificationChannelType;
import com.example.alarm.domain.NotificationStatus;
import com.example.alarm.domain.NotificationType;

import java.time.Instant;
import java.util.Map;

/**
 * 알림 단건 응답 DTO. {@code POST}, {@code GET /{id}}, {@code PATCH /{id}/read} 등에서 사용.
 */
public record NotificationResponse(
        String id,
        String recipientId,
        NotificationType type,
        NotificationChannelType channel,
        NotificationStatus status,
        int attempts,
        Map<String, Object> referenceData,
        Instant nextAttemptAt,
        Instant updatedAt,
        String lastFailureReason,
        boolean read,
        Instant readAt) {

    /**
     * 도메인 엔티티를 응답 DTO로 변환.
     */
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getExternalId(), n.getRecipientId(), n.getType(), n.getChannel(),
                n.getStatus(), n.getAttempts(), n.getReferenceData(),
                n.getNextAttemptAt(), n.getUpdatedAt(),
                n.getLastFailureReason(), n.isRead(), n.getReadAt());
    }
}
