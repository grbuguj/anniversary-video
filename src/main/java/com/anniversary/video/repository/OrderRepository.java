package com.anniversary.video.repository;

import com.anniversary.video.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findAllByOrderByCreatedAtDesc();

    Optional<Order> findByAccessToken(String accessToken);

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

    // 이어하기: 동일 이름+전화번호의 PAID 미완료 주문
    Optional<Order> findTopByCustomerPhoneAndCustomerNameAndStatusOrderByCreatedAtDesc(
            String phone, String name, Order.OrderStatus status);

    // 통계 쿼리
    long countByStatus(Order.OrderStatus status);
    long countByStatusAndCreatedAtAfter(Order.OrderStatus status, LocalDateTime after);

    // ── SLA 집계 쿼리 ────────────────────────────────────────────────────

    /** 시간대별 완료 건 조회 (gen_minutes 계산용) */
    @Query("SELECT o FROM Order o " +
           "WHERE o.status = 'COMPLETED' " +
           "AND o.genCompletedAt >= :from AND o.genCompletedAt < :to")
    List<Order> findCompletedInRange(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** 시간대별 실패 건수 */
    @Query("SELECT COUNT(o) FROM Order o " +
           "WHERE o.status = 'FAILED' " +
           "AND o.updatedAt >= :from AND o.updatedAt < :to")
    long countFailedInRange(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** 시간대별 재시도 합계 */
    @Query("SELECT COALESCE(SUM(o.retryCount), 0) FROM Order o " +
           "WHERE o.genCompletedAt >= :from AND o.genCompletedAt < :to")
    long sumRetryCountInRange(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** 일별 매출 (COMPLETED 건의 amount 합) */
    @Query("SELECT COALESCE(SUM(o.amount), 0) FROM Order o " +
           "WHERE o.status = 'COMPLETED' " +
           "AND o.updatedAt >= :from AND o.updatedAt < :to")
    long sumRevenueInRange(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
