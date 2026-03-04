package com.anniversary.video.controller;

import com.anniversary.video.domain.Order;
import com.anniversary.video.dto.OrderCreateRequest;
import com.anniversary.video.dto.OrderCreateResponse;
import com.anniversary.video.service.OrderService;
import com.anniversary.video.service.S3Service;
import com.anniversary.video.service.VideoGenerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;
    private final S3Service s3Service;
    private final VideoGenerationService videoGenerationService;

    @Value("${portone.store-id}")
    private String portoneStoreId;

    @Value("${portone.channel-key}")
    private String portoneChannelKey;

    /** 주문 생성 */
    @PostMapping
    public ResponseEntity<OrderCreateResponse> createOrder(
            @Valid @RequestBody OrderCreateRequest request) {
        return ResponseEntity.ok(orderService.createOrder(request));
    }

    /** 주문 상태 조회 (accessToken 기반) */
    @GetMapping("/t/{accessToken}/status")
    public ResponseEntity<Map<String, Object>> getOrderStatus(@PathVariable String accessToken) {
        Order order = orderService.findByAccessToken(accessToken);
        return ResponseEntity.ok(Map.of(
                "orderId",     order.getId(),
                "accessToken", order.getAccessToken(),
                "status",      order.getStatus(),
                "photoCount",  order.getPhotoCount() != null ? order.getPhotoCount() : 0,
                "downloadUrl", order.getDownloadUrl() != null ? order.getDownloadUrl() : "",
                "updatedAt",   order.getUpdatedAt()
        ));
    }

    /** 결제 성공 후 S3 업로드 URL 재발급 (accessToken 기반) */
    @GetMapping("/t/{accessToken}/upload-urls")
    public ResponseEntity<Map<String, Object>> getUploadUrls(@PathVariable String accessToken) {
        Order order = orderService.findByAccessToken(accessToken);

        if (order.getStatus() == Order.OrderStatus.PENDING) {
            return ResponseEntity.badRequest().body(Map.of("message", "결제가 완료되지 않은 주문입니다."));
        }
        if (order.getStatus() == Order.OrderStatus.PROCESSING
                || order.getStatus() == Order.OrderStatus.COMPLETED) {
            return ResponseEntity.badRequest().body(Map.of("message", "이미 제작이 시작된 주문입니다."));
        }

        int photoCount = 10;
        List<S3Service.PresignedUploadInfo> uploadInfos =
                s3Service.generateUploadUrls(order.getId(), photoCount);

        List<Map<String, Object>> urls = uploadInfos.stream()
                .map(i -> Map.<String, Object>of(
                        "index",     i.index(),
                        "uploadUrl", i.uploadUrl(),
                        "s3Key",     i.s3Key()
                ))
                .collect(Collectors.toList());

        log.info("업로드 URL 재발급 - orderId: {}, photoCount: {}", order.getId(), photoCount);
        return ResponseEntity.ok(Map.of("orderId", order.getId(), "presignedUrls", urls));
    }

    /**
     * 사진 업로드 완료 신고 → 영상 생성 시작 (accessToken 기반)
     */
    @PostMapping("/t/{accessToken}/upload-complete")
    public ResponseEntity<Map<String, Object>> uploadComplete(
            @PathVariable String accessToken,
            @RequestBody Map<String, Object> body) {

        Order order = orderService.findByAccessToken(accessToken);
        Long orderId = order.getId();
        int photoCount = orderService.handleUploadComplete(orderId, body);
        videoGenerationService.startVideoGeneration(orderId);
        log.info("영상 생성 시작 - orderId: {}", orderId);

        return ResponseEntity.ok(Map.of(
                "result",     "ok",
                "orderId",    orderId,
                "photoCount", photoCount,
                "message",    "영상 제작이 시작되었습니다. 24시간 내 완성 후 문자로 안내드립니다."
        ));
    }

    /** 이어하기 거부 시 기존 PAID 주문 취소 (accessToken 기반) */
    @PostMapping("/t/{accessToken}/cancel")
    public ResponseEntity<Map<String, Object>> cancelOrder(@PathVariable String accessToken) {
        Order order = orderService.findByAccessToken(accessToken);
        if (order.getStatus() != Order.OrderStatus.PAID) {
            return ResponseEntity.badRequest().body(Map.of(
                "message", "취소할 수 없는 상태입니다: " + order.getStatus()
            ));
        }
        orderService.markAsFailed(order.getId(), "고객 재주문 요청으로 취소");
        log.info("주문 취소 (재주문 허용) - orderId: {}", order.getId());
        return ResponseEntity.ok(Map.of("result", "ok"));
    }

    /** 다운로드 URL 재발급 (accessToken 기반) */
    @PostMapping("/t/{accessToken}/download-url")
    public ResponseEntity<Map<String, String>> refreshDownloadUrl(@PathVariable String accessToken) {
        Order order = orderService.findByAccessToken(accessToken);
        String newUrl = orderService.refreshDownloadUrl(order.getId());
        return ResponseEntity.ok(Map.of("downloadUrl", newUrl));
    }

    /** BGM 목록 (프론트 선택 UI용) */
    @GetMapping("/bgm-list")
    public ResponseEntity<List<Map<String, String>>> getBgmList() {
        return ResponseEntity.ok(BgmConstants.BGM_LIST);
    }

    /** 포트원 설정 (프론트 동적 로드) */
    @GetMapping("/payment-config")
    public ResponseEntity<Map<String, String>> getPaymentConfig() {
        return ResponseEntity.ok(Map.of(
                "storeId",    portoneStoreId,
                "channelKey", portoneChannelKey
        ));
    }
}
