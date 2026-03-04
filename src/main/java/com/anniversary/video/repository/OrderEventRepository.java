package com.anniversary.video.repository;

import com.anniversary.video.domain.OrderEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderEventRepository extends JpaRepository<OrderEvent, Long> {

    /** 특정 주문의 이벤트 목록 (시간순) */
    List<OrderEvent> findByOrderIdOrderByCreatedAtAsc(Long orderId);

    /** 특정 이벤트 타입의 건수 (기간 내) */
    long countByEventTypeAndCreatedAtBetween(String eventType, LocalDateTime from, LocalDateTime to);

    /** 일별 퍼널 집계용: 이벤트 타입별 카운트 */
    @Query("SELECT e.eventType, COUNT(e) FROM OrderEvent e " +
           "WHERE e.createdAt >= :from AND e.createdAt < :to " +
           "GROUP BY e.eventType")
    List<Object[]> countByEventTypeGrouped(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
