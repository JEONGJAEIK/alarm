package com.example.alarm.api.dto;

import com.example.alarm.domain.NotificationChannelType;
import com.example.alarm.domain.NotificationType;
import com.example.alarm.service.NotificationService.RegisterCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;

/**
 * 알림 등록 요청 DTO. {@code POST /api/notifications}의 body로 사용.
 *
 * @param recipientId 수신자 식별자 (필수)
 * @param type 알림 타입 (필수)
 * @param channel 발송 채널 (필수)
 * @param eventId 멱등 키 구성용 이벤트 식별자 (필수)
 * @param referenceData 알림과 함께 보존할 자유 JSON 맵 (선택)
 * @param scheduledAt 미래 시각이면 예약 발송 (선택)
 */
public record CreateNotificationRequest(
        @NotBlank String recipientId,
        @NotNull NotificationType type,
        @NotNull NotificationChannelType channel,
        @NotBlank String eventId,
        Map<String, Object> referenceData,
        Instant scheduledAt) {

    /**
     * 서비스 계층의 {@link RegisterCommand}로 변환. 필드 매핑이 한 곳에 응집되어
     * 인자 순서·필드 변경 시 컨트롤러를 건드리지 않는다.
     */
    public RegisterCommand toCommand() {
        return new RegisterCommand(recipientId, type, channel, eventId, referenceData, scheduledAt);
    }
}
