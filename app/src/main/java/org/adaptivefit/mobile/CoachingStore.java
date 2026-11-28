package org.adaptivefit.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

final class CoachingStore extends SQLiteOpenHelper {
    static final String LOSS = "loss", GROWTH = "growth", MAINTAIN = "maintain";
    private static final Pattern DECIMAL = Pattern.compile("(?:\\p{Nd}+(?:\\.\\p{Nd}*)?|\\.\\p{Nd}+)");
    private final Context context;

    CoachingStore(Context context) {
        super(context, "coaching.db", null, 1, db -> {
            // The platform default deletes the file and retries with an empty database.
            throw new SQLiteDatabaseCorruptException("Cannot access damaged coaching database");
        });
        this.context = context;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE coaching (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
        throw new IllegalStateException("Unsupported coaching database version");
    }

    private String read(String key) {
        try (Cursor row = getReadableDatabase().rawQuery(
                "SELECT value FROM coaching WHERE key = ?", new String[]{key})) {
            if (row.moveToFirst()) return row.getString(0);
        }
        SharedPreferences legacy = context.getSharedPreferences("coaching", Context.MODE_PRIVATE);
        return legacy.getString(key, null);
    }

    private void write(String key, String value) {
        try (SQLiteStatement statement = getWritableDatabase().compileStatement(
                "INSERT OR REPLACE INTO coaching (key, value) VALUES (?, ?)")) {
            statement.bindString(1, key);
            statement.bindString(2, value);
            if (statement.executeInsert() == -1) throw new SQLiteException("Coaching write failed");
        }
    }

    private static String validGoal(String value) {
        if (LOSS.equals(value) || GROWTH.equals(value) || MAINTAIN.equals(value)) return value;
        throw new IllegalArgumentException("Unknown coaching goal");
    }

    String goal() {
        String value = read("goal");
        return value == null ? null : validGoal(value);
    }

    void saveGoal(String value) {
        write("goal", validGoal(value));
    }

    static BigDecimal positiveDecimal(String text, char decimalSeparator) {
        String plain = text.replace(decimalSeparator, '.');
        if (!DECIMAL.matcher(plain).matches()) throw new NumberFormatException();
        BigDecimal value = new BigDecimal(plain);
        if (value.signum() <= 0) throw new NumberFormatException();
        return value.stripTrailingZeros();
    }

    SortedSet<LocalDate> measurementDates(LocalDate first, LocalDate until) {
        // A weight session can stay open for years. Visit stored date keys only.
        SortedSet<LocalDate> dates = new TreeSet<>();
        String start = first.toString(), end = until.toString();
        try (Cursor rows = getReadableDatabase().rawQuery(
                "SELECT key FROM coaching WHERE key >= ? AND key < ?", new String[]{start, end})) {
            while (rows.moveToNext()) dates.add(LocalDate.parse(rows.getString(0)));
        }
        SharedPreferences legacy = context.getSharedPreferences("coaching", Context.MODE_PRIVATE);
        for (String key : legacy.getAll().keySet()) {
            if (key.compareTo(start) >= 0 && key.compareTo(end) < 0) dates.add(LocalDate.parse(key));
        }
        return dates;
    }

    BigDecimal weight(LocalDate day) {
        String value = read(day.toString());
        return value == null ? null : positiveDecimal(value, '.');
    }

    void saveWeight(LocalDate day, BigDecimal kg) {
        if (kg.signum() <= 0) throw new IllegalArgumentException("Expected positive weight");
        write(day.toString(), kg.toPlainString());
    }
}
