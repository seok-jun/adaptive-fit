package org.adaptivefit.mobile;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.math.BigDecimal;
import java.time.LocalDate;

final class WorkoutStore extends SQLiteOpenHelper {
    WorkoutStore(Context context) {
        super(context, "workouts.db", null, 3, db -> {
            throw new android.database.sqlite.SQLiteDatabaseCorruptException("Cannot access damaged workout database");
        });
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE exercise (name TEXT PRIMARY KEY, weight TEXT NOT NULL, "
                + "goal INTEGER NOT NULL CHECK(goal BETWEEN 1 AND 100))");
        db.execSQL("CREATE TABLE actual (id INTEGER PRIMARY KEY, "
                + "exercise TEXT NOT NULL REFERENCES exercise(name), weight TEXT NOT NULL, "
                + "reps INTEGER NOT NULL CHECK(reps BETWEEN 1 AND 100), "
                + "partial INTEGER NOT NULL CHECK(partial BETWEEN 0 AND reps), recorded_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX actual_exercise ON actual(exercise, id)");
        addSessions(db);
        addDailyGoals(db);
    }

    private void addSessions(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE workout_session (id INTEGER PRIMARY KEY, kind TEXT NOT NULL, "
                + "request_key TEXT NOT NULL UNIQUE, ended_at INTEGER, cardio_minutes INTEGER, "
                + "CHECK((kind='weight' AND cardio_minutes IS NULL) OR "
                + "(kind='cardio' AND ended_at IS NOT NULL AND cardio_minutes IS NOT NULL "
                + "AND typeof(cardio_minutes)='integer' AND cardio_minutes BETWEEN 1 AND 2147483647)))");
        db.execSQL("CREATE UNIQUE INDEX open_weight ON workout_session(kind) WHERE ended_at IS NULL");
        db.execSQL("ALTER TABLE actual ADD COLUMN session_id INTEGER REFERENCES workout_session(id)");
        db.execSQL("CREATE INDEX actual_time ON actual(recorded_at)");
        db.execSQL("CREATE INDEX actual_session ON actual(session_id)");
        db.execSQL("CREATE INDEX session_end ON workout_session(kind,ended_at,id)");
        db.execSQL("CREATE TABLE cardio_draft (slot INTEGER PRIMARY KEY CHECK(slot=1), "
                + "request_key TEXT UNIQUE NOT NULL,input TEXT NOT NULL,receipt_id INTEGER REFERENCES workout_session(id))");
        db.execSQL("CREATE TABLE receipt (kind TEXT PRIMARY KEY, session_id INTEGER NOT NULL REFERENCES workout_session(id))");
    }

    private void addDailyGoals(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE daily_goal (day TEXT NOT NULL, kind TEXT NOT NULL, exercise TEXT NOT NULL,"
                + " weight TEXT, reps INTEGER NOT NULL, sets INTEGER NOT NULL, minutes INTEGER NOT NULL,"
                + " PRIMARY KEY(day,kind,exercise),"
                + " CHECK((kind='weight' AND exercise<>'' AND weight IS NOT NULL AND reps BETWEEN 1 AND 100"
                + " AND sets>0 AND minutes=0) OR (kind='cardio' AND exercise='' AND weight IS NULL"
                + " AND reps=0 AND sets=0 AND minutes>0)))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
        if (to != 3 || (from != 1 && from != 2)) {
            throw new IllegalStateException("Unknown workout database upgrade " + from + " → " + to);
        }
        if (from == 1) addSessions(db);
        addDailyGoals(db);
    }

    void configure(String exercise, BigDecimal weight, int goal) {
        ContentValues values = new ContentValues();
        values.put("name", exercise);
        values.put("weight", weight.toPlainString());
        values.put("goal", goal);
        getWritableDatabase().insertOrThrow("exercise", null, values);
    }

    Cursor plan(String exercise) {
        return getReadableDatabase().rawQuery("SELECT COALESCE(d.weight, (SELECT weight FROM actual "
                + "WHERE actual.exercise = e.name ORDER BY id DESC LIMIT 1), e.weight), COALESCE(d.reps, e.goal) "
                + "FROM exercise e LEFT JOIN daily_goal d ON d.day=? AND d.kind='weight' AND d.exercise=e.name "
                + "WHERE e.name=?", new String[]{LocalDate.now().toString(), exercise});
    }

    void record(String exercise, BigDecimal weight, int reps, int partial) {
        ContentValues values = new ContentValues();
        values.put("exercise", exercise);
        values.put("weight", weight.toPlainString());
        values.put("reps", reps);
        values.put("partial", partial);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long session = openWeight();
            if (session == 0) {
                ContentValues start = new ContentValues();
                start.put("kind", "weight");
                start.put("request_key", java.util.UUID.randomUUID().toString());
                session = db.insertOrThrow("workout_session", null, start);
            }
            values.put("session_id", session);
            values.put("recorded_at", System.currentTimeMillis());
            db.insertOrThrow("actual", null, values);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private long id(String sql, String... args) {
        try (Cursor row = getReadableDatabase().rawQuery(sql, args)) {
            return row.moveToFirst() ? row.getLong(0) : 0;
        }
    }

    long openWeight() {
        return id("SELECT id FROM workout_session WHERE kind='weight' AND ended_at IS NULL");
    }

    long latestReceipt(String kind) {
        return id("SELECT session_id FROM receipt WHERE kind=?", kind);
    }

    private void receipt(SQLiteDatabase db, String kind, long session) {
        db.execSQL("INSERT OR REPLACE INTO receipt(kind,session_id) VALUES (?,?)", new Object[]{kind, session});
    }

    long finishWeight(long expectedId) {
        SQLiteDatabase db = getWritableDatabase();
        String[] args = {Long.toString(expectedId)};
        db.beginTransaction();
        try {
            ContentValues end = new ContentValues();
            end.put("ended_at", System.currentTimeMillis());
            if (db.update("workout_session", end, "id=? AND kind='weight' AND ended_at IS NULL "
                    + "AND EXISTS(SELECT 1 FROM actual WHERE session_id=workout_session.id)", args) == 1) {
                receipt(db, "weight", expectedId);
            } else if (id("SELECT id FROM workout_session WHERE id=? AND kind='weight' AND ended_at IS NOT NULL", args) == 0) {
                throw new IllegalStateException("No weight session to finish");
            }
            db.setTransactionSuccessful();
            return expectedId;
        } finally {
            db.endTransaction();
        }
    }

    String newCardio() {
        String key = java.util.UUID.randomUUID().toString();
        getWritableDatabase().execSQL("INSERT OR REPLACE INTO cardio_draft(slot,request_key,input) VALUES(1,?,'')",
                new Object[]{key});
        return key;
    }

    Cursor cardioDraft() {
        return getReadableDatabase().rawQuery("SELECT request_key,input,receipt_id FROM cardio_draft WHERE slot=1", null);
    }

    void editCardio(String key, String input) {
        ContentValues values = new ContentValues();
        values.put("input", input);
        if (getWritableDatabase().update("cardio_draft", values, "request_key=? AND receipt_id IS NULL",
                new String[]{key}) != 1) throw new IllegalStateException("Expired cardio draft");
    }

    long cardioReceipt(String key) {
        return id("SELECT id FROM workout_session WHERE kind='cardio' AND request_key=?", key);
    }

    long finishCardio(String key) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long session = cardioReceipt(key);
            if (session == 0) {
                int minutes;
                try (Cursor draft = cardioDraft()) {
                    if (!draft.moveToFirst() || !key.equals(draft.getString(0))) {
                        throw new IllegalStateException("Expired cardio draft");
                    }
                    String input = draft.getString(1);
                    if (!input.matches("[0-9]+")) throw new NumberFormatException();
                    minutes = Integer.parseInt(input);
                    if (minutes < 1) throw new NumberFormatException();
                }
                ContentValues completed = new ContentValues();
                completed.put("request_key", key);
                completed.put("kind", "cardio");
                completed.put("ended_at", System.currentTimeMillis());
                completed.put("cardio_minutes", minutes);
                session = db.insertOrThrow("workout_session", null, completed);
                db.execSQL("UPDATE cardio_draft SET receipt_id=? WHERE request_key=?", new Object[]{session, key});
                receipt(db, "cardio", session);
            }
            db.setTransactionSuccessful();
            return session;
        } finally {
            db.endTransaction();
        }
    }

    Cursor latest(String exercise) {
        return getReadableDatabase().rawQuery("SELECT weight, reps, partial, "
                + "(SELECT COUNT(*) FROM actual WHERE exercise = ?) FROM actual "
                + "WHERE exercise = ? ORDER BY id DESC LIMIT 1", new String[]{exercise, exercise});
    }

    Cursor history(String exercise) {
        return getReadableDatabase().rawQuery("SELECT weight, reps, partial FROM actual WHERE exercise = ? ORDER BY id DESC", new String[]{exercise});
    }
}
