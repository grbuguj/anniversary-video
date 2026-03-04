package com.anniversary.video.service;

import com.anniversary.video.domain.Order;
import com.anniversary.video.domain.OrderPhoto;
import com.anniversary.video.dto.OrderCreateRequest;
import com.anniversary.video.dto.OrderCreateResponse;
import com.anniversary.video.repository.OrderPhotoRepository;
import com.anniversary.video.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderPhotoRepository orderPhotoRepository;
    private final S3Service s3Service;
    private final EventLoggingService eventLoggingService;

    // ── 주문 생성 (Rate Limit + 이어하기 감지) ────────────────────────────
    @Transactional
    public OrderCreateResponse createOrder(OrderCreateRequest request) {

        // 1) PROCESSING 중인 주문은 신규 차단
        boolean hasProcessing = orderRepository
                .findByCustomerPhoneAndCreatedAtAfterAndStatusNot(
                        request.getCustomerPhone(),
                        LocalDateTime.now().minusHours(24),
                        Order.OrderStatus.FAILED
                ).stream()
                .anyMatch(o -> o.getStatus() == Order.OrderStatus.PROCESSING);
        if (hasProcessing) {
            throw new IllegalStateException(
                    "영상 제작 중인 주문이 있습니다. 완료 후 다시 시도해 주세요.");
        }

        // 2) 동일 이름+전화번호의 PAID 미완료 주문 → 이어하기 응답
        var existingOpt = orderRepository
                .findTopByCustomerPhoneAndCustomerNameAndStatusOrderByCreatedAtDesc(
                        request.getCustomerPhone(),
                        request.getCustomerName(),
                        Order.OrderStatus.PAID
                );

        if (existingOpt.isPresent()) {
            Order existing = existingOpt.get();
            log.info("이어하기 대상 주문 발견 - orderId: {}, 고객: {}",
                    existing.getId(), existing.getCustomerName());

            List<S3Service.PresignedUploadInfo> uploadInfos =
                    s3Service.generateUploadUrls(existing.getId(), existing.getPhotoCount());
            List<OrderCreateResponse.PresignedUrlInfo> urls = uploadInfos.stream()
                    .map(i -> OrderCreateResponse.PresignedUrlInfo.builder()
                            .index(i.index()).uploadUrl(i.uploadUrl()).s3Key(i.s3Key()).build())
                    .collect(Collectors.toList());

            return OrderCreateResponse.builder()
                    .orderId(existing.getId())
                    .accessToken(existing.getAccessToken())
                    .amount(existing.getAmount())
                    .presignedUrls(urls)
                    .isExistingOrder(true)
                    .existingCustomerName(existing.getCustomerName())
                    .build();
        }

        // 3) 신규 주문 생성
        Order order = Order.builder()
                .customerName(request.getCustomerName())
                .customerPhone(request.getCustomerPhone())
                .customerEmail(request.getCustomerEmail())
                .photoCount(request.getPhotoCount())
                .introTitle(request.getIntroTitle())
                .bgmTrack(request.getBgmTrack() != null ? request.getBgmTrack() : "bgm_01")
                .status(Order.OrderStatus.PENDING)
                .s3InputPath("uploads/" + System.currentTimeMillis())
                .build();
        Order saved = orderRepository.save(order);

        List<S3Service.PresignedUploadInfo> uploadInfos =
                s3Service.generateUploadUrls(saved.getId(), request.getPhotoCount());

        for (S3Service.PresignedUploadInfo info : uploadInfos) {
            orderPhotoRepository.save(OrderPhoto.builder()
                    .order(saved).s3Key(info.s3Key()).sortOrder(info.index()).build());
        }

        List<OrderCreateResponse.PresignedUrlInfo> presignedUrlInfos = uploadInfos.stream()
                .map(i -> OrderCreateResponse.PresignedUrlInfo.builder()
                        .index(i.index()).uploadUrl(i.uploadUrl()).s3Key(i.s3Key()).build())
                .collect(Collectors.toList());

        log.info("주문 생성 - orderId: {}, 고객: {}", saved.getId(), saved.getCustomerName());
        eventLoggingService.log(saved.getId(), "order_created", null);

        return OrderCreateResponse.builder()
                .orderId(saved.getId()).accessToken(saved.getAccessToken())
                .amount(saved.getAmount())
                .presignedUrls(presignedUrlInfos).build();
    }

    // ── 사진 업로드 완료 처리 (컨트롤러에서 이동) ──────────────────────────
    @Transactional
    public int handleUploadComplete(Long orderId, Map<String, Object> body) {
        Order order = findById(orderId);

        if (order.getStatus() != Order.OrderStatus.PAID) {
            throw new IllegalStateException(
                    "결제 완료 상태의 주문만 업로드 완료 처리가 가능합니다. 현재 상태: " + order.getStatus());
        }

        @SuppressWarnings("unchecked")
        List<Map<String, String>> photoList = (List<Map<String, String>>) body.get("photos");
        @SuppressWarnings("unchecked")
        List<String> s3Keys = (List<String>) body.get("s3Keys");

        if ((photoList == null || photoList.isEmpty()) && (s3Keys == null || s3Keys.isEmpty())) {
            throw new IllegalArgumentException("업로드된 사진 정보가 없습니다.");
        }

        // 기존 OrderPhoto 삭제
        List<OrderPhoto> existing = orderPhotoRepository.findByOrderIdOrderBySortOrder(orderId);
        if (!existing.isEmpty()) orderPhotoRepository.deleteAll(existing);

        // OrderPhoto 저장
        List<OrderPhoto> photos = new ArrayList<>();
        if (photoList != null && !photoList.isEmpty()) {
            for (int i = 0; i < photoList.size(); i++) {
                photos.add(OrderPhoto.builder()
                        .order(order)
                        .s3Key(photoList.get(i).get("s3Key"))
                        .caption(photoList.get(i).get("caption"))
                        .sortOrder(i)
                        .build());
            }
        } else {
            for (int i = 0; i < s3Keys.size(); i++) {
                photos.add(OrderPhoto.builder()
                        .order(order).s3Key(s3Keys.get(i)).sortOrder(i).build());
            }
        }
        orderPhotoRepository.saveAll(photos);
        log.info("OrderPhoto 저장 완료 - orderId: {}, count: {}", orderId, photos.size());

        // BGM 선택값 업데이트
        String bgmTrack = (String) body.get("bgmTrack");
        if (bgmTrack != null && !bgmTrack.isBlank()) {
            order.setBgmTrack(bgmTrack);
            log.info("BGM 설정 - orderId: {}, bgm: {}", orderId, bgmTrack);
        }
        orderRepository.save(order);

        eventLoggingService.log(orderId, "upload_complete",
                String.format("{\"photoCount\":%d}", photos.size()));

        return photos.size();
    }

    // ── 다운로드 URL 재발급 ───────────────────────────────────────────────
    @Transactional
    public String refreshDownloadUrl(Long orderId) {
        Order order = findById(orderId);
        if (order.getStatus() != Order.OrderStatus.COMPLETED) {
            throw new IllegalStateException("완성된 주문이 아닙니다: " + orderId);
        }
        if (order.getS3OutputPath() == null) {
            throw new IllegalStateException("S3 결과 파일이 없습니다: " + orderId);
        }
        String newUrl = s3Service.generateDownloadUrl(order.getS3OutputPath());
        order.setDownloadUrl(newUrl);
        order.setDownloadExpiresAt(LocalDateTime.now().plusHours(72));
        orderRepository.save(order);
        log.info("다운로드 URL 재발급 - orderId: {}", orderId);
        return newUrl;
    }

    @Transactional
    public Order markAsPaid(Long orderId, String paymentKey) {
        Order order = findById(orderId);
        order.setPaymentKey(paymentKey);
        order.updateStatus(Order.OrderStatus.PAID);
        log.info("결제 완료 - orderId: {}", orderId);
        return orderRepository.save(order);
    }

    @Transactional
    public Order markAsCompleted(Long orderId, String s3OutputPath, String downloadUrl) {
        Order order = findById(orderId);
        order.setS3OutputPath(s3OutputPath);
        order.setDownloadUrl(downloadUrl);
        order.setDownloadExpiresAt(LocalDateTime.now().plusHours(72));
        order.setGenCompletedAt(LocalDateTime.now());

        // gen_minutes 계산
        if (order.getGenStartedAt() != null) {
            long seconds = Duration.between(order.getGenStartedAt(), order.getGenCompletedAt()).getSeconds();
            order.setGenMinutes(BigDecimal.valueOf(seconds)
                    .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));
        }

        order.updateStatus(Order.OrderStatus.COMPLETED);
        log.info("영상 완성 - orderId: {}, path: {}, genMinutes: {}",
                orderId, s3OutputPath, order.getGenMinutes());
        return orderRepository.save(order);
    }

    @Transactional
    public Order markAsFailed(Long orderId, String memo) {
        Order order = findById(orderId);
        order.updateStatus(Order.OrderStatus.FAILED);
        order.setAdminMemo(memo);
        return orderRepository.save(order);
    }

    @Transactional
    public Order markAsFailed(Long orderId, String memo, String failureStage) {
        Order order = findById(orderId);
        order.updateStatus(Order.OrderStatus.FAILED);
        order.setAdminMemo(memo);
        order.setFailureStage(failureStage);
        return orderRepository.save(order);
    }

    /** 관리자 상태 변경 */
    @Transactional
    public Order updateStatus(Long orderId, Order.OrderStatus newStatus) {
        Order order = findById(orderId);
        order.updateStatus(newStatus);
        return orderRepository.save(order);
    }

    /** 관리자 메모 저장 */
    @Transactional
    public Order updateMemo(Long orderId, String memo) {
        Order order = findById(orderId);
        order.setAdminMemo(memo);
        return orderRepository.save(order);
    }

    /** 관리자 재생성 준비 (retryCount 증가 + PAID 전환) */
    @Transactional
    public Order prepareRegeneration(Long orderId) {
        Order order = findById(orderId);
        order.setRetryCount(order.getRetryCount() + 1);
        order.updateStatus(Order.OrderStatus.PAID);
        order.setGenStartedAt(null);
        order.setGenCompletedAt(null);
        order.setGenMinutes(null);
        order.setFailureStage(null);
        return orderRepository.save(order);
    }

    public Order findById(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + orderId));
    }

    public Order findByAccessToken(String accessToken) {
        return orderRepository.findByAccessToken(accessToken)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 접근입니다."));
    }

    @Transactional
    public Order save(Order order) {
        return orderRepository.save(order);
    }

    public List<Order> findAll() {
        return orderRepository.findAllByOrderByCreatedAtDesc();
    }
}
