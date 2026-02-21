package com.anniversary.video.repository;

import com.anniversary.video.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findAllByOrderByCreatedAtDesc();

    // 스케줄러: 상태 + 생성시간 기준
    List<Order> findByStatusAndCreatedAtBefore(Order.OrderStatus status, LocalDateTime before);

    // 스케줄러: 상태 + 수정시간 기준 (stuck 감지)
    List<Order> findByStatusAndUpdatedAtBefore(Order.OrderStatus status, LocalDateTime before);

    // 스케줄러: 상태 + 수정시간 범위 (업로드 리마인더)
    List<Order> findByStatusAndUpdatedAtBetween(
            Order.OrderStatus status, LocalDateTime from, LocalDateTime to);

    // Rate limit: 동일 번호 최근 주문 조회
    List<Order> findByCustomerPhoneAndCreatedAtAfterAndStatusNot(
            String phone, LocalDateTime after, Order.OrderStatus excludeStatus);

    // 웹훅: paymentKey로 찾기
    Optional<Order> findByPaymentKey(String paymentKey);

    // 통계 쿼리
    long countByStatus(Order.OrderStatus status);
    long countByStatusAndCreatedAtAfter(Order.OrderStatus status, LocalDateTime after);
}
