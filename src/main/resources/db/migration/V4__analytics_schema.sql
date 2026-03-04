-- V4: 분석 파이프라인 + 누락 컬럼 보강
-- orders 테이블: bgm_track, 생성 시간 추적, 실패 단계
-- order_events: 프론트/백엔드 이벤트 로깅
-- daily_funnel_metrics / hourly_sla_metrics: 집계 테이블

-- ── orders 누락 컬럼 추가 ─────────────────────────────────────────────

ALTER TABLE orders ADD COLUMN bgm_track VARCHAR(20) DEFAULT 'bgm_01';

ALTER TABLE orders ADD COLUMN gen_started_at DATETIME NULL;

ALTER TABLE orders ADD COLUMN gen_completed_at DATETIME NULL;

ALTER TABLE orders ADD COLUMN gen_minutes DECIMAL(6,2) NULL;

ALTER TABLE orders ADD COLUMN failure_stage VARCHAR(30) NULL;

-- ── order_events (이벤트 로깅) ────────────────────────────────────────

CREATE TABLE IF NOT EXISTS order_events (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    order_id      BIGINT       NULL,
    event_type    VARCHAR(40)  NOT NULL COMMENT 'page_view, pay_start, pay_success, upload_start, upload_complete, gen_start, gen_complete, gen_fail, notify_sent, download_click',
    source        VARCHAR(10)  NOT NULL DEFAULT 'server' COMMENT 'front | server',
    payload       JSON         NULL     COMMENT '자유 형식 추가 데이터',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_order_events_order (order_id),
    INDEX idx_order_events_type (event_type),
    INDEX idx_order_events_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── daily_funnel_metrics (일별 퍼널 집계) ─────────────────────────────

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
    revenue         INT      NOT NULL DEFAULT 0 COMMENT '원 단위',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_metric_date (metric_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── hourly_sla_metrics (시간별 SLA 집계) ──────────────────────────────

CREATE TABLE IF NOT EXISTS hourly_sla_metrics (
    id                BIGINT   NOT NULL AUTO_INCREMENT,
    metric_hour       DATETIME NOT NULL COMMENT '시각 기준 (분/초 = 0)',
    gen_count         INT      NOT NULL DEFAULT 0 COMMENT '해당 시간 완료 건수',
    avg_gen_minutes   DECIMAL(6,2) NULL COMMENT '평균 생성 시간(분)',
    max_gen_minutes   DECIMAL(6,2) NULL COMMENT '최대 생성 시간(분)',
    p95_gen_minutes   DECIMAL(6,2) NULL COMMENT '95퍼센타일 생성 시간(분)',
    fail_count        INT      NOT NULL DEFAULT 0,
    retry_count       INT      NOT NULL DEFAULT 0,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_metric_hour (metric_hour)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
