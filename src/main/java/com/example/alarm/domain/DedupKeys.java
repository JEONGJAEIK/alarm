package com.example.alarm.domain;

import java.util.Locale;

/**
 * 알림 중복 제거 키(dedup key) 생성 유틸리티.
 *
 * <p>동일한 eventId와 channel 조합이 두 번 이상 처리되지 않도록 고유 키를 파생한다.
 * 인스턴스화 불가 유틸리티 클래스.
 */
public final class DedupKeys {
    private DedupKeys() {}

    /**
     * eventId와 channel로부터 중복 제거 키를 파생한다.
     *
     * <p>키 포맷: {@code "<eventId(소문자)>::<channelName>"}. eventId는
     * {@link Locale#ROOT}로 소문자 변환되므로 대소문자를 구별하지 않는다.
     * 동일 이벤트·채널 쌍에 대해 항상 동일한 키를 반환하므로 유니크 인덱스 조회에 사용할 수 있다.
     *
     * @param eventId 외부 이벤트 식별자 (blank 불가)
     * @param channel 알림 채널 유형 (null 불가)
     * @return 중복 제거 키 문자열
     * @throws IllegalArgumentException eventId가 blank이거나 channel이 null일 때
     */
    public static String derive(String eventId, NotificationChannelType channel) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId는 빈 값일 수 없습니다");
        }
        if (channel == null) {
            throw new IllegalArgumentException("channel은 null일 수 없습니다");
        }
        return eventId.toLowerCase(Locale.ROOT) + "::" + channel.name();
    }
}
