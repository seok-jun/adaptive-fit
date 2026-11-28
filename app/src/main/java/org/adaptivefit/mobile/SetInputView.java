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
        void onSet(BigDecimal kilograms, int repetitions, int partialRepetitions);
    }

    private final EditText weight;
    private final Switch partial;
    private final Spinner partialCount;
    private final List<Button> choices = new ArrayList<>();

    public SetInputView(Context context, BigDecimal suggestedWeight, int suggestedReps,
                        int[] counts, OnSetListener listener) {
        super(context);
        Foundation.card(this);
        int touch = Foundation.size(context, R.dimen.touch);
        TextView plan = Foundation.text(context, 22, true);
        plan.setText(context.getString(R.string.plan, suggestedWeight.toPlainString(), suggestedReps));
        addView(plan);

        TextView label = Foundation.text(context, 16, false);
        label.setText(R.string.weight);
        Foundation.add(this, label, R.dimen.space_medium);
        weight = new EditText(context);
        weight.setId(View.generateViewId());
        label.setLabelFor(weight.getId());
        weight.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        weight.setSingleLine(true);
        weight.setMinHeight(touch);
        weight.setSelectAllOnFocus(true);
        weight.setText(suggestedWeight.toPlainString());
        addView(weight);

        TextView error = Foundation.text(context, 16, false);
        error.setTextColor(context.getColor(R.color.error));
        error.setText(R.string.invalid_weight);
        error.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(GONE);
        addView(error);
        weight.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                error.setVisibility(GONE);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        partial = new Switch(context);
        partial.setText(R.string.partial);
        partial.setTextSize(16);
        partial.setMinHeight(touch);
        addView(partial);
        partialCount = new Spinner(context);
        partialCount.setContentDescription(context.getString(R.string.partial_amount));
        partialCount.setMinimumHeight(touch);
        int maximum = 0;
        for (int count : counts) maximum = Math.max(maximum, count);
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
        addView(partialCount);
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

        TextView title = Foundation.text(context, 20, true);
        title.setText(R.string.actual);
        Foundation.add(this, title, R.dimen.space_small);
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
            choice.setTextSize(18);
            choice.setMinHeight(touch);
            choice.setTextColor(context.getColor(R.color.surface));
            choice.setBackgroundTintList(new ColorStateList(
                    new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_selected}, {}},
                    new int[]{context.getColor(R.color.inactive), context.getColor(R.color.active), context.getColor(R.color.ink)}));
            choice.setOnClickListener(button -> {
                BigDecimal kilograms;
                try {
                    kilograms = new BigDecimal(weight.getText().toString());
                    if (kilograms.signum() < 0) throw new NumberFormatException();
                } catch (NumberFormatException invalid) {
                    error.setVisibility(VISIBLE);
                    weight.requestFocus();
                    return;
                }
                error.setVisibility(GONE);
                int segment = selectedPartialCount();
                if (segment > count) return;
                for (Button option : choices) {
                    int value = (int) option.getTag();
                    String labelText = context.getString(value == suggestedReps ? R.string.recommended : R.string.rep, value);
                    option.setSelected(option == choice);
                    option.setText(option == choice ? context.getString(R.string.selected_count, labelText) : labelText);
                }
                partial.setChecked(false);
                listener.onSet(kilograms, count, segment);
            });
            choices.add(choice);
            row.addView(choice, new LinearLayout.LayoutParams(0, -2, 1));
        }
    }

    private int selectedPartialCount() {
        return partial.isChecked() ? partialCount.getSelectedItemPosition() + 1 : 0;
    }

    private void updateEnabledCounts() {
        for (Button choice : choices) choice.setEnabled((int) choice.getTag() >= selectedPartialCount());
    }
}
