package com.example.alarm.dispatch;

/**
 * 단일 polling tick에서 due한 알림을 클레임·발송·finalize까지 처리하는 책임을 정의.
 *
 * <p>현재 구현은 DB 폴링이지만, 인터페이스 분리로 향후 메시지 브로커 기반 구현으로 교체 가능.
 */
public interface NotificationDispatcher {

    /**
     * 한 번의 디스패치 사이클을 실행한다.
     *
     * @param workerId 호출 워커 식별자 (감사·디버깅용)
     * @return 이번 tick에 실제 처리된 알림 건수
     */
    int runOnce(String workerId);
}
