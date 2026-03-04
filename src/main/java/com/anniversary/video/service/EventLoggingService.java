package com.anniversary.video.service;

import com.anniversary.video.domain.OrderEvent;
import com.anniversary.video.repository.OrderEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 비동기 이벤트 로깅 전담 서비스.
 * 다른 서비스에서 1줄로 호출: eventLoggingService.log(orderId, "gen_start", null)
 * 실패해도 비즈니스 로직에 영향 없도록 @Async + try-catch.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventLoggingService {

    private final OrderEventRepository orderEventRepository;

    // ── 서버 이벤트 ───────────────────────────────────────────────────────

    @Async("eventLogExecutor")
    public void log(Long orderId, String eventType, String payload) {
        try {
            orderEventRepository.save(OrderEvent.builder()
                    .orderId(orderId)
                    .eventType(eventType)
                    .source("server")
                    .payload(payload)
                    .build());
            log.debug("이벤트 기록 - orderId: {}, type: {}", orderId, eventType);
        } catch (Exception e) {
            log.warn("이벤트 기록 실패 - orderId: {}, type: {}, error: {}",
                    orderId, eventType, e.getMessage());
        }
    }

    // ── 프론트 비콘 이벤트 ─────────────────────────────────────────────────

    @Async("eventLogExecutor")
    public void logFrontEvent(Long orderId, String eventType, String payload) {
        try {
            orderEventRepository.save(OrderEvent.builder()
                    .orderId(orderId)
                    .eventType(eventType)
                    .source("front")
                    .payload(payload)
                    .build());
            log.debug("프론트 이벤트 기록 - orderId: {}, type: {}", orderId, eventType);
        } catch (Exception e) {
            log.warn("프론트 이벤트 기록 실패 - orderId: {}, type: {}, error: {}",
                    orderId, eventType, e.getMessage());
        }
    }
}
