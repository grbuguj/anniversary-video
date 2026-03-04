package com.anniversary.video.service;

import com.anniversary.video.domain.Order;
import com.anniversary.video.domain.OrderPhoto;
import com.anniversary.video.repository.OrderPhotoRepository;
import com.anniversary.video.repository.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDateTime;
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
    private final EventLoggingService eventLoggingService;
    private final Executor clipTaskExecutor;

    @Value("${xai.api-key}")
    private String xaiApiKey;

    @Value("${xai.base-url}")
    private String xaiBaseUrl;

    public VideoGenerationService(
            OrderRepository orderRepository,
            OrderPhotoRepository orderPhotoRepository,
            FfmpegService ffmpegService,
            S3Service s3Service,
            NotificationService notificationService,
            OrderService orderService,
            EventLoggingService eventLoggingService,
            @Qualifier("clipTaskExecutor") Executor clipTaskExecutor) {
        this.orderRepository       = orderRepository;
        this.orderPhotoRepository  = orderPhotoRepository;
        this.ffmpegService         = ffmpegService;
        this.s3Service             = s3Service;
        this.notificationService   = notificationService;
        this.orderService          = orderService;
        this.eventLoggingService   = eventLoggingService;
        this.clipTaskExecutor      = clipTaskExecutor;
    }

    // ── 비동기 영상 생성 진입점 (주문 단위) ──────────────────────────────
    @Async("videoTaskExecutor")
    public void startVideoGeneration(Long orderId) {
        log.info("▶ 영상 생성 시작 - orderId: {}", orderId);
        Order order = orderRepository.findById(orderId).orElseThrow();
        String failureStage = null;

        try {
            order.updateStatus(Order.OrderStatus.PROCESSING);
            order.setGenStartedAt(LocalDateTime.now());
            orderRepository.save(order);
            eventLoggingService.log(orderId, "gen_start", null);

            List<OrderPhoto> photos = orderPhotoRepository.findByOrderIdOrderBySortOrder(orderId);
            log.info("처리할 사진 수: {} (병렬 처리)", photos.size());

            // ── 사진 → 클립 병렬 생성 ────────────────────────────────────
            failureStage = "clip_generation";
            generateClipsInParallel(orderId, photos);

            // ── FFmpeg: 클립 합성 + BGM ───────────────────────────────────
            failureStage = "ffmpeg_merge";
            List<OrderPhoto> completedPhotos =
                    orderPhotoRepository.findByOrderIdOrderBySortOrder(orderId);
            String finalS3Key = ffmpegService.mergeClipsWithMusic(orderId, completedPhotos, order);

            // ── S3 업로드 완료 → 다운로드 URL ─────────────────────────────
            failureStage = "s3_upload";
            String downloadUrl = s3Service.generateDownloadUrl(finalS3Key);

            // ── 완료 처리 ─────────────────────────────────────────────────
            orderService.markAsCompleted(orderId, finalS3Key, downloadUrl);

            Order completedOrder = orderRepository.findById(orderId).orElseThrow();
            notificationService.sendCompletionAlert(completedOrder, downloadUrl);
            eventLoggingService.log(orderId, "gen_complete",
                    String.format("{\"genMinutes\":%s}", completedOrder.getGenMinutes()));

            log.info("✅ 영상 생성 전체 완료 - orderId: {}", orderId);

        } catch (Exception e) {
            log.error("❌ 영상 생성 실패 - orderId: {}, stage: {}, error: {}",
                    orderId, failureStage, e.getMessage(), e);
            orderService.markAsFailed(orderId, e.getMessage(), failureStage);
            notificationService.sendFailureAlert(
                    orderRepository.findById(orderId).orElse(order));
            eventLoggingService.log(orderId, "gen_fail",
                    String.format("{\"stage\":\"%s\",\"error\":\"%s\"}",
                            failureStage, truncate(e.getMessage(), 200)));
        }
    }

    // ── FFmpeg 머지만 실행 (DEV용 — 클립 생성 건너뛰고 머지부터 시작) ──────
    @Async("videoTaskExecutor")
    public void startMergeOnly(Long orderId) {
        log.info("▶ FFmpeg 머지 전용 시작 - orderId: {}", orderId);
        Order order = orderRepository.findById(orderId).orElseThrow();
        String failureStage = null;

        try {
            order.updateStatus(Order.OrderStatus.PROCESSING);
            order.setGenStartedAt(LocalDateTime.now());
            orderRepository.save(order);

            // clipS3Key가 있는 사진만 필터링
            List<OrderPhoto> clipsReady = orderPhotoRepository.findByOrderIdOrderBySortOrder(orderId)
                    .stream()
                    .filter(p -> p.getClipS3Key() != null && !p.getClipS3Key().isBlank())
                    .collect(Collectors.toList());

            log.info("FFmpeg 머지 대상 클립: {}개", clipsReady.size());

            // FFmpeg: 클립 합성 + BGM
            failureStage = "ffmpeg_merge";
            String finalS3Key = ffmpegService.mergeClipsWithMusic(orderId, clipsReady, order);

            // S3 업로드 완료 → 다운로드 URL
            failureStage = "s3_upload";
            String downloadUrl = s3Service.generateDownloadUrl(finalS3Key);

            // 완료 처리
            orderService.markAsCompleted(orderId, finalS3Key, downloadUrl);

            Order completedOrder = orderRepository.findById(orderId).orElseThrow();
            notificationService.sendCompletionAlert(completedOrder, downloadUrl);
            eventLoggingService.log(orderId, "gen_complete",
                    String.format("{\"genMinutes\":%s}", completedOrder.getGenMinutes()));

            log.info("✅ FFmpeg 머지 전체 완료 - orderId: {}", orderId);

        } catch (Exception e) {
            log.error("❌ FFmpeg 머지 실패 - orderId: {}, stage: {}, error: {}",
                    orderId, failureStage, e.getMessage(), e);
            orderService.markAsFailed(orderId, e.getMessage(), failureStage);
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

    // ── xAI Grok Imagine: 이미지 → 영상 클립 생성 ──────────────────────
    private String generateClip(Long orderId, OrderPhoto photo) throws Exception {
        log.info("Grok Imagine 호출 시작 - orderId: {}, sortOrder: {}", orderId, photo.getSortOrder());

        String imageUrl = s3Service.generateDownloadUrl(photo.getS3Key());
        WebClient client = buildXaiClient();

        // 캡션이 있으면 프롬프트에 반영
        String promptText = buildVideoPrompt();

        Map<String, Object> reqBody = Map.of(
                "model",        "grok-imagine-video",
                "prompt",       promptText,
                "image",        Map.of("url", imageUrl),
                "duration",     6,
                "resolution",   "720p"
        );

        Map taskResp;
        try {
            taskResp = client.post()
                    .uri("/v1/videos/generations")
                    .bodyValue(reqBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("Grok Imagine API 오류 - status: {}, body: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Grok Imagine API 오류: " + e.getStatusCode());
        }

        String requestId = (String) taskResp.get("request_id");
        log.info("Grok Imagine 작업 생성 - requestId: {}, sortOrder: {}", requestId, photo.getSortOrder());

        String outputUrl = pollUntilDone(client, requestId, photo.getSortOrder());

        String clipS3Key = "clips/" + orderId + "/clip_"
                + String.format("%02d", photo.getSortOrder()) + ".mp4";
        s3Service.uploadFromUrl(outputUrl, clipS3Key);

        return clipS3Key;
    }

    // ── Grok Imagine 완료 폴링 ────────────────────────────────────────────
    private static final long POLL_INTERVAL_MS = 5_000L;          // 5초 간격
    private static final long POLL_TIMEOUT_MS  = 10 * 60 * 1000L; // 10분 타임아웃

    private String pollUntilDone(WebClient client, String requestId, int sortOrder)
            throws InterruptedException {
        long startTime = System.currentTimeMillis();
        int pollCount = 0;

        while (System.currentTimeMillis() - startTime < POLL_TIMEOUT_MS) {
            pollCount++;

            Map status;
            try {
                status = client.get()
                        .uri("/v1/videos/" + requestId)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();
            } catch (WebClientResponseException e) {
                log.warn("Grok 폴링 HTTP 오류 #{} - requestId: {}, status: {}",
                        pollCount, requestId, e.getStatusCode());
                Thread.sleep(POLL_INTERVAL_MS);
                continue;
            }

            String taskStatus = (String) status.get("status");

            log.info("Grok 폴링 #{} - requestId: {}, sortOrder: {}, status: {}, 경과: {}초",
                    pollCount, requestId, sortOrder, taskStatus,
                    (System.currentTimeMillis() - startTime) / 1000);

            if ("done".equals(taskStatus)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> video = (Map<String, Object>) status.get("video");
                if (video != null && video.get("url") != null) {
                    return (String) video.get("url");
                }
                throw new RuntimeException("Grok 결과 URL 없음 - requestId: " + requestId);
            }
            if ("expired".equals(taskStatus)) {
                throw new RuntimeException("Grok 작업 만료 - requestId: " + requestId);
            }

            // pending — 계속 폴링
            Thread.sleep(POLL_INTERVAL_MS);
        }

        long elapsedMin = (System.currentTimeMillis() - startTime) / 60_000;
        throw new RuntimeException("Grok 타임아웃 (" + elapsedMin + "분 초과) - requestId: " + requestId);
    }

    // ── 영상 생성 프롬프트 ─────────────────────────────────────────
    private String buildVideoPrompt() {
        return "Gentle, natural subtle movement. Soft cinematic atmosphere. "
             + "Warm nostalgic tone. High detail, sharp focus, fine textures. "
             + "Preserve the exact facial identity and features. "
             + "Do NOT morph, distort, or alter any face.";
    }

    private WebClient buildXaiClient() {
        return WebClient.builder()
                .baseUrl(xaiBaseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + xaiApiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
