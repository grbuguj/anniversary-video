package com.anniversary.video.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
@AllArgsConstructor
public class OrderCreateResponse {
    private Long orderId;
    private int amount;
    private List<PresignedUrlInfo> presignedUrls;

    /** true면 기존 PAID 미완료 주문 → 프론트에서 이어하기 모달 띄움 */
    @Builder.Default
    private boolean isExistingOrder = false;

    /** 이어하기일 때 표시할 고객 이름 */
    private String existingCustomerName;

    @Getter
    @Builder
    @AllArgsConstructor
    public static class PresignedUrlInfo {
        private int index;
        private String uploadUrl;
        private String s3Key;
    }
}
