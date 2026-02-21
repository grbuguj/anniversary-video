package com.anniversary.video.controller;

import com.anniversary.video.dto.PaymentConfirmRequest;
import com.anniversary.video.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;

    /** 결제 검증 (포트원 V2 paymentId 기반) */
    @PostMapping("/confirm")
    public ResponseEntity<Map<String, Object>> confirmPayment(
            @Valid @RequestBody PaymentConfirmRequest request) {
        Map<String, Object> result = paymentService.confirmPayment(
                request.getPaymentId(), request.getOrderId(), request.getAmount());
        return ResponseEntity.ok(result);
    }

    /** 포트원 웹훅 수신 */
    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(@RequestBody Map<String, Object> payload) {
        log.info("포트원 웹훅 수신 - type: {}", payload.get("type"));
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
