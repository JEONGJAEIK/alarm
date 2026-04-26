package com.example.alarm.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * {@link DeadLetter}에 대한 데이터 접근 인터페이스.
 *
 * <p>Spring Data JPA가 런타임에 구현체를 생성한다. 자동 진입 시점의
 * {@code findByNotificationId} 단건 조회와 관리자 화면용 페이지 조회를 지원한다.
 */
public interface DeadLetterRepository extends JpaRepository<DeadLetter, Long> {

    /**
     * notification_id로 DLQ row를 단건 조회한다.
     *
     * @param notificationId notification.id
     * @return 해당 알림의 DLQ row (없으면 empty)
     */
    Optional<DeadLetter> findByNotificationId(Long notificationId);

    /**
     * 모든 DLQ row를 페이지로 조회한다. 정렬은 호출자가 {@link Pageable}로 지정한다
     * (관리자 컨트롤러에서 {@code Sort.by("updatedAt").descending()} 강제).
     *
     * @param pageable 페이지/정렬 정보
     * @return DLQ 페이지
     */
    @Override
    Page<DeadLetter> findAll(Pageable pageable);
}
