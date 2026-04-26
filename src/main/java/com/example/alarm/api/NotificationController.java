package com.example.alarm.api;

import com.example.alarm.api.dto.CreateNotificationRequest;
import com.example.alarm.api.dto.NotificationResponse;
import com.example.alarm.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 알림 등록(POST) 및 단건 상태 조회(GET) 엔드포인트.
 *
 * <p>모든 요청은 {@code X-User-Id} 헤더 필수. GET은 본인 또는 {@code X-Admin: true}일 때만 허용.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    /**
     * 알림 등록 요청을 받아 outbox에 PENDING 행을 생성한다.
     *
     * <p>{@code caller}는 {@code X-User-Id} 헤더 인증 게이트 역할만 한다
     * (실제 수신자는 요청 body의 {@code recipientId}이며, 호출자는 보통 시스템 서비스).
     *
     * @return 202 Accepted + 생성/기존 알림 응답 DTO
     */
    @PostMapping
    @SuppressWarnings("unused") // caller는 인증 게이트 전용 — AuthInterceptor가 401 강제
    public ResponseEntity<NotificationResponse> create(@RequestHeader("X-User-Id") String caller,
                                                       @RequestBody @Valid CreateNotificationRequest req) {
        var n = service.register(req.toCommand());
        var body = NotificationResponse.from(n);
        return ResponseEntity.accepted().body(body);
    }

    /**
     * 알림 단건 상태 조회. 본인 또는 관리자({@code X-Admin: true})만 허용.
     *
     * @param id          조회할 알림 ID
     * @param caller      인증된 사용자 ID ({@code X-User-Id} 헤더)
     * @param adminHeader X-Admin 헤더 값 (기본값 "false")
     * @return 알림 응답 DTO
     */
    @GetMapping("/{id}")
    public NotificationResponse getStatus(@PathVariable String id,
                                          @RequestHeader("X-User-Id") String caller,
                                          @RequestHeader(name = "X-Admin", required = false, defaultValue = "false") String adminHeader) {
        boolean isAdmin = "true".equalsIgnoreCase(adminHeader);
        var n = service.findById(id, caller, isAdmin);
        var body = NotificationResponse.from(n);
        return body;
    }

    /**
     * 알림 읽음 처리. 본인만 호출 가능.
     *
     * @param id     읽음 처리할 알림 ID
     * @param caller 인증된 사용자 ID ({@code X-User-Id} 헤더)
     * @return 200 OK + 읽음 처리된 알림 응답
     */
    @PatchMapping("/{id}/read")
    public NotificationResponse markRead(@PathVariable String id,
                                         @RequestHeader("X-User-Id") String caller) {
        var n = service.markReadByOwner(id, caller);
        var body = NotificationResponse.from(n);
        return body;
    }
}
