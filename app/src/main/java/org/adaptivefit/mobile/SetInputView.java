package org.adaptivefit.mobile;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Emits one actual set per valid count tap. All draft edits stay inside the view. */
public final class SetInputView extends LinearLayout {
    public interface OnSetListener {
        void onSet(BigDecimal kilograms, int repetitions, int partialRepetitions, SaveResult result);
    }

    /** The consumer reports the outcome for this request once, after saving. */
    public interface SaveResult {
        void complete(boolean saved);
    }

    private SaveResult pending;
    private long nextInputAt;
    private final EditText weight;
    private final Switch partial;
    private final Spinner partialCount;
    private final List<Button> choices = new ArrayList<>();

    public SetInputView(Context context, BigDecimal suggestedWeight, int suggestedReps,
                        int[] counts, OnSetListener listener) {
        super(context);
        Foundation.card(this);
        int touch = Foundation.size(context, R.dimen.touch);
        TextView plan = Foundation.text(context, R.style.FitLabel, true);
        plan.setText(R.string.plan);
        addView(plan);
        TextView metric = Foundation.text(context, R.style.FitMetric, false);
        metric.setText(Foundation.measurement(context.getString(R.string.result,
                suggestedWeight.toPlainString(), suggestedReps)));
        addView(metric);
        TextView outcome = Foundation.text(context, R.style.FitBody, false);
        outcome.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        outcome.setVisibility(GONE);
        addView(outcome);

        LinearLayout options = new LinearLayout(context);
        options.setOrientation(VERTICAL);
        Button edit = new Button(context, null, android.R.attr.borderlessButtonStyle);
        edit.setText(context.getString(R.string.edit_weight, suggestedWeight.toPlainString()));
        edit.setMinHeight(touch);
        edit.setAllCaps(false);
        edit.setTextAppearance(R.style.FitBody);
        edit.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        options.addView(edit);

        TextView label = Foundation.text(context, R.style.FitBody, false);
        label.setText(R.string.weight);
        Foundation.add(options, label, R.dimen.space_medium);
        weight = new EditText(context);
        weight.setId(View.generateViewId());
        label.setLabelFor(weight.getId());
        weight.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        weight.setBackgroundTintList(controlTint(context, android.R.attr.state_focused));
        weight.setSingleLine(true);
        weight.setMinHeight(touch);
        weight.setSelectAllOnFocus(true);
        weight.setText(suggestedWeight.toPlainString());
        options.addView(weight);
        label.setVisibility(GONE);
        weight.setVisibility(GONE);

        TextView error = Foundation.text(context, R.style.FitBody, false);
        error.setTextColor(context.getColor(R.color.error));
        error.setText(R.string.invalid_weight);
        error.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(GONE);
        options.addView(error);
        edit.setOnClickListener(v -> {
            boolean open = weight.getVisibility() != VISIBLE;
            if (!open && !validWeight(weight.getText().toString())) {
                error.setVisibility(VISIBLE);
                weight.requestFocus();
                return;
            }
            label.setVisibility(open ? VISIBLE : GONE);
            weight.setVisibility(open ? VISIBLE : GONE);
            edit.setText(open ? context.getString(R.string.editing_weight)
                    : context.getString(R.string.edit_weight, weight.getText().toString()));
        });
        weight.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                error.setVisibility(GONE);
                if (validWeight(s.toString())) {
                    metric.setText(Foundation.measurement(context.getString(R.string.result,
                            s.toString(), suggestedReps)));
                }
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        partial = new Switch(context);
        partial.setThumbTintList(controlTint(context, android.R.attr.state_checked));
        partial.setTrackTintList(ColorStateList.valueOf(context.getColor(R.color.control_track)));
        partial.setText(R.string.partial);
        partial.setTextAppearance(R.style.FitBody);
        partial.setMinHeight(touch);
        options.addView(partial);
        partialCount = new Spinner(context);
        partialCount.setContentDescription(context.getString(R.string.partial_amount));
        partialCount.setMinimumHeight(touch);
        final int maximum = 100;
        List<String> amounts = new ArrayList<>();
        for (int i = 1; i <= maximum; i++) amounts.add(context.getString(R.string.rep, i));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, amounts) {
            @Override
            public View getDropDownView(int position, View recycled, android.view.ViewGroup parent) {
                View item = super.getDropDownView(position, recycled, parent);
                item.setMinimumHeight(touch);
                return item;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        partialCount.setAdapter(adapter);
        partialCount.setVisibility(GONE);
        options.addView(partialCount);
        partialCount.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                partialCount.setContentDescription(context.getString(R.string.partial_value, position + 1));
                updateEnabledCounts();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        partial.setOnCheckedChangeListener((button, checked) -> {
            partialCount.setVisibility(checked ? VISIBLE : GONE);
            if (!checked) partialCount.setSelection(0);
            updateEnabledCounts();
        });

        Foundation.add(this, options, R.dimen.space_small);
        TextView title = Foundation.text(context, R.style.FitHeading, true);
        title.setText(R.string.actual);
        Foundation.add(this, title, R.dimen.space_small);
        Button other = new Button(context);
        java.util.function.IntConsumer submit = count -> {
            if (pending != null || android.os.SystemClock.uptimeMillis() < nextInputAt) return;
            String value = weight.getText().toString();
            if (!validWeight(value)) {
                error.setVisibility(VISIBLE);
                weight.requestFocus();
                return;
            }
            error.setVisibility(GONE);
            BigDecimal kilograms = new BigDecimal(value);
            int segment = selectedPartialCount();
            if (segment > count) return;
            final long duplicateWindowEnd = android.os.SystemClock.uptimeMillis()
                    + android.view.ViewConfiguration.getDoubleTapTimeout();
            pending = new SaveResult() {
                @Override public void complete(boolean saved) {
                    post(() -> {
                        if (pending != this) return;
                        pending = null;
                        other.setEnabled(true);
                        edit.setEnabled(true);
                        weight.setEnabled(true);
                        partial.setEnabled(true);
                        partialCount.setEnabled(true);
                        if (saved) {
                            nextInputAt = duplicateWindowEnd;
                            long remaining = nextInputAt - android.os.SystemClock.uptimeMillis();
                            if (remaining > 0) postDelayed(SetInputView.this::updateEnabledCounts, remaining);
                            edit.setText(context.getString(R.string.edit_weight, kilograms.toPlainString()));
                            weight.setVisibility(GONE);
                            label.setVisibility(GONE);
                            partial.setChecked(false);
                            outcome.setVisibility(GONE);
                        } else {
                            outcome.setTextColor(context.getColor(R.color.error));
                            outcome.setText(R.string.save_failed);
                        }
                        updateEnabledCounts();
                    });
                }
            };
            outcome.setTextColor(context.getColor(R.color.muted));
            outcome.setText(R.string.saving);
            outcome.setVisibility(VISIBLE);
            other.setEnabled(false);
            edit.setEnabled(false);
            weight.setEnabled(false);
            partial.setEnabled(false);
            partialCount.setEnabled(false);
            updateEnabledCounts();
            listener.onSet(kilograms, count, segment, pending);
        };
        LinearLayout row = null;
        int columns = getResources().getConfiguration().fontScale > 1.3f
                || getResources().getConfiguration().screenWidthDp < 350 ? 1 : 2;
        for (int i = 0; i < counts.length; i++) {
            int count = counts[i];
            if (i % columns == 0) {
                row = new LinearLayout(context);
                addView(row);
            }
            Button choice = new Button(context);
            choice.setTag(count);
            choice.setText(context.getString(count == suggestedReps ? R.string.recommended : R.string.rep, count));
            Foundation.repAction(choice, count == suggestedReps);
            choice.setOnClickListener(button -> submit.accept(count));
            choices.add(choice);
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
            cell.topMargin = Foundation.size(context, R.dimen.space_small);
            if (i % columns != 0) cell.setMarginStart(Foundation.size(context, R.dimen.space_small));
            row.addView(choice, cell);
        }
        other.setText(R.string.other_count);
        other.setOnClickListener(v -> {
            int first = Math.max(1, selectedPartialCount());
            String[] labels = new String[maximum - first + 1];
            for (int i = 0; i < labels.length; i++) labels[i] = context.getString(R.string.rep, first + i);
            new android.app.AlertDialog.Builder(context).setTitle(R.string.actual)
                    .setItems(labels, (dialog, index) -> submit.accept(first + index)).show();
        });
        addView(other);

    }

    private static ColorStateList controlTint(Context context, int state) {
        return new ColorStateList(new int[][]{{state}, {}}, new int[]{
                context.getColor(R.color.active), context.getColor(R.color.inactive)});
    }

    private static boolean validWeight(String value) {
        try {
            return new BigDecimal(value).signum() >= 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private int selectedPartialCount() {
        return partial.isChecked() ? partialCount.getSelectedItemPosition() + 1 : 0;
    }

    private void updateEnabledCounts() {
        for (Button choice : choices) {
            boolean allowed = (int) choice.getTag() >= selectedPartialCount();
            choice.setEnabled(pending == null && android.os.SystemClock.uptimeMillis() >= nextInputAt && allowed);
            choice.setPaintFlags(allowed ? choice.getPaintFlags() & ~android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    : choice.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        }
    }
}
