package com.example.alarm.domain;

import java.time.Instant;

/**
 * DLQ failure_history JSON 컬럼의 단일 항목을 표현하는 immutable record.
 *
 * <p>알림이 DLQ로 이동할 때마다 한 항목이 누적되어 {@code List<FailureEntry>}로 직렬화된다.
 * 관리자 API에서 실패 추이를 조회할 때 사용된다.
 *
 * @param at          실패가 push된 시각 (UTC)
 * @param reason      관리자 노출용 구체 실패 사유 (1000자 이내 권고)
 * @param reviveCount 이 항목이 push될 시점의 dlq.revive_count 값
 */
public record FailureEntry(Instant at, String reason, int reviveCount) {}
