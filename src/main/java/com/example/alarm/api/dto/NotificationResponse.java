package com.example.alarm.api.dto;

import com.example.alarm.domain.Notification;
import com.example.alarm.domain.NotificationChannelType;
import com.example.alarm.domain.NotificationStatus;
import com.example.alarm.domain.NotificationType;

import java.time.Instant;
import java.util.Map;

/**
 * 알림 단건 응답 DTO. {@code POST}, {@code GET /{id}}, {@code PATCH /{id}/read} 등에서 사용.
 *
 * <p>실패 상세 사유는 사용자에게 노출하지 않으므로 본 DTO에는 포함되지 않는다.
 * 운영자용 상세 사유는 별도 관리자 DTO에서만 노출된다.
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
                n.isRead(), n.getReadAt());
    }
}
