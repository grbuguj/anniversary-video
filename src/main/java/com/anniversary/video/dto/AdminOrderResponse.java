package com.anniversary.video.dto;

import com.anniversary.video.domain.Order;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class AdminOrderResponse {

    private Long id;
    private String customerName;
    private String customerPhone;
    private String customerEmail;
    private int amount;
    private String status;
    private Integer photoCount;
    private String introTitle;
    private String bgmTrack;
    private String paymentKey;
    private String s3OutputPath;
    private String downloadUrl;
    private LocalDateTime downloadExpiresAt;
    private String adminMemo;
    private int retryCount;
    private LocalDateTime genStartedAt;
    private LocalDateTime genCompletedAt;
    private BigDecimal genMinutes;
    private String failureStage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static AdminOrderResponse from(Order order) {
        return AdminOrderResponse.builder()
                .id(order.getId())
                .customerName(order.getCustomerName())
                .customerPhone(order.getCustomerPhone())
                .customerEmail(order.getCustomerEmail())
                .amount(order.getAmount() != null ? order.getAmount() : 29900)
                .status(order.getStatus().name())
                .photoCount(order.getPhotoCount())
                .introTitle(order.getIntroTitle())
                .bgmTrack(order.getBgmTrack())
                .paymentKey(order.getPaymentKey())
                .s3OutputPath(order.getS3OutputPath())
                .downloadUrl(order.getDownloadUrl())
                .downloadExpiresAt(order.getDownloadExpiresAt())
                .adminMemo(order.getAdminMemo())
                .retryCount(order.getRetryCount() != null ? order.getRetryCount() : 0)
                .genStartedAt(order.getGenStartedAt())
                .genCompletedAt(order.getGenCompletedAt())
                .genMinutes(order.getGenMinutes())
                .failureStage(order.getFailureStage())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }
}
