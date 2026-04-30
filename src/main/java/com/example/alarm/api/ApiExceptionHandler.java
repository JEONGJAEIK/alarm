package com.example.alarm.api;

import com.example.alarm.service.DuplicateNotificationException;
import com.example.alarm.service.ForbiddenException;
import com.example.alarm.service.NotificationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 컨트롤러 계층의 예외를 일관된 JSON 에러 응답으로 변환.
 *
 * <p>응답 본문은 {@code {"error": "<code>", "message": "<msg>"}} 형식이며,
 * 검증 실패 시 {@code fields} 키로 필드별 메시지를 추가한다.
 *
 * <p>매핑:
 * <ul>
 *   <li>검증 실패 → 400 {@code validation_failed}</li>
 *   <li>필수 헤더 누락 → 400 {@code missing_header}</li>
 *   <li>인증 헤더 누락 → 401 ({@link ResponseStatusException} 경유)</li>
 *   <li>본인 아닌 알림 접근 → 403 {@code forbidden}</li>
 *   <li>알림 없음 → 404 {@code not_found}</li>
 *   <li>리소스 상태 충돌 → 409 {@code conflict}</li>
 *   <li>그 외 → 500 {@code internal_error}</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 알림 미존재 예외를 404로 변환.
     */
    @ExceptionHandler(NotificationNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotificationNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error("not_found", ex.getMessage()));
    }

    /**
     * 중복 알림 등록 시도를 409 Conflict로 변환.
     *
     * <p>동일 {@code (eventId, channel)} 조합이 이미 등록된 상태로, dedup_key UNIQUE
     * 제약이 멱등성을 보장한 결과다.
     */
    @ExceptionHandler(DuplicateNotificationException.class)
    public ResponseEntity<Map<String, Object>> duplicate(DuplicateNotificationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error("duplicate", ex.getMessage()));
    }

    /**
     * 권한 없음 예외를 403으로 변환.
     */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> forbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(error("forbidden", ex.getMessage()));
    }

    /**
     * Spring {@link ResponseStatusException}을 상태 코드 그대로 반환.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> responseStatus(ResponseStatusException ex) {
        String message = ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();
        return ResponseEntity.status(ex.getStatusCode())
                .body(error(ex.getStatusCode().toString(), message));
    }

    /**
     * Bean Validation 실패를 400으로 변환. 필드별 오류 목록을 포함.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> badRequest(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(f ->
                fields.put(f.getField(), f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage()));
        Map<String, Object> body = error("validation_failed", "검증 실패");
        body.put("fields", fields);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 잘못된 인자 예외를 400으로 변환.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegalArg(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(error("bad_request", ex.getMessage()));
    }

    /**
     * 도메인 invariant 위반 또는 리소스 상태 충돌을 409로 변환.
     *
     * <p>예: DEAD_LETTER 상태가 아닌 알림에 revive 시도, 이미 종결된 알림 상태 전이 시도 등
     * 운영자 입력에 의한 상태 충돌이므로 5xx가 아닌 4xx로 분류.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> illegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error("conflict", ex.getMessage()));
    }

    /**
     * 필수 요청 헤더 누락을 400으로 변환.
     *
     * <p>예: 운영자 API 호출 시 {@code X-Admin-Id} 헤더 누락. 클라이언트 입력 오류이므로
     * 명시적 400으로 응답해 5xx로 잘못 분류되는 것을 방지한다.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> missingHeader(MissingRequestHeaderException ex) {
        String message = "필수 헤더 누락: " + ex.getHeaderName();
        return ResponseEntity.badRequest().body(error("missing_header", message));
    }

    /**
     * 처리되지 않은 예외를 500으로 변환. 예외 클래스명만 노출.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> internal(Exception ex) {
        return ResponseEntity.internalServerError()
                .body(error("internal_error", ex.getClass().getSimpleName()));
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", code);
        m.put("message", message);
        return m;
    }
}
