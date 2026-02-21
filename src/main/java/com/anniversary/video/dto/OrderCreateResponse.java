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
    private List<PresignedUrlInfo> presignedUrls; // 사진 업로드용 URL 목록

    @Getter
    @Builder
    @AllArgsConstructor
    public static class PresignedUrlInfo {
        private int index;       // 사진 순서
        private String uploadUrl; // S3 Presigned PUT URL
        private String s3Key;     // 업로드 후 서버에 전달할 키
    }
}
