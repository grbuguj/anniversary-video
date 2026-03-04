package com.anniversary.video.controller;

import com.anniversary.video.domain.Order;
import com.anniversary.video.domain.OrderPhoto;
import com.anniversary.video.repository.OrderPhotoRepository;
import com.anniversary.video.service.OrderService;
import com.anniversary.video.service.S3Service;
import com.anniversary.video.service.VideoGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 로컬 개발 전용 — spring.profiles.active=local 일 때만 활성화
 * 프로덕션에서는 빈 자체가 등록되지 않음
 */
@RestController
@RequestMapping("/api/dev")
@RequiredArgsConstructor
@Slf4j
@Profile("local")
public class DevController {

    private final OrderService orderService;
    private final OrderPhotoRepository orderPhotoRepository;
    private final S3Service s3Service;
    private final VideoGenerationService videoGenerationService;
    private final com.anniversary.video.service.NotificationService notificationService;

    /**
     * SMS 테스트 — 지정 번호로 테스트 문자 발송
     * POST /api/dev/test-sms?phone=01012345678
     */
    @PostMapping("/test-sms")
    public ResponseEntity<Map<String, Object>> testSms(@RequestParam String phone) {
        notificationService.sendTestSms(phone);
        return ResponseEntity.ok(Map.of("result", "ok", "to", phone, "message", "SMS 발송 시도! 로그 확인하세요."));
    }

    /**
     * 결제 스킵 → 주문을 바로 PAID 상태로
     * POST /api/dev/orders/{orderId}/skip-payment
     */
    @PostMapping("/orders/{orderId}/skip-payment")
    public ResponseEntity<Map<String, Object>> skipPayment(@PathVariable Long orderId) {
        orderService.markAsPaid(orderId, "DEV_SKIP_" + orderId);
        log.warn("[DEV] 결제 스킵 - orderId: {}", orderId);
        // presigned URL도 함께 반환 — DEV에서도 실제 S3 업로드 가능
        int photoCount = 10;
        List<S3Service.PresignedUploadInfo> uploadInfos = s3Service.generateUploadUrls(orderId, photoCount);
        List<Map<String, Object>> urls = uploadInfos.stream()
                .map(i -> Map.<String, Object>of(
                        "index",     i.index(),
                        "uploadUrl", i.uploadUrl(),
                        "s3Key",     i.s3Key()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "result",        "ok",
                "orderId",       orderId,
                "status",        "PAID",
                "presignedUrls", urls
        ));
    }

    /**
     * 업로드 스킵 → 더미 OrderPhoto 생성 후 PROCESSING 상태로
     * POST /api/dev/orders/{orderId}/skip-upload
     * S3 없는 로컬 환경에서 업로드 화면 → 완료 화면 흐름 테스트용
     */
    @PostMapping("/orders/{orderId}/skip-upload")
    public ResponseEntity<Map<String, Object>> skipUpload(@PathVariable Long orderId) {
        Order order = orderService.findById(orderId);

        if (order.getStatus() != Order.OrderStatus.PAID) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "PAID 상태가 아닙니다. 현재: " + order.getStatus()
            ));
        }

        // 기존 OrderPhoto 제거
        List<OrderPhoto> existing = orderPhotoRepository.findByOrderIdOrderBySortOrder(orderId);
        if (!existing.isEmpty()) orderPhotoRepository.deleteAll(existing);

        // 더미 OrderPhoto 생성 (photoCount만큼)
        int count = order.getPhotoCount() != null ? order.getPhotoCount() : 3;
        List<OrderPhoto> dummies = IntStream.range(0, count)
                .mapToObj(i -> OrderPhoto.builder()
                        .order(order)
                        .s3Key("dev/dummy/photo_" + String.format("%02d", i) + ".jpg")
                        .sortOrder(i)
                        .build())
                .collect(Collectors.toList());
        orderPhotoRepository.saveAll(dummies);

        // 상태를 PROCESSING으로 변경 (영상 생성 실제 호출은 안 함)
        order.updateStatus(Order.OrderStatus.PROCESSING);
        log.warn("[DEV] 업로드 스킵, 더미 OrderPhoto {}개 생성 - orderId: {}", count, orderId);

        return ResponseEntity.ok(Map.of(
                "result",     "ok",
                "orderId",    orderId,
                "photoCount", count,
                "status",     "PROCESSING",
                "message",    "더미 사진 " + count + "장으로 처리됨 (영상 생성 실제 실행 안 함)"
        ));
    }

    /**
     * FFmpeg 머지만 트리거 — 클립이 이미 S3에 있는 주문에서 FFmpeg 파이프라인만 테스트
     * clipS3Key가 없는 사진은 건너뜀
     * POST /api/dev/orders/{orderId}/trigger-merge
     */
    @PostMapping("/orders/{orderId}/trigger-merge")
    public ResponseEntity<Map<String, Object>> triggerMerge(@PathVariable Long orderId) {
        Order order = orderService.findById(orderId);

        List<OrderPhoto> photos = orderPhotoRepository.findByOrderIdOrderBySortOrder(orderId);
        List<OrderPhoto> clipsReady = photos.stream()
                .filter(p -> p.getClipS3Key() != null && !p.getClipS3Key().isBlank())
                .collect(Collectors.toList());

        if (clipsReady.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "message", "클립이 하나도 없습니다. 먼저 영상 생성을 실행하세요."
            ));
        }

        log.warn("[DEV] FFmpeg 머지 트리거 - orderId: {}, 전체 {}장 중 클립 {}개 사용",
                orderId, photos.size(), clipsReady.size());

        // 비동기로 FFmpeg 머지 실행
        videoGenerationService.startMergeOnly(orderId);

        return ResponseEntity.ok(Map.of(
            "result",      "ok",
            "orderId",     orderId,
            "totalPhotos", photos.size(),
            "clipsReady",  clipsReady.size(),
            "message",     "FFmpeg 머지 시작! 클립 " + clipsReady.size() + "개로 영상 생성 중"
        ));
    }

    /**
     * 영상 생성 직접 트리거 — S3에 사진이 이미 있는 PAID 주문에 사용
     * POST /api/dev/orders/{orderId}/trigger-video
     */
    @PostMapping("/orders/{orderId}/trigger-video")
    public ResponseEntity<Map<String, Object>> triggerVideo(@PathVariable Long orderId) {
        Order order = orderService.findById(orderId);

        if (order.getStatus() != Order.OrderStatus.PAID) {
            return ResponseEntity.badRequest().body(Map.of(
                "message", "PAID 상태가 아닙니다. 현재: " + order.getStatus()
            ));
        }

        List<OrderPhoto> photos = orderPhotoRepository.findByOrderIdOrderBySortOrder(orderId);
        if (photos.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "message", "업로드된 사진이 없습니다. 먼저 사진을 업로드하세요."
            ));
        }

        log.warn("[DEV] 영상 생성 트리거 - orderId: {}, 사진 {}장", orderId, photos.size());
        videoGenerationService.startVideoGeneration(orderId);

        return ResponseEntity.ok(Map.of(
            "result",     "ok",
            "orderId",    orderId,
            "photoCount", photos.size(),
            "message",    "영상 생성 시작! 로그 확인하세요. /status?orderId=" + orderId + " 에서 진행 상황 확인 가능"
        ));
    }
}
