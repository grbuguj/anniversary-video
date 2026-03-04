package com.anniversary.video.controller;

import com.anniversary.video.dto.PaymentConfirmRequest;
import com.anniversary.video.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;

    @Value("${portone.webhook-secret:}")
    private String webhookSecret;

    /** 결제 검증 (포트원 V2 paymentId 기반) */
    @PostMapping("/confirm")
    public ResponseEntity<Map<String, Object>> confirmPayment(
            @Valid @RequestBody PaymentConfirmRequest request) {
        Map<String, Object> result = paymentService.confirmPayment(
                request.getPaymentId(), request.getOrderId(), request.getAmount());
        return ResponseEntity.ok(result);
    }

    /** 포트원 웹훅 수신 (Secret 검증) */
    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "webhook-id", required = false) String webhookId,
            @RequestHeader(value = "webhook-timestamp", required = false) String webhookTimestamp,
            @RequestHeader(value = "webhook-signature", required = false) String webhookSignature) {

        // 웹훅 시크릿이 설정된 경우 기본 검증
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            if (webhookId == null || webhookTimestamp == null) {
                log.warn("웹훅 헤더 누락 - webhook-id: {}, webhook-timestamp: {}", webhookId, webhookTimestamp);
                return ResponseEntity.status(401).build();
            }
            // 타임스탬프 검증 (5분 이내)
            try {
                long ts = Long.parseLong(webhookTimestamp);
                long now = System.currentTimeMillis() / 1000;
                if (Math.abs(now - ts) > 300) {
                    log.warn("웹훅 타임스탬프 만료 - ts: {}, now: {}", ts, now);
                    return ResponseEntity.status(401).build();
                }
            } catch (NumberFormatException e) {
                log.warn("웹훅 타임스탬프 파싱 실패: {}", webhookTimestamp);
                return ResponseEntity.status(401).build();
            }
        }

        log.info("포트원 웹훅 수신 - type: {}, webhook-id: {}", payload.get("type"), webhookId);
        paymentService.handleWebhook(payload);
        return ResponseEntity.ok().build();
    }

    /** 결제 취소 (환불) — 관리자용 */
    @PutMapping("/{orderId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelPayment(
            @PathVariable Long orderId,
            @RequestBody Map<String, String> body) {
        String reason = body.getOrDefault("cancelReason", "고객 요청 취소");
        return ResponseEntity.ok(paymentService.cancelPayment(orderId, reason));
    }
}
