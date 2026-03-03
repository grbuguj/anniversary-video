package com.anniversary.video.service;

import com.anniversary.video.domain.Order;
import com.anniversary.video.repository.OrderEventRepository;
import com.anniversary.video.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderScheduler {

    private final OrderRepository orderRepository;
    private final OrderEventRepository orderEventRepository;
    private final NotificationService notificationService;
    private final VideoGenerationService videoGenerationService;
    private final JdbcTemplate jdbcTemplate;

    private static final int MAX_AUTO_RETRY  = 2;
    private static final String TMP_BASE_DIR  = "/tmp/anniversary/";
    private static final long   TMP_MAX_AGE_H = 3;

    // ── PENDING 24h 자동만료 — 매시 정각 ──────────────────────────────────
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void expirePendingOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        List<Order> stale = orderRepository.findByStatusAndCreatedAtBefore(
                Order.OrderStatus.PENDING, cutoff);

        if (stale.isEmpty()) return;

        stale.forEach(o -> {
            o.updateStatus(Order.OrderStatus.FAILED);
            o.setAdminMemo("[자동만료] 24시간 내 결제 미완료");
            log.info("PENDING 자동만료 - orderId: {}", o.getId());
        });
        orderRepository.saveAll(stale);
        log.info("자동만료 완료 - {}건", stale.size());
    }

    // ── PAID 사진 업로드 리마인더 — 매시 30분 ─────────────────────────────
    @Scheduled(cron = "0 30 * * * *")
    @Transactional
    public void remindPaidOrders() {
        LocalDateTime twoHoursAgo  = LocalDateTime.now().minusHours(2);
        LocalDateTime twelveHoursAgo = LocalDateTime.now().minusHours(12);

        List<Order> needRemind = orderRepository.findByStatusAndUpdatedAtBetween(
                Order.OrderStatus.PAID, twelveHoursAgo, twoHoursAgo);

        if (needRemind.isEmpty()) return;

        needRemind.forEach(o -> {
            log.info("구매 후 사진 업로드 안한 고객 리마인더 - orderId: {}", o.getId());
            notificationService.sendUploadReminder(o);
        });
    }

    // ── tmp 디렉터리 정리 — 매시 20분 ─────────────────────────────────────
    @Scheduled(cron = "0 20 * * * *")
    public void cleanTmpDirs() {
        Path base = Paths.get(TMP_BASE_DIR);
        if (!Files.exists(base)) return;

        long threshold = System.currentTimeMillis() - TMP_MAX_AGE_H * 3600_000L;
        int[] counts = {0, 0};

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(base)) {
            for (Path dir : stream) {
                if (!Files.isDirectory(dir)) continue;
                try {
                    BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class);
                    if (attrs.lastModifiedTime().toMillis() < threshold) {
                        deleteRecursively(dir);
                        counts[0]++;
                        log.info("[TmpClean] 삭제: {}", dir);
                    } else {
                        counts[1]++;
                    }
                } catch (IOException e) {
                    log.warn("[TmpClean] 실패: {} - {}", dir, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[TmpClean] 디렉터리 순회 실패: {}", e.getMessage());
            return;
        }

        if (counts[0] > 0)
            log.info("[TmpClean] 완료 - 삭제: {}개, 유지: {}개", counts[0], counts[1]);
    }

    // ── PROCESSING stuck 감지 & 자동재시도 — 매 10분 ──────────────────────
    @Scheduled(cron = "0 */10 * * * *")
    @Transactional
    public void detectStuckProcessing() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(2);
        List<Order> stuck = orderRepository.findByStatusAndUpdatedAtBefore(
                Order.OrderStatus.PROCESSING, cutoff);

        for (Order order : stuck) {
            int retry = order.getRetryCount();
            log.warn("⚠️ PROCESSING stuck - orderId: {}, retryCount: {}", order.getId(), retry);

            if (retry < MAX_AUTO_RETRY) {
                order.setRetryCount(retry + 1);
                order.setAdminMemo(String.format("[자동재시도 %d/%d] stuck 감지", retry + 1, MAX_AUTO_RETRY));
                order.updateStatus(Order.OrderStatus.PAID);
                order.setGenStartedAt(null);
                order.setGenCompletedAt(null);
                order.setGenMinutes(null);
                order.setFailureStage(null);
                orderRepository.save(order);
                videoGenerationService.startVideoGeneration(order.getId());
                log.info("자동재시도 실행 - orderId: {}, retry: {}/{}", order.getId(), retry + 1, MAX_AUTO_RETRY);
            } else {
                order.updateStatus(Order.OrderStatus.FAILED);
                order.setAdminMemo(String.format("[최종실패] %d회 재시도 모두 실패", MAX_AUTO_RETRY));
                order.setFailureStage("stuck_timeout");
                orderRepository.save(order);
                notificationService.sendFailureAlert(order);
                log.error("최종 실패 - orderId: {}", order.getId());
            }
        }
    }

    // ── 일별 퍼널 집계 — 매일 01:05 ──────────────────────────────────────
    @Scheduled(cron = "0 5 1 * * *")
    @Transactional
    public void aggregateDailyFunnel() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDateTime from = yesterday.atStartOfDay();
        LocalDateTime to   = yesterday.plusDays(1).atStartOfDay();

        log.info("일별 퍼널 집계 시작 - date: {}", yesterday);

        // order_events에서 이벤트 타입별 카운트
        List<Object[]> eventCounts = orderEventRepository.countByEventTypeGrouped(from, to);
        Map<String, Long> countMap = eventCounts.stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Long) row[1]
                ));

        long revenue = orderRepository.sumRevenueInRange(from, to);

        jdbcTemplate.update(
                "INSERT INTO daily_funnel_metrics " +
                "(metric_date, page_views, orders_created, payments_done, uploads_done, " +
                " gen_started, gen_completed, gen_failed, revenue) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "page_views=VALUES(page_views), orders_created=VALUES(orders_created), " +
                "payments_done=VALUES(payments_done), uploads_done=VALUES(uploads_done), " +
                "gen_started=VALUES(gen_started), gen_completed=VALUES(gen_completed), " +
                "gen_failed=VALUES(gen_failed), revenue=VALUES(revenue)",
                yesterday,
                countMap.getOrDefault("page_view", 0L),
                countMap.getOrDefault("order_created", 0L),
                countMap.getOrDefault("pay_success", 0L),
                countMap.getOrDefault("upload_complete", 0L),
                countMap.getOrDefault("gen_start", 0L),
                countMap.getOrDefault("gen_complete", 0L),
                countMap.getOrDefault("gen_fail", 0L),
                revenue
        );

        log.info("일별 퍼널 집계 완료 - date: {}, events: {}", yesterday, countMap);
    }

    // ── 시간별 SLA 집계 — 매시 10분 ──────────────────────────────────────
    @Scheduled(cron = "0 10 * * * *")
    @Transactional
    public void aggregateHourlySla() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime hourStart = now.withMinute(0).withSecond(0).withNano(0).minusHours(1);
        LocalDateTime hourEnd   = hourStart.plusHours(1);

        List<Order> completed = orderRepository.findCompletedInRange(hourStart, hourEnd);
        long failCount  = orderRepository.countFailedInRange(hourStart, hourEnd);
        long retryCount = orderRepository.sumRetryCountInRange(hourStart, hourEnd);

        if (completed.isEmpty() && failCount == 0) return;

        // genMinutes 통계 계산
        List<BigDecimal> minutes = completed.stream()
                .map(Order::getGenMinutes)
                .filter(Objects::nonNull)
                .sorted()
                .collect(Collectors.toList());

        BigDecimal avgMin = null, maxMin = null, p95Min = null;
        if (!minutes.isEmpty()) {
            avgMin = minutes.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(minutes.size()), 2, java.math.RoundingMode.HALF_UP);
            maxMin = minutes.get(minutes.size() - 1);
            int p95Idx = (int) Math.ceil(minutes.size() * 0.95) - 1;
            p95Min = minutes.get(Math.max(0, p95Idx));
        }

        jdbcTemplate.update(
                "INSERT INTO hourly_sla_metrics " +
                "(metric_hour, gen_count, avg_gen_minutes, max_gen_minutes, p95_gen_minutes, " +
                " fail_count, retry_count) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "gen_count=VALUES(gen_count), avg_gen_minutes=VALUES(avg_gen_minutes), " +
                "max_gen_minutes=VALUES(max_gen_minutes), p95_gen_minutes=VALUES(p95_gen_minutes), " +
                "fail_count=VALUES(fail_count), retry_count=VALUES(retry_count)",
                hourStart,
                completed.size(),
                avgMin, maxMin, p95Min,
                failCount, retryCount
        );

        log.info("시간별 SLA 집계 완료 - hour: {}, completed: {}, failed: {}, avgMin: {}",
                hourStart, completed.size(), failCount, avgMin);
    }

    private void deleteRecursively(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                if (e != null) throw e;
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
