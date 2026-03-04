-- V5: accessToken 보안 컬럼 추가 (고객 URL에 orderId 대신 사용)

ALTER TABLE orders ADD COLUMN access_token VARCHAR(36) NULL;

-- 기존 데이터에 UUID 생성 (MySQL 8.0+)
UPDATE orders SET access_token = UUID() WHERE access_token IS NULL;

-- NOT NULL + UNIQUE 제약 추가
ALTER TABLE orders MODIFY COLUMN access_token VARCHAR(36) NOT NULL;
ALTER TABLE orders ADD UNIQUE INDEX uk_access_token (access_token);
