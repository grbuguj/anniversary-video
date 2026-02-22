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

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderPhotoRepository orderPhotoRepository;
    private final S3Service s3Service;

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

            // presigned URL 새로 발급 (기존 URL 만료됐을 수 있으므로)
            List<S3Service.PresignedUploadInfo> uploadInfos =
                    s3Service.generateUploadUrls(existing.getId(), existing.getPhotoCount());
            List<OrderCreateResponse.PresignedUrlInfo> urls = uploadInfos.stream()
                    .map(i -> OrderCreateResponse.PresignedUrlInfo.builder()
                            .index(i.index()).uploadUrl(i.uploadUrl()).s3Key(i.s3Key()).build())
                    .collect(Collectors.toList());

            return OrderCreateResponse.builder()
                    .orderId(existing.getId())
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

        return OrderCreateResponse.builder()
                .orderId(saved.getId()).amount(saved.getAmount())
                .presignedUrls(presignedUrlInfos).build();
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
        order.updateStatus(Order.OrderStatus.COMPLETED);
        log.info("영상 완성 - orderId: {}, path: {}", orderId, s3OutputPath);
        return orderRepository.save(order);
    }

    @Transactional
    public Order markAsFailed(Long orderId, String memo) {
        Order order = findById(orderId);
        order.updateStatus(Order.OrderStatus.FAILED);
        order.setAdminMemo(memo);
        return orderRepository.save(order);
    }

    public Order findById(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + orderId));
    }

    public List<Order> findAll() {
        return orderRepository.findAllByOrderByCreatedAtDesc();
    }
}
