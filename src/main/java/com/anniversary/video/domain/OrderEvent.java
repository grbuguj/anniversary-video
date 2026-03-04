package com.anniversary.video.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "order_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 연관 주문 ID (page_view 등 주문 생성 전 이벤트는 null) */
    private Long orderId;

    /**
     * 이벤트 유형:
     * page_view, order_created, pay_start, pay_success,
     * upload_start, upload_complete,
     * gen_start, gen_complete, gen_fail,
     * notify_sent, download_click
     */
    @Column(nullable = false, length = 40)
    private String eventType;

    /** 이벤트 발생 출처: front | server */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String source = "server";

    /** 자유 형식 추가 데이터 (JSON) */
    @Column(columnDefinition = "JSON")
    private String payload;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
