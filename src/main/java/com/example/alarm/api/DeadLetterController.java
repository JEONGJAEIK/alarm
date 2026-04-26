package com.example.alarm.api;

import com.example.alarm.api.dto.NotificationListItem;
import com.example.alarm.api.dto.NotificationResponse;
import com.example.alarm.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 관리자용 데드레터 목록 + 수동 재시도 엔드포인트.
 *
 * <p>{@code /api/admin/**} 경로는 {@code AdminHeaderInterceptor}가 가로채
 * {@code X-Admin: true} 헤더를 검증한다.
 */
@RestController
@RequestMapping("/api/admin/dead-letters")
@RequiredArgsConstructor
public class DeadLetterController {

    private final NotificationService service;

    /**
     * 데드레터 알림 목록을 최근 갱신 순으로 반환.
     *
     * @param limit 1~200 사이 (서비스 레이어에서 클램프), 기본 50
     */
    @GetMapping
    public List<NotificationListItem> list(@RequestParam(defaultValue = "50") int limit) {
        var notifications = service.listDeadLetter(limit);
        var body = notifications.stream()
                .map(NotificationListItem::from)
                .toList();
        return body;
    }

    /**
     * 데드레터를 PENDING으로 되살린다. 재시도 카운트는 0으로 초기화.
     */
    @PostMapping("/{id}/retry")
    public NotificationResponse retry(@PathVariable String id) {
        var n = service.retryDeadLetter(id);
        var body = NotificationResponse.from(n);
        return body;
    }
}
