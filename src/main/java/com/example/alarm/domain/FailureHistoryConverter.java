package com.example.alarm.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * {@code List<FailureEntry>}와 JSON 문자열을 상호 변환하는 JPA AttributeConverter.
 *
 * <p>{@code null} 또는 빈 리스트는 {@code "[]"}로 정규화되어 NOT NULL 컬럼 제약을 만족시킨다.
 * 직렬화/역직렬화 실패 시 {@link IllegalStateException}을 던져 묵시적 데이터 손실을 방지한다.
 *
 * <p>JPA가 {@code @Converter}로 관리하는 인스턴스는 인자 없는 생성자로 만들어지므로,
 * Spring 주입에 의존하지 않고 {@link ReferenceDataConverter}와 동일하게 정적
 * {@link ObjectMapper}를 사용한다. 이로 인해 Spring이 구성하는 전역 ObjectMapper 빈
 * (예: 추가 등록 모듈, 직렬화 정책)과는 분리되어 동작하므로, 본 컨버터의 직렬화 동작은
 * 이 클래스 내 정적 매퍼 설정에만 의존한다. Jackson 3.x는 {@link java.time.Instant} 등
 * Java 8 시간 타입을 기본 지원한다.
 */
@Converter
public class FailureHistoryConverter implements AttributeConverter<List<FailureEntry>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<FailureEntry>> TYPE = new TypeReference<>() {};

    /**
     * 엔티티 속성을 DB 컬럼(JSON 문자열)으로 변환한다.
     *
     * @param attribute 변환할 리스트 ({@code null} 또는 빈 리스트면 {@code "[]"} 반환)
     * @return JSON 배열 문자열
     * @throws IllegalStateException Jackson 직렬화 실패 시
     */
    @Override
    public String convertToDatabaseColumn(List<FailureEntry> attribute) {
        try {
            return MAPPER.writeValueAsString(attribute == null ? List.of() : attribute);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "failure_history 직렬화 실패 (size=" + (attribute == null ? 0 : attribute.size()) + ")", e);
        }
    }

    /**
     * DB 컬럼(JSON 문자열)을 엔티티 속성으로 변환한다.
     *
     * @param dbData DB에서 읽은 JSON 문자열 ({@code null} 또는 blank이면 빈 리스트 반환)
     * @return 파싱된 리스트 또는 빈 리스트
     * @throws IllegalStateException Jackson 역직렬화 실패 시
     */
    @Override
    public List<FailureEntry> convertToEntityAttribute(String dbData) {
        try {
            if (dbData == null || dbData.isBlank()) return List.of();
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "failure_history 역직렬화 실패 (length=" + (dbData == null ? 0 : dbData.length()) + ")", e);
        }
    }
}
