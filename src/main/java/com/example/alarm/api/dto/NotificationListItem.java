package com.example.alarm.api.dto;

import com.example.alarm.domain.Notification;
import com.example.alarm.domain.NotificationChannelType;
import com.example.alarm.domain.NotificationStatus;
import com.example.alarm.domain.NotificationType;

import java.time.Instant;

/**
 * 사용자/관리자 알림 목록의 개별 항목 DTO. 목록 응답에서 referenceData 같은 무거운 필드는 제외.
 */
public record NotificationListItem(
        String id,
        NotificationType type,
        NotificationChannelType channel,
        NotificationStatus status,
        boolean read,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 도메인 엔티티를 목록 항목 DTO로 변환.
     */
    public static NotificationListItem from(Notification n) {
        return new NotificationListItem(
                n.getId(), n.getType(), n.getChannel(), n.getStatus(),
                n.isRead(), n.getCreatedAt(), n.getUpdatedAt());
    }
}
