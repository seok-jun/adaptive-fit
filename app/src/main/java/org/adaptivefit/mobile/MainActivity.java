package org.adaptivefit.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.math.BigDecimal;
import java.util.LinkedHashSet;

public final class MainActivity extends Activity {
    private WorkoutStore store;
    private LinearLayout page;
    private String exercise;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new WorkoutStore(this);
        exercise = getPreferences(MODE_PRIVATE).getString("exercise", null);
        if (exercise == null) choose(); else openExercise();
    }

    @Override protected void onDestroy() {
        store.close();
        super.onDestroy();
    }

    private void page(int title) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.canvas));
        scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
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
            button(name, () -> {
                exercise = name;
                getPreferences(MODE_PRIVATE).edit().putString("exercise", name).apply();
                openExercise();
            });
        }
        coachingButton();
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
        try (Cursor plan = store.plan(exercise)) {
            if (!plan.moveToFirst()) {
                setup();
                return;
            }
            int goal = plan.getInt(1);
            LinearLayout latest = new LinearLayout(this);
            latest.setOrientation(LinearLayout.VERTICAL);
            latest.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            page.addView(latest);
            showLatest(latest);
            button(getString(R.string.history), this::history);
            page.addView(new Space(this), new LinearLayout.LayoutParams(1, 0, 1));
            SetInputView input = new SetInputView(this, new BigDecimal(plan.getString(0)), goal,
                    choices(goal), (kg, reps, partial, result) -> {
                        try {
                            store.record(exercise, kg, reps, partial);
                        } catch (SQLiteException failure) {
                            result.complete(false);
                            return;
                        }
                        result.complete(true);
                        showLatest(latest);
                    });
            Foundation.add(page, input, R.dimen.space_medium);
        }
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
