package org.adaptivefit.mobile;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LocalFeedbackModel {
    enum Evidence {
        PERFORMANCE, REPS, MINUTES, WEIGHT, COMPARISON;

        boolean available(FeedbackRepository.Result r) {
            return switch (this) {
                case PERFORMANCE -> r.current().hasWorkout();
                case REPS -> r.current().sets() > 0;
                case MINUTES -> r.current().minutes() > 0;
                case WEIGHT -> r.weights().first() != null;
                case COMPARISON -> r.compareSets() || r.compareMinutes();
            };
        }
    }

    record Example(int order, String goal, Evidence evidence, double[] vector) {}
    private record Neighbor(Example example, double distance) {}
    private static final class Vote { int count; double distance; }
    private static final int FEATURES = 18;

    static Evidence predict(Context context, FeedbackRepository.Result result) throws IOException, JSONException {
        try (InputStream stream = context.getAssets().open("feedback/examples.json")) {
            return predict(stream, result);
        }
    }

    static Evidence predict(InputStream stream, FeedbackRepository.Result result) throws IOException, JSONException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) text.append(line);
        JSONObject model = new JSONObject(text.toString());
        if (model.getInt("version") != 1 || model.getString("source").isEmpty()) {
            throw new IOException("Unsupported feedback model");
        }
        JSONArray rows = model.getJSONArray("examples");
        List<Example> examples = new ArrayList<>();
        Set<Integer> orders = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            JSONArray values = row.getJSONArray("vector");
            int order = row.getInt("order");
            if (values.length() != FEATURES || !orders.add(order)) throw new IOException("Invalid model row");
            double[] vector = new double[FEATURES];
            for (int j = 0; j < vector.length; j++) {
                vector[j] = values.getDouble(j);
                if (!Double.isFinite(vector[j]) || Math.abs(vector[j]) > 1) throw new IOException("Invalid feature");
            }
            examples.add(new Example(order, row.getString("goal"), Evidence.valueOf(row.getString("evidence")), vector));
        }
        return classify(result, examples);
    }

    static double[] features(FeedbackRepository.Result r) {
        // Distribution across matched exercises preserves opposite load-volume changes across lifts.
        double[] volumeDirection = new double[3];
        int matched = 0;
        for (Map.Entry<String,FeedbackRepository.Exercise> e : r.current().exercises().entrySet()) {
            FeedbackRepository.Exercise previous = r.previous().exercises().get(e.getKey());
            if (previous == null) continue;
            int direction = e.getValue().volume().compareTo(previous.volume());
            volumeDirection[direction + 1]++;
            matched++;
        }
        if (matched > 0) {
            for (int i = 0; i < volumeDirection.length; i++) volumeDirection[i] /= matched;
        }
        return new double[]{
                flag(CoachingStore.LOSS.equals(r.goal())), flag(CoachingStore.GROWTH.equals(r.goal())),
                flag(CoachingStore.MAINTAIN.equals(r.goal())), flag("period".equals(r.kind())),
                flag("weight".equals(r.kind())), flag("cardio".equals(r.kind())), flag(r.current().sets() > 0),
                flag(r.current().minutes() > 0), flag(r.weights().first() != null), flag(r.compareSets()),
                flag(r.compareMinutes()), flag(matched > 0), volumeDirection[0], volumeDirection[1], volumeDirection[2],
                r.compareMinutes() ? Long.compare(r.current().minutes(), r.previous().minutes()) : 0,
                flag(r.weights().delta() != null), r.weights().delta() == null ? 0 : r.weights().delta().signum()
        };
    }

    private static int flag(boolean value) { return value ? 1 : 0; }

    static Evidence classify(FeedbackRepository.Result result, List<Example> examples) {
        if (result.goal() == null || !result.current().hasWorkout()) return null;
        double[] input = features(result);
        List<Neighbor> candidates = new ArrayList<>();
        for (Example e : examples) {
            if (!result.goal().equals(e.goal) || !e.evidence.available(result)) continue;
            double distance = 0;
            for (int i = 0; i < input.length; i++) {
                double difference = input[i] - e.vector[i];
                distance += difference * difference;
            }
            candidates.add(new Neighbor(e, distance));
        }
        candidates.sort(Comparator.comparingDouble(Neighbor::distance).thenComparingInt(n -> n.example.order));
        if (candidates.size() < 3) throw new IllegalStateException("Insufficient model examples");
        EnumMap<Evidence,Vote> votes = new EnumMap<>(Evidence.class);
        for (int i = 0; i < 3; i++) {
            Neighbor n = candidates.get(i);
            Vote vote = votes.computeIfAbsent(n.example.evidence, ignored -> new Vote());
            vote.count++;
            vote.distance += n.distance;
        }
        return votes.keySet().stream().min(Comparator.<Evidence>comparingInt(e -> -votes.get(e).count)
                .thenComparingDouble(e -> votes.get(e).distance).thenComparingInt(Enum::ordinal)).get();
    }
}
