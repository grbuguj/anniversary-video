package com.anniversary.video.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ── 입력값 검증 실패 (400) ─────────────────────────────────────────────
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid",
                        (a, b) -> a  // 중복 필드 시 첫 번째 메시지 유지
                ));
        return errorResponse(HttpStatus.BAD_REQUEST, "입력값을 확인해주세요", errors);
    }

    // ── 비즈니스 로직: 잘못된 요청 (400) ──────────────────────────────────
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException e) {
        log.warn("요청 오류: {}", e.getMessage());
        return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage(), null);
    }

    // ── 비즈니스 로직: 상태 충돌 (409) ────────────────────────────────────
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        log.warn("상태 오류: {}", e.getMessage());
        return errorResponse(HttpStatus.CONFLICT, e.getMessage(), null);
    }

    // ── 외부 API 오류: 포트원, RunwayML 등 (502) ─────────────────────────
    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<Map<String, Object>> handleWebClient(WebClientResponseException e) {
        log.error("외부 API 오류 - status: {}, body: {}",
                e.getStatusCode(), e.getResponseBodyAsString());
        return errorResponse(HttpStatus.BAD_GATEWAY,
                "외부 서비스 연동 중 오류가 발생했습니다", null);
    }

    // ── 숫자 파싱 등 (400) ────────────────────────────────────────────────
    @ExceptionHandler(NumberFormatException.class)
    public ResponseEntity<Map<String, Object>> handleNumberFormat(NumberFormatException e) {
        log.warn("숫자 파싱 오류: {}", e.getMessage());
        return errorResponse(HttpStatus.BAD_REQUEST, "올바른 숫자 형식이 아닙니다", null);
    }

    // ── 그 외 서버 오류 (500) — 최후의 방어선 ─────────────────────────────
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception e) {
        log.error("서버 오류: {}", e.getMessage(), e);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "처리 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.", null);
    }

    private ResponseEntity<Map<String, Object>> errorResponse(
            HttpStatus status, String message, Object detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("message", message);
        if (detail != null) body.put("detail", detail);
        body.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.status(status).body(body);
    }
}
