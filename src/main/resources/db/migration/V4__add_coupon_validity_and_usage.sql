-- 쿠폰 유효기간과 사용 처리.

-- 이벤트마다 "발급 후 며칠 동안 쓸 수 있는지". 기존 이벤트는 30일.
ALTER TABLE coupon_event
    ADD COLUMN coupon_validity_days INT NOT NULL DEFAULT 30 AFTER issue_mode;

-- 발급된 쿠폰의 만료 시각, 사용 시각, 할인액, 낙관적 락 버전
ALTER TABLE issued_coupon
    ADD COLUMN expires_at      DATETIME(6) NULL AFTER member_id,
    ADD COLUMN used_at         DATETIME(6) NULL AFTER expires_at,
    ADD COLUMN discount_amount INT         NULL AFTER used_at,
    ADD COLUMN version         BIGINT      NOT NULL DEFAULT 0 AFTER discount_amount;

-- 이미 발급된 쿠폰은 발급 시각 + 30일로 채운 뒤 NOT NULL 로 바꾼다
UPDATE issued_coupon
SET expires_at = DATE_ADD(created_at, INTERVAL 30 DAY)
WHERE expires_at IS NULL;

ALTER TABLE issued_coupon
    MODIFY COLUMN expires_at DATETIME(6) NOT NULL;

-- 내 쿠폰 목록 조회용 (회원별 최신순)
CREATE INDEX idx_issued_coupon_member ON issued_coupon (member_id, id);
