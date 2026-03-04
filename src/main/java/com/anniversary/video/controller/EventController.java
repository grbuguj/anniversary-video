package com.anniversary.video.controller;

import com.anniversary.video.service.EventLoggingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 프론트엔드 비콘 이벤트 수신 엔드포인트.
 * page_view, pay_start, upload_start, download_click 등
 * 프론트에서 발생하는 사용자 행동을 order_events에 기록.
 *
 * Request body:
 * {
 *   "orderId": 123,        // nullable (page_view 등)
 *   "eventType": "page_view",
 *   "payload": "{...}"     // optional, 자유 형식 JSON 문자열
 * }
 */
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
@Slf4j
public class EventController {

    private final EventLoggingService eventLoggingService;

    @PostMapping
    public ResponseEntity<Map<String, String>> receiveEvent(@RequestBody Map<String, Object> body) {
        Long orderId = body.get("orderId") != null
                ? ((Number) body.get("orderId")).longValue()
                : null;
        String eventType = (String) body.get("eventType");
        String payload   = body.get("payload") != null ? body.get("payload").toString() : null;

        if (eventType == null || eventType.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "eventType은 필수입니다."));
        }

        eventLoggingService.logFrontEvent(orderId, eventType, payload);
        return ResponseEntity.ok(Map.of("result", "ok"));
    }
}
