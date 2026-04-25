-- 사용자 알림 목록 + read 필터 쿼리 커버
CREATE INDEX ix_notification_recipient_read
    ON notification (recipient_id, is_read, created_at DESC);

-- 데드레터 목록 쿼리 (status='DEAD_LETTER' ORDER BY updated_at DESC) 커버
CREATE INDEX ix_notification_status_updated
    ON notification (status, updated_at DESC);

-- in_app_message 알림별 조회 (향후 admin/디버깅용) 커버
CREATE INDEX ix_inapp_notification_id
    ON in_app_message (notification_id);
