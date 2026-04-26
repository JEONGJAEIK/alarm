package com.example.alarm.api;

import com.example.alarm.api.dto.AdminDeadLetterListItem;
import com.example.alarm.api.dto.AdminDeadLetterResponse;
import com.example.alarm.service.DeadLetterService;
import com.example.alarm.service.DeadLetterService.DeadLetterView;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

/**
 * 관리자용 DLQ 목록 + 단건 + 수동 재시도 엔드포인트.
 *
 * <p>{@code /api/admin/**} 경로는 {@code AdminHeaderInterceptor}가 가로채
 * {@code X-Admin: true} 헤더를 검증한다. retry 엔드포인트는 추가로 {@code X-Admin-Id}
 * 헤더로 명령자 식별자를 받아 {@code last_revived_by}에 기록한다.
 */
@RestController
@RequestMapping("/api/admin/dead-letters")
@RequiredArgsConstructor
public class DeadLetterController {

    private final DeadLetterService service;

    @GetMapping
    public Page<AdminDeadLetterListItem> list(@PageableDefault(size = 50) Pageable pageable) {
        return service.list(pageable)
                .map(v -> AdminDeadLetterListItem.from(v.notification(), v.deadLetter()));
    }

    @GetMapping("/{externalId}")
    public AdminDeadLetterResponse get(@PathVariable String externalId) {
        DeadLetterView v = service.findOne(externalId);
        return AdminDeadLetterResponse.from(v.notification(), v.deadLetter());
    }

    @PostMapping("/{externalId}/retry")
    public AdminDeadLetterResponse retry(@PathVariable String externalId,
                                         @RequestHeader("X-Admin-Id") String adminId) {
        if (adminId == null || adminId.isBlank()) {
            throw new IllegalArgumentException("X-Admin-Id 헤더가 비어 있습니다");
        }
        DeadLetterView v = service.revive(externalId, adminId);
        return AdminDeadLetterResponse.from(v.notification(), v.deadLetter());
    }
}
