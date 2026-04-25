package com.example.alarm.service;

import com.example.alarm.domain.Notification;
import com.example.alarm.domain.NotificationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 INSERT를 독립 트랜잭션으로 처리하는 헬퍼 컴포넌트.
 *
 * <p>{@link NotificationService#register}의 dedup race condition 처리를 위해
 * INSERT를 {@code REQUIRES_NEW} 트랜잭션으로 격리한다. INSERT 실패 시 해당 트랜잭션만
 * 롤백되어 호출자 트랜잭션이 오염되지 않는다.
 */
@Component
class NotificationInsertHelper {

    private final NotificationRepository repo;

    NotificationInsertHelper(NotificationRepository repo) {
        this.repo = repo;
    }

    /**
     * 알림을 독립 트랜잭션에서 INSERT하고 flush한다.
     *
     * <p>dedup_key UNIQUE 제약 위반 시 {@link org.springframework.dao.DataIntegrityViolationException}이
     * 발생하며 이 트랜잭션만 롤백된다.
     *
     * @param n 저장할 알림 엔티티
     * @return flush 완료 후 저장된 알림 엔티티
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification insertAndFlush(Notification n) {
        return repo.saveAndFlush(n);
    }
}
