package com.anniversary.video.controller;

import com.anniversary.video.domain.Order;
import com.anniversary.video.dto.AdminOrderResponse;
import com.anniversary.video.repository.OrderRepository;
import com.anniversary.video.service.OrderService;
import com.anniversary.video.service.S3Service;
import com.anniversary.video.service.PaymentService;
import com.anniversary.video.service.VideoGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;   // 직접 save 호출
    private final PaymentService paymentService;
    private final VideoGenerationService videoGenerationService;
    private final S3Service s3Service;

    /** 전체 주문 목록 (상태 필터 지원) */
    @GetMapping("/orders")
    public ResponseEntity<List<AdminOrderResponse>> getAllOrders(
            @RequestParam(required = false) String status) {

        List<Order> all = orderService.findAll();
        if (status != null && !status.isBlank()) {
            Order.OrderStatus filterStatus = Order.OrderStatus.valueOf(status.toUpperCase());
            all = all.stream().filter(o -> o.getStatus() == filterStatus).collect(Collectors.toList());
        }
        return ResponseEntity.ok(all.stream().map(AdminOrderResponse::from).collect(Collectors.toList()));
    }

    /** 주문 상세 */
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<AdminOrderResponse> getOrder(@PathVariable Long orderId) {
        return ResponseEntity.ok(AdminOrderResponse.from(orderService.findById(orderId)));
    }

    /**
     * 상태 수동 변경 — ✅ save() 추가 (버그 수정)
     * body: { "status": "COMPLETED", "downloadUrl": "..." }
     */
    @PutMapping("/orders/{orderId}/status")
    public ResponseEntity<Map<String, Object>> updateStatus(
            @PathVariable Long orderId,
            @RequestBody Map<String, String> body) {

        Order order = orderService.findById(orderId);
        Order.OrderStatus newStatus = Order.OrderStatus.valueOf(body.get("status"));
        order.updateStatus(newStatus);

        // COMPLETED 수동 처리 시 다운로드 URL 직접 입력 가능
        if (body.containsKey("downloadUrl")) {
            order.setDownloadUrl(body.get("downloadUrl"));
            order.setDownloadExpiresAt(LocalDateTime.now().plusHours(72));
        }
        // COMPLETED + S3 결과 파일 있으면 자동 URL 재발급
        if (newStatus == Order.OrderStatus.COMPLETED
                && order.getS3OutputPath() != null
                && order.getDownloadUrl() == null) {
            order.setDownloadUrl(s3Service.generateDownloadUrl(order.getS3OutputPath()));
            order.setDownloadExpiresAt(LocalDateTime.now().plusHours(72));
        }

        orderRepository.save(order);  // ✅ 저장
        log.info("관리자 상태 변경 - orderId: {}, status: {}", orderId, newStatus);

        return ResponseEntity.ok(Map.of(
                "result", "ok",
                "status", newStatus.name(),
                "downloadUrl", order.getDownloadUrl() != null ? order.getDownloadUrl() : ""
        ));
    }

    /**
     * 메모 저장 — ✅ save() 추가 (버그 수정)
     */
    @PutMapping("/orders/{orderId}/memo")
    public ResponseEntity<Map<String, String>> updateMemo(
            @PathVariable Long orderId,
            @RequestBody Map<String, String> body) {

        Order order = orderService.findById(orderId);
        order.setAdminMemo(body.get("memo"));
        orderRepository.save(order);  // ✅ 저장
        log.info("메모 저장 - orderId: {}", orderId);
        return ResponseEntity.ok(Map.of("result", "ok"));
    }

    /**
     * 영상 재생성 — ✅ save() + retryCount 증가 (버그 수정)
     */
    @PostMapping("/orders/{orderId}/regenerate")
    public ResponseEntity<Map<String, String>> regenerate(@PathVariable Long orderId) {
        Order order = orderService.findById(orderId);
        order.setRetryCount(order.getRetryCount() + 1);
        order.updateStatus(Order.OrderStatus.PAID);
        orderRepository.save(order);  // ✅ 저장
        videoGenerationService.startVideoGeneration(orderId);
        log.info("관리자 재생성 - orderId: {}, retry: {}", orderId, order.getRetryCount());
        return ResponseEntity.ok(Map.of("result", "ok", "retryCount", String.valueOf(order.getRetryCount())));
    }

    /** 다운로드 URL 재발급 */
    @PostMapping("/orders/{orderId}/refresh-url")
    public ResponseEntity<Map<String, String>> refreshUrl(@PathVariable Long orderId) {
        String url = orderService.refreshDownloadUrl(orderId);
        return ResponseEntity.ok(Map.of("downloadUrl", url));
    }

    /** 대시보드 통계 */
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> dashboard() {
        List<Order> all = orderService.findAll();
        LocalDateTime todayStart = LocalDateTime.now().toLocalDate().atStartOfDay();

        long pending    = all.stream().filter(o -> o.getStatus() == Order.OrderStatus.PENDING).count();
        long paid       = all.stream().filter(o -> o.getStatus() == Order.OrderStatus.PAID).count();
        long processing = all.stream().filter(o -> o.getStatus() == Order.OrderStatus.PROCESSING).count();
        long completed  = all.stream().filter(o -> o.getStatus() == Order.OrderStatus.COMPLETED).count();
        long failed     = all.stream().filter(o -> o.getStatus() == Order.OrderStatus.FAILED).count();
        long todayOrders= all.stream().filter(o -> o.getCreatedAt() != null && o.getCreatedAt().isAfter(todayStart)).count();
        long revenue    = completed * 29900L;
        long todayRevenue = all.stream()
                .filter(o -> o.getStatus() == Order.OrderStatus.COMPLETED
                        && o.getUpdatedAt() != null && o.getUpdatedAt().isAfter(todayStart))
                .count() * 29900L;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", all.size());
        result.put("pending", pending);
        result.put("paid", paid);
        result.put("processing", processing);
        result.put("completed", completed);
        result.put("failed", failed);
        result.put("revenue", revenue);
        result.put("todayOrders", todayOrders);
        result.put("todayRevenue", todayRevenue);
        return ResponseEntity.ok(result);
    }

    /** 결제 취소/환불 */
    @PutMapping("/orders/{orderId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelOrder(
            @PathVariable Long orderId,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("cancelReason", "관리자 취소") : "관리자 취소";
        Map<String, Object> result = paymentService.cancelPayment(orderId, reason);
        log.info("관리자 취소/환불 - orderId: {}, reason: {}", orderId, reason);
        return ResponseEntity.ok(result);
    }
}