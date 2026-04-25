package com.example.alarm.api;

import com.example.alarm.service.ForbiddenException;
import com.example.alarm.service.NotificationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
 *   <li>인증 헤더 누락 → 401 ({@link ResponseStatusException} 경유)</li>
 *   <li>본인 아닌 알림 접근 → 403 {@code forbidden}</li>
 *   <li>알림 없음 → 404 {@code not_found}</li>
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
