package org.adaptivefit.mobile;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;

/** Run on a fresh synthetic install; use phase=afterRestart after force-stopping the app. */
public final class DailyCoachFlowTest extends Instrumentation {
    private static final String EXERCISE = "벤치 프레스";
    private static final String OTHER = "스쿼트";
    private static final String QUESTION = "어제 내 운동량을 보고 오늘 목표를 제안해 줘";
    private final LocalDate today = LocalDate.now();
    private final LocalDate yesterday = today.minusDays(1);
    private final ZoneId zone = ZoneId.systemDefault();
    private final AtomicReference<DailyCoach.Request> received = new AtomicReference<>();
    private Activity page;
    private MainActivity main;
    private WorkoutStore workouts;
    private CoachingStore coaching;
    private DailyGoals goals;
    private String phase;
    private String stage;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        phase = args == null ? "" : args.getString("phase", "");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int status = Activity.RESULT_CANCELED;
        DailyCoach.Provider original = DailyCoach.provider;
        try {
            stage = "fixture preflight";
            if (phase.isEmpty()) {
                check(!getTargetContext().getDatabasePath("workouts.db").exists(), "fresh test install required");
                check(!getTargetContext().getDatabasePath("coaching.db").exists(), "pre-existing coaching data");
            }
            workouts = new WorkoutStore(getTargetContext());
            coaching = new CoachingStore(getTargetContext());
            goals = new DailyGoals(workouts);
            if ("afterRestart".equals(phase)) {
                stage = "cold read of saved plans and actuals";
                check(goals.target(EXERCISE, today).reps() == 7, "weight plan lost");
                check(goals.target(null, today).minutes() == 22, "cardio plan lost");
                check(number("SELECT count(*) FROM actual") == 15, "wrong persisted set count");
            } else {
                check(phase.isEmpty(), "unknown phase");
                seed();
                checkMissingInputs();
                checkRequestsAndEdits();
                checkCardio();
                checkDisconnected();
                checkFailedAndStale();
            }
            result.putString("stream", "PASS daily coach " + (phase.isEmpty() ? "UI, provider requests, actuals and daily plans" : phase));
            status = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("stream", "FAIL " + stage + "\n" + android.util.Log.getStackTraceString(failure));
        } finally {
            DailyCoach.provider = original;
            if (page != null) runOnMainSync(page::finish);
            if (main != null && main != page) runOnMainSync(main::finish);
            waitForIdleSync();
            if (workouts != null) workouts.close();
            if (coaching != null) coaching.close();
        }
        finish(status, result);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private long number(String sql) {
        try (Cursor row = workouts.getReadableDatabase().rawQuery(sql, null)) {
            check(row.moveToFirst(), "empty query");
            return row.getLong(0);
        }
    }

    private void seed() {
        stage = "create synthetic rows and migrate v2";
        workouts.configure(EXERCISE, new BigDecimal("40"), 8);
        workouts.configure(OTHER, new BigDecimal("99"), 4);
        long previousStart = yesterday.atStartOfDay(zone).toInstant().toEpochMilli();
        long start = today.atStartOfDay(zone).toInstant().toEpochMilli();
        insertSet(EXERCISE, 8, 1, previousStart);
        insertSet(EXERCISE, 7, 0, start - 1);
        insertSet(OTHER, 98, 0, previousStart);
        for (int i = 0; i < 11; i++) insertSet(EXERCISE, 6, 0, start + i);
        String key = workouts.newCardio();
        workouts.editCardio(key, "16");
        long id = workouts.finishCardio(key);
        SQLiteDatabase db = workouts.getWritableDatabase();
        db.execSQL("UPDATE workout_session SET ended_at=? WHERE id=?", new Object[]{previousStart, id});
        // Removing the only v3 addition creates the preceding v2 schema in this fresh test DB.
        db.execSQL("DROP TABLE daily_goal");
        db.setVersion(2);
        workouts.close();
        workouts = new WorkoutStore(getTargetContext());
        goals = new DailyGoals(workouts);
        check(number("PRAGMA user_version") == 3, "v2 migration did not run");
        check(number("SELECT count(*) FROM actual") == 14, "migration lost sets");
        check(workouts.latestReceipt("cardio") == id, "migration lost receipt");
        goals.save(EXERCISE, yesterday, new DailyCoach.Target(new BigDecimal("45"), 10, 4, 0));
        goals.save(null, yesterday, new DailyCoach.Target(null, 0, 0, 30));
    }

    private void insertSet(String exercise, int reps, int partial, long timestamp) {
        workouts.getWritableDatabase().execSQL("INSERT INTO actual(exercise,weight,reps,partial,recorded_at)"
                + " VALUES(?, '40', ?, ?, ?)", new Object[]{exercise, reps, partial, timestamp});
    }

    private String actuals() {
        StringBuilder snapshot = new StringBuilder();
        for (String sql : new String[]{"SELECT * FROM actual ORDER BY id", "SELECT * FROM workout_session ORDER BY id"}) {
            try (Cursor rows = workouts.getReadableDatabase().rawQuery(sql, null)) {
                while (rows.moveToNext()) {
                    for (int col = 0; col < rows.getColumnCount(); col++) snapshot.append(rows.getString(col)).append('|');
                    snapshot.append('\n');
                }
            }
        }
        return snapshot.toString();
    }

    private DailyCoach.Response respond(DailyCoach.Request request) {
        received.set(request);
        boolean cardio = request.exercise() == null;
        String ref = cardio ? "day:cardio:" + yesterday : "day:weight:" + EXERCISE + ":" + yesterday;
        DailyCoach.Target proposal = cardio ? new DailyCoach.Target(null, 0, 0, 20)
                : new DailyCoach.Target(new BigDecimal("48"), 8, 3, 0);
        return new DailyCoach.Response(request.id(), "합성 코칭 " + request.goal(),
                Collections.singletonList(ref), proposal);
    }

    private void checkMissingInputs() throws Exception {
        stage = "unconfigured goal and exercise with no history";
        DailyCoach.provider = this::respond;
        page = launch(DailyCoachActivity.class);
        click("daily_suggest_0");
        check(ui(() -> tagged("daily_pick_goal").isShown()), "missing goal action");
        check(received.get() == null, "issued request without goal");
        coaching.saveGoal(CoachingStore.GROWTH);
        ui(() -> {
            ((Spinner) tagged("daily_exercise")).setSelection(0);
            return null;
        });
        waitForIdleSync();
        click("daily_suggest_0");
        check(received.get() == null, "issued request without actuals");
        closeCoach();
    }

    private void checkFailedAndStale() throws Exception {
        stage = "provider error and late result after question change";
        String snapshot = actuals();
        DailyCoach.provider = request -> { throw new IllegalStateException("synthetic failure"); };
        click("daily_suggest_0");
        awaitScreen(getTargetContext().getString(R.string.daily_coach_failed));
        check(ui(() -> page.getWindow().getDecorView().findViewWithTag("daily_apply") == null), "failed answer accepted");

        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), ended = new CountDownLatch(1);
        DailyCoach.provider = request -> {
            entered.countDown();
            try {
                // Model a transport that delivers its old result after cancellation.
                boolean done = false;
                while (!done) {
                    try { done = release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) { continue; }
                    if (!done) throw new IllegalStateException("fixture timed out");
                }
                return new DailyCoach.Response(request.id(), "오래된 합성 답변",
                        Collections.singletonList(request.evidence().get(0).ref()), null);
            } finally {
                ended.countDown();
            }
        };
        try {
            click("daily_suggest_0");
            check(entered.await(3, TimeUnit.SECONDS), "delayed provider not called");
            DailyCoach.provider = this::respond;
            edit("daily_question", QUESTION);
            click("daily_ask");
            awaitFeedback(CoachingStore.MAINTAIN);
            release.countDown();
            check(ended.await(3, TimeUnit.SECONDS), "delayed provider did not return");
            SystemClock.sleep(100);
            waitForIdleSync();
            check(ui(() -> findText(page.getWindow().getDecorView(), "오래된 합성 답변") == null), "stale reply rendered");
            check(snapshot.equals(actuals()) && goals.target(EXERCISE, today).reps() == 7,
                    "failed request changed stored data");
        } finally {
            release.countDown();
        }
    }

    private void checkRequestsAndEdits() throws Exception {
        stage = "open coaching from active exercise";
        coaching.saveGoal(CoachingStore.GROWTH);
        DailyCoach.provider = this::respond;
        main = (MainActivity) launch(MainActivity.class);
        page = main;
        ui(() -> {
            View selector = findText(main.getWindow().getDecorView(), EXERCISE);
            check(selector != null && selector.performClick(), "exercise selection");
            return null;
        });
        waitForIdleSync();
        ActivityMonitor monitor = addMonitor(DailyCoachActivity.class.getName(), null, false);
        try {
            click("daily_entry");
            page = waitForMonitorWithTimeout(monitor, 5000);
            check(page != null, "coaching screen not reached");
        } finally {
            removeMonitor(monitor);
        }
        waitForIdleSync();
        String initial = actuals();
        String recommended = ui(() -> ((TextView) tagged("daily_suggest_0")).getText().toString());
        click("daily_suggest_0");
        awaitFeedback(CoachingStore.GROWTH);
        DailyCoach.Request request = received.get();
        check(request.question().equals(recommended) && EXERCISE.equals(request.exercise()), "recommended request");
        check(request.yesterdayTarget().sets() == 4 && request.todayTarget() == null, "planned vs performed");
        String prior = evidence(request, "day:weight:" + EXERCISE + ":" + yesterday);
        check(prior.contains("2세트") && prior.contains("14회") && prior.contains("1회"), "yesterday actual totals: " + prior);
        check(evidence(request, "day:weight:" + EXERCISE + ":" + today).contains("11세트"), "today truncated");
        check(request.evidence().size() == 12, "expected two aggregates and ten recent records");
        check(request.evidence().stream().noneMatch(e -> e.text().contains(OTHER)), "other exercise leaked");
        check(ui(() -> findText(tagged("daily_citations"), prior) != null), "missing locally rendered citation");
        check(initial.equals(actuals()), "asking changed performance");

        for (String goal : new String[]{CoachingStore.LOSS, CoachingStore.MAINTAIN}) {
            coaching.saveGoal(goal);
            closeCoach();
            page = launch(DailyCoachActivity.class);
            edit("daily_question", QUESTION);
            click("daily_ask");
            awaitFeedback(goal);
            check(QUESTION.equals(received.get().question()), "free input substituted");
            check(evidence(received.get(), "day:weight:" + EXERCISE + ":" + yesterday).equals(prior),
                    "goal change altered source");
        }
        stage = "modify proposal, reject bad value, apply and resume live set entry";
        edit("daily_kg", "55");
        edit("daily_reps", "0");
        click("daily_apply");
        check(goals.target(EXERCISE, today) == null && actuals().equals(initial), "invalid edit persisted");
        edit("daily_reps", "7");
        edit("daily_sets", "2");
        click("daily_apply");
        DailyCoach.Target target = goals.target(EXERCISE, today);
        check(target != null && target.kg().compareTo(new BigDecimal("55")) == 0
                && target.reps() == 7 && target.sets() == 2, "edited goal missing");
        check(goals.target(OTHER, today) == null && initial.equals(actuals()), "apply changed historical rows");
        check(goals.target(EXERCISE, today.plusDays(1)) == null, "goal rolled forward");
        closeCoach();
        page = main;
        click(Integer.valueOf(6));
        try (Cursor row = workouts.latest(EXERCISE)) {
            check(row.moveToFirst() && "55".equals(row.getString(0)) && row.getInt(1) == 6,
                    "returned screen failed to use new weight with actual reps");
        }
        check(number("SELECT count(*) FROM actual") == 15, "new set count");
    }

    private void checkCardio() throws Exception {
        stage = "edit daily cardio goal and finish actual minutes";
        String initial = actuals();
        page = launch(DailyCoachActivity.class);
        ui(() -> {
            Spinner selector = (Spinner) tagged("daily_exercise");
            selector.setSelection(selector.getCount() - 1);
            return null;
        });
        waitForIdleSync();
        click("daily_suggest_1");
        awaitFeedback(CoachingStore.MAINTAIN);
        check(received.get().exercise() == null && received.get().yesterdayTarget().minutes() == 30,
                "cardio goal and scope");
        check(evidence(received.get(), "day:cardio:" + yesterday).contains("16분"), "missing actual minutes");
        edit("daily_minutes", "22");
        click("daily_apply");
        check(goals.target(null, today).minutes() == 22 && initial.equals(actuals()), "cardio apply changed actuals");
        closeCoach();
        String key = workouts.newCardio();
        page = startActivitySync(new Intent(getTargetContext(), FeedbackActivity.class)
                .putExtra("mode", "cardio").putExtra("key", key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        check(ui(() -> ((TextView) tagged("feedback_minutes")).getText().length() == 0), "prefilled actual cardio");
        edit("feedback_minutes", "19");
        click("feedback_finish_cardio");
        check(number("SELECT SUM(cardio_minutes) FROM workout_session WHERE kind='cardio'") == 35, "wrong actual minutes");
        closeCoach();
    }

    private void checkDisconnected() throws Exception {
        stage = "unconnected provider retains evidence";
        DailyCoach.provider = null;
        page = launch(DailyCoachActivity.class);
        click("daily_suggest_0");
        check(ui(() -> page.getWindow().getDecorView().findViewWithTag("daily_apply") == null
                || !tagged("daily_apply").isShown() || !tagged("daily_apply").isEnabled()), "disconnected apply enabled");
        check(goals.target(EXERCISE, today).reps() == 7, "existing plan changed");
    }

    private static String evidence(DailyCoach.Request request, String ref) {
        for (DailyCoach.Evidence item : request.evidence()) if (ref.equals(item.ref())) return item.text();
        throw new AssertionError("missing evidence " + ref);
    }

    private void awaitFeedback(String goal) throws Exception {
        awaitScreen("합성 코칭 " + goal);
    }

    private void awaitScreen(String text) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ui(() -> findText(page.getWindow().getDecorView(), text) != null)) return;
            SystemClock.sleep(40);
        }
        throw new AssertionError("missing text " + text);
    }

    private Activity launch(Class<? extends Activity> type) {
        Activity activity = startActivitySync(new Intent(getTargetContext(), type)
                .putExtra("exercise", EXERCISE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        return activity;
    }

    private void closeCoach() {
        runOnMainSync(page::finish);
        waitForIdleSync();
        page = null;
    }

    private static View findText(View view, String value) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(value)) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = findText(group.getChildAt(i), value);
                if (child != null) return child;
            }
        }
        return null;
    }

    private <T> T ui(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        runOnMainSync(task);
        return task.get();
    }

    private View tagged(Object tag) {
        View view = page.getWindow().getDecorView().findViewWithTag(tag);
        check(view != null, "missing view " + tag);
        return view;
    }

    private void click(Object tag) throws Exception {
        ui(() -> {
            check(tagged(tag).performClick(), "cannot click " + tag);
            return null;
        });
        waitForIdleSync();
    }

    private void edit(String tag, String text) throws Exception {
        ui(() -> {
            ((EditText) tagged(tag)).setText(text);
            return null;
        });
    }
}
