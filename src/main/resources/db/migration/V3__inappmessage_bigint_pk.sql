-- in_app_message PK를 CHAR(36) UUID에서 BIGINT AUTO_INCREMENT로 변경
-- 클러스터 인덱스 효율(sequential append, page split 회피) + PK 크기 절감(36→8 bytes)
-- 외부 노출 없는 inbox row이므로 UUID surrogate가 주는 분산 호환 가치는 단일 DB에서 불필요

ALTER TABLE in_app_message DROP PRIMARY KEY;
ALTER TABLE in_app_message DROP COLUMN id;
ALTER TABLE in_app_message
    ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (id);
