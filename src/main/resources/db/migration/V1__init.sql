-- 所有时间列为 UTC（连接参数 connectionTimeZone=UTC），精度到毫秒。

CREATE TABLE tenant (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    name           VARCHAR(100) NOT NULL,
    api_key_hash   CHAR(64)     NOT NULL COMMENT 'SHA-256(api key) 十六进制；明文只在创建/轮换时返回一次',
    api_key_prefix VARCHAR(16)  NOT NULL COMMENT '便于识别的前缀，非秘密',
    ingest_qps     INT          NOT NULL DEFAULT 50,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_api_key_hash (api_key_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE endpoint (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT        NOT NULL,
    url               VARCHAR(2048) NOT NULL,
    description       VARCHAR(255)  NULL,
    secret_ciphertext VARBINARY(256) NOT NULL COMMENT 'AES-256-GCM：iv(12) || 密文 || tag(16)',
    event_types       VARCHAR(1024) NOT NULL COMMENT '逗号分隔的订阅模式：*、order.*、order.created',
    enabled           TINYINT(1)    NOT NULL DEFAULT 1,
    max_attempts      INT           NOT NULL DEFAULT 8,
    deliver_qps       INT           NOT NULL DEFAULT 20,
    created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at        DATETIME(3)   NULL COMMENT '软删除：已有投递记录仍可追溯',
    PRIMARY KEY (id),
    KEY idx_endpoint_tenant (tenant_id, deleted_at),
    CONSTRAINT fk_endpoint_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE event (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash    CHAR(64)     NOT NULL COMMENT 'SHA-256(type \n payload)：同键不同内容视为冲突',
    type            VARCHAR(128) NOT NULL,
    payload         JSON         NOT NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_idempotency (tenant_id, idempotency_key),
    KEY idx_event_tenant_id (tenant_id, id),
    CONSTRAINT fk_event_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE delivery (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id        BIGINT       NOT NULL COMMENT '冗余，便于控制台按租户查询',
    event_id         BIGINT       NOT NULL,
    endpoint_id      BIGINT       NOT NULL,
    status           VARCHAR(16)  NOT NULL COMMENT 'PENDING / IN_FLIGHT / SUCCEEDED / DEAD',
    attempts         INT          NOT NULL DEFAULT 0,
    next_attempt_at  DATETIME(3)  NOT NULL,
    lease_until      DATETIME(3)  NULL,
    last_status_code INT          NULL,
    last_error       VARCHAR(512) NULL,
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_delivery_event_endpoint (event_id, endpoint_id),
    KEY idx_delivery_claim (status, next_attempt_at) COMMENT '抢任务：status=PENDING AND next_attempt_at<=NOW()',
    KEY idx_delivery_lease (status, lease_until) COMMENT '清扫：status=IN_FLIGHT AND lease_until<NOW()',
    KEY idx_delivery_tenant (tenant_id, id),
    KEY idx_delivery_endpoint (endpoint_id),
    CONSTRAINT fk_delivery_event FOREIGN KEY (event_id) REFERENCES event (id),
    CONSTRAINT fk_delivery_endpoint FOREIGN KEY (endpoint_id) REFERENCES endpoint (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE delivery_attempt (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    delivery_id      BIGINT        NOT NULL,
    attempt_no       INT           NOT NULL,
    started_at       DATETIME(3)   NOT NULL,
    duration_ms      INT           NOT NULL,
    status_code      INT           NULL,
    error            VARCHAR(512)  NULL,
    response_snippet VARCHAR(1024) NULL COMMENT '响应体截断，避免大响应撑爆表',
    PRIMARY KEY (id),
    UNIQUE KEY uk_attempt (delivery_id, attempt_no),
    CONSTRAINT fk_attempt_delivery FOREIGN KEY (delivery_id) REFERENCES delivery (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
