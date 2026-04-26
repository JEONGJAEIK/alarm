package com.example.alarm.channel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * {@link InAppMessage}의 영속성 진입점.
 */
public interface InAppMessageRepository extends JpaRepository<InAppMessage, Long> {
    List<InAppMessage> findByRecipientIdOrderByCreatedAtDesc(String recipientId);
}
