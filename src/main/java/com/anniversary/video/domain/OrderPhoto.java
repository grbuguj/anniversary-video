package com.anniversary.video.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "order_photos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false, length = 300)
    private String s3Key;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    // AI 생성 결과 클립 S3 키
    @Column(length = 300)
    private String clipS3Key;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
