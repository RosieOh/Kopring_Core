-- 보낼 알림을 발급과 같은 트랜잭션에서 기록해 두는 Outbox.
-- 발급이 커밋되면 알림 기록도 반드시 남으므로, 발송 직전에 서버가 죽어도 재기동 후 이어서 보낼 수 있다.
CREATE TABLE notification_outbox
(
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    event_type      VARCHAR(50)   NOT NULL,
    payload         VARCHAR(1000) NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    attempt_count   INT           NOT NULL,
    next_attempt_at DATETIME(6)   NOT NULL,
    last_error      VARCHAR(500)  NULL,
    processed_at    DATETIME(6)   NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    -- 스케줄러가 "보낼 차례가 된 PENDING" 을 찾는 조회용
    KEY idx_notification_outbox_status_next_attempt (status, next_attempt_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
