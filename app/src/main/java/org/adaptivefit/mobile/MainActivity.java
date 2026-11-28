package org.adaptivefit.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import java.time.LocalDate;
import java.util.Objects;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashSet;

public final class MainActivity extends Activity {
    private static final int REQUEST_CAMERA_SELECTION = 71;
    private WorkoutStore store;
    private LinearLayout page;
    private String exercise;
    private LinearLayout feedbackActions;
    private boolean awaitingCameraSelection;

    private DefaultCoach defaultCoach;
    private DefaultCoachView coachView;
    private SetInputView setInput;
    private BigDecimal fallbackWeight;
    private int fallbackReps;
    private boolean resumed;
    private LocalDate displayedDay;
    private DailyCoach.Target displayedTarget;
    private final BroadcastReceiver dateChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { bindDefault(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new WorkoutStore(this);
        defaultCoach = new DefaultCoach(this, store);
        exercise = getPreferences(MODE_PRIVATE).getString("exercise", null);
        if (exercise == null) choose(); else openExercise();
    }

    @Override protected void onDestroy() {
        defaultCoach.unbind();
        store.close();
        super.onDestroy();
    }

    private void page(int title) {
        defaultCoach.unbind();
        coachView = null;
        setInput = null;
        displayedTarget = null;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.canvas));
        scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        feedbackActions = null;
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setFocusableInTouchMode(true);
        Foundation.pad(page, R.dimen.page_margin);
        scroll.addView(page);
        TextView heading = Foundation.text(this, R.style.FitTitle, true);
        heading.setText(exercise == null ? getString(title) : exercise);
        page.addView(heading);
        setContentView(scroll);
    }

    private void button(String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(v -> action.run());
        Foundation.add(page, button, R.dimen.space_small);
    }

    private void coachingButton() {
        button(getString(R.string.coaching_title), () -> startActivity(new Intent(this, CoachingActivity.class)));
    }

    private void choose() {
        exercise = null;
        getPreferences(MODE_PRIVATE).edit().remove("exercise").apply();
        page(R.string.choose_exercise);
        for (String name : getResources().getStringArray(R.array.exercises)) {
            button(name, () -> selectExercise(name));
        }
        button(getString(R.string.camera_entry), () -> {
            if (awaitingCameraSelection) return;
            awaitingCameraSelection = true;
            startActivityForResult(new Intent(this, CameraSelectionActivity.class), REQUEST_CAMERA_SELECTION);
        });
        coachingButton();
        addFeedbackActions();
    }

    private void selectExercise(String name) {
        exercise = name;
        getPreferences(MODE_PRIVATE).edit().putString("exercise", name).apply();
        openExercise();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_CAMERA_SELECTION || !awaitingCameraSelection) return;
        awaitingCameraSelection = false;
        if (result != RESULT_OK || data == null || exercise != null) return;
        String name = data.getStringExtra(CameraSelectionActivity.EXTRA_EXERCISE);
        if (Arrays.asList(getResources().getStringArray(R.array.exercises)).contains(name)) {
            selectExercise(name);
        }
    }

    private EditText field(int resource, int flags) {
        TextView label = Foundation.text(this, R.style.FitBody, false);
        label.setText(resource);
        Foundation.add(page, label, R.dimen.space_medium);
        EditText field = new EditText(this);
        field.setId(View.generateViewId());
        label.setLabelFor(field.getId());
        field.setSingleLine();
        field.setInputType(InputType.TYPE_CLASS_NUMBER | flags);
        page.addView(field);
        return field;
    }

    private void setup() {
        EditText weight = field(R.string.initial_weight, InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText goal = field(R.string.goal, 0);
        button(getString(R.string.start_sets), () -> {
            BigDecimal kg;
            int reps;
            try {
                kg = new BigDecimal(weight.getText().toString());
                if (kg.signum() < 0) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                weight.setError(getString(R.string.invalid_weight));
                return;
            }
            try {
                reps = Integer.parseInt(goal.getText().toString());
                if (reps < 1 || reps > 100) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                goal.setError(getString(R.string.goal_range));
                return;
            }
            try {
                store.configure(exercise, kg, reps);
            } catch (SQLiteException failure) {
                Toast.makeText(this, R.string.setup_failed, Toast.LENGTH_LONG).show();
                return;
            }
            openExercise();
        });
    }

    private void openExercise() {
        page(R.string.choose_exercise);
        button(getString(R.string.choose_exercise), this::choose);
        coachingButton();
        coachView = new DefaultCoachView(this, false);
        page.addView(coachView);
        displayedDay = LocalDate.now();
        try (Cursor plan = store.plan(exercise)) {
            if (!plan.moveToFirst()) {
                setup();
                addFeedbackActions();
                bindDefault();
                return;
            }
            fallbackWeight = new BigDecimal(plan.getString(0));
            fallbackReps = plan.getInt(1);
            LinearLayout latest = new LinearLayout(this);
            latest.setOrientation(LinearLayout.VERTICAL);
            latest.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            page.addView(latest);
            showLatest(latest);
            button(getString(R.string.history), this::history);
            page.addView(new Space(this), new LinearLayout.LayoutParams(1, 0, 1));
            setInput = new SetInputView(this, fallbackWeight, fallbackReps,
                    choices(fallbackReps), (kg, reps, partial, result) -> {
                        try {
                            if (!LocalDate.now().equals(displayedDay)) {
                                bindDefault();
                                result.complete(false);
                                return;
                            }
                            store.record(exercise, kg, reps, partial);
                        } catch (SQLiteException failure) {
                            result.complete(false);
                            return;
                        }
                        result.complete(true);
                        showLatest(latest);
                        refreshFeedbackActions();
                    });
            Foundation.add(page, setInput, R.dimen.space_medium);
            bindDefault();
        } catch (RuntimeException failure) {
            button(getString(R.string.feedback_retry), this::openExercise);
            Toast.makeText(this, R.string.coaching_read_failed, Toast.LENGTH_LONG).show();
        }
        addFeedbackActions();
    }

    @Override protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(Intent.ACTION_DATE_CHANGED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(dateChanged, filter);
        resumed = true;
        bindDefault();
        refreshFeedbackActions();
    }

    @Override protected void onPause() {
        resumed = false;
        defaultCoach.unbind();
        unregisterReceiver(dateChanged);
        super.onPause();
    }

    private void bindDefault() {
        if (!resumed || coachView == null) return;
        LocalDate today = LocalDate.now();
        boolean newDay = !today.equals(displayedDay);
        if (newDay && setInput != null) {
            try (Cursor plan = store.plan(exercise)) {
                if (plan.moveToFirst()) {
                    fallbackWeight = new BigDecimal(plan.getString(0));
                    fallbackReps = plan.getInt(1);
                }
            } catch (SQLiteException failure) {
                Toast.makeText(this, R.string.coaching_read_failed, Toast.LENGTH_LONG).show();
                return;
            }
        }
        displayedDay = today;
        defaultCoach.bind(exercise, state -> {
            coachView.show(state);
            DailyCoach.Target target = state.target();
            if (setInput != null && (newDay || !Objects.equals(target, displayedTarget))) {
                int reps = target == null ? fallbackReps : target.reps();
                setInput.recommendation(target == null ? fallbackWeight : target.kg(),
                        reps, choices(reps), target != null);
            }
            displayedTarget = target;
        });
    }

    private void addFeedbackActions() {
        feedbackActions = new LinearLayout(this);
        feedbackActions.setOrientation(LinearLayout.VERTICAL);
        Foundation.add(page, feedbackActions, R.dimen.space_medium);
        refreshFeedbackActions();
    }

    private void action(String tag, int title, Runnable handler) {
        Button button = new Button(this);
        button.setTag(tag);
        button.setText(title);
        button.setOnClickListener(v -> handler.run());
        Foundation.add(feedbackActions, button, R.dimen.space_small);
    }

    private Intent feedback(String mode) {
        return new Intent(this, FeedbackActivity.class).putExtra("mode", mode);
    }

    private void refreshFeedbackActions() {
        if (feedbackActions == null) return;
        feedbackActions.removeAllViews();
        action("daily_entry", exercise == null ? R.string.daily_coach_title : R.string.default_coach_change, () -> startActivity(
                new Intent(this, DailyCoachActivity.class).putExtra("exercise", exercise)));
        action("feedback_period", R.string.feedback_period, () -> startActivity(feedback("period")));
        action("feedback_new_cardio", R.string.feedback_new_cardio, () -> {
            try {
                String key = store.newCardio();
                startActivity(feedback("cardio").putExtra("key", key));
            } catch (RuntimeException failure) {
                Toast.makeText(this, R.string.coaching_save_failed, Toast.LENGTH_LONG).show();
            }
        });
        try {
            long open = store.openWeight();
            if (open != 0) action("feedback_finish_weight", R.string.feedback_finish_weight,
                    () -> startActivity(feedback("weight").putExtra("id", open)));
            try (Cursor draft = store.cardioDraft()) {
                if (draft.moveToFirst() && draft.isNull(2)) {
                    String key = draft.getString(0);
                    action("feedback_resume_cardio", R.string.feedback_resume_cardio,
                            () -> startActivity(feedback("cardio").putExtra("key", key)));
                }
            }
            latestReceipt("weight", R.string.feedback_latest_weight);
            latestReceipt("cardio", R.string.feedback_latest_cardio);
        } catch (RuntimeException failure) {
            TextView status = Foundation.text(this, R.style.FitBody, false);
            status.setText(R.string.coaching_read_failed);
            feedbackActions.addView(status);
            action("feedback_retry", R.string.feedback_retry, this::refreshFeedbackActions);
        }
    }

    private void latestReceipt(String kind, int title) {
        long id = store.latestReceipt(kind);
        if (id != 0) action("feedback_latest_" + kind, title,
                () -> startActivity(feedback("receipt").putExtra("id", id)));
    }

    private static int[] choices(int goal) {
        LinkedHashSet<Integer> counts = new LinkedHashSet<>();
        counts.add(goal);
        for (int n : new int[]{goal - 1, goal + 1, 15}) {
            if (n >= 1 && n <= 100) counts.add(n);
        }
        return counts.stream().mapToInt(Integer::intValue).toArray();
    }

    private void showLatest(LinearLayout latest) {
        latest.removeAllViews();
        try (Cursor rows = store.latest(exercise)) {
            if (rows.moveToFirst()) latest.addView(new SetRecordView(this, rows.getInt(3),
                    new BigDecimal(rows.getString(0)), rows.getInt(1), rows.getInt(2)));
        }
    }

    private void history() {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(R.string.history);
        try (Cursor rows = store.history(exercise)) {
            String[] entries = new String[rows.getCount()];
            while (rows.moveToNext()) {
                int reps = rows.getInt(1), partial = rows.getInt(2);
                String entry = getString(R.string.result, rows.getString(0), reps);
                if (partial > 0) entry += "\n" + getString(R.string.mixed, reps - partial, partial);
                entries[rows.getPosition()] = entry;
            }
            if (entries.length == 0) dialog.setMessage(R.string.no_sets);
            else dialog.setItems(entries, null);
        }
        dialog.setPositiveButton(android.R.string.ok, null).show();
    }
}
