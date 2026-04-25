CREATE TABLE notification (
    id                  CHAR(36)        NOT NULL,
    recipient_id        VARCHAR(100)    NOT NULL,
    type                VARCHAR(40)     NOT NULL,
    channel             VARCHAR(20)     NOT NULL,
    dedup_key           VARCHAR(200)    NOT NULL,
    reference_data      JSON            NULL,
    status              VARCHAR(20)     NOT NULL,
    attempts            INT             NOT NULL DEFAULT 0,
    created_at          DATETIME(6)     NOT NULL,
    updated_at          DATETIME(6)     NOT NULL,
    next_attempt_at     DATETIME(6)     NOT NULL,
    claimed_at          DATETIME(6)     NULL,
    claimed_by          VARCHAR(100)    NULL,
    last_failure_reason VARCHAR(1000)   NULL,
    last_failure_at     DATETIME(6)     NULL,
    is_read             BOOLEAN         NOT NULL DEFAULT FALSE,
    read_at             DATETIME(6)     NULL,
    version             BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_notification_dedup UNIQUE (dedup_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX ix_notification_pending_due ON notification (status, next_attempt_at);
CREATE INDEX ix_notification_recipient   ON notification (recipient_id, created_at);
CREATE INDEX ix_notification_claim       ON notification (status, claimed_at);

CREATE TABLE notification_template (
    id              CHAR(36)        NOT NULL,
    type            VARCHAR(40)     NOT NULL,
    channel         VARCHAR(20)     NOT NULL,
    title_template  VARCHAR(200)    NULL,
    body_template   TEXT            NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_template UNIQUE (type, channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE in_app_message (
    id              CHAR(36)        NOT NULL,
    recipient_id    VARCHAR(100)    NOT NULL,
    notification_id CHAR(36)        NOT NULL,
    title           VARCHAR(200)    NOT NULL,
    body            TEXT            NULL,
    created_at      DATETIME(6)     NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX ix_inapp_recipient ON in_app_message (recipient_id, created_at);
