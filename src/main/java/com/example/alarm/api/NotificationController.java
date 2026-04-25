package com.example.alarm.api;

import com.example.alarm.api.auth.AdminHeaderInterceptor;
import com.example.alarm.api.auth.CurrentUser;
import com.example.alarm.api.dto.CreateNotificationRequest;
import com.example.alarm.api.dto.NotificationResponse;
import com.example.alarm.service.NotificationService;
import com.example.alarm.service.NotificationService.RegisterCommand;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 알림 등록(POST) 및 단건 상태 조회(GET) 엔드포인트.
 *
 * <p>모든 요청은 {@code X-User-Id} 헤더 필수. GET은 본인 또는 {@code X-Admin: true}일 때만 허용.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    /**
     * 생성자 주입.
     *
     * @param service 알림 비즈니스 로직 서비스
     */
    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /**
     * 알림 등록 요청을 받아 outbox에 PENDING 행을 생성한다.
     *
     * @param caller 인증된 사용자 ID ({@code X-User-Id} 헤더에서 주입)
     * @param req    알림 등록 요청 DTO
     * @return 202 Accepted + 생성/기존 알림 응답 DTO
     */
    @PostMapping
    public ResponseEntity<NotificationResponse> create(@CurrentUser String caller,
                                                       @RequestBody @Valid CreateNotificationRequest req) {
        var n = service.register(new RegisterCommand(
                req.recipientId(), req.type(), req.channel(),
                req.eventId(), req.referenceData(), req.scheduledAt()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(NotificationResponse.from(n));
    }

    /**
     * 알림 단건 상태 조회. 본인 또는 관리자({@code X-Admin: true})만 허용.
     *
     * @param id     조회할 알림 ID
     * @param caller 인증된 사용자 ID ({@code X-User-Id} 헤더에서 주입)
     * @param req    HTTP 요청 (X-Admin 헤더 확인용)
     * @return 알림 응답 DTO
     */
    @GetMapping("/{id}")
    public NotificationResponse getStatus(@PathVariable String id,
                                          @CurrentUser String caller,
                                          HttpServletRequest req) {
        boolean isAdmin = "true".equalsIgnoreCase(req.getHeader(AdminHeaderInterceptor.HEADER));
        return NotificationResponse.from(service.findById(id, caller, isAdmin));
    }
}
