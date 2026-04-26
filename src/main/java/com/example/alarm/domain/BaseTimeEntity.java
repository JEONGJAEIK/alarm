package com.example.alarm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * JPA 엔티티의 공통 시간 필드를 제공하는 추상 슈퍼클래스.
 *
 * <p>{@link AuditingEntityListener}가 영속화 시점에 {@code createdAt}을, 갱신 시점에
 * {@code updatedAt}을 자동으로 채운다. 시간 소스는 {@code @EnableJpaAuditing}이 가리키는
 * {@code DateTimeProvider}이며, 이 프로젝트는 {@link java.time.Clock}을 위임받는 빈을 사용한다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
