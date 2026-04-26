package com.example.alarm.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterTest {

    private static final Instant T0 = Instant.parse("2026-04-26T10:00:00Z");

    @Test
    void create는_첫_실패_사유와_reviveCount_0인_history_1건을_만든다() {
        DeadLetter d = DeadLetter.create(42L, "SMTP 504", T0);

        assertThat(d.getNotificationId()).isEqualTo(42L);
        assertThat(d.getReviveCount()).isZero();
        assertThat(d.getLastRevivedAt()).isNull();
        assertThat(d.getLastRevivedBy()).isNull();
        assertThat(d.getFailureHistory()).hasSize(1);
        assertThat(d.getFailureHistory().get(0))
                .isEqualTo(new FailureEntry(T0, "SMTP 504", 0));
    }

    @Test
    void appendFailure는_history에_새_항목을_push하고_reviveCount는_미변경() {
        DeadLetter d = DeadLetter.create(42L, "first", T0);
        d.markRevived("admin1", T0.plusSeconds(60));

        d.appendFailure("second", T0.plusSeconds(120));

        assertThat(d.getFailureHistory()).hasSize(2);
        assertThat(d.getFailureHistory().get(1))
                .isEqualTo(new FailureEntry(T0.plusSeconds(120), "second", 1));
        assertThat(d.getReviveCount()).isEqualTo(1);
    }

    @Test
    void appendFailure가_상한_10을_초과하면_가장_오래된_항목이_drop된다() {
        DeadLetter d = DeadLetter.create(42L, "r0", T0);
        for (int i = 1; i < 11; i++) {
            d.appendFailure("r" + i, T0.plusSeconds(i));
        }

        assertThat(d.getFailureHistory()).hasSize(10);
        assertThat(d.getFailureHistory().get(0).reason()).isEqualTo("r1");
        assertThat(d.getFailureHistory().get(9).reason()).isEqualTo("r10");
    }

    @Test
    void markRevived는_count_증가_적시_관리자_기록하고_history는_미변경() {
        DeadLetter d = DeadLetter.create(42L, "first", T0);
        var before = d.getFailureHistory();

        d.markRevived("admin42", T0.plusSeconds(60));

        assertThat(d.getReviveCount()).isEqualTo(1);
        assertThat(d.getLastRevivedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(d.getLastRevivedBy()).isEqualTo("admin42");
        assertThat(d.getFailureHistory()).isEqualTo(before);
    }

    @Test
    void appendFailure의_사유가_1000자를_초과하면_truncate된다() {
        DeadLetter d = DeadLetter.create(42L, "r0", T0);
        String huge = "x".repeat(1500);

        d.appendFailure(huge, T0.plusSeconds(1));

        assertThat(d.getFailureHistory().get(1).reason()).hasSize(1000);
    }
}
