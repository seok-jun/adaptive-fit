package org.adaptivefit.mobile;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

final class FeedbackRepository {
    enum Unit { DAY, WEEK, MONTH }

    record Range(LocalDate start, LocalDate end, ZoneId zone) {
        static Range of(LocalDate day, Unit unit, ZoneId zone) {
            LocalDate start = switch (unit) {
                case DAY -> day;
                case WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                case MONTH -> day.withDayOfMonth(1);
            };
            LocalDate end = switch (unit) {
                case DAY -> start.plusDays(1);
                case WEEK -> start.plusWeeks(1);
                case MONTH -> start.plusMonths(1);
            };
            return new Range(start, end, zone);
        }

        long from() { return start.atStartOfDay(zone).toInstant().toEpochMilli(); }
        long until() { return end.atStartOfDay(zone).toInstant().toEpochMilli(); }
        String label() { return start.equals(end.minusDays(1)) ? start.toString() : start + " ~ " + end.minusDays(1); }
    }

    record Exercise(long sets, long full, long partial, BigDecimal volume, Map<BigDecimal,Long> repsAtWeight) {}
    record Totals(Map<String, Exercise> exercises, long minutes, long sets, long full, long partial) {
        boolean hasWorkout() { return sets > 0 || minutes > 0; }
    }

    static final class Builder {
        private static final class ExerciseRows {
            long sets, full, partial;
            BigDecimal volume = BigDecimal.ZERO;
            final Map<BigDecimal,Long> weights = new TreeMap<>();
        }
        private final Map<String,ExerciseRows> exercises = new TreeMap<>();
        private long minutes;

        void set(String name, BigDecimal kg, int reps, int partial) {
            ExerciseRows row = exercises.computeIfAbsent(name, ignored -> new ExerciseRows());
            row.sets++;
            row.full += reps - partial;
            row.partial += partial;
            row.volume = row.volume.add(kg.multiply(BigDecimal.valueOf(reps)));
            row.weights.merge(kg, (long) reps, Long::sum);
        }

        void cardio(long duration) { minutes += duration; }

        Totals build() {
            Map<String,Exercise> result = new TreeMap<>();
            long sets = 0, full = 0, partial = 0;
            for (Map.Entry<String,ExerciseRows> entry : exercises.entrySet()) {
                ExerciseRows e = entry.getValue();
                result.put(entry.getKey(), new Exercise(e.sets, e.full, e.partial, e.volume,
                        Collections.unmodifiableMap(new TreeMap<>(e.weights))));
                sets += e.sets;
                full += e.full;
                partial += e.partial;
            }
            return new Totals(Collections.unmodifiableMap(result), minutes, sets, full, partial);
        }
    }

    record Measurements(LocalDate first, BigDecimal firstKg, LocalDate last, BigDecimal lastKg) {
        BigDecimal delta() { return first == null || first.equals(last) ? null : lastKg.subtract(firstKg); }
    }

    record Result(String kind, String goal, Range range, Range previousRange, boolean ongoing,
                  Totals current, Totals previous, Measurements weights) {
        boolean compareSets() { return current.sets > 0 && previous.sets > 0; }
        boolean compareMinutes() { return current.minutes > 0 && previous.minutes > 0; }
    }

    private final WorkoutStore workouts;
    private final CoachingStore coaching;
    FeedbackRepository(WorkoutStore workouts, CoachingStore coaching) {
        this.workouts = workouts;
        this.coaching = coaching;
    }

    private static Totals totals(SQLiteDatabase db, Range range, Long session) {
        boolean period = session == null;
        String[] args = period ? new String[]{Long.toString(range.from()), Long.toString(range.until())}
                : new String[]{Long.toString(session)};
        Builder result = new Builder();
        try (Cursor sets = db.rawQuery("SELECT exercise,weight,reps,partial FROM actual WHERE "
                + (period ? "recorded_at>=? AND recorded_at<?" : "session_id=?"), args)) {
            while (sets.moveToNext()) {
                result.set(sets.getString(0), new BigDecimal(sets.getString(1)), sets.getInt(2), sets.getInt(3));
            }
        }
        try (Cursor rows = db.rawQuery("SELECT cardio_minutes FROM workout_session WHERE kind='cardio' AND "
                + (period ? "ended_at>=? AND ended_at<?" : "id=?"), args)) {
            while (rows.moveToNext()) result.cardio(rows.getLong(0));
        }
        return result.build();
    }

    private Measurements measurements(Range range) {
        LocalDate first = null, last = null;
        BigDecimal firstKg = null, lastKg = null;
        for (LocalDate date : coaching.measurementDates(range.start, range.end)) {
            BigDecimal kg = coaching.weight(date);
            if (kg == null) continue;
            if (first == null) {
                first = date;
                firstKg = kg;
            }
            last = date;
            lastKg = kg;
        }
        return new Measurements(first, firstKg, last, lastKg);
    }

    Result period(LocalDate day, Unit unit) {
        ZoneId zone = ZoneId.systemDefault();
        String goal = coaching.goal();
        Range range = Range.of(day, unit, zone);
        Range before = Range.of(range.start.minusDays(1), unit, zone);
        LocalDate today = LocalDate.now(zone);
        SQLiteDatabase db = workouts.getReadableDatabase();
        Totals current, previous;
        db.beginTransactionNonExclusive();
        try {
            current = totals(db, range, null);
            previous = totals(db, before, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return new Result("period", goal, range, before, !today.isBefore(range.start) && today.isBefore(range.end),
                current, previous, measurements(range));
    }

    private record Session(long id, String kind, long ended, Range range) {}
    private static final String SESSION = "SELECT id,kind,ended_at,"
            + "(SELECT MIN(recorded_at) FROM actual WHERE session_id=workout_session.id) FROM workout_session ";

    private static Session session(SQLiteDatabase db, ZoneId zone, String filter, String... args) {
        try (Cursor row = db.rawQuery(SESSION + filter, args)) {
            if (!row.moveToFirst()) return null;
            long end = row.getLong(2);
            long first = row.isNull(3) ? end : Math.min(end, row.getLong(3));
            Range range = new Range(Instant.ofEpochMilli(first).atZone(zone).toLocalDate(),
                    Instant.ofEpochMilli(end).atZone(zone).toLocalDate().plusDays(1), zone);
            return new Session(row.getLong(0), row.getString(1), end, range);
        }
    }

    Result receipt(long id) {
        ZoneId zone = ZoneId.systemDefault();
        String goal = coaching.goal();
        SQLiteDatabase db = workouts.getReadableDatabase();
        Session target, prior;
        Totals current, previous;
        db.beginTransactionNonExclusive();
        try {
            target = session(db, zone, "WHERE id=? AND ended_at IS NOT NULL", Long.toString(id));
            if (target == null) throw new IllegalStateException("Missing receipt");
            String end = Long.toString(target.ended);
            prior = session(db, zone, "WHERE kind=? AND (ended_at<? OR (ended_at=? AND id<?)) "
                    + "ORDER BY ended_at DESC,id DESC LIMIT 1", target.kind, end, end, Long.toString(id));
            current = totals(db, target.range, target.id);
            previous = prior == null ? new Builder().build() : totals(db, prior.range, prior.id);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return new Result(target.kind, goal, target.range, prior == null ? null : prior.range, false,
                current, previous, measurements(target.range));
    }
}
