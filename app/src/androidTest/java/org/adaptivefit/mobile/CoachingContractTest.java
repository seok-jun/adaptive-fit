package org.adaptivefit.mobile;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.os.Bundle;
import android.os.Parcel;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.File;
import java.io.RandomAccessFile;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/** Run on the dedicated synthetic emulator. Only known fixture keys are removed. */
@TargetApi(29)
public final class CoachingContractTest extends Instrumentation {
    private static final LocalDate EARLY = LocalDate.of(2026, 1, 4);
    private static final LocalDate LATE = LocalDate.of(2026, 1, 8);
    private static final LocalDate EMPTY = LocalDate.of(2026, 1, 9);
    private static final LocalDate BAD = LocalDate.of(2026, 1, 10);
    private static final String[] KEYS = {"goal", EARLY.toString(), LATE.toString(),
            EMPTY.toString(), BAD.toString()};
    private static final String TRIGGER = "test_coaching_reject_write";
    private MainActivity main;
    private CoachingActivity page;
    private CoachingStore store;
    private WorkoutStore workouts;
    private SharedPreferences legacy;
    private SharedPreferences selection;
    private String exercise;
    private String stage = "fixture preflight";
    private String phase;
    private boolean ownsFixture;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        phase = args == null ? "" : args.getString("phase", "");
        start();
    }

    @Override public void onStart() {
        Bundle report = new Bundle();
        boolean passed = false;
        try {
            Context context = getTargetContext();
            store = new CoachingStore(context);
            workouts = new WorkoutStore(context);
            legacy = context.getSharedPreferences("coaching", Context.MODE_PRIVATE);
            selection = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
            exercise = context.getResources().getStringArray(R.array.exercises)[0];
            if ("afterRestart".equals(phase)) {
                require(CoachingStore.MAINTAIN.equals(store.goal()));
                require("80.5".equals(store.weight(EARLY).toPlainString()));
                require("79.2".equals(store.weight(LATE).toPlainString()));
                require(exercise.equals(selection.getString("exercise", null)));
                require(legacy.getInt(BAD.toString(), 0) == 73);
                ownsFixture = true;
                stage = "process restart restoration";
                launchMain();
                openCoaching();
                pick(true, LATE);
                pick(false, EARLY);
                ui(() -> {
                    require(checked(R.string.goal_maintain));
                    result(EARLY, "80.5", LATE, "79.2", "-1.3");
                });
            } else {
                require(phase.isEmpty() || "beforeRestart".equals(phase));
                runContract();
            }
            passed = true;
        } catch (Throwable failure) {
            report.putString("stream", "FAIL: " + stage);
        } finally {
            try {
                if (ownsFixture && !(passed && "beforeRestart".equals(phase))) cleanFixture();
                if (store != null) store.close();
                if (workouts != null) workouts.close();
            } catch (Throwable failure) {
                passed = false;
                report.putString("stream", "FAIL: fixture cleanup");
            }
        }
        if (passed) report.putString("stream", "PASS: " + ("afterRestart".equals(phase)
                ? "cold restoration and fixture cleanup"
                : "both entry points, missing values, corrupt file preservation, legacy reads, corrections, validation, failures, retry and recreation"));
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, report);
    }

    private void runContract() throws Exception {
        require(!selection.contains("exercise"));
        for (String key : KEYS) require(!legacy.contains(key) && rows(key) == 0);
        try (android.database.Cursor plan = workouts.plan(exercise)) {
            require(!plan.moveToFirst());
        }
        require(DatabaseUtils.longForQuery(store.getReadableDatabase(),
                "SELECT count(*) FROM sqlite_master WHERE name = ?", new String[]{TRIGGER}) == 0);
        ownsFixture = true;
        launchMain();
        stage = "unselected exercise entry";
        long sets = setCount();
        openCoaching();
        pick(true, LATE);
        pick(false, EARLY);
        ui(() -> {
            require(find(root(page), RadioGroup.class).getCheckedRadioButtonId() == View.NO_ID);
            result(EARLY, null, LATE, null, null);
            submit("");
            has(page, R.string.invalid_body_kg);
        });
        require(rows(LATE.toString()) == 0);
        backToMain();
        require(!selection.contains("exercise") && setCount() == sets);

        stage = "read-only legacy fallback";
        require(legacy.edit().putString("goal", CoachingStore.LOSS)
                .putString(EARLY.toString(), "80.5").putString(LATE.toString(), "78.6")
                .putInt(BAD.toString(), 73).commit());
        Map<String, ?> original = legacy.getAll();
        stage = "corrupt database preservation";
        checkCorruptDatabase();
        stage = "read-only legacy fallback";
        openCoaching();
        pick(true, LATE);
        pick(false, EARLY);
        ui(() -> {
            require(checked(R.string.goal_loss));
            result(EARLY, "80.5", LATE, "78.6", "-1.9");
        });
        for (String key : KEYS) require(rows(key) == 0);
        require(original.equals(legacy.getAll()));

        stage = "decimal writes and directional comparisons";
        pick(true, EARLY);
        ui(() -> submit("80.5"));
        pick(true, LATE);
        ui(() -> {
            submit("79.2");
            result(EARLY, "80.5", LATE, "79.2", "-1.3");
        });
        pick(false, LATE);
        pick(true, EARLY);
        ui(() -> result(LATE, "79.2", EARLY, "80.5", "+1.3"));
        pick(false, EMPTY);
        ui(() -> result(EMPTY, null, EARLY, "80.5", null));
        pick(true, EMPTY);
        pick(false, EARLY);
        ui(() -> {
            result(EARLY, "80.5", EMPTY, null, null);
            submit("-1");
            has(page, R.string.invalid_body_kg);
        });
        require(rows(EMPTY.toString()) == 0);

        stage = "retained drafts and confirmed values";
        pick(true, LATE);
        ui(() -> {
            submit("");
            has(page, R.string.invalid_body_kg);
        });
        backToMain();
        openCoaching();
        pick(true, LATE);
        pick(false, EARLY);
        ui(() -> {
            require("79.2".contentEquals(input().getText()));
            for (String invalid : new String[]{"0", "-4.3", ".", "+", "1e2", "NaN", "Infinity", "80 kg", "1,200.4"}) {
                submit(invalid);
                has(page, R.string.invalid_body_kg);
                require(invalid.contentEquals(input().getText()));
                result(EARLY, "80.5", LATE, "79.2", "-1.3");
            }
            input().setText("72.55");
        });
        pick(false, LATE);
        ui(() -> {
            require("72.55".contentEquals(input().getText()));
            result(LATE, "79.2", LATE, "79.2", "0");
        });
        stage = "same-day correction and reopen";
        ui(() -> {
            submit("80.5");
            result(LATE, "80.5", LATE, "80.5", "0");
        });
        backToMain();
        openCoaching();
        pick(true, LATE);
        pick(false, LATE);
        ui(() -> {
            require("80.5".contentEquals(input().getText()));
            result(LATE, "80.5", LATE, "80.5", "0");
        });
        pick(false, EARLY);
        ui(() -> result(EARLY, "80.5", LATE, "80.5", "0"));

        stage = "write failure and retry";
        store.getWritableDatabase().execSQL("CREATE TRIGGER " + TRIGGER
                + " BEFORE INSERT ON coaching BEGIN SELECT RAISE(ABORT, 'test rejection'); END");
        ui(() -> {
            submit("79.2");
            has(page, R.string.coaching_save_failed);
            require(!containsText(root(page), page.getString(R.string.weight_saved)));
            require("79.2".contentEquals(input().getText()));
            result(EARLY, "80.5", LATE, "80.5", "0");
            click(page, R.string.goal_maintain);
            require(checked(R.string.goal_loss) && !checked(R.string.goal_maintain));
        });
        require(CoachingStore.LOSS.equals(store.goal()));
        require("80.5".equals(store.weight(LATE).toPlainString()));
        store.getWritableDatabase().execSQL("DROP TRIGGER " + TRIGGER);
        ui(() -> {
            click(page, R.string.save_body_weight);
            result(EARLY, "80.5", LATE, "79.2", "-1.3");
            has(page, R.string.weight_saved);
            click(page, R.string.goal_maintain);
            require(checked(R.string.goal_maintain));
            require(!containsText(root(page), page.getString(R.string.coaching_save_failed)));
        });
        require(original.equals(legacy.getAll()));
        require(rows("goal") == 1 && rows(EARLY.toString()) == 1 && rows(LATE.toString()) == 1);
        stage = "corrupt fallback";
        pick(false, BAD);
        ui(() -> result(BAD, page.getString(R.string.coaching_read_failed), LATE, "79.2", null));
        require(rows(BAD.toString()) == 0 && original.equals(legacy.getAll()));

        stage = "configuration recreation";
        pick(false, EARLY);
        ui(() -> {
            input().setText("72.55");
            Bundle saved = new Bundle();
            callActivityOnSaveInstanceState(page, saved);
            require(EARLY.toString().equals(saved.getString("baseline")));
            require(LATE.toString().equals(saved.getString("selected")));
            Parcel parcel = Parcel.obtain();
            try {
                parcel.writeBundle(saved);
                String encoded = new String(parcel.marshall(), StandardCharsets.UTF_16LE);
                require(!encoded.contains("72.55") && !encoded.contains("79.2") && !encoded.contains("80.5"));
            } finally {
                parcel.recycle();
            }
        });
        ActivityMonitor recreation = addMonitor(CoachingActivity.class.getName(), null, false);
        ui(page::recreate);
        page = (CoachingActivity) waitForMonitorWithTimeout(recreation, 7000);
        removeMonitor(recreation);
        require(page != null);
        waitForIdleSync();
        ui(() -> {
            require(checked(R.string.goal_maintain));
            require("79.2".contentEquals(input().getText()));
            result(EARLY, "80.5", LATE, "79.2", "-1.3");
        });
        require(CoachingStore.positiveDecimal("79,250", ',').compareTo(new BigDecimal("79.25")) == 0);
        stage = "selected exercise entry and return";
        backToMain();
        workouts.configure(exercise, new BigDecimal("100"), 8);
        ui(() -> button(main, exercise).performClick());
        openCoaching();
        ui(() -> require(checked(R.string.goal_maintain)));
        stage = "selected exercise return";
        backToMain();
        ui(() -> has(main, R.string.history));
        require(exercise.equals(selection.getString("exercise", null)) && setCount() == sets);
    }

    private void checkCorruptDatabase() throws Exception {
        Context target = getTargetContext();
        File directory = Files.createTempDirectory(target.getCacheDir().toPath(), "coaching-contract").toFile();
        File file = new File(directory, "coaching.db");
        AtomicBoolean accessedLegacy = new AtomicBoolean();
        Context fixture = new ContextWrapper(target) {
            @Override public File getDatabasePath(String name) {
                require("coaching.db".equals(name));
                return file;
            }

            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                require("coaching".equals(name));
                accessedLegacy.set(true);
                return legacy;
            }
        };
        try {
            try (CoachingStore fresh = new CoachingStore(fixture)) {
                fresh.saveGoal(CoachingStore.GROWTH);
                fresh.saveWeight(EARLY, new BigDecimal("82.3"));
            }
            try (RandomAccessFile bytes = new RandomAccessFile(file, "rw")) {
                bytes.write("damaged header!!".getBytes(StandardCharsets.US_ASCII));
            }
            byte[] damaged = Files.readAllBytes(file.toPath());
            try (CoachingStore damagedStore = new CoachingStore(fixture)) {
                expectCorruption(damagedStore::goal);
                expectCorruption(() -> damagedStore.weight(EARLY));
                expectCorruption(() -> damagedStore.saveWeight(EARLY, new BigDecimal("75.4")));
                expectCorruption(() -> damagedStore.saveGoal(CoachingStore.MAINTAIN));
            }
            require(!accessedLegacy.get());
            require(Arrays.equals(damaged, Files.readAllBytes(file.toPath())));
        } finally {
            SQLiteDatabase.deleteDatabase(file);
            Files.delete(directory.toPath());
        }
    }

    private static void expectCorruption(Runnable operation) {
        try {
            operation.run();
        } catch (SQLiteDatabaseCorruptException expected) {
            return;
        }
        throw new AssertionError("Corrupt database accepted");
    }

    private void cleanFixture() {
        SQLiteDatabase db = store.getWritableDatabase();
        db.execSQL("DROP TRIGGER IF EXISTS " + TRIGGER);
        SharedPreferences.Editor editor = legacy.edit();
        for (String key : KEYS) {
            db.delete("coaching", "key = ?", new String[]{key});
            editor.remove(key);
        }
        require(editor.commit());
        workouts.getWritableDatabase().delete("exercise", "name = ?", new String[]{exercise});
        require(selection.edit().remove("exercise").commit());
    }

    private long rows(String key) {
        return DatabaseUtils.queryNumEntries(store.getReadableDatabase(), "coaching", "key = ?", new String[]{key});
    }

    private long setCount() {
        return DatabaseUtils.queryNumEntries(workouts.getReadableDatabase(), "actual");
    }

    private void launchMain() {
        main = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitForIdleSync();
    }

    private void openCoaching() throws Exception {
        ActivityMonitor monitor = addMonitor(CoachingActivity.class.getName(), null, false);
        ui(() -> click(main, R.string.coaching_title));
        page = (CoachingActivity) waitForMonitorWithTimeout(monitor, 7000);
        removeMonitor(monitor);
        require(page != null);
        waitForIdleSync();
    }

    private void backToMain() throws Exception {
        ActivityMonitor resumed = addMonitor(MainActivity.class.getName(), null, false);
        ui(page::onBackPressed);
        require(waitForMonitorWithTimeout(resumed, 7000) == main);
        waitForIdleSync();
    }

    private void pick(boolean record, LocalDate day) throws Exception {
        String format = page.getString(record ? R.string.record_date : R.string.reference_date, "");
        ui(() -> find(root(page), Button.class, v -> v.getText().toString().startsWith(format)).performClick());
        waitForIdleSync();
        ui(() -> {
            for (View window : WindowInspector.getGlobalWindowViews()) {
                DatePicker picker = find(window, DatePicker.class);
                if (picker != null) {
                    picker.updateDate(day.getYear(), day.getMonthValue() - 1, day.getDayOfMonth());
                    window.findViewById(android.R.id.button1).performClick();
                    return;
                }
            }
            throw new AssertionError();
        });
        waitForIdleSync();
    }

    private void submit(String draft) {
        input().setText(draft);
        click(page, R.string.save_body_weight);
    }

    private EditText input() {
        return find(root(page), EditText.class);
    }

    private boolean checked(int label) {
        return ((RadioButton) button(page, page.getString(label))).isChecked();
    }

    private void result(LocalDate before, String beforeValue, LocalDate after, String afterValue, String difference) {
        String change = difference == null ? page.getString(R.string.no_comparison) : page.getString(R.string.value_kg, difference);
        require(containsText(root(page), page.getString(R.string.weight_comparison,
                before, display(beforeValue), after, display(afterValue), change)));
    }

    private String display(String value) {
        if (value == null) return page.getString(R.string.unmeasured);
        if (value.equals(page.getString(R.string.coaching_read_failed))) return value;
        return page.getString(R.string.value_kg, value);
    }

    private static View root(Activity activity) {
        return activity.findViewById(android.R.id.content);
    }

    private static Button button(Activity activity, String text) {
        return find(root(activity), Button.class, v -> text.contentEquals(v.getText()));
    }

    private static void click(Activity activity, int label) {
        button(activity, activity.getString(label)).performClick();
    }

    private static void has(Activity activity, int text) {
        require(containsText(root(activity), activity.getString(text)));
    }

    private static boolean containsText(View view, String text) {
        return find(view, TextView.class, v -> v.isShown() && text.contentEquals(v.getText())) != null;
    }

    private static <T extends View> T find(View view, Class<T> type) {
        return find(view, type, v -> true);
    }

    private static <T extends View> T find(View view, Class<T> type, Predicate<T> matches) {
        if (type.isInstance(view) && matches.test(type.cast(view))) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T child = find(group.getChildAt(i), type, matches);
                if (child != null) return child;
            }
        }
        return null;
    }

    private void ui(Runnable check) throws Exception {
        FutureTask<Void> task = new FutureTask<>(check, null);
        runOnMainSync(task);
        task.get();
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError();
    }
}
