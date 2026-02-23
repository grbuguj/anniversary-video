package com.anniversary.video.service;

import com.anniversary.video.domain.Order;
import com.anniversary.video.domain.OrderPhoto;
import com.anniversary.video.repository.OrderPhotoRepository;
import com.anniversary.video.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@Slf4j
public class VideoGenerationService {

    private final OrderRepository orderRepository;
    private final OrderPhotoRepository orderPhotoRepository;
    private final FfmpegService ffmpegService;
    private final S3Service s3Service;
    private final NotificationService notificationService;
    private final OrderService orderService;
    private final Executor clipTaskExecutor;

    @Value("${runwayml.api-key}")
    private String runwayApiKey;

    @Value("${runwayml.base-url}")
    private String runwayBaseUrl;

    public VideoGenerationService(
            OrderRepository orderRepository,
            OrderPhotoRepository orderPhotoRepository,
            FfmpegService ffmpegService,
            S3Service s3Service,
            NotificationService notificationService,
            OrderService orderService,
            @Qualifier("clipTaskExecutor") Executor clipTaskExecutor) {
        this.orderRepository       = orderRepository;
        this.orderPhotoRepository  = orderPhotoRepository;
        this.ffmpegService         = ffmpegService;
        this.s3Service             = s3Service;
        this.notificationService   = notificationService;
        this.orderService          = orderService;
        this.clipTaskExecutor      = clipTaskExecutor;
    }

    // ── 비동기 영상 생성 진입점 (주문 단위) ──────────────────────────────
    @Async("videoTaskExecutor")
    public void startVideoGeneration(Long orderId) {
        log.info("▶ 영상 생성 시작 - orderId: {}", orderId);
        Order order = orderRepository.findById(orderId).orElseThrow();

        try {
            order.updateStatus(Order.OrderStatus.PROCESSING);
            orderRepository.save(order);

            List<OrderPhoto> photos = orderPhotoRepository.findByOrderIdOrderBySortOrder(orderId);
            log.info("처리할 사진 수: {} (병렬 처리)", photos.size());

            // ── 사진 → 클립 병렬 생성 ────────────────────────────────────
            generateClipsInParallel(orderId, photos);

            // ── FFmpeg: 클립 합성 + BGM ───────────────────────────────────
            List<OrderPhoto> completedPhotos =
                    orderPhotoRepository.findByOrderIdOrderBySortOrder(orderId);
            String finalS3Key = ffmpegService.mergeClipsWithMusic(orderId, completedPhotos, order);

            // ── 완료 처리 ─────────────────────────────────────────────────
            String downloadUrl = s3Service.generateDownloadUrl(finalS3Key);
            orderService.markAsCompleted(orderId, finalS3Key, downloadUrl);

            Order completedOrder = orderRepository.findById(orderId).orElseThrow();
            notificationService.sendCompletionAlert(completedOrder, downloadUrl);

            log.info("✅ 영상 생성 전체 완료 - orderId: {}", orderId);

        } catch (Exception e) {
            log.error("❌ 영상 생성 실패 - orderId: {}, error: {}", orderId, e.getMessage(), e);
            orderService.markAsFailed(orderId, e.getMessage());
            notificationService.sendFailureAlert(order);
        }
    }

    // ── 클립 병렬 생성 ────────────────────────────────────────────────────
    private static final int CLIP_MAX_RETRY = 3;

    private void generateClipsInParallel(Long orderId, List<OrderPhoto> photos) {
        int total = photos.size();
        AtomicInteger done = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = photos.stream()
                .map(photo -> CompletableFuture
                        .supplyAsync(() -> generateClipWithRetry(orderId, photo), clipTaskExecutor)
                        .thenAccept(clipS3Key -> {
                            photo.setClipS3Key(clipS3Key);
                            orderPhotoRepository.save(photo);
                            int n = done.incrementAndGet();
                            log.info("클립 완료 [{}/{}] sortOrder={}, s3Key={}",
                                    n, total, photo.getSortOrder(), clipS3Key);
                        })
                )
                .collect(Collectors.toList());

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("병렬 클립 생성 중 오류: " + cause.getMessage(), cause);
        }

        log.info("전체 클립 생성 완료 - orderId: {}, {}장", orderId, total);
    }

    /**
     * 클립 1장 생성 — 실패 시 최대 CLIP_MAX_RETRY 회 재시도.
     * 재시도 간격: 1차 10초, 2차 30초 (지수 백오프)
     */
    private String generateClipWithRetry(Long orderId, OrderPhoto photo) {
        int sortOrder = photo.getSortOrder();
        Exception lastEx = null;

        for (int attempt = 1; attempt <= CLIP_MAX_RETRY; attempt++) {
            try {
                if (attempt > 1) {
                    long waitMs = attempt == 2 ? 10_000L : 30_000L;
                    log.warn("클립 재시도 {}/{} - orderId: {}, sortOrder: {}, {}초 대기",
                            attempt, CLIP_MAX_RETRY, orderId, sortOrder, waitMs / 1000);
                    Thread.sleep(waitMs);
                }
                return generateClip(orderId, photo);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("인터럽트 - sortOrder: " + sortOrder, ie);
            } catch (Exception e) {
                lastEx = e;
                log.error("클립 생성 실패 {}/{} - orderId: {}, sortOrder: {}, error: {}",
                        attempt, CLIP_MAX_RETRY, orderId, sortOrder, e.getMessage());
            }
        }
        throw new RuntimeException(
                "클립 생성 최종 실패 (" + CLIP_MAX_RETRY + "회 시도) - sortOrder: " + sortOrder
                + ", error: " + lastEx.getMessage(), lastEx);
    }

    // ── RunwayML: 이미지 → 영상 클립 생성 ───────────────────────────────
    private String generateClip(Long orderId, OrderPhoto photo) throws Exception {
        log.info("RunwayML 호출 시작 - orderId: {}, sortOrder: {}", orderId, photo.getSortOrder());

        // S3 임시 URL 생성 (RunwayML이 직접 다운로드할 수 있도록)
        String imageUrl = s3Service.generateDownloadUrl(photo.getS3Key());

        WebClient client = buildRunwayClient();

        Map<String, Object> reqBody = Map.of(
                "model",       "gen3a_turbo",
                "promptImage", imageUrl,
                "promptText",  "Gentle, natural subtle movement. " +
                               "Soft breathing and emotional atmosphere. Cinematic.",
                "duration",    5,          // 초
                "ratio",       "1280:768"  // 가로형 (16:9에 가까운 비율)
        );

        Map taskResp;
        try {
            taskResp = client.post()
                    .uri("/image_to_video")
                    .bodyValue(reqBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("RunwayML API 오류 - status: {}, body: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("RunwayML API 오류: " + e.getStatusCode());
        }

        String taskId = (String) taskResp.get("id");
        log.info("RunwayML 작업 생성 - taskId: {}, sortOrder: {}", taskId, photo.getSortOrder());

        // 완료까지 폴링 (최대 10분)
        String outputUrl = pollUntilDone(client, taskId, photo.getSortOrder());

        // RunwayML 결과 영상 → S3 저장
        String clipS3Key = "clips/" + orderId + "/clip_"
                + String.format("%02d", photo.getSortOrder()) + ".mp4";
        s3Service.uploadFromUrl(outputUrl, clipS3Key);

        return clipS3Key;
    }

    // ── RunwayML 완료 폴링 ────────────────────────────────────────────────
    private String pollUntilDone(WebClient client, String taskId, int sortOrder)
            throws InterruptedException {
        int maxRetry = 120; // 5초 × 120 = 최대 10분

        for (int i = 0; i < maxRetry; i++) {
            Thread.sleep(5000);

            Map status = client.get()
                    .uri("/tasks/" + taskId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            String taskStatus = (String) status.get("status");
            Number progress   = (Number) status.get("progressRatio");

            log.info("RunwayML 폴링 #{} - taskId: {}, sortOrder: {}, status: {}, progress: {}%",
                    i + 1, taskId, sortOrder, taskStatus,
                    progress != null ? String.format("%.0f", progress.doubleValue() * 100) : "?");

            if ("SUCCEEDED".equals(taskStatus)) {
                @SuppressWarnings("unchecked")
                List<String> output = (List<String>) status.get("output");
                if (output != null && !output.isEmpty()) return output.get(0);
                throw new RuntimeException("RunwayML 결과 URL 없음 - taskId: " + taskId);
            }
            if ("FAILED".equals(taskStatus) || "CANCELLED".equals(taskStatus)) {
                String failureCode = (String) status.get("failure");
                throw new RuntimeException("RunwayML 작업 실패 - taskId: " + taskId
                        + ", reason: " + failureCode);
            }
        }
        throw new RuntimeException("RunwayML 타임아웃 (10분 초과) - taskId: " + taskId);
    }

    // ── RunwayML WebClient 빌더 ───────────────────────────────────────────
    private WebClient buildRunwayClient() {
        return WebClient.builder()
                .baseUrl(runwayBaseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + runwayApiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-Runway-Version", "2024-11-06")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();
    }
}
