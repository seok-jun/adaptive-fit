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
import java.util.concurrent.atomic.AtomicInteger;
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
            if (phase.isEmpty() || "automatic".equals(phase)) {
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
                check(number("SELECT count(*) FROM actual") == 16, "wrong persisted set count");
            } else if ("automatic".equals(phase)) {
                seed();
                checkV3Migration();
                checkDefaultCoaching();
            } else {
                check(phase.isEmpty(), "unknown phase");
                seed();
                checkMissingInputs();
                checkRequestsAndEdits();
                checkCardio();
                checkDisconnected();
                checkFailedAndStale();
                checkUnplannedMidnightDraft();
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
        check(number("PRAGMA user_version") == 4, "v2 migration did not run");
        check(number("SELECT count(*) FROM actual") == 14, "migration lost sets");
        check(workouts.latestReceipt("cardio") == id, "migration lost receipt");
        goals.save(EXERCISE, yesterday, new DailyCoach.Target(new BigDecimal("45"), 10, 4, 0));
        goals.save(null, yesterday, new DailyCoach.Target(null, 0, 0, 30));
    }

    private void insertSet(String exercise, int reps, int partial, long timestamp) {
        workouts.getWritableDatabase().execSQL("INSERT INTO actual(exercise,weight,reps,partial,recorded_at)"
                + " VALUES(?, '40', ?, ?, ?)", new Object[]{exercise, reps, partial, timestamp});
    }

    private void checkV3Migration() {
        stage = "retain v3 daily goals, facts and receipts after upgrade";
        String before = actuals();
        long receipt = workouts.latestReceipt("cardio");
        SQLiteDatabase db = workouts.getWritableDatabase();
        db.execSQL("ALTER TABLE daily_goal DROP COLUMN feedback");
        db.execSQL("ALTER TABLE daily_goal DROP COLUMN citations");
        db.setVersion(3);
        workouts.close();
        workouts = new WorkoutStore(getTargetContext());
        goals = new DailyGoals(workouts);
        check(number("PRAGMA user_version") == 4, "v3 upgrade missing");
        DailyCoach.Target old = goals.target(EXERCISE, yesterday);
        check(old.kg().compareTo(new BigDecimal("45")) == 0 && old.reps() == 10 && old.sets() == 4,
                "v3 weight goal lost");
        check(goals.target(null, yesterday).minutes() == 30 && goals.target(null, today) == null,
                "v3 cardio goal or date changed");
        check(actuals().equals(before) && workouts.latestReceipt("cardio") == receipt, "upgrade changed actuals");
    }

    private void choose(String exercise, boolean fromSetPage) throws Exception {
        ui(() -> {
            if (fromSetPage) findText(main.getWindow().getDecorView(), main.getString(R.string.choose_exercise)).performClick();
            View item = findText(main.getWindow().getDecorView(), exercise);
            check(item != null && item.performClick(), "cannot select exercise " + exercise);
            return null;
        });
        waitForIdleSync();
    }

    private void checkDefaultCoaching() throws Exception {
        String initial = actuals();
        stage = "unconnected automatic coaching leaves set input usable";
        coaching.saveGoal(CoachingStore.GROWTH);
        DailyCoach.provider = null;
        main = (MainActivity) launch(MainActivity.class);
        page = main;
        choose(EXERCISE, false);
        check(ui(() -> tagged(8).isEnabled()), "set input blocked without provider");
        check(goals.target(EXERCISE, today) == null && actuals().equals(initial), "unconnected entry wrote data");

        stage = "automatic weight goal, deduplication and editable actual draft";
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        DailyCoach.provider = request -> {
            calls.incrementAndGet();
            entered.countDown();
            check(release.await(7, TimeUnit.SECONDS), "weight provider release");
            return respond(request);
        };
        choose(EXERCISE, true);
        check(entered.await(4, TimeUnit.SECONDS), "weight workout did not request default coaching");
        DefaultCoach secondBinding = new DefaultCoach(getTargetContext(), workouts);
        try {
            ui(() -> {
                secondBinding.bind(EXERCISE, state -> {});
                View root = main.getWindow().getDecorView();
                View edit = findText(root, main.getString(R.string.edit_weight, "40"));
                check(edit != null && edit.performClick(), "unable to edit live weight draft");
                firstWeightField(root).setText("37.5");
                return null;
            });
            release.countDown();
            awaitFeedback(CoachingStore.GROWTH);
        } finally {
            release.countDown();
            ui(() -> { secondBinding.unbind(); return null; });
        }

        DailyCoach.Request request = received.get();
        check(today.equals(request.day()) && request.todayTarget() == null
                        && request.yesterdayTarget().sets() == 4 && request.goal().equals(CoachingStore.GROWTH),
                "automatic request omitted day, goal or yesterday's plan");
        String prior = evidence(request, DailyGoals.totalRef(EXERCISE, yesterday));
        check(prior.contains("2세트") && prior.contains("14회") && prior.contains("1회"), "wrong yesterday facts");
        awaitScreen(prior);
        check(goals.target(EXERCISE, today).sets() == 3 && goals.target(EXERCISE, yesterday).sets() == 4,
                "automatic plan replaced previous day");
        check(calls.get() == 1 && actuals().equals(initial), "duplicated call or fake actual record");
        check(ui(() -> firstWeightField(main.getWindow().getDecorView()).getText().toString().equals("37.5")),
                "automatic response overwrote edited weight");
        click(Integer.valueOf(8));
        try (Cursor row = workouts.latest(EXERCISE)) {
            check(row.moveToFirst() && "37.5".equals(row.getString(0)) && row.getInt(1) == 8,
                    "automatic goal replaced actual set draft on write");
        }
        choose(EXERCISE, true);
        awaitFeedback(CoachingStore.GROWTH);
        awaitScreen(prior);
        check(calls.get() == 1, "saved weight coaching requested again");
        checkAutoCardio();
        checkManualPriority();
    }

    private void openCardio(String key) {
        page = startActivitySync(new Intent(getTargetContext(), FeedbackActivity.class)
                .putExtra("mode", "cardio").putExtra("key", key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
    }

    private void checkAutoCardio() throws Exception {
        stage = "automatic cardio goal and actual minutes remain separate";
        String initial = actuals();
        String key = workouts.newCardio();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        DailyCoach.provider = request -> {
            calls.incrementAndGet();
            entered.countDown();
            check(release.await(7, TimeUnit.SECONDS), "cardio provider release");
            return respond(request);
        };
        try {
            openCardio(key);
            check(entered.await(4, TimeUnit.SECONDS), "cardio did not request automatically");
            edit("feedback_minutes", "13");
            release.countDown();
            awaitFeedback(CoachingStore.GROWTH);
        } finally {
            release.countDown();
        }
        DailyCoach.Request request = received.get();
        check(request.exercise() == null && request.yesterdayTarget().minutes() == 30, "cardio request scope");
        String prior = evidence(request, DailyGoals.totalRef(null, yesterday));
        check(prior.contains("16분"), "prior cardio target treated as completed minutes");
        awaitScreen(prior);
        check(goals.target(null, today).minutes() == 20 && actuals().equals(initial), "cardio goal wrote actuals");
        check(ui(() -> ((TextView) tagged("feedback_minutes")).getText().toString().equals("13")), "lost draft minutes");
        closeCoach();
        openCardio(key);
        awaitFeedback(CoachingStore.GROWTH);
        check(calls.get() == 1, "duplicate cardio request on reentry");
        click("feedback_finish_cardio");
        check(number("SELECT SUM(cardio_minutes) FROM workout_session WHERE kind='cardio'") == 29,
                "auto target replaced actual minutes on write");
        closeCoach();
        page = main;
    }

    private void checkManualPriority() throws Exception {
        stage = "manual target wins over pending automatic response";
        String initial = actuals();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        DailyCoach.provider = request -> {
            entered.countDown();
            check(release.await(7, TimeUnit.SECONDS), "manual priority provider release");
            return new DailyCoach.Response(request.id(), "무시할 기본 답변",
                    Collections.singletonList(DailyGoals.totalRef(OTHER, yesterday)),
                    new DailyCoach.Target(new BigDecimal("50"), 9, 5, 0));
        };
        try {
            choose(OTHER, true);
            check(entered.await(4, TimeUnit.SECONDS), "missing competing default request");
            // Same write used by the manual editor. The foreground workout is still bound to the pending reply.
            goals.save(OTHER, today, new DailyCoach.Target(new BigDecimal("31"), 5, 2, 0));
            release.countDown();
            awaitScreen("31");
        } finally {
            release.countDown();
        }
        check(goals.target(OTHER, today).reps() == 5 && actuals().equals(initial), "default won write race");
        check(ui(() -> findText(main.getWindow().getDecorView(), "무시할 기본 답변") == null),
                "stale default explanation shown for manual target");
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
        // Exercise manual first setup on a screen that has no automatic provider yet.
        DailyCoach.provider = null;
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
        DailyCoach.provider = this::respond;
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
            DailyCoach.provider = null;
            closeCoach();
            page = launch(DailyCoachActivity.class);
            DailyCoach.provider = this::respond;
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

    private void checkUnplannedMidnightDraft() throws Exception {
        stage = "unplanned exercise keeps its editable draft across midnight";
        DailyCoach.provider = null;
        closeCoach();
        page = main;
        check(goals.target(OTHER, yesterday) == null && goals.target(OTHER, today) == null,
                "fixture unexpectedly has a daily target");
        ui(() -> {
            View root = main.getWindow().getDecorView();
            findText(root, main.getString(R.string.choose_exercise)).performClick();
            findText(main.getWindow().getDecorView(), OTHER).performClick();
            return null;
        });
        waitForIdleSync();
        ui(() -> {
            View root = main.getWindow().getDecorView();
            EditText draft = firstWeightField(root);
            check(draft != null, "set entry field missing");
            View edit = findText(root, main.getString(R.string.edit_weight, "40"));
            check(edit != null && edit.performClick() && draft.isShown(), "cannot open weight draft");
            draft.setText("37.5");
            // Advance just the rendered screen's date without changing the emulator's clock.
            java.lang.reflect.Field displayed = MainActivity.class.getDeclaredField("displayedDay");
            displayed.setAccessible(true);
            displayed.set(main, yesterday);
            check(tagged(4).performClick(), "actual repetitions tap");
            check(draft.isAttachedToWindow() && draft == firstWeightField(main.getWindow().getDecorView()),
                    "rollover rebuilt an unplanned set input");
            return null;
        });
        waitForIdleSync();
        try (Cursor row = workouts.latest(OTHER)) {
            check(row.moveToFirst() && "37.5".equals(row.getString(0)) && row.getInt(1) == 4,
                    "rollover lost the edited actual weight or count tap");
        }
        check(number("SELECT count(*) FROM actual") == 16, "rollover must add one set");
    }

    private static EditText firstWeightField(View view) {
        if (view instanceof EditText field) return field;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText field = firstWeightField(group.getChildAt(i));
                if (field != null) return field;
            }
        }
        return null;
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
