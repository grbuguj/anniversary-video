package com.anniversary.video.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String customerName;

    @Column(nullable = false, length = 20)
    private String customerPhone;

    @Column(length = 100)
    private String customerEmail;

    @Column(nullable = false)
    @Builder.Default
    private Integer amount = 29900;

    @Column(length = 200)
    private String paymentKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    private Integer photoCount;

    /** 영상 인트로에 표시될 제목 (예: 어머니 환갑, 홍길순 님의 60년) */
    @Column(length = 50)
    private String introTitle;

    /** 선택한 BGM 트랙 (예: bgm_01, bgm_02 ...) */
    @Column(length = 20)
    @Builder.Default
    private String bgmTrack = "bgm_01";

    @Column(length = 300)
    private String s3InputPath;

    @Column(length = 300)
    private String s3OutputPath;

    @Column(length = 500)
    private String downloadUrl;

    private LocalDateTime downloadExpiresAt;

    @Column(columnDefinition = "TEXT")
    private String adminMemo;

    /** 재시도 횟수 (스케줄러 자동재시도 + 관리자 수동재시도 합산) */
    @Builder.Default
    private Integer retryCount = 0;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<OrderPhoto> photos = new ArrayList<>();

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public void updateStatus(OrderStatus newStatus) {
        this.status = newStatus;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.retryCount == null) this.retryCount = 0;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum OrderStatus {
        PENDING, PAID, PROCESSING, COMPLETED, FAILED
    }
}
