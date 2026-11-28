package org.adaptivefit.mobile;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.math.BigDecimal;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;

public final class CoachingActivity extends Activity {
    private CoachingStore records;
    private LocalDate baseline;
    private LocalDate selected;
    private Button baselineButton;
    private Button selectedButton;
    private RadioGroup goals;
    private String confirmedGoal;
    private TextView goalStatus;
    private EditText weight;
    private TextView weightStatus;
    private TextView comparison;
    private LinearLayout column;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        records = new CoachingStore(this);
        selected = state == null ? LocalDate.now() : LocalDate.parse(state.getString("selected"));
        baseline = state == null ? selected.minusDays(1) : LocalDate.parse(state.getString("baseline"));

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
        label(R.string.coaching_title, true);
        label(R.string.coaching_goal, false);
        goals = new RadioGroup(this);
        column.addView(goals);
        addGoal(CoachingStore.LOSS, R.string.goal_loss);
        addGoal(CoachingStore.GROWTH, R.string.goal_growth);
        addGoal(CoachingStore.MAINTAIN, R.string.goal_maintain);
        goalStatus = status();

        label(R.string.daily_weight, true);
        selectedButton = button(() -> pickDate(true));
        TextView weightLabel = label(R.string.body_kg, false);
        weight = new EditText(this);
        weight.setId(View.generateViewId());
        weightLabel.setLabelFor(weight.getId());
        weight.setSingleLine();
        // Keep pasted input intact for validation while requesting a decimal keyboard.
        weight.setRawInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        weight.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        column.addView(weight);
        button(this::saveWeight).setText(R.string.save_body_weight);
        weightStatus = status();
        label(R.string.compare_dates, true);
        baselineButton = button(() -> pickDate(false));
        comparison = status();
    }

    @Override protected void onResume() {
        super.onResume();
        try {
            confirmedGoal = records.goal();
            goalStatus.setText(null);
        } catch (RuntimeException failure) {
            goalStatus.setText(R.string.coaching_read_failed);
        }
        showConfirmedGoal();
        refresh(true);
    }

    private void addGoal(String key, int title) {
        RadioButton option = new RadioButton(this);
        option.setId(View.generateViewId());
        option.setTag(key);
        option.setText(title);
        goals.addView(option);
        option.setOnClickListener(v -> {
            try {
                records.saveGoal(key);
                confirmedGoal = key;
                goalStatus.setText(null);
            } catch (RuntimeException failure) {
                showConfirmedGoal();
                goalStatus.setText(R.string.coaching_save_failed);
            }
        });
    }

    private void showConfirmedGoal() {
        if (confirmedGoal == null) goals.clearCheck();
        else goals.check(goals.findViewWithTag(confirmedGoal).getId());
    }

    private TextView label(int title, boolean heading) {
        TextView label = Foundation.text(this, heading ? R.style.FitTitle : R.style.FitBody, heading);
        label.setText(title);
        Foundation.add(column, label, R.dimen.space_medium);
        return label;
    }

    private TextView status() {
        TextView status = Foundation.text(this, R.style.FitBody, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Foundation.add(column, status, R.dimen.space_small);
        return status;
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
        BigDecimal kg;
        char separator = DecimalFormatSymbols.getInstance(
                getResources().getConfiguration().getLocales().get(0)).getDecimalSeparator();
        try {
            kg = CoachingStore.positiveDecimal(weight.getText().toString().trim(), separator);
        } catch (NumberFormatException invalid) {
            weightStatus.setText(R.string.invalid_body_kg);
            return;
        }
        try {
            records.saveWeight(selected, kg);
        } catch (RuntimeException failure) {
            weightStatus.setText(R.string.coaching_save_failed);
            return;
        }
        refresh(false);
        weightStatus.setText(R.string.weight_saved);
    }

    private String display(BigDecimal kg) {
        return kg == null ? getString(R.string.unmeasured) : getString(R.string.value_kg, kg.toPlainString());
    }

    private void refresh(boolean loadDraft) {
        selectedButton.setText(getString(R.string.record_date, selected));
        baselineButton.setText(getString(R.string.reference_date, baseline));
        if (loadDraft) weightStatus.setText(null);

        BigDecimal after = null;
        String afterText;
        try {
            after = records.weight(selected);
            afterText = display(after);
        } catch (RuntimeException failure) {
            afterText = getString(R.string.coaching_read_failed);
        }
        if (loadDraft) weight.setText(after == null ? "" : after.toPlainString());

        BigDecimal before = null;
        String beforeText;
        if (baseline.equals(selected)) {
            before = after;
            beforeText = afterText;
        } else {
            try {
                before = records.weight(baseline);
                beforeText = display(before);
            } catch (RuntimeException failure) {
                beforeText = getString(R.string.coaching_read_failed);
            }
        }
        String change = getString(R.string.no_comparison);
        if (before != null && after != null) {
            BigDecimal difference = after.subtract(before).stripTrailingZeros();
            String signed = (difference.signum() > 0 ? "+" : "") + difference.toPlainString();
            change = getString(R.string.value_kg, signed);
        }
        comparison.setText(getString(R.string.weight_comparison, baseline, beforeText, selected, afterText, change));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("baseline", baseline.toString());
        state.putString("selected", selected.toString());
    }

    @Override protected void onDestroy() {
        records.close();
        super.onDestroy();
    }
}
