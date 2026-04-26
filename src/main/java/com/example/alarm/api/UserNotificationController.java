package com.example.alarm.api;

import com.example.alarm.api.dto.NotificationListItem;
import com.example.alarm.service.ForbiddenException;
import com.example.alarm.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 수신자별 알림 목록을 반환하는 엔드포인트.
 *
 * <p>요청자({@code X-User-Id})가 path의 {@code userId}와 일치할 때만 허용한다.
 * 본인 검증은 service에서 수행되어 다른 진입점(이벤트 리스너 등) 호출 시도 자동 보호된다.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/users/{userId}/notifications")
public class UserNotificationController {

    private final NotificationService service;

    /**
     * 사용자 알림 목록 조회. {@code read} 파라미터로 읽음 여부 필터 가능.
     *
     * <p>페이지네이션은 Spring Data {@link Pageable} 표준({@code page}, {@code size} 쿼리 파라미터)을 따른다.
     * 기본값 {@code page=0, size=50}이며, 정렬은 Repository 메서드명으로 {@code createdAt DESC} 강제된다.
     *
     * @param userId   조회 대상 수신자
     * @param caller   {@code X-User-Id} 헤더의 호출자 (service에서 본인 검증)
     * @param read     true/false 시 해당 읽음 상태로 필터, 생략 시 전체
     * @param pageable 페이지 정보 (size는 서비스 레이어에서 1~200으로 클램프)
     * @return 알림 목록 (가장 최근 등록 순)
     * @throws ForbiddenException 호출자가 path의 userId와 다를 때 (service에서 throw)
     */
    @GetMapping
    public List<NotificationListItem> list(@PathVariable String userId,
                                           @RequestHeader("X-User-Id") String caller,
                                           @RequestParam(required = false) Boolean read,
                                           @PageableDefault(size = 50) Pageable pageable) {
        return service.listForRecipient(userId, caller, read, pageable).stream()
                .map(NotificationListItem::from)
                .toList();
    }
}
