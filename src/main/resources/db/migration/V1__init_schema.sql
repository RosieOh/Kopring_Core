-- M1(1~5단계)까지의 스키마.
-- 한 번 적용된 마이그레이션 파일은 고치지 않는다. 바꿀 것이 생기면 V2, V3 ... 새 파일로 추가한다.
--
-- enum 컬럼은 VARCHAR 로만 두고 CHECK 제약을 걸지 않는다.
-- (Hibernate 가 자동 생성하던 CHECK 제약은 enum 값을 추가할 때마다 DDL 을 바꿔야 해서 뺐다)

CREATE TABLE member
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    email        VARCHAR(100) NOT NULL,
    password     VARCHAR(255) NOT NULL,
    nickname     VARCHAR(20)  NOT NULL,
    role         VARCHAR(20)  NOT NULL,
    phone_number VARCHAR(20)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_member_email UNIQUE (email)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE coupon_event
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    name            VARCHAR(50) NOT NULL,
    discount_type   VARCHAR(20) NOT NULL,
    discount_value  INT         NOT NULL,
    start_at        DATETIME(6) NOT NULL,
    end_at          DATETIME(6) NOT NULL,
    total_quantity  INT         NOT NULL,
    issued_quantity INT         NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE issued_coupon
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    coupon_event_id BIGINT      NOT NULL,
    member_id       BIGINT      NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- 1인 1매의 최후 방어선. (coupon_event_id) 로 시작하므로 이벤트별 발급 건수 조회도 이 인덱스를 탄다
    CONSTRAINT uk_issued_coupon_event_member UNIQUE (coupon_event_id, member_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
