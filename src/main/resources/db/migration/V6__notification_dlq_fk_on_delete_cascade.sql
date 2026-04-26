-- notification_dlq.fk_dlq_notification에 ON DELETE CASCADE 부여
-- 운영상 notification은 hard-delete 정책이지만, 테스트/관리 작업의 정리 동작을 위해
-- cascade로 정의. notification 삭제 시 dlq 자식 row도 자동 삭제된다.

ALTER TABLE notification_dlq DROP FOREIGN KEY fk_dlq_notification;

ALTER TABLE notification_dlq
    ADD CONSTRAINT fk_dlq_notification FOREIGN KEY (notification_id)
        REFERENCES notification(id) ON DELETE CASCADE;
