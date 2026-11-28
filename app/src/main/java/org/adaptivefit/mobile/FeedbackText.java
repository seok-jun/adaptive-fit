package org.adaptivefit.mobile;

import android.content.Context;
import java.math.BigDecimal;
import java.util.Map;
import java.util.StringJoiner;

final class FeedbackText {
    interface Strings { String get(int resource, Object... values); }
    private final Strings strings;
    FeedbackText(Context context) { this(context::getString); }
    FeedbackText(Strings strings) { this.strings = strings; }

    static String number(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static String signed(BigDecimal value) { return (value.signum() > 0 ? "+" : "") + number(value); }
    private String text(int resource, Object... args) { return strings.get(resource, args); }

    String goal(String goal) {
        int label = R.string.feedback_no_goal;
        if (CoachingStore.LOSS.equals(goal)) label = R.string.goal_loss;
        if (CoachingStore.GROWTH.equals(goal)) label = R.string.goal_growth;
        if (CoachingStore.MAINTAIN.equals(goal)) label = R.string.goal_maintain;
        return text(R.string.feedback_goal, text(label));
    }

    private String performance(FeedbackRepository.Totals totals) {
        StringJoiner lines = new StringJoiner("\n");
        if (totals.sets() > 0) lines.add(text(R.string.feedback_sets, totals.sets(), totals.full(), totals.partial()));
        if (totals.minutes() > 0) lines.add(text(R.string.feedback_duration, totals.minutes()));
        return lines.toString();
    }

    private String exercises(FeedbackRepository.Totals totals) {
        StringJoiner lines = new StringJoiner("\n");
        for (Map.Entry<String,FeedbackRepository.Exercise> line : totals.exercises().entrySet()) {
            FeedbackRepository.Exercise e = line.getValue();
            lines.add(text(R.string.feedback_exercise, line.getKey(), e.sets(), e.full(), e.partial(), number(e.volume())));
            StringJoiner loads = new StringJoiner(" · ");
            e.repsAtWeight().forEach((kg, reps) -> loads.add(text(R.string.feedback_load, number(kg), reps)));
            lines.add(loads.toString());
        }
        return lines.toString();
    }

    private String measurements(FeedbackRepository.Measurements m) {
        if (m.first() == null) return text(R.string.feedback_no_measure);
        String first = text(R.string.feedback_measure, m.first(), number(m.firstKg()));
        if (m.delta() == null) return first;
        return first + "\n" + text(R.string.feedback_measure, m.last(), number(m.lastKg()))
                + "\n" + text(R.string.feedback_mass_change, signed(m.delta()));
    }

    private String comparison(FeedbackRepository.Result r) {
        StringJoiner lines = new StringJoiner("\n");
        if (r.previousRange() != null) lines.add(text(R.string.feedback_previous, r.previousRange().label()));
        if (r.compareSets()) lines.add(text(R.string.feedback_sets_change,
                signed(BigDecimal.valueOf(r.current().sets() - r.previous().sets()))));
        if (r.compareMinutes()) lines.add(text(R.string.feedback_minutes_change,
                signed(BigDecimal.valueOf(r.current().minutes() - r.previous().minutes()))));
        if (!r.compareSets() && !r.compareMinutes()) lines.add(text(R.string.feedback_no_compare));
        return lines.toString();
    }

    String summary(FeedbackRepository.Result r) {
        StringJoiner lines = new StringJoiner("\n");
        lines.add(text(R.string.feedback_current, r.range().label())
                + (r.ongoing() ? " · " + text(R.string.feedback_ongoing) : ""));
        lines.add(r.current().hasWorkout() ? performance(r.current()) : text(R.string.feedback_empty));
        if (r.current().sets() > 0) lines.add(exercises(r.current()));
        lines.add(measurements(r.weights()));
        lines.add(comparison(r));
        return lines.toString();
    }

    String feedback(FeedbackRepository.Result r, LocalFeedbackModel.Evidence choice) {
        if (choice == null || !choice.available(r)) throw new IllegalArgumentException("Unavailable evidence");
        String evidence = switch (choice) {
            case PERFORMANCE -> performance(r.current());
            case REPS -> exercises(r.current());
            case MINUTES -> text(R.string.feedback_duration, r.current().minutes());
            case WEIGHT -> measurements(r.weights()) + "\n" + performance(r.current());
            case COMPARISON -> comparison(r);
        };
        return text(R.string.feedback_ai, r.range().label(), evidence);
    }
}
