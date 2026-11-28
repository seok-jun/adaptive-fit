package org.adaptivefit.mobile;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class CoachingActivity extends Activity {
    private SharedPreferences records;
    private LocalDate baseline;
    private LocalDate selected;
    private Button baselineButton;
    private Button selectedButton;
    private EditText weight;
    private TextView comparison;
    private LinearLayout column;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        records = getSharedPreferences("coaching", MODE_PRIVATE);
        selected = state == null ? LocalDate.now() : LocalDate.parse(state.getString("selected"));
        baseline = state == null ? selected.minusDays(1) : LocalDate.parse(state.getString("baseline"));

        ScrollView scroll = new ScrollView(this);
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
        label(R.string.coaching_title, true);
        label(R.string.coaching_goal, false);
        RadioGroup goals = new RadioGroup(this);
        column.addView(goals);
        addGoal(goals, "loss", R.string.goal_loss);
        addGoal(goals, "growth", R.string.goal_growth);
        addGoal(goals, "maintain", R.string.goal_maintain);

        label(R.string.daily_weight, true);
        selectedButton = button(() -> pickDate(true));
        TextView weightLabel = label(R.string.body_kg, false);
        weight = new EditText(this);
        weight.setId(View.generateViewId());
        weightLabel.setLabelFor(weight.getId());
        weight.setSingleLine();
        weight.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        column.addView(weight);
        button(this::saveWeight).setText(R.string.save_body_weight);
        label(R.string.compare_dates, true);
        baselineButton = button(() -> pickDate(false));
        comparison = Foundation.text(this, R.style.FitBody, false);
        comparison.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Foundation.add(column, comparison, R.dimen.space_small);
        refresh(true);
        if (state != null) weight.setText(state.getString("draft"));
    }

    private void addGoal(RadioGroup group, String key, int title) {
        RadioButton option = new RadioButton(this);
        option.setId(View.generateViewId());
        option.setText(title);
        group.addView(option);
        if (key.equals(records.getString("goal", ""))) group.check(option.getId());
        option.setOnClickListener(v -> records.edit().putString("goal", key).apply());
    }

    private TextView label(int title, boolean heading) {
        TextView label = Foundation.text(this, heading ? R.style.FitTitle : R.style.FitBody, heading);
        label.setText(title);
        Foundation.add(column, label, R.dimen.space_medium);
        return label;
    }

    private Button button(Runnable action) {
        Button button = new Button(this);
        button.setOnClickListener(v -> action.run());
        Foundation.add(column, button, R.dimen.space_small);
        return button;
    }

    private void pickDate(boolean edit) {
        LocalDate initial = edit ? selected : baseline;
        new DatePickerDialog(this, (picker, year, month, day) -> {
            LocalDate date = LocalDate.of(year, month + 1, day);
            if (edit) selected = date;
            else baseline = date;
            refresh(edit);
        }, initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth()).show();
    }

    private void saveWeight() {
        String input = weight.getText().toString().trim();
        SharedPreferences.Editor editor = records.edit();
        if (input.isEmpty()) {
            editor.remove(selected.toString());
        } else {
            try {
                BigDecimal kg = new BigDecimal(input);
                if (kg.signum() <= 0) throw new NumberFormatException();
                editor.putString(selected.toString(), kg.stripTrailingZeros().toPlainString());
            } catch (NumberFormatException invalid) {
                weight.setError(getString(R.string.invalid_body_kg));
                return;
            }
        }
        editor.apply();
        refresh(true);
        Toast.makeText(this, R.string.weight_saved, Toast.LENGTH_SHORT).show();
    }

    private String display(String value) {
        return value == null ? getString(R.string.unmeasured) : getString(R.string.value_kg, value);
    }

    private void refresh(boolean loadDraft) {
        String before = records.getString(baseline.toString(), null);
        String after = records.getString(selected.toString(), null);
        if (loadDraft) {
            weight.setError(null);
            weight.setText(after);
        }
        selectedButton.setText(getString(R.string.record_date, selected));
        baselineButton.setText(getString(R.string.reference_date, baseline));
        String change = getString(R.string.no_comparison);
        if (before != null && after != null) {
            BigDecimal diff = new BigDecimal(after).subtract(new BigDecimal(before));
            change = display((diff.signum() > 0 ? "+" : "") + diff.stripTrailingZeros().toPlainString());
        }
        comparison.setText(getString(R.string.weight_comparison, baseline, display(before),
                selected, display(after), change));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("baseline", baseline.toString());
        state.putString("selected", selected.toString());
        state.putString("draft", weight.getText().toString());
    }
}
