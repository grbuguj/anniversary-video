-- anniversary_video 데이터베이스 스키마
-- MySQL 8.0+

CREATE TABLE IF NOT EXISTS orders (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    customer_name        VARCHAR(50)  NOT NULL,
    customer_phone       VARCHAR(20)  NOT NULL,
    customer_email       VARCHAR(100),
    amount               INT          NOT NULL DEFAULT 29900,
    payment_key          VARCHAR(200),
    status               ENUM('PENDING','PAID','PROCESSING','COMPLETED','FAILED') NOT NULL DEFAULT 'PENDING',
    photo_count          INT,
    s3_input_path        VARCHAR(300),
    s3_output_path       VARCHAR(300),
    download_url         VARCHAR(500),
    download_expires_at  DATETIME,
    admin_memo           TEXT,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at),
    INDEX idx_payment_key (payment_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS order_photos (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_id    BIGINT       NOT NULL,
    s3_key      VARCHAR(300) NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    clip_s3_key VARCHAR(300),
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_order_id (order_id),
    CONSTRAINT fk_order_photos_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
