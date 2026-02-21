package com.anniversary.video.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PaymentConfirmRequest {
    @NotBlank
    private String paymentId;   // 포트원 V2 결제 ID
    @NotBlank
    private String orderId;
    private int amount;
}
