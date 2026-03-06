package com.anniversary.video.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    /** 고객 접근용 보안 토큰 (URL에 노출, orderId 대신 사용) */
    @Column(nullable = false, unique = true, length = 36)
    private String accessToken;

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

    @Column(length = 50)
    private String outroTitle;

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

    /** 영상 생성 시작 시각 */
    private LocalDateTime genStartedAt;

    /** 영상 생성 완료 시각 */
    private LocalDateTime genCompletedAt;

    /** 생성 소요 시간 (분) */
    @Column(precision = 6, scale = 2)
    private java.math.BigDecimal genMinutes;

    /** 실패 단계 (clip_generation, ffmpeg_merge, s3_upload 등) */
    @Column(length = 30)
    private String failureStage;

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
        if (this.accessToken == null) this.accessToken = UUID.randomUUID().toString();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum OrderStatus {
        PENDING, PAID, PROCESSING, COMPLETED, FAILED
    }
}
