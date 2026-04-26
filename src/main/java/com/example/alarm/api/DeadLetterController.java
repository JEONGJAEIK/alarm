package com.example.alarm.api;

import com.example.alarm.api.dto.NotificationListItem;
import com.example.alarm.api.dto.NotificationResponse;
import com.example.alarm.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

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
     * 데드레터 알림 목록을 최근 갱신 순으로 페이지 반환.
     *
     * <p>페이지네이션은 Spring Data {@link Pageable} 표준({@code page}, {@code size} 쿼리 파라미터)을 따른다.
     * 기본값 {@code page=0, size=50}이며, 정렬은 Repository 메서드명({@code OrderByUpdatedAtDesc})으로 강제된다.
     * 응답은 {@link Page} 타입으로 직렬화되어 {@code content}, {@code totalElements}, {@code totalPages},
     * {@code number}, {@code size} 등의 페이지 메타가 함께 포함된다.
     */
    @GetMapping
    public Page<NotificationListItem> list(@PageableDefault(size = 50) Pageable pageable) {
        return service.listDeadLetter(pageable).map(NotificationListItem::from);
    }

    /**
     * 데드레터를 PENDING으로 되살린다. 재시도 카운트는 0으로 초기화.
     */
    @PostMapping("/{id}/retry")
    public NotificationResponse retry(@PathVariable String id) {
        return NotificationResponse.from(service.retryDeadLetter(id));
    }
}
