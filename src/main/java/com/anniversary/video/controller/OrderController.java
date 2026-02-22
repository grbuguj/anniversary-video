package com.anniversary.video.controller;

import com.anniversary.video.domain.Order;
import com.anniversary.video.domain.OrderPhoto;
import com.anniversary.video.dto.OrderCreateRequest;
import com.anniversary.video.dto.OrderCreateResponse;
import com.anniversary.video.repository.OrderPhotoRepository;
import com.anniversary.video.service.OrderService;
import com.anniversary.video.service.S3Service;
import com.anniversary.video.service.VideoGenerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
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
    private final OrderPhotoRepository orderPhotoRepository;
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

    /** 주문 상태 조회 */
    @GetMapping("/{orderId}/status")
    public ResponseEntity<Map<String, Object>> getOrderStatus(@PathVariable Long orderId) {
        Order order = orderService.findById(orderId);
        return ResponseEntity.ok(Map.of(
                "orderId",     order.getId(),
                "status",      order.getStatus(),
                "photoCount",  order.getPhotoCount() != null ? order.getPhotoCount() : 0,
                "downloadUrl", order.getDownloadUrl() != null ? order.getDownloadUrl() : "",
                "updatedAt",   order.getUpdatedAt()
        ));
    }

    /**
     * 결제 성공 후 S3 업로드 URL 재발급
     * — 토스 리다이렉트 후 presignedUrls가 소실되므로 이 API로 재획득
     */
    @GetMapping("/{orderId}/upload-urls")
    public ResponseEntity<Map<String, Object>> getUploadUrls(@PathVariable Long orderId) {
        Order order = orderService.findById(orderId);

        if (order.getStatus() == Order.OrderStatus.PENDING) {
            return ResponseEntity.badRequest().body(Map.of("message", "결제가 완료되지 않은 주문입니다."));
        }
        if (order.getStatus() == Order.OrderStatus.PROCESSING
                || order.getStatus() == Order.OrderStatus.COMPLETED) {
            return ResponseEntity.badRequest().body(Map.of("message", "이미 제작이 시작된 주문입니다."));
        }

        int photoCount = order.getPhotoCount() != null ? order.getPhotoCount() : 12;
        List<S3Service.PresignedUploadInfo> uploadInfos =
                s3Service.generateUploadUrls(orderId, photoCount);

        List<Map<String, Object>> urls = uploadInfos.stream()
                .map(i -> Map.<String, Object>of(
                        "index",     i.index(),
                        "uploadUrl", i.uploadUrl(),
                        "s3Key",     i.s3Key()
                ))
                .collect(Collectors.toList());

        log.info("업로드 URL 재발급 - orderId: {}, photoCount: {}", orderId, photoCount);
        return ResponseEntity.ok(Map.of("orderId", orderId, "presignedUrls", urls));
    }

    /**
     * 사진 업로드 완료 신고
     * 프론트가 S3 업로드 끝나면 이 API 호출 → OrderPhoto 저장 → 영상 생성 시작
     *
     * Request body:
     * {
     *   "s3Keys": ["uploads/1/photo_00.jpg", "uploads/1/photo_01.jpg", ...]
     * }
     */
    @PostMapping("/{orderId}/upload-complete")
    public ResponseEntity<Map<String, Object>> uploadComplete(
            @PathVariable Long orderId,
            @RequestBody Map<String, Object> body) {

        Order order = orderService.findById(orderId);

        // PAID 상태만 허용
        if (order.getStatus() != Order.OrderStatus.PAID) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "결제 완료 상태의 주문만 업로드 완료 처리가 가능합니다. 현재 상태: " + order.getStatus()
            ));
        }

        // photos 배열(caption 포함 신규) 또는 s3Keys(구버전) 처리
        @SuppressWarnings("unchecked")
        List<Map<String, String>> photoList = (List<Map<String, String>>) body.get("photos");
        @SuppressWarnings("unchecked")
        List<String> s3Keys = (List<String>) body.get("s3Keys");

        if ((photoList == null || photoList.isEmpty()) && (s3Keys == null || s3Keys.isEmpty())) {
            return ResponseEntity.badRequest().body(Map.of("message", "업로드된 사진 정보가 없습니다."));
        }

        // 기존 OrderPhoto 삭제
        List<OrderPhoto> existing = orderPhotoRepository.findByOrderIdOrderBySortOrder(orderId);
        if (!existing.isEmpty()) orderPhotoRepository.deleteAll(existing);

        List<OrderPhoto> photos = new ArrayList<>();
        if (photoList != null && !photoList.isEmpty()) {
            // 신규: caption 포함
            for (int i = 0; i < photoList.size(); i++) {
                photos.add(OrderPhoto.builder()
                        .order(order)
                        .s3Key(photoList.get(i).get("s3Key"))
                        .caption(photoList.get(i).get("caption"))
                        .sortOrder(i)
                        .build());
            }
        } else {
            // 구버전 fallback
            for (int i = 0; i < s3Keys.size(); i++) {
                photos.add(OrderPhoto.builder()
                        .order(order).s3Key(s3Keys.get(i)).sortOrder(i).build());
            }
        }
        orderPhotoRepository.saveAll(photos);
        log.info("OrderPhoto 저장 완료 - orderId: {}, count: {}", orderId, photos.size());

        videoGenerationService.startVideoGeneration(orderId);
        log.info("영상 생성 시작 - orderId: {}", orderId);

        return ResponseEntity.ok(Map.of(
                "result",     "ok",
                "orderId",    orderId,
                "photoCount", photos.size(),
                "message",    "영상 제작이 시작되었습니다. 24시간 내 완성 후 문자로 안내드립니다."
        ));
    }

    /** 다운로드 URL 재발급 (만료 시) */
    @PostMapping("/{orderId}/download-url")
    public ResponseEntity<Map<String, String>> refreshDownloadUrl(@PathVariable Long orderId) {
        String newUrl = orderService.refreshDownloadUrl(orderId);
        return ResponseEntity.ok(Map.of("downloadUrl", newUrl));
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
