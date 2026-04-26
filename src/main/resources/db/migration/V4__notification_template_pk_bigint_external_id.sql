-- notification·notification_template PK를 BIGINT AUTO_INCREMENT로 변경
-- notification은 외부 노출(API URL)이 있으므로 UNIQUE external_id CHAR(16) 추가 — internal/external ID 분리
-- in_app_message.notification_id는 internal Long FK로 동기 변경

-- 자식 테이블 FK 컬럼 형식 변경
ALTER TABLE in_app_message DROP COLUMN notification_id;
ALTER TABLE in_app_message ADD COLUMN notification_id BIGINT NOT NULL AFTER recipient_id;
ALTER TABLE in_app_message ADD INDEX ix_inapp_notification_id (notification_id);

-- notification PK 재정의 + external_id 추가
ALTER TABLE notification DROP PRIMARY KEY;
ALTER TABLE notification DROP COLUMN id;
ALTER TABLE notification
    ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD COLUMN external_id CHAR(16) NOT NULL AFTER id,
    ADD PRIMARY KEY (id),
    ADD UNIQUE KEY uq_notification_external_id (external_id);

-- notification_template PK 재정의 (외부 노출 없으므로 external_id 없음)
ALTER TABLE notification_template DROP PRIMARY KEY;
ALTER TABLE notification_template DROP COLUMN id;
ALTER TABLE notification_template
    ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (id);
