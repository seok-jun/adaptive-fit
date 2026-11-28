package org.adaptivefit.mobile;

import static org.junit.Assert.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.BeforeClass;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class FeedbackContractTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final LocalDate DAY = LocalDate.of(2025, 1, 1);
    private static final FeedbackRepository.Range RANGE = FeedbackRepository.Range.of(DAY, FeedbackRepository.Unit.WEEK, SEOUL);
    private static final List<LocalFeedbackModel.Example> EXAMPLES = new ArrayList<>();
    private static FeedbackText text;
    private static final FeedbackRepository.Measurements NONE = new FeedbackRepository.Measurements(null, null, null, null);

    @BeforeClass public static void installProductFixtures() throws Exception {
        // android.org.json is a stub in host tests. Load the shipped rows for the same pure classifier.
        String json = new String(Files.readAllBytes(Paths.get("src/main/assets/feedback/examples.json")),
                StandardCharsets.UTF_8);
        Pattern row = Pattern.compile("\\{\"order\":(\\d+),\"goal\":\"([a-z]+)\",\"evidence\":\"([A-Z]+)\",\"vector\":\\[([^]]+)\\]\\}");
        Matcher match = row.matcher(json);
        while (match.find()) {
            double[] vector = Arrays.stream(match.group(4).split(",")).mapToDouble(Double::parseDouble).toArray();
            EXAMPLES.add(new LocalFeedbackModel.Example(Integer.parseInt(match.group(1)), match.group(2),
                    LocalFeedbackModel.Evidence.valueOf(match.group(3)), vector));
        }
        assertTrue(EXAMPLES.size() >= 9);
        Map<Integer,String> strings = new HashMap<>();
        NodeList entries = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse("src/main/res/values/strings.xml").getElementsByTagName("string");
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            strings.put(R.string.class.getField(entry.getAttribute("name")).getInt(null),
                    entry.getTextContent().replace("\\n", "\n"));
        }
        text = new FeedbackText((id, args) -> String.format(Locale.ROOT, strings.get(id), args));
    }

    private static FeedbackRepository.Totals actuals() {
        FeedbackRepository.Builder totals = new FeedbackRepository.Builder();
        totals.set("레그 프레스", new BigDecimal("40.00"), 15, 5);
        totals.set("벤치 프레스", new BigDecimal("20"), 8, 0);
        totals.cardio(25);
        return totals.build();
    }

    private static FeedbackRepository.Result result(String goal, FeedbackRepository.Totals totals,
             FeedbackRepository.Totals previous, FeedbackRepository.Measurements m) {
        return new FeedbackRepository.Result("period", goal, RANGE,
                FeedbackRepository.Range.of(RANGE.start().minusDays(1), FeedbackRepository.Unit.WEEK, SEOUL),
                true, totals, previous, m);
    }

    @Test public void measuresOnlyRecordedSetsAndMinutes() {
        FeedbackRepository.Totals totals = actuals();
        assertEquals(2, totals.sets());
        assertEquals(18, totals.full());
        assertEquals(5, totals.partial());
        assertEquals(25, totals.minutes());
        assertEquals("600", FeedbackText.number(totals.exercises().get("레그 프레스").volume()));
        assertEquals("160", FeedbackText.number(totals.exercises().get("벤치 프레스").volume()));
        assertEquals(Long.valueOf(15), totals.exercises().get("레그 프레스").repsAtWeight().get(new BigDecimal("40")));
        FeedbackRepository.Builder durations = new FeedbackRepository.Builder();
        durations.cardio(Integer.MAX_VALUE);
        durations.cardio(1);
        assertEquals(2147483648L, durations.build().minutes());
    }

    @Test public void calendarBoundariesRetainInstantsAndRespectZoneChanges() {
        assertEquals(LocalDate.of(2024, 12, 30), RANGE.start());
        assertEquals(LocalDate.of(2025, 1, 6), RANGE.end());
        assertEquals(LocalDate.of(2024, 12, 23), FeedbackRepository.Range.of(LocalDate.of(2024, 12, 29),
                FeedbackRepository.Unit.WEEK, SEOUL).start());
        FeedbackRepository.Range month = FeedbackRepository.Range.of(RANGE.start(), FeedbackRepository.Unit.MONTH, SEOUL);
        assertEquals(DAY, month.end());
        assertEquals(LocalDate.of(2024,12,1), month.start());
        FeedbackRepository.Range january = FeedbackRepository.Range.of(DAY, FeedbackRepository.Unit.MONTH, SEOUL);
        assertEquals(january.from(), month.until());
        long[] instants = {month.from() - 1, month.from(), month.until() - 1, month.until()};
        assertArrayEquals(new long[]{month.from(), month.until() - 1}, Arrays.stream(instants)
                .filter(t -> t >= month.from() && t < month.until()).toArray());
        long time = Instant.parse("2024-12-31T16:00:00Z").toEpochMilli();
        assertTrue(time >= january.from() && time < january.until());
        FeedbackRepository.Range easternJanuary = FeedbackRepository.Range.of(DAY, FeedbackRepository.Unit.MONTH, NY);
        assertTrue(time < easternJanuary.from());
        for (int[] scenario : new int[][]{{3,9,23},{11,2,25}}) {
            FeedbackRepository.Range change = FeedbackRepository.Range.of(LocalDate.of(2025,scenario[0],scenario[1]),
                    FeedbackRepository.Unit.DAY, NY);
            assertEquals(scenario[2], Duration.ofMillis(change.until()-change.from()).toHours());
        }
    }

    @Test public void goalHoldoutsSelectAvailableGroundedEvidence() {
        FeedbackRepository.Builder prior = new FeedbackRepository.Builder();
        prior.set("레그 프레스", new BigDecimal("35"), 10, 0);
        prior.set("벤치 프레스", new BigDecimal("25"), 8, 0);
        prior.cardio(33);
        FeedbackRepository.Measurements measured = new FeedbackRepository.Measurements(DAY, new BigDecimal("70"),
                DAY.plusDays(1), new BigDecimal("69"));
        assertEquals(new BigDecimal("-1"), measured.delta());
        String[] goals = {"loss", "growth", "maintain"};
        LocalFeedbackModel.Evidence[] expected = {LocalFeedbackModel.Evidence.WEIGHT,
                LocalFeedbackModel.Evidence.REPS, LocalFeedbackModel.Evidence.COMPARISON};
        String[] values = {"체중 차이 -1 kg", "600 kg×회", "시간 차이 -8분"};
        for (int i = 0; i < goals.length; i++) {
            FeedbackRepository.Result r = result(goals[i], actuals(), prior.build(), measured);
            double[] features = LocalFeedbackModel.features(r);
            assertEquals(0.5, features[12], 0);
            assertEquals(0.5, features[14], 0);
            assertFalse(EXAMPLES.stream().anyMatch(e -> Arrays.equals(features, e.vector())));
            LocalFeedbackModel.Evidence output = LocalFeedbackModel.classify(r, EXAMPLES);
            assertEquals(expected[i], output);
            String message = text.feedback(r, output);
            assertTrue(message, message.contains(values[i]));
            assertTrue(message.contains(RANGE.label()));
            String summary = text.summary(r);
            for (String s : new String[]{"진행 중","2024-12-23","전 구간 18회","구간 반복 5회","160 kg×회","유산소 25분"}) {
                assertTrue(summary, summary.contains(s));
            }
        }
    }

    @Test public void absentInputsAndCardioOnlyAreGrounded() {
        FeedbackRepository.Totals empty = new FeedbackRepository.Builder().build();
        FeedbackRepository.Builder cardio = new FeedbackRepository.Builder();
        cardio.cardio(25);
        FeedbackRepository.Measurements one = new FeedbackRepository.Measurements(DAY, BigDecimal.TEN, DAY, BigDecimal.TEN);
        assertNull(one.delta());
        FeedbackRepository.Result r = result("growth", cardio.build(), empty, one);
        assertEquals(LocalFeedbackModel.Evidence.MINUTES, LocalFeedbackModel.classify(r, EXAMPLES));
        assertTrue(text.feedback(r, LocalFeedbackModel.Evidence.MINUTES).contains("유산소 25분"));
        String summary = text.summary(r);
        assertFalse(summary.contains("체중 차이"));
        assertFalse(summary.contains("세트"));
        assertTrue(summary.contains("비교할 운동 기록이 부족"));
        assertTrue(text.summary(result(null, empty, empty, NONE)).contains("미측정"));
        assertNull(LocalFeedbackModel.classify(result(null, actuals(), empty, NONE), EXAMPLES));
        assertNull(LocalFeedbackModel.classify(result("loss", empty, empty, NONE), EXAMPLES));
        assertEquals(LocalFeedbackModel.Evidence.PERFORMANCE,
                LocalFeedbackModel.classify(result("maintain", actuals(), empty, NONE), EXAMPLES));
    }
}
