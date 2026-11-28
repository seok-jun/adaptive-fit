package org.adaptivefit.mobile;

import static org.junit.Assert.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class DailyCoachTest {
    @Test public void responsesMustBelongToRequestAndCiteItsEvidence() {
        List<DailyCoach.Evidence> data = new ArrayList<>();
        data.add(new DailyCoach.Evidence("yesterday", "전날 실제 2세트"));
        DailyCoach.Request request = new DailyCoach.Request("r", "벤치 프레스", "오늘 목표?", "growth",
                LocalDate.of(2026, 1, 2), ZoneId.of("Asia/Seoul"), null, null, data);
        data.clear();
        DailyCoach.Target lift = new DailyCoach.Target(BigDecimal.ZERO, 8, 2, 0);
        List<String> refs = new ArrayList<>(Arrays.asList("yesterday"));
        DailyCoach.Response answer = new DailyCoach.Response("r", "코칭", refs, lift);
        refs.clear();
        assertTrue(answer.valid(request));
        assertTrue(new DailyCoach.Response("r", "실적 설명", answer.refs(), null).valid(request));
        assertFalse(new DailyCoach.Response("old", "코칭", answer.refs(), lift).valid(request));
        assertFalse(new DailyCoach.Response("r", "코칭", Arrays.asList("other"), lift).valid(request));
        assertFalse(new DailyCoach.Response("r", "코칭", null, lift).valid(request));
        assertFalse(new DailyCoach.Response("r", " ", answer.refs(), lift).valid(request));
        assertFalse(new DailyCoach.Response("r", "코칭", answer.refs(),
                new DailyCoach.Target(null, 0, 0, 20)).valid(request));
    }

    @Test public void dailyTargetsKeepExerciseUnitsSeparate() {
        assertTrue(new DailyCoach.Target(null, 0, 0, 20).valid(true));
        assertFalse(new DailyCoach.Target(null, 0, 0, 0).valid(true));
        assertFalse(new DailyCoach.Target(BigDecimal.ONE, 1, 1, 20).valid(true));
        assertFalse(new DailyCoach.Target(BigDecimal.ONE.negate(), 1, 1, 0).valid(false));
        assertFalse(new DailyCoach.Target(BigDecimal.ONE, 101, 1, 0).valid(false));
        assertFalse(new DailyCoach.Target(BigDecimal.ONE, 1, 0, 0).valid(false));
    }
}
