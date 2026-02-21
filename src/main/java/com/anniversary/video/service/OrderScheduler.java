package com.anniversary.video.service;

import com.anniversary.video.domain.Order;
import com.anniversary.video.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderScheduler {

    private final OrderRepository orderRepository;
    private final NotificationService notificationService;
    private final VideoGenerationService videoGenerationService;

    private static final int MAX_AUTO_RETRY = 2;

    /**
     * PENDING 24h 자동만료 — 매시 정각
     */
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

    /**
     * PAID 상태 2시간 경과 알림 — 사진 업로드 안 한 고객한테 SMS 리마인더
     * 매 시간 실행
     */
    @Scheduled(cron = "0 30 * * * *")
    @Transactional
    public void remindPaidOrders() {
        LocalDateTime twoHoursAgo  = LocalDateTime.now().minusHours(2);
        LocalDateTime twelveHoursAgo = LocalDateTime.now().minusHours(12);

        // PAID인데 2~12시간 경과 → 주의 필요
        List<Order> needRemind = orderRepository.findByStatusAndUpdatedAtBetween(
                Order.OrderStatus.PAID, twelveHoursAgo, twoHoursAgo);

        if (needRemind.isEmpty()) return;

        needRemind.forEach(o -> {
            log.info("구매 후 사진 업로드 안한 고객 리마인더 - orderId: {}", o.getId());
            notificationService.sendUploadReminder(o);
        });
    }

    /**
     * PROCESSING stuck 감지 & 자동재시도 — 매 10분
     */
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
                orderRepository.save(order);
                videoGenerationService.startVideoGeneration(order.getId());
                log.info("자동재시도 실행 - orderId: {}, retry: {}/{}", order.getId(), retry + 1, MAX_AUTO_RETRY);
            } else {
                order.updateStatus(Order.OrderStatus.FAILED);
                order.setAdminMemo(String.format("[최종실패] %d회 재시도 모두 실패", MAX_AUTO_RETRY));
                orderRepository.save(order);
                notificationService.sendFailureAlert(order);
                log.error("최종 실패 - orderId: {}", order.getId());
            }
        }
    }
}
