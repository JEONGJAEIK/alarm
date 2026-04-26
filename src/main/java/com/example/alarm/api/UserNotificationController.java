package com.example.alarm.api;

import com.example.alarm.api.dto.NotificationListItem;
import com.example.alarm.service.ForbiddenException;
import com.example.alarm.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 수신자별 알림 목록을 반환하는 엔드포인트.
 *
 * <p>요청자({@code X-User-Id})가 path의 {@code userId}와 일치할 때만 허용한다.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/users/{userId}/notifications")
public class UserNotificationController {

    private final NotificationService service;

    /**
     * 사용자 알림 목록 조회. {@code read} 파라미터로 읽음 여부 필터 가능.
     *
     * @param userId 조회 대상 수신자
     * @param caller {@code X-User-Id} 헤더의 호출자 (본인 검증)
     * @param read true/false 시 해당 읽음 상태로 필터, 생략 시 전체
     * @param limit 1~200 사이 (서비스 레이어에서 클램프), 기본 50
     * @return 알림 목록 (가장 최근 등록 순)
     * @throws ForbiddenException 호출자가 path의 userId와 다를 때
     */
    @GetMapping
    public List<NotificationListItem> list(@PathVariable String userId,
                                           @RequestHeader("X-User-Id") String caller,
                                           @RequestParam(required = false) Boolean read,
                                           @RequestParam(defaultValue = "50") int limit) {
        if (!userId.equals(caller)) {
            throw new ForbiddenException("본인의 알림만 조회할 수 있습니다");
        }
        var notifications = service.listForRecipient(userId, read, limit);
        var body = notifications.stream()
                .map(NotificationListItem::from)
                .toList();
        return body;
    }
}
