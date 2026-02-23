package com.anniversary.video.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class S3Service {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${aws.s3.bucket}")
    private String bucket;

    @Value("${cloudfront.download-expire-hours:72}")
    private int downloadExpireHours;

    public S3Service(S3Client s3Client, S3Presigner s3Presigner) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
    }

    // ── 사진 업로드용 Presigned PUT URL 생성 ──────────────────────────────
    public List<PresignedUploadInfo> generateUploadUrls(Long orderId, int photoCount) {
        return generateUploadUrls(orderId, photoCount, "image/jpeg");
    }

    public List<PresignedUploadInfo> generateUploadUrls(Long orderId, int photoCount, String contentType) {
        List<PresignedUploadInfo> result = new ArrayList<>();
        for (int i = 0; i < photoCount; i++) {
            // s3Key는 .jpg 고정 (RunwayML은 확장자 무관, 실제 바이트만 읽음)
            String s3Key = "uploads/" + orderId + "/photo_" + String.format("%02d", i) + ".jpg";
            // Content-Type 미지정 → presigned URL에 서명 포함 안 됨
            // 프론트에서 파일 타입에 맞는 헤더를 자유롭게 보낼 수 있음
            PutObjectRequest putReq = PutObjectRequest.builder()
                    .bucket(bucket).key(s3Key).build();
            PutObjectPresignRequest presignReq = PutObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(30))
                    .putObjectRequest(putReq).build();
            String url = s3Presigner.presignPutObject(presignReq).url().toString();
            result.add(new PresignedUploadInfo(i, url, s3Key));
        }
        return result;
    }

    // ── 다운로드 Presigned GET URL 생성 ───────────────────────────────────
    public String generateDownloadUrl(String s3Key) {
        GetObjectRequest getReq = GetObjectRequest.builder()
                .bucket(bucket).key(s3Key).build();
        GetObjectPresignRequest presignReq = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofHours(downloadExpireHours))
                .getObjectRequest(getReq).build();
        return s3Presigner.presignGetObject(presignReq).url().toString();
    }

    // ── 외부 URL → S3 업로드 (RunwayML 결과 영상 저장용) ─────────────────
    public String uploadFromUrl(String sourceUrl, String s3Key) throws Exception {
        log.info("URL → S3 업로드 시작: {} → {}", sourceUrl, s3Key);
        Path tmpFile = Files.createTempFile("runway_clip_", ".mp4");
        try {
            try (InputStream in = new URL(sourceUrl).openStream()) {
                Files.copy(in, tmpFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return uploadFile(tmpFile, s3Key, "video/mp4");
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    // ── 로컬 파일 → S3 업로드 ────────────────────────────────────────────
    public String uploadFile(Path localFile, String s3Key, String contentType) throws Exception {
        long fileSize = Files.size(localFile);
        log.info("S3 업로드: {} ({} bytes) → {}", localFile.getFileName(), fileSize, s3Key);
        PutObjectRequest putReq = PutObjectRequest.builder()
                .bucket(bucket).key(s3Key).contentType(contentType)
                .contentLength(fileSize).build();
        s3Client.putObject(putReq, RequestBody.fromFile(localFile));
        log.info("S3 업로드 완료: {}", s3Key);
        return s3Key;
    }

    // ── S3 파일 → 로컬 다운로드 ─────────────────────────────────────────
    public Path downloadToLocal(String s3Key, Path targetPath) throws Exception {
        log.info("S3 다운로드: {} → {}", s3Key, targetPath);
        GetObjectRequest getReq = GetObjectRequest.builder()
                .bucket(bucket).key(s3Key).build();
        s3Client.getObject(getReq, targetPath);
        return targetPath;
    }

    public record PresignedUploadInfo(int index, String uploadUrl, String s3Key) {}
}
