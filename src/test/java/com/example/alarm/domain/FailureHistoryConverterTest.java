package com.example.alarm.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FailureHistoryConverterTest {

    private final FailureHistoryConverter converter = new FailureHistoryConverter();

    @Test
    void round_trip은_원본과_동일한_엔트리_리스트를_반환한다() {
        List<FailureEntry> entries = List.of(
                new FailureEntry(Instant.parse("2026-04-26T10:00:00Z"), "SMTP 504", 0),
                new FailureEntry(Instant.parse("2026-04-26T11:00:00Z"), "SMTP 550", 1));

        String json = converter.convertToDatabaseColumn(entries);
        List<FailureEntry> back = converter.convertToEntityAttribute(json);

        assertThat(back).containsExactlyElementsOf(entries);
    }

    @Test
    void null_을_빈_배열로_정규화한다() {
        assertThat(converter.convertToDatabaseColumn(null)).isEqualTo("[]");
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
    }

    @Test
    void 빈_리스트는_빈_JSON_배열로_변환된다() {
        assertThat(converter.convertToDatabaseColumn(List.of())).isEqualTo("[]");
    }

    @Test
    void Instant_나노초_정밀도가_round_trip에_보존된다() {
        Instant nano = Instant.parse("2026-04-26T10:00:00.123456789Z");
        FailureEntry in = new FailureEntry(nano, "x", 0);

        String json = converter.convertToDatabaseColumn(List.of(in));
        Instant out = converter.convertToEntityAttribute(json).get(0).at();

        assertThat(out).isEqualTo(nano);
    }
}
