package org.adaptivefit.mobile;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Stores daily plans independently of performed sets and completed cardio sessions. */
final class DailyGoals {
    private final WorkoutStore workouts;

    DailyGoals(WorkoutStore workouts) {
        this.workouts = workouts;
    }

    DailyCoach.Target target(String exercise, LocalDate day) {
        return readTarget(workouts.getReadableDatabase(), exercise, day);
    }

    private DailyCoach.Target readTarget(SQLiteDatabase db, String exercise, LocalDate day) {
        try (Cursor row = db.rawQuery("SELECT weight,reps,sets,minutes FROM daily_goal "
                + "WHERE day=? AND kind=? AND exercise=?", new String[]{day.toString(),
                exercise == null ? "cardio" : "weight", exercise == null ? "" : exercise})) {
            if (!row.moveToFirst()) return null;
            return targetFrom(row);
        }
    }

    private static DailyCoach.Target targetFrom(Cursor row) {
        return new DailyCoach.Target(row.isNull(0) ? null : new BigDecimal(row.getString(0)),
                row.getInt(1), row.getInt(2), row.getInt(3));
    }

    DefaultCoach.State saved(String exercise, LocalDate day) {
        try (Cursor row = workouts.getReadableDatabase().rawQuery("SELECT weight,reps,sets,minutes,feedback,citations"
                + " FROM daily_goal WHERE day=? AND kind=? AND exercise=?", new String[]{day.toString(),
                exercise == null ? "cardio" : "weight", exercise == null ? "" : exercise})) {
            if (!row.moveToFirst()) return null;
            List<String> citations = new ArrayList<>();
            if (!row.isNull(5)) {
                try {
                    JSONArray json = new JSONArray(row.getString(5));
                    for (int i = 0; i < json.length(); i++) citations.add(json.getString(i));
                } catch (JSONException e) {
                    throw new IllegalStateException("Invalid daily coaching evidence", e);
                }
            }
            return new DefaultCoach.State(DefaultCoach.Status.READY, targetFrom(row), row.getString(4),
                    Collections.unmodifiableList(citations));
        }
    }

    void saveFirst(DailyCoach.Request request, DailyCoach.Response response) {
        if (!response.valid(request) || response.proposal() == null) {
            throw new IllegalArgumentException("Invalid default coaching response");
        }
        JSONArray citations = new JSONArray();
        for (DailyCoach.Evidence item : request.evidence()) {
            if (response.refs().contains(item.ref())) citations.put(item.text());
        }
        String exercise = request.exercise();
        DailyCoach.Target target = response.proposal();
        workouts.getWritableDatabase().execSQL("INSERT OR IGNORE INTO daily_goal "
                + "(day,kind,exercise,weight,reps,sets,minutes,feedback,citations) VALUES (?,?,?,?,?,?,?,?,?)",
                new Object[]{request.day().toString(), exercise == null ? "cardio" : "weight",
                        exercise == null ? "" : exercise, target.kg() == null ? null : target.kg().toPlainString(),
                        target.reps(), target.sets(), target.minutes(), response.feedback(), citations.toString()});
    }

    void save(String exercise, LocalDate day, DailyCoach.Target target) {
        if (day == null || (exercise != null && exercise.trim().isEmpty())
                || target == null || !target.valid(exercise == null)) {
            throw new IllegalArgumentException("Invalid daily workout goal");
        }
        workouts.getWritableDatabase().execSQL("INSERT OR REPLACE INTO daily_goal "
                + "(day,kind,exercise,weight,reps,sets,minutes) VALUES (?,?,?,?,?,?,?)",
                new Object[]{day.toString(), exercise == null ? "cardio" : "weight", exercise == null ? "" : exercise,
                        target.kg() == null ? null : target.kg().toPlainString(),
                        target.reps(), target.sets(), target.minutes()});
    }

    DailyCoach.Request request(String exercise, String question, String goal, LocalDate day, ZoneId zone) {
        SQLiteDatabase db = workouts.getReadableDatabase();
        db.beginTransactionNonExclusive();
        try {
            LocalDate yesterday = day.minusDays(1);
            List<DailyCoach.Evidence> evidence = new ArrayList<>();
            addDailyTotal(db, exercise, day, zone, evidence);
            addDailyTotal(db, exercise, yesterday, zone, evidence);
            addRecent(db, exercise, day, zone, evidence);
            DailyCoach.Request request = new DailyCoach.Request(UUID.randomUUID().toString(), exercise, question,
                    goal, day, zone, readTarget(db, exercise, yesterday), readTarget(db, exercise, day),
                    Collections.unmodifiableList(evidence));
            db.setTransactionSuccessful();
            return request;
        } finally {
            db.endTransaction();
        }
    }

    private static String start(LocalDate day, ZoneId zone) {
        return Long.toString(day.atStartOfDay(zone).toInstant().toEpochMilli());
    }

    static String totalRef(String exercise, LocalDate day) {
        return exercise == null ? "day:cardio:" + day : "day:weight:" + exercise + ":" + day;
    }

    private static void addDailyTotal(SQLiteDatabase db, String exercise, LocalDate day, ZoneId zone,
                                      List<DailyCoach.Evidence> result) {
        String from = start(day, zone), until = start(day.plusDays(1), zone);
        if (exercise == null) {
            try (Cursor row = db.rawQuery("SELECT SUM(cardio_minutes) FROM workout_session "
                    + "WHERE kind='cardio' AND ended_at>=? AND ended_at<?", new String[]{from, until})) {
                if (row.moveToFirst() && !row.isNull(0)) {
                    result.add(new DailyCoach.Evidence(totalRef(null, day),
                            day + " 유산소 실제 합계 " + row.getLong(0) + "분"));
                }
            }
        } else {
            try (Cursor row = db.rawQuery("SELECT COUNT(*),SUM(reps-partial),SUM(partial) FROM actual "
                    + "WHERE exercise=? AND recorded_at>=? AND recorded_at<?", new String[]{exercise, from, until})) {
                if (row.moveToFirst() && row.getLong(0) > 0) {
                    result.add(new DailyCoach.Evidence(totalRef(exercise, day),
                            day + " " + exercise + " 실제 합계 " + row.getLong(0) + "세트 · 전 구간 "
                                    + row.getLong(1) + "회 · 구간 반복 " + row.getLong(2) + "회"));
                }
            }
        }
    }

    private static void addRecent(SQLiteDatabase db, String exercise, LocalDate day, ZoneId zone,
                                  List<DailyCoach.Evidence> result) {
        String until = start(day.plusDays(1), zone);
        if (exercise == null) {
            try (Cursor rows = db.rawQuery("SELECT id,ended_at,cardio_minutes FROM workout_session "
                    + "WHERE kind='cardio' AND ended_at<? ORDER BY ended_at DESC,id DESC LIMIT 10",
                    new String[]{until})) {
                while (rows.moveToNext()) {
                    result.add(new DailyCoach.Evidence("cardio:" + rows.getLong(0),
                            timestamp(rows.getLong(1), zone) + " 유산소 실제 " + rows.getInt(2) + "분"));
                }
            }
        } else {
            try (Cursor rows = db.rawQuery("SELECT id,recorded_at,weight,reps,partial FROM actual "
                    + "WHERE exercise=? AND recorded_at<? ORDER BY id DESC LIMIT 10", new String[]{exercise, until})) {
                while (rows.moveToNext()) {
                    int reps = rows.getInt(3), partial = rows.getInt(4);
                    result.add(new DailyCoach.Evidence("set:" + rows.getLong(0),
                            timestamp(rows.getLong(1), zone) + " " + exercise + " 실제 " + rows.getString(2)
                                    + "kg × " + reps + "회 · 전 구간 " + (reps - partial) + "회 · 구간 반복 "
                                    + partial + "회"));
                }
            }
        }
    }

    private static String timestamp(long millis, ZoneId zone) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(zone));
    }
}
