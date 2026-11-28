package org.adaptivefit.mobile;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.File;
import java.io.RandomAccessFile;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Use a fresh synthetic app installation. The restart phase retains the same data. */
public final class FeedbackFlowTest extends Instrumentation {
    private FeedbackActivity page;
    private WorkoutStore workouts;
    private CoachingStore coaching;
    private String phase;
    private String stage;
    private static final String FIRST = "테스트 프레스", SECOND = "테스트 스쿼트";

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        phase = args == null ? "" : args.getString("phase", "");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int status = Activity.RESULT_CANCELED;
        try {
            stage = "fixture preflight";
            if ("afterRestart".equals(phase)) {
                openStores();
                checkRestart();
            } else {
                check(phase.isEmpty(), "unknown phase");
                Context target = getTargetContext();
                check(!target.getDatabasePath("workouts.db").exists(), "requires fresh fixture installation");
                check(!target.getDatabasePath("coaching.db").exists(), "coaching fixture already exists");
                createLegacyFixture(target);
                openStores();
                checkMigration();
                checkSessions();
                checkScreens();
                checkDamage();
            }
            result.putString("stream", "PASS " + (phase.isEmpty() ? "migration, sessions, offline screens, fallback, corruption" : "cold receipts and draft"));
            status = Activity.RESULT_OK;
        } catch (Throwable error) {
            result.putString("stream", "FAIL " + stage + "\n" + android.util.Log.getStackTraceString(error));
        } finally {
            closePage();
            if (workouts != null) workouts.close();
            if (coaching != null) coaching.close();
        }
        finish(status, result);
    }

    private void openStores() {
        workouts = new WorkoutStore(getTargetContext());
        coaching = new CoachingStore(getTargetContext());
    }

    private static void createLegacyFixture(Context target) {
        try (SQLiteDatabase db = target.openOrCreateDatabase("workouts.db", Context.MODE_PRIVATE, null)) {
            db.execSQL("CREATE TABLE exercise(name TEXT PRIMARY KEY, weight TEXT NOT NULL, goal INTEGER NOT NULL CHECK(goal BETWEEN 1 AND 100))");
            db.execSQL("CREATE TABLE actual(id INTEGER PRIMARY KEY, exercise TEXT NOT NULL REFERENCES exercise(name), weight TEXT NOT NULL, reps INTEGER NOT NULL CHECK(reps BETWEEN 1 AND 100), partial INTEGER NOT NULL CHECK(partial BETWEEN 0 AND reps), recorded_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX actual_exercise ON actual(exercise,id)");
            db.execSQL("INSERT INTO exercise VALUES('이전 종목','30',8)");
            db.execSQL("INSERT INTO actual VALUES(91,'이전 종목','30',8,2,?)",
                    new Object[]{LocalDate.of(2025, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()});
            db.setVersion(1);
        }
        check(target.getSharedPreferences("coaching", Context.MODE_PRIVATE).edit()
                .putString("goal", CoachingStore.GROWTH).putString("2025-01-01", "70.5").commit(), "legacy fixture");
    }

    private void checkMigration() {
        stage = "v1 upgrade and legacy fallback";
        check(count("SELECT count(*) FROM actual WHERE id=91 AND session_id IS NULL AND reps=8 AND partial=2") == 1, "legacy set");
        check(count("SELECT count(*) FROM workout_session") == 0, "fabricated historical receipt");
        check(coaching.weight(LocalDate.of(2025, 1, 1)).compareTo(new BigDecimal("70.5")) == 0, "legacy weight");
    }

    private void checkSessions() throws Exception {
        stage = "write rollback, contention, stale receipts and duplicate requests";
        workouts.configure(FIRST, new BigDecimal("40"), 15);
        workouts.configure(SECOND, new BigDecimal("20"), 8);
        SQLiteDatabase db = workouts.getWritableDatabase();
        db.execSQL("CREATE TRIGGER reject_fixture_set BEFORE INSERT ON actual BEGIN SELECT RAISE(ABORT,'fixture'); END");
        try {
            expectFailure(() -> workouts.record(FIRST, new BigDecimal("40"), 15, 5));
            check(workouts.openWeight() == 0, "half-saved empty weight group");
        } finally {
            db.execSQL("DROP TRIGGER reject_fixture_set");
        }
        workouts.record(FIRST, new BigDecimal("40"), 15, 5);
        long group = workouts.openWeight();
        check(group > 0, "open group");
        workouts.record(SECOND, new BigDecimal("20"), 8, 0);
        check(workouts.openWeight() == group, "exercise switch split group");

        String expired = workouts.newCardio();
        String key = workouts.newCardio();
        try {
            workouts.editCardio(expired, "50");
            throw new AssertionError("stale draft write accepted");
        } catch (IllegalStateException expected) { /* stale form */ }
        try {
            workouts.finishCardio(expired);
            throw new AssertionError("stale draft committed");
        } catch (IllegalStateException expected) { /* stale form */ }
        for (String invalid : new String[]{"", "-1", "0", "5.5", "2147483648"}) {
            workouts.editCardio(key, invalid);
            try {
                workouts.finishCardio(key);
                throw new AssertionError("invalid duration accepted: " + invalid);
            } catch (NumberFormatException expected) { /* draft remains editable */ }
        }
        workouts.editCardio(key, "25");
        db.execSQL("CREATE TRIGGER reject_fixture_cardio BEFORE INSERT ON workout_session WHEN NEW.kind='cardio' BEGIN SELECT RAISE(ABORT,'fixture'); END");
        try {
            expectFailure(() -> workouts.finishCardio(key));
            check(workouts.latestReceipt("cardio") == 0, "half-saved cardio receipt");
        } finally {
            db.execSQL("DROP TRIGGER reject_fixture_cardio");
        }
        long cardio = workouts.finishCardio(key);
        check(workouts.finishCardio(key) == cardio && workouts.openWeight() == group, "retry or parallel cardio altered group");
        check(workouts.finishWeight(group) == group, "weight receipt");
        check(count("SELECT count(*) FROM actual WHERE session_id=" + group) == 2, "group size");

        workouts.record(FIRST, BigDecimal.TEN, 1, 0);
        long next = workouts.openWeight();
        check(next != group && workouts.finishWeight(group) == group && workouts.openWeight() == next, "stale button closed new group");
        // Race a further real set against closing the group; both serial orders are valid.
        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> set = threads.submit(() -> {
                start.await();
                workouts.record(SECOND, BigDecimal.TEN, 2, 0);
                return null;
            });
            Future<?> close = threads.submit(() -> {
                start.await();
                return workouts.finishWeight(next);
            });
            start.countDown();
            set.get(5, TimeUnit.SECONDS);
            close.get(5, TimeUnit.SECONDS);
            check(count("SELECT count(*) FROM actual WHERE weight='10' AND session_id IS NOT NULL") == 2, "lost racing set");
        } finally {
            threads.shutdownNow();
        }
        // Move the mixed group across a local midnight. Query each day and its cross-day receipt.
        LocalDate day = LocalDate.of(2026, 1, 5);
        long midnight = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        db.execSQL("UPDATE actual SET recorded_at=? WHERE session_id=? AND exercise=?", new Object[]{midnight - 1, group, FIRST});
        db.execSQL("UPDATE actual SET recorded_at=? WHERE session_id=? AND exercise=?", new Object[]{midnight, group, SECOND});
        db.execSQL("UPDATE workout_session SET ended_at=? WHERE id IN (?,?)", new Object[]{midnight + 1000, group, cardio});
        // Keep a third unclosed set on Monday to check period reads before finish.
        workouts.record(FIRST, BigDecimal.ONE, 1, 0);
        db.execSQL("UPDATE actual SET recorded_at=? WHERE id=(SELECT MAX(id) FROM actual)", new Object[]{midnight + 2000});
        coaching.saveWeight(day.minusDays(1), new BigDecimal("70"));
        coaching.saveWeight(day, new BigDecimal("69"));
        FeedbackRepository repository = new FeedbackRepository(workouts, coaching);
        FeedbackRepository.Result receiptData = repository.receipt(group);
        check(receiptData.current().sets() == 2 && receiptData.current().full() == 18
                && receiptData.current().partial() == 5 && receiptData.current().minutes() == 0, "receipt totals");
        check(receiptData.current().exercises().get(FIRST).volume().intValueExact() == 600
                && receiptData.current().exercises().get(SECOND).volume().intValueExact() == 160, "per-exercise volumes");
        check(receiptData.weights().delta().intValueExact() == -1, "cross-midnight measured delta");
        FeedbackRepository.Result sunday = repository.period(day.minusDays(1), FeedbackRepository.Unit.DAY);
        FeedbackRepository.Result monday = repository.period(day, FeedbackRepository.Unit.DAY);
        check(sunday.current().sets() == 1 && sunday.current().partial() == 5, "Sunday midnight exclusion");
        check(monday.current().sets() == 2 && monday.current().minutes() == 25, "Monday includes open set and cardio");
        check(monday.weights().delta() == null, "single measurement invented change");
        for (FeedbackRepository.Unit unit : new FeedbackRepository.Unit[]{FeedbackRepository.Unit.WEEK, FeedbackRepository.Unit.MONTH}) {
            FeedbackRepository.Result report = repository.period(day, unit);
            check(report.current().sets() == (unit == FeedbackRepository.Unit.WEEK ? 2 : 3), "calendar " + unit);
        }
        check(getTargetContext().getSharedPreferences("feedback-test", 0).edit().putLong("weight", group).putLong("cardio", cardio).commit(), "persist test receipt references");
    }

    private void checkDamage() throws Exception {
        stage = "non-destructive corrupt-file reads";
        File dir = Files.createTempDirectory(getTargetContext().getCacheDir().toPath(), "feedback-corrupt").toFile();
        File file = new File(dir, "workouts.db");
        Context fixture = new ContextWrapper(getTargetContext()) {
            @Override public File getDatabasePath(String name) { return file; }
        };
        try (WorkoutStore good = new WorkoutStore(fixture)) {
            good.configure(FIRST, BigDecimal.TEN, 8);
        }
        try (RandomAccessFile bytes = new RandomAccessFile(file, "rw")) { bytes.writeUTF("damaged header"); }
        byte[] original = Files.readAllBytes(file.toPath());
        for (int i = 0; i < 2; i++) {
            try (WorkoutStore damaged = new WorkoutStore(fixture)) {
                expectFailure(() -> new FeedbackRepository(damaged, coaching).period(LocalDate.now(), FeedbackRepository.Unit.DAY));
            }
            check(Arrays.equals(original, Files.readAllBytes(file.toPath())), "corrupt file changed");
        }
    }

    private long count(String sql) {
        return DatabaseUtils.longForQuery(workouts.getReadableDatabase(), sql, null);
    }

    private static void expectFailure(Runnable run) {
        try { run.run(); } catch (SQLiteException expected) { return; }
        throw new AssertionError("expected SQLite failure");
    }

    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }

    private <T> T ui(Callable<T> block) throws Exception {
        FutureTask<T> task = new FutureTask<>(block);
        runOnMainSync(task);
        return task.get();
    }

    private void closePage() {
        if (page != null) {
            runOnMainSync(page::finish);
            waitForIdleSync();
            page = null;
        }
    }

    private FeedbackActivity launch(String mode, long id, String unit, String day) {
        closePage();
        Intent intent = new Intent(getTargetContext(), FeedbackActivity.class)
                .putExtra("mode", mode).putExtra("id", id).putExtra("unit", unit).putExtra("day", day)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        page = (FeedbackActivity) startActivitySync(intent);
        waitForIdleSync();
        return page;
    }

    private static void collect(View view, StringBuilder out) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) out.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }

    private String screen() throws Exception {
        return ui(() -> {
            StringBuilder text = new StringBuilder();
            collect(page.getWindow().getDecorView(), text);
            return text.toString();
        });
    }

    private String awaitText(String expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 7000;
        String actual;
        do {
            actual = screen();
            if (actual.contains(expected)) return actual;
            SystemClock.sleep(60);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("missing " + expected + " in " + actual);
    }

    private long receipt(String kind) {
        return getTargetContext().getSharedPreferences("feedback-test", 0).getLong(kind, -1);
    }

    private String tagged(String tag) throws Exception {
        return ui(() -> {
            View view = page.getWindow().getDecorView().findViewWithTag(tag);
            return view instanceof TextView ? ((TextView) view).getText().toString() : "";
        });
    }

    private void click(String tag) throws Exception {
        ui(() -> {
            View view = page.getWindow().getDecorView().findViewWithTag(tag);
            check(view != null && view.performClick(), "button " + tag);
            return null;
        });
        waitForIdleSync();
    }

    private void checkScreens() throws Exception {
        stage = "read-only old rows and date-key weights";
        launch("period", 0, "DAY", "2025-01-01");
        awaitText("이전 종목");
        awaitText("70.5");
        stage = "weight and cardio receipt scopes";
        launch("receipt", receipt("weight"), "DAY", "2026-01-05");
        awaitText(FIRST);
        awaitText(SECOND);
        check(tagged("feedback_goal").contains("근비대"), "current weight goal");
        awaitModel();
        String growthText = tagged("feedback_ai");
        check(growthText.contains("600") && growthText.contains("160"), "growth evidence lacked exercise volumes: " + growthText);
        launch("receipt", receipt("cardio"), "DAY", "2026-01-05");
        awaitText("25");
        check(!tagged("feedback_body").contains(FIRST), "weight leaked into cardio receipt");
        awaitModel();

        stage = "day, Monday week and calendar month on screen";
        launch("period", 0, "DAY", "2026-01-04");
        awaitText(FIRST);
        check(!tagged("feedback_body").contains(SECOND), "Monday set included on Sunday");
        launch("period", 0, "DAY", "2026-01-05");
        awaitText(SECOND);
        awaitText("25");
        click("feedback_WEEK");
        awaitText("2026-01-05");
        awaitText("2026-01-11");
        click("feedback_MONTH");
        awaitText("2026-01-31");
        awaitText(FIRST);
        awaitText(SECOND);

        stage = "goal refresh on same two receipts";
        coaching.saveGoal(CoachingStore.LOSS);
        for (String kind : new String[]{"weight", "cardio"}) {
            launch("receipt", receipt(kind), "DAY", "2026-01-05");
            awaitText("감량");
            awaitModel();
            check(tagged("feedback_goal").contains("감량"), "goal did not change for " + kind);
            check(tagged("feedback_ai").contains("실측 체중"), "loss evidence did not refresh for " + kind);
        }

        stage = "model exception, timeout and late completion";
        long rows = count("SELECT count(*) FROM actual");
        long cardio = count("SELECT sum(cardio_minutes) FROM workout_session WHERE kind='cardio'");
        FeedbackActivity.ModelRunner production = FeedbackActivity.modelRunner;
        try {
            FeedbackActivity.modelRunner = (context, data) -> LocalFeedbackModel.predict(
                    new java.io.ByteArrayInputStream(new byte[]{123, 0}), data);
            launch("receipt", receipt("weight"), "DAY", "2026-01-05");
            awaitText("AI 피드백을 사용할 수 없음");
            awaitText(FIRST);
            for (boolean throwLate : new boolean[]{false, true}) checkTimeout(throwLate);
            checkStalePeriod();
        } finally {
            FeedbackActivity.modelRunner = production;
        }
        check(count("SELECT count(*) FROM actual") == rows, "failed AI changed actual rows");
        check(count("SELECT sum(cardio_minutes) FROM workout_session WHERE kind='cardio'") == cardio, "failed AI changed cardio minutes");

        stage = "unavailable measurement with retry";
        String date = "2026-01-07";
        android.content.SharedPreferences legacy = getTargetContext().getSharedPreferences("coaching", 0);
        legacy.edit().putInt(date, 99).commit();
        launch("period", 0, "DAY", date);
        awaitText("다시");
        legacy.edit().remove(date).commit();
        click("feedback_retry");
        awaitText(date);

        // Completed receipts and subsequent draft must survive a real force-stop between invocations.
        String expired = workouts.newCardio();
        String key = workouts.newCardio();
        try {
            workouts.editCardio(expired, "50");
            throw new AssertionError("stale draft write accepted");
        } catch (IllegalStateException expected) { /* stale form */ }
        try {
            workouts.finishCardio(expired);
            throw new AssertionError("stale draft committed");
        } catch (IllegalStateException expected) { /* stale form */ }
        for (String invalid : new String[]{"", "-1", "0", "5.5", "2147483648"}) {
            workouts.editCardio(key, invalid);
            try {
                workouts.finishCardio(key);
                throw new AssertionError("invalid duration accepted: " + invalid);
            } catch (NumberFormatException expected) { /* draft remains editable */ }
        }
        workouts.editCardio(key, "13");
        check(getTargetContext().getSharedPreferences("feedback-test", 0).edit().putString("draft", key).commit(), "persist test draft reference");
    }

    private void awaitModel() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 7000;
        do {
            String ai = tagged("feedback_ai");
            if (ai.startsWith("AI 피드백") && ai.length() > 8 && !ai.contains("사용할 수 없")) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("normal inference missing: " + screen());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) throws Exception {
        long until = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < until) {
            try {
                if (latch.await(100, TimeUnit.MILLISECONDS)) return;
            } catch (InterruptedException expectedCancellation) {
                // Exercise a model that delivers after cancellation.
            }
        }
        throw new AssertionError("test latch timed out");
    }

    private void checkTimeout(boolean throwLate) throws Exception {
        CountDownLatch release = new CountDownLatch(1), returned = new CountDownLatch(1);
        FeedbackActivity.modelRunner = (context, data) -> {
            try {
                awaitUninterruptibly(release);
                if (throwLate) throw new IllegalStateException("late synthetic failure");
                return LocalFeedbackModel.Evidence.REPS;
            } finally {
                returned.countDown();
            }
        };
        try {
            launch("receipt", receipt("weight"), "DAY", "2026-01-05");
            awaitText("AI 피드백을 사용할 수 없음");
            String fallback = tagged("feedback_ai"), body = tagged("feedback_body");
            release.countDown();
            check(returned.await(3, TimeUnit.SECONDS), "timeout fixture did not return");
            SystemClock.sleep(150);
            waitForIdleSync();
            check(fallback.equals(tagged("feedback_ai")) && body.equals(tagged("feedback_body")), "late result replaced fallback");
        } finally {
            release.countDown();
        }
    }

    private void checkStalePeriod() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        FeedbackActivity.modelRunner = (context, data) -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                awaitUninterruptibly(release);
                throw new IllegalStateException("stale failure");
            }
            return LocalFeedbackModel.Evidence.MINUTES;
        };
        try {
            launch("period", 0, "DAY", "2026-01-05");
            check(entered.await(2, TimeUnit.SECONDS), "missing first model request");
            click("feedback_MONTH");
            release.countDown();
            awaitText("2026-01-31");
            awaitModel();
            check(!tagged("feedback_ai").contains("사용할 수 없"), "old month result applied");
        } finally {
            release.countDown();
        }
    }

    private void checkRestart() throws Exception {
        stage = "reopen completed receipts and unfinished minutes after process death";
        for (String kind : new String[]{"weight", "cardio"}) {
            launch("receipt", receipt(kind), "DAY", "2026-01-05");
            awaitText("감량");
            awaitModel();
        }
        launch("cardio", 0, "DAY", "2026-01-05");
        awaitText("13");
        click("feedback_finish_cardio");
        awaitText("13");
        String draft = getTargetContext().getSharedPreferences("feedback-test", 0).getString("draft", null);
        check(draft != null, "missing draft key");
        long id = workouts.finishCardio(draft);
        check(id == workouts.latestReceipt("cardio"), "retry after restart duplicated receipt");
        check(count("SELECT sum(cardio_minutes) FROM workout_session WHERE kind='cardio'") == 38, "restart sum");
    }
}
