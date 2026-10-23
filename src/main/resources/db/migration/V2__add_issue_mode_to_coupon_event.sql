-- 발급 방식(DIRECT / QUEUE). 이미 등록된 이벤트는 기존 동작과 같은 즉시 발급(DIRECT)으로 둔다.
ALTER TABLE coupon_event
    ADD COLUMN issue_mode VARCHAR(20) NOT NULL DEFAULT 'DIRECT' AFTER total_quantity;
