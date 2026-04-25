package com.example.alarm.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Map;

/**
 * {@code Map<String, Object>} 타입의 referenceData를 JSON 문자열로 변환하는 JPA 컨버터.
 *
 * <p>직렬화 실패 시 {@link IllegalStateException}을 던져 묵시적 데이터 손실을 방지한다.
 * {@code null} 또는 빈 맵은 DB에 {@code NULL}로 저장하고, 읽을 때는 빈 맵으로 복원한다.
 */
@Converter
public class ReferenceDataConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> TYPE = new TypeReference<>() {};

    /**
     * 엔티티 속성을 DB 컬럼(JSON 문자열)으로 변환한다.
     *
     * @param attribute 변환할 맵 (null 또는 빈 맵이면 {@code null} 반환)
     * @return JSON 문자열 또는 {@code null}
     * @throws IllegalStateException Jackson 직렬화 실패 시
     */
    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        if (attribute == null || attribute.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("referenceData 직렬화 실패", e);
        }
    }

    /**
     * DB 컬럼(JSON 문자열)을 엔티티 속성으로 변환한다.
     *
     * @param dbData DB에서 읽은 JSON 문자열 (null 또는 blank이면 빈 맵 반환)
     * @return 파싱된 맵 또는 빈 맵
     * @throws IllegalStateException Jackson 역직렬화 실패 시
     */
    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("referenceData 역직렬화 실패", e);
        }
    }
}
