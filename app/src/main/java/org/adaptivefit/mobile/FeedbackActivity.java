package org.adaptivefit.mobile;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.LocalDate;

public final class FeedbackActivity extends Activity {
    interface ModelRunner {
        LocalFeedbackModel.Evidence predict(Context context, FeedbackRepository.Result result) throws Exception;
    }
    static volatile ModelRunner modelRunner = LocalFeedbackModel::predict;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver zoneChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };
    private WorkoutStore workouts;
    private DefaultCoach defaultCoach;
    private CoachingStore coaching;
    private FeedbackRepository repository;
    private FeedbackText format;
    private LinearLayout column;
    private LocalDate day;
    private FeedbackRepository.Unit unit;
    private String mode, key, unsaved;
    private long id;
    private Inference pending;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        workouts = new WorkoutStore(this);
        defaultCoach = new DefaultCoach(this, workouts);
        coaching = new CoachingStore(this);
        repository = new FeedbackRepository(workouts, coaching);
        format = new FeedbackText(this);
        Bundle args = state == null ? getIntent().getExtras() : state;
        if (args == null) args = new Bundle();
        mode = args.getString("mode", "period");
        id = args.getLong("id");
        key = args.getString("key");
        unsaved = args.getString("unsaved");
        day = LocalDate.parse(args.getString("day", LocalDate.now().toString()));
        unit = FeedbackRepository.Unit.valueOf(args.getString("unit", "DAY"));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("mode", mode);
        state.putLong("id", id);
        state.putString("key", key);
        state.putString("unsaved", unsaved);
        state.putString("day", day.toString());
        state.putString("unit", unit.name());
    }

    @Override protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        registerReceiver(zoneChanged, filter);
        refresh();
    }

    @Override protected void onPause() {
        defaultCoach.unbind();
        unregisterReceiver(zoneChanged);
        cancelInference();
        super.onPause();
    }

    @Override protected void onDestroy() {
        defaultCoach.unbind();
        workouts.close();
        coaching.close();
        super.onDestroy();
    }

    private void layout() {
        defaultCoach.unbind();
        ScrollView scroll = new ScrollView(this);
        scroll.setSaveFromParentEnabled(false);
        scroll.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        scroll.setBackgroundColor(getColor(R.color.canvas));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setFocusableInTouchMode(true);
        Foundation.pad(column, R.dimen.page_margin);
        scroll.addView(column);
        setContentView(scroll);
    }

    private TextView label(String value, boolean heading) {
        TextView label = Foundation.text(this, heading ? R.style.FitTitle : R.style.FitBody, heading);
        label.setText(value);
        Foundation.add(column, label, R.dimen.space_small);
        return label;
    }

    private Button button(String tag, String value, Runnable action) {
        Button button = new Button(this);
        button.setTag(tag);
        button.setText(value);
        button.setOnClickListener(v -> action.run());
        Foundation.add(column, button, R.dimen.space_small);
        return button;
    }

    private void error(int message) {
        layout();
        label(getString(message), false);
        button("feedback_retry", getString(R.string.feedback_retry), this::refresh);
    }

    private void refresh() {
        cancelInference();
        if ("weight".equals(mode)) {
            try {
                id = workouts.finishWeight(id);
                mode = "receipt";
            } catch (RuntimeException failure) {
                error(R.string.coaching_save_failed);
                return;
            }
        }
        try {
            if ("cardio".equals(mode) && openCardio()) return;
            boolean period = "period".equals(mode);
            FeedbackRepository.Result result = period ? repository.period(day, unit) : repository.receipt(id);
            layout();
            int title = period ? R.string.feedback_period : "weight".equals(result.kind())
                    ? R.string.feedback_weight : R.string.feedback_cardio;
            label(getString(title), true);
            if (period) controls();
            label(format.goal(result.goal()), false).setTag("feedback_goal");
            label(getString(R.string.feedback_local), false);
            SessionSummaryView summary = new SessionSummaryView(this);
            Foundation.add(column, summary, R.dimen.space_medium);
            boolean infer = result.goal() != null && result.current().hasWorkout();
            summary.showEvidence(format.summary(result), infer ? getString(R.string.feedback_loading) : "");
            if (infer) {
                pending = new Inference(result, summary);
                pending.start();
            }
        } catch (RuntimeException failure) {
            error(R.string.coaching_read_failed);
        }
    }

    private void controls() {
        button("feedback_date", getString(R.string.record_date, day), () -> {
            new DatePickerDialog(this, (view, y, m, d) -> {
                day = LocalDate.of(y, m + 1, d);
                refresh();
            }, day.getYear(), day.getMonthValue() - 1, day.getDayOfMonth()).show();
        });
        for (FeedbackRepository.Unit option : FeedbackRepository.Unit.values()) {
            int title = switch (option) {
                case DAY -> R.string.feedback_day;
                case WEEK -> R.string.feedback_week;
                case MONTH -> R.string.feedback_month;
            };
            Button tab = button("feedback_" + option, getString(title), () -> { unit = option; refresh(); });
            tab.setEnabled(option != unit);
        }
    }

    private boolean openCardio() {
        String input;
        try (Cursor draft = workouts.cardioDraft()) {
            if (!draft.moveToFirst() || (key != null && !key.equals(draft.getString(0)))) {
                if (key != null) {
                    long prior = workouts.cardioReceipt(key);
                    if (prior != 0) {
                        id = prior;
                        mode = "receipt";
                        return false;
                    }
                }
                throw new IllegalStateException("Expired cardio draft");
            }
            key = draft.getString(0);
            if (!draft.isNull(2)) {
                id = draft.getLong(2);
                mode = "receipt";
                return false;
            }
            input = draft.getString(1);
        }
        layout();
        label(getString(R.string.feedback_finish_cardio), true);
        DefaultCoachView daily = new DefaultCoachView(this, true);
        column.addView(daily);
        defaultCoach.bind(null, daily::show);
        button("daily_entry", getString(R.string.default_coach_change), () -> startActivity(
                new Intent(this, DailyCoachActivity.class).putExtra("cardio", true)));
        TextView caption = label(getString(R.string.feedback_minutes), false);
        EditText minutes = new EditText(this);
        minutes.setTag("feedback_minutes");
        minutes.setId(View.generateViewId());
        caption.setLabelFor(minutes.getId());
        minutes.setSingleLine();
        minutes.setRawInputType(InputType.TYPE_CLASS_NUMBER);
        minutes.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        minutes.setText(unsaved == null ? input : unsaved);
        column.addView(minutes);
        minutes.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                unsaved = s.toString();
                try {
                    workouts.editCardio(key, unsaved);
                    unsaved = null;
                } catch (RuntimeException failure) {
                    minutes.setError(getString(R.string.coaching_save_failed));
                }
            }
        });
        button("feedback_finish_cardio", getString(R.string.feedback_finish_cardio), () -> {
            try {
                if (unsaved != null) workouts.editCardio(key, unsaved);
                id = workouts.finishCardio(key);
                unsaved = null;
                mode = "receipt";
            } catch (NumberFormatException invalid) {
                minutes.setError(getString(R.string.feedback_invalid_minutes));
                return;
            } catch (RuntimeException failure) {
                minutes.setError(getString(R.string.coaching_save_failed));
                return;
            }
            refresh();
        });
        return true;
    }

    private void cancelInference() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
    }

    // Only the UI thread can take ownership of a terminal transition.
    private final class Inference {
        final FeedbackRepository.Result result;
        final SessionSummaryView summary;
        final long deadline = SystemClock.elapsedRealtime() + 2000;
        final Runnable timeout = () -> complete(null);
        Thread worker;

        Inference(FeedbackRepository.Result result, SessionSummaryView summary) {
            this.result = result;
            this.summary = summary;
        }

        void start() {
            ModelRunner runner = modelRunner;
            Context app = getApplicationContext();
            worker = new Thread(() -> {
                LocalFeedbackModel.Evidence evidence = null;
                try {
                    evidence = runner.predict(app, result);
                } catch (Exception failure) {
                    // The local summary remains available on all inference failures.
                }
                LocalFeedbackModel.Evidence output = evidence;
                ui.post(() -> complete(output));
            }, "local-feedback");
            ui.postDelayed(timeout, Math.max(0, deadline - SystemClock.elapsedRealtime()));
            worker.start();
        }

        void cancel() {
            ui.removeCallbacks(timeout);
            worker.interrupt();
        }

        void complete(LocalFeedbackModel.Evidence output) {
            if (pending != this) return;
            boolean onTime = SystemClock.elapsedRealtime() < deadline;
            cancelInference();
            String message = output != null && onTime && output.available(result)
                    ? format.feedback(result, output) : getString(R.string.feedback_fallback);
            summary.showFeedback(message);
        }
    }
}
