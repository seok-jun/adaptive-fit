package org.adaptivefit.mobile;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.math.BigDecimal;

final class WorkoutStore extends SQLiteOpenHelper {
    WorkoutStore(Context context) {
        super(context, "workouts.db", null, 1);
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
    }

    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
        throw new IllegalStateException("Unknown workout database version " + from);
    }

    void configure(String exercise, BigDecimal weight, int goal) {
        ContentValues values = new ContentValues();
        values.put("name", exercise);
        values.put("weight", weight.toPlainString());
        values.put("goal", goal);
        getWritableDatabase().insertOrThrow("exercise", null, values);
    }

    Cursor plan(String exercise) {
        return getReadableDatabase().rawQuery("SELECT COALESCE((SELECT weight FROM actual "
                + "WHERE actual.exercise = e.name ORDER BY id DESC LIMIT 1), e.weight), e.goal "
                + "FROM exercise e WHERE e.name = ?", new String[]{exercise});
    }

    void record(String exercise, BigDecimal weight, int reps, int partial) {
        ContentValues values = new ContentValues();
        values.put("exercise", exercise);
        values.put("weight", weight.toPlainString());
        values.put("reps", reps);
        values.put("partial", partial);
        values.put("recorded_at", System.currentTimeMillis());
        getWritableDatabase().insertOrThrow("actual", null, values);
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
