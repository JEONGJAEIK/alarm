-- notification 라이프사이클·실패 사유를 분리:
--  1) notification.last_failure_reason 제거 (사용자 노출 안티패턴 회피)
--  2) notification_dlq 테이블 신설 (운영자 전용 상세 사유 + revive 이력)

ALTER TABLE notification DROP COLUMN last_failure_reason;

CREATE TABLE notification_dlq (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    notification_id BIGINT       NOT NULL,
    failure_history JSON         NOT NULL,
    revive_count    INT          NOT NULL DEFAULT 0,
    last_revived_at DATETIME(6)  NULL,
    last_revived_by VARCHAR(100) NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_dlq_notification UNIQUE (notification_id),
    CONSTRAINT fk_dlq_notification FOREIGN KEY (notification_id)
        REFERENCES notification(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX ix_dlq_updated_at ON notification_dlq (updated_at DESC);
