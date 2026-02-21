package com.anniversary.video.service;

import com.anniversary.video.domain.Order;
import com.anniversary.video.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final NotificationService notificationService;

    @Value("${portone.api-secret}")
    private String portoneApiSecret;

    private static final String PORTONE_BASE_URL = "https://api.portone.io";

    // ── 결제 검증 (포트원 V2) ─────────────────────────────────────────────
    public Map<String, Object> confirmPayment(String paymentId, String orderId, int amount) {
        Long orderIdLong = Long.parseLong(orderId);
        Order order = orderService.findById(orderIdLong);

        // 이미 처리된 주문 방지
        if (order.getStatus() != Order.OrderStatus.PENDING) {
            throw new IllegalStateException("이미 처리된 주문입니다: " + orderId);
        }

        // 포트원 V2 결제 조회 API로 검증
        Map paymentData;
        try {
            paymentData = buildPortoneClient()
                    .get()
                    .uri("/payments/" + paymentId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("포트원 결제 조회 실패 - status: {}, body: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("결제 정보를 확인할 수 없습니다.");
        }

        // 결제 상태 확인
        String status = (String) paymentData.get("status");
        if (!"PAID".equals(status)) {
            throw new IllegalStateException("결제가 완료되지 않았습니다. 상태: " + status);
        }

        // 금액 위변조 검증
        Map totalAmount = (Map) paymentData.get("amount");
        int paidAmount = ((Number) totalAmount.get("paid")).intValue();
        if (order.getAmount() == null || order.getAmount() != paidAmount) {
            log.error("금액 불일치 - 예상: {}, 실제: {}", order.getAmount(), paidAmount);
            throw new IllegalArgumentException("결제 금액이 일치하지 않습니다.");
        }

        log.info("✅ 포트원 결제 검증 완료 - orderId: {}, paymentId: {}", orderId, paymentId);

        // PAID 처리 — 영상 생성은 사진 업로드 완료 후
        Order paidOrder = orderService.markAsPaid(orderIdLong, paymentId);
        notificationService.sendOrderConfirmation(paidOrder);

        return Map.of("result", "ok", "orderId", orderIdLong, "status", "PAID");
    }

    // ── 결제 취소 (환불) ──────────────────────────────────────────────────
    @Transactional
    public Map<String, Object> cancelPayment(Long orderId, String cancelReason) {
        Order order = orderService.findById(orderId);

        if (order.getStatus() == Order.OrderStatus.COMPLETED) {
            throw new IllegalStateException("영상 제작 완료된 주문은 취소할 수 없습니다.");
        }
        if (order.getStatus() == Order.OrderStatus.FAILED) {
            throw new IllegalStateException("이미 실패 처리된 주문입니다.");
        }
        if (order.getPaymentKey() == null) {
            // 결제 전 (PENDING) → 주문만 취소
            order.updateStatus(Order.OrderStatus.FAILED);
            order.setAdminMemo("[취소] " + cancelReason);
            orderRepository.save(order);
            return Map.of("status", "CANCELLED", "message", "주문이 취소되었습니다.");
        }

        // 포트원 V2 취소 API 호출
        try {
            buildPortoneClient()
                    .post()
                    .uri("/payments/" + order.getPaymentKey() + "/cancel")
                    .bodyValue(Map.of("reason", cancelReason))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("포트원 취소 실패 - orderId: {}, body: {}", orderId, e.getResponseBodyAsString());
            throw new RuntimeException("결제 취소 실패: " + e.getResponseBodyAsString());
        }

        order.updateStatus(Order.OrderStatus.FAILED);
        order.setAdminMemo("[취소/환불] " + cancelReason);
        orderRepository.save(order);

        log.info("✅ 결제 취소 완료 - orderId: {}", orderId);
        return Map.of("result", "ok", "message", "환불 처리되었습니다.");
    }

    // ── 포트원 웹훅 처리 ──────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    public void handleWebhook(Map<String, Object> payload) {
        String type      = (String) payload.get("type");
        String paymentId = (String) payload.get("paymentId");
        log.info("포트원 웹훅 - type: {}, paymentId: {}", type, paymentId);

        if (!"Transaction.Paid".equals(type) || paymentId == null) return;

        // 주문 찾기 (paymentKey = portone paymentId)
        orderRepository.findByPaymentKey(paymentId).ifPresent(order -> {
            if (order.getStatus() == Order.OrderStatus.PENDING) {
                Order paid = orderService.markAsPaid(order.getId(), paymentId);
                notificationService.sendOrderConfirmation(paid);
                log.info("웹훅 결제 완료 처리 - orderId: {}", order.getId());
            }
        });
    }

    private WebClient buildPortoneClient() {
        return WebClient.builder()
                .baseUrl(PORTONE_BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "PortOne " + portoneApiSecret)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
