package com.example.alarm.template;

import com.example.alarm.domain.NotificationChannelType;
import com.example.alarm.domain.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * {@link NotificationTemplate}의 영속성 진입점.
 */
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, String> {

    /**
     * 타입과 채널 조합으로 템플릿을 조회. 등록 안 된 조합은 빈 Optional.
     */
    Optional<NotificationTemplate> findByTypeAndChannel(NotificationType type,
                                                        NotificationChannelType channel);
}
