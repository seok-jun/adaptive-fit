package org.adaptivefit.mobile;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

public final class DailyCoachActivity extends Activity {
    private WorkoutStore workouts;
    private CoachingStore coaching;
    private DailyGoals goals;
    private String exercise;
    private EditText question;
    private TextView status;
    private LinearLayout details, answer, evidence;
    private DailyCoach.Request active;
    private Thread worker;
    private boolean resumed;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> {
        clearAnswer();
        message(R.string.daily_coach_failed);
    };
    private final BroadcastReceiver clockChange = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { preview(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        workouts = new WorkoutStore(this);
        coaching = new CoachingStore(this);
        goals = new DailyGoals(workouts);
        ScrollView scroll = new ScrollView(this);
        scroll.setSaveFromParentEnabled(false);
        scroll.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        scroll.setBackgroundColor(getColor(R.color.canvas));
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setFocusableInTouchMode(true);
        Foundation.pad(column, R.dimen.page_margin);
        scroll.addView(column);
        setContentView(scroll);
        label(column, getString(R.string.daily_coach_title), true);
        ArrayList<String> names = new ArrayList<>(Arrays.asList(getResources().getStringArray(R.array.exercises)));
        int cardio = names.size();
        names.add(getString(R.string.daily_coach_cardio));
        String initial = state == null ? getIntent().getStringExtra("exercise") : state.getString("exercise");
        boolean openCardio = state == null ? getIntent().getBooleanExtra("cardio", false) : initial == null;
        int index = openCardio ? cardio : Math.max(0, names.indexOf(initial));
        exercise = index == cardio ? null : names.get(index);
        Spinner picker = new Spinner(this);
        picker.setTag("daily_exercise");
        picker.setContentDescription(getString(R.string.choose_exercise));
        ArrayAdapter<String> choices = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        picker.setAdapter(choices);
        picker.setSelection(index);
        Foundation.add(column, picker, R.dimen.space_small);
        details = section(column, "daily_details");
        question = field(column, R.string.daily_coach_question, "daily_question", InputType.TYPE_CLASS_TEXT,
                state == null ? "" : state.getString("question", ""));
        button(column, R.string.daily_coach_ask, "daily_ask", this::ask);
        int[] suggestions = {R.string.daily_coach_suggest_goal, R.string.daily_coach_suggest_yesterday};
        for (int i = 0; i < suggestions.length; i++) {
            int prompt = suggestions[i];
            button(column, prompt, "daily_suggest_" + i, () -> {
                question.setText(prompt);
                ask();
            });
        }
        status = label(column, "", false);
        status.setTag("daily_status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        answer = section(column, "daily_answer");
        evidence = section(column, "daily_evidence");
        picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = position == cardio ? null : names.get(position);
                if (!Objects.equals(selected, exercise)) {
                    exercise = selected;
                    preview();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        question.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (active != null) {
                    clearAnswer();
                    message(0);
                }
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        IntentFilter filter = new IntentFilter(Intent.ACTION_DATE_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        registerReceiver(clockChange, filter);
        preview();
    }

    @Override protected void onPause() {
        resumed = false;
        unregisterReceiver(clockChange);
        clearAnswer();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("exercise", exercise);
        state.putString("question", question.getText().toString());
    }

    @Override protected void onDestroy() {
        workouts.close();
        coaching.close();
        super.onDestroy();
    }

    private TextView label(LinearLayout column, String value, boolean heading) {
        TextView view = Foundation.text(this, heading ? R.style.FitHeading : R.style.FitBody, heading);
        view.setText(value);
        Foundation.add(column, view, R.dimen.space_small);
        return view;
    }

    private LinearLayout section(LinearLayout column, String tag) {
        LinearLayout child = new LinearLayout(this);
        child.setOrientation(LinearLayout.VERTICAL);
        child.setTag(tag);
        column.addView(child);
        return child;
    }

    private void button(LinearLayout column, int text, String tag, Runnable action) {
        Button view = new Button(this);
        view.setText(text);
        view.setTag(tag);
        view.setOnClickListener(v -> action.run());
        Foundation.add(column, view, R.dimen.space_small);
    }

    private EditText field(LinearLayout column, int caption, String tag, int type, String value) {
        TextView title = label(column, getString(caption), false);
        EditText view = new EditText(this);
        view.setId(View.generateViewId());
        title.setLabelFor(view.getId());
        view.setTag(tag);
        view.setSingleLine(true);
        view.setInputType(type);
        view.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        view.setText(value);
        column.addView(view);
        return view;
    }

    private void message(int resource) {
        status.setVisibility(resource == 0 ? View.GONE : View.VISIBLE);
        if (resource != 0) status.setText(resource);
    }

    private void clearAnswer() {
        active = null;
        ui.removeCallbacks(timeout);
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        answer.removeAllViews();
    }

    private String targetText(DailyCoach.Target target) {
        return exercise == null ? getString(R.string.daily_coach_minutes_target, target.minutes())
                : getString(R.string.daily_coach_weight_target, target.kg().toPlainString(), target.reps(), target.sets());
    }

    private DailyCoach.Request load() {
        details.removeAllViews();
        evidence.removeAllViews();
        ZoneId zone = ZoneId.systemDefault();
        DailyCoach.Request request = goals.request(exercise, question.getText().toString(), coaching.goal(),
                LocalDate.now(zone), zone);
        label(details, getString(R.string.daily_coach_date, request.day(), zone.getId()), false);
        label(details, new FeedbackText(this).goal(request.goal()), false);
        if (DailyCoach.provider == null && (request.goal() == null || request.evidence().isEmpty())) {
            label(details, getString(R.string.daily_coach_no_provider), false);
        }
        if (request.goal() == null) {
            button(details, R.string.daily_coach_pick_goal, "daily_pick_goal",
                    () -> startActivity(new Intent(this, CoachingActivity.class)));
        }
        if (request.todayTarget() != null)
            label(details, getString(R.string.daily_coach_today, targetText(request.todayTarget())), false);
        if (request.yesterdayTarget() != null)
            label(details, getString(R.string.daily_coach_yesterday, targetText(request.yesterdayTarget())), false);
        label(evidence, getString(R.string.daily_coach_recent), true);
        String yesterday = DailyGoals.totalRef(exercise, request.day().minusDays(1));
        boolean hasYesterday = false;
        for (DailyCoach.Evidence fact : request.evidence()) {
            label(evidence, fact.text(), false);
            if (yesterday.equals(fact.ref())) hasYesterday = true;
        }
        if (!hasYesterday) label(evidence, getString(R.string.daily_coach_unconfirmed), false);
        return request;
    }

    private int unavailable(DailyCoach.Request request) {
        if (request.goal() == null) return R.string.daily_coach_need_goal;
        if (request.evidence().isEmpty())
            return exercise == null ? R.string.daily_coach_no_cardio : R.string.daily_coach_no_weight;
        return DailyCoach.provider == null ? R.string.daily_coach_no_provider : 0;
    }

    private void preview() {
        if (!resumed) return;
        clearAnswer();
        try {
            message(unavailable(load()));
        } catch (RuntimeException failure) {
            message(R.string.coaching_read_failed);
        }
    }

    private void ask() {
        clearAnswer();
        if (question.getText().toString().trim().isEmpty()) {
            question.setError(getString(R.string.daily_coach_empty));
            return;
        }
        DailyCoach.Request request;
        try {
            request = load();
        } catch (RuntimeException failure) {
            message(R.string.coaching_read_failed);
            return;
        }
        int problem = unavailable(request);
        if (problem != 0) {
            message(problem);
            return;
        }
        DailyCoach.Provider provider = DailyCoach.provider;
        active = request;
        message(R.string.daily_coach_wait);
        worker = new Thread(() -> {
            DailyCoach.Response response = null;
            try {
                response = provider.answer(request);
            } catch (Exception ignored) {
                // A failed call leaves the local evidence visible and can be retried.
            }
            DailyCoach.Response result = response;
            ui.post(() -> receive(request, result));
        }, "daily-coaching");
        ui.postDelayed(timeout, 60_000);
        worker.start();
    }

    private boolean current(DailyCoach.Request request) {
        if (active != request || !resumed) return false;
        ZoneId zone = ZoneId.systemDefault();
        try {
            if (request.zone().equals(zone) && request.day().equals(LocalDate.now(zone))
                    && Objects.equals(request.exercise(), exercise) && Objects.equals(request.goal(), coaching.goal())
                    && request.question().equals(question.getText().toString())) return true;
            preview();
            message(R.string.daily_coach_changed);
        } catch (RuntimeException failure) {
            clearAnswer();
            message(R.string.coaching_read_failed);
        }
        return false;
    }

    private void receive(DailyCoach.Request request, DailyCoach.Response response) {
        if (!current(request)) return;
        ui.removeCallbacks(timeout);
        worker = null;
        if (response == null || !response.valid(request)) {
            clearAnswer();
            message(R.string.daily_coach_failed);
            return;
        }
        message(0);
        label(answer, response.feedback(), true).setTag("daily_feedback");
        LinearLayout citations = section(answer, "daily_citations");
        label(citations, getString(R.string.daily_coach_citations), true);
        for (DailyCoach.Evidence fact : request.evidence()) {
            if (response.refs().contains(fact.ref())) label(citations, fact.text(), false);
        }
        if (response.proposal() != null) editor(request, response.proposal());
    }

    private void editor(DailyCoach.Request request, DailyCoach.Target target) {
        label(answer, getString(R.string.daily_coach_target), true);
        int number = InputType.TYPE_CLASS_NUMBER;
        if (exercise == null) {
            EditText minutes = field(answer, R.string.daily_coach_target_minutes, "daily_minutes", number,
                    Integer.toString(target.minutes()));
            button(answer, R.string.daily_coach_apply, "daily_apply", () -> {
                try {
                    save(request, new DailyCoach.Target(null, 0, 0, Integer.parseInt(minutes.getText().toString())));
                } catch (IllegalArgumentException invalid) {
                    minutes.setError(getString(R.string.feedback_invalid_minutes));
                }
            });
        } else {
            EditText kg = field(answer, R.string.daily_coach_target_kg, "daily_kg",
                    number | InputType.TYPE_NUMBER_FLAG_DECIMAL, target.kg().toPlainString());
            EditText reps = field(answer, R.string.daily_coach_target_reps, "daily_reps", number,
                    Integer.toString(target.reps()));
            EditText sets = field(answer, R.string.daily_coach_target_sets, "daily_sets", number,
                    Integer.toString(target.sets()));
            button(answer, R.string.daily_coach_apply, "daily_apply", () -> {
                try {
                    save(request, new DailyCoach.Target(new BigDecimal(kg.getText().toString()),
                            Integer.parseInt(reps.getText().toString()), Integer.parseInt(sets.getText().toString()), 0));
                } catch (IllegalArgumentException invalid) {
                    message(R.string.daily_coach_bad_target);
                }
            });
        }
    }

    private void save(DailyCoach.Request request, DailyCoach.Target target) {
        if (!current(request)) return;
        if (!target.valid(exercise == null)) throw new IllegalArgumentException("Invalid target");
        try {
            goals.save(exercise, request.day(), target);
        } catch (RuntimeException failure) {
            message(R.string.coaching_save_failed);
            return;
        }
        preview();
        message(R.string.daily_coach_applied);
    }
}
