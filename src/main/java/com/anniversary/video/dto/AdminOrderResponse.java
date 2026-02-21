package com.anniversary.video.dto;

import com.anniversary.video.domain.Order;
import lombok.Builder;
import lombok.Getter;

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
    private String paymentKey;
    private String s3OutputPath;
    private String downloadUrl;
    private LocalDateTime downloadExpiresAt;
    private String adminMemo;
    private int retryCount;
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
                .paymentKey(order.getPaymentKey())
                .s3OutputPath(order.getS3OutputPath())
                .downloadUrl(order.getDownloadUrl())
                .downloadExpiresAt(order.getDownloadExpiresAt())
                .adminMemo(order.getAdminMemo())
                .retryCount(order.getRetryCount() != null ? order.getRetryCount() : 0)
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }
}
