-- ============================================================
-- anniversary_video 데이터베이스 — 최종 스키마 (V1 ~ V5 반영)
-- MySQL 8.0+
-- 이 파일은 참조용입니다. 실제 DB는 Flyway migration으로 관리됩니다.
-- ============================================================

CREATE TABLE IF NOT EXISTS orders (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    access_token         VARCHAR(36)  NOT NULL,
    customer_name        VARCHAR(50)  NOT NULL,
    customer_phone       VARCHAR(20)  NOT NULL,
    customer_email       VARCHAR(100),
    amount               INT          NOT NULL DEFAULT 29900,
    payment_key          VARCHAR(200),
    status               VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    photo_count          INT          DEFAULT 0,
    intro_title          VARCHAR(50)  NULL,
    bgm_track            VARCHAR(20)  DEFAULT 'bgm_01',
    s3_input_path        VARCHAR(300),
    s3_output_path       VARCHAR(300),
    download_url         VARCHAR(500),
    download_expires_at  DATETIME,
    admin_memo           TEXT,
    retry_count          INT          NOT NULL DEFAULT 0,
    gen_started_at       DATETIME     NULL,
    gen_completed_at     DATETIME     NULL,
    gen_minutes          DECIMAL(6,2) NULL,
    failure_stage        VARCHAR(30)  NULL,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_status (status),
    INDEX idx_phone (customer_phone),
    INDEX idx_created_at (created_at),
    INDEX idx_payment_key (payment_key),
    UNIQUE KEY uk_access_token (access_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS order_photos (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_id    BIGINT       NOT NULL,
    s3_key      VARCHAR(300) NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    clip_s3_key VARCHAR(300),
    caption     VARCHAR(20),
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_order_id (order_id),
    CONSTRAINT fk_photos_order FOREIGN KEY (order_id)
        REFERENCES orders(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS order_events (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    order_id      BIGINT       NULL,
    event_type    VARCHAR(40)  NOT NULL,
    source        VARCHAR(10)  NOT NULL DEFAULT 'server',
    payload       JSON         NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_order_events_order (order_id),
    INDEX idx_order_events_type (event_type),
    INDEX idx_order_events_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS daily_funnel_metrics (
    id              BIGINT   NOT NULL AUTO_INCREMENT,
    metric_date     DATE     NOT NULL,
    page_views      INT      NOT NULL DEFAULT 0,
    orders_created  INT      NOT NULL DEFAULT 0,
    payments_done   INT      NOT NULL DEFAULT 0,
    uploads_done    INT      NOT NULL DEFAULT 0,
    gen_started     INT      NOT NULL DEFAULT 0,
    gen_completed   INT      NOT NULL DEFAULT 0,
    gen_failed      INT      NOT NULL DEFAULT 0,
    revenue         INT      NOT NULL DEFAULT 0,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_metric_date (metric_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS hourly_sla_metrics (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    metric_hour       DATETIME     NOT NULL,
    gen_count         INT          NOT NULL DEFAULT 0,
    avg_gen_minutes   DECIMAL(6,2) NULL,
    max_gen_minutes   DECIMAL(6,2) NULL,
    p95_gen_minutes   DECIMAL(6,2) NULL,
    fail_count        INT          NOT NULL DEFAULT 0,
    retry_count       INT          NOT NULL DEFAULT 0,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_metric_hour (metric_hour)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
