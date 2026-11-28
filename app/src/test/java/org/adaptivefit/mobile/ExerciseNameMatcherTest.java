package org.adaptivefit.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class ExerciseNameMatcherTest {
    private static final String[] CATALOG = {"레그 프레스", "벤치 프레스", "랫 풀다운", "스쿼트"};

    private static String match(String... lines) {
        return ExerciseNameMatcher.match(Collections.singletonList(Arrays.asList(lines)), CATALOG);
    }

    @Test public void bothLanguagesReturnTheExistingKey() {
        String[] english = {"Leg Press", "ＢＥＮＣＨ-PRESS", "LAT PULL DOWN", "sqUaT"};
        for (int i = 0; i < CATALOG.length; i++) {
            assertEquals(CATALOG[i], match(CATALOG[i]));
            assertEquals(CATALOG[i], match(english[i]));
        }
        assertEquals("랫 풀다운", match("LAT PULLDOWN"));
        assertEquals("랫 풀다운", match("랫풀다운"));
    }

    @Test public void acceptsCompleteAdjacentLinesWithinOneBlockOnly() {
        assertEquals("벤치 프레스", match("SERIAL 123", "BENCH", "PRESS", "벤치 프레스"));
        assertEquals("랫 풀다운", match("LAT", "PULL", "DOWN"));
        List<List<String>> separate = Arrays.asList(Collections.singletonList("LEG"),
                Collections.singletonList("PRESS"));
        assertNull(ExerciseNameMatcher.match(separate, CATALOG));
    }

    @Test public void ambiguousUnknownAndPartialLabelsProduceNoCandidate() {
        assertNull(match());
        assertNull(match("  "));
        assertNull(match("PRESS"));
        assertNull(match("SQUATTING"));
        assertNull(match("NEW LEG PRESS MACHINE"));
        assertNull(match("레그 프레스", "BENCH PRESS"));
        assertNull(ExerciseNameMatcher.match(Arrays.asList(Collections.singletonList("SQUAT"),
                Collections.singletonList("LEG PRESS")), CATALOG));
        assertNull(ExerciseNameMatcher.match(Collections.singletonList(Collections.singletonList("SQUAT")),
                new String[]{"벤치 프레스"}));
    }
}
