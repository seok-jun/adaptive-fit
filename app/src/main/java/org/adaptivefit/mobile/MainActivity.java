package org.adaptivefit.mobile;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.math.BigDecimal;

public final class MainActivity extends Activity {
    private int setCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.canvas));
        scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setFocusableInTouchMode(true);
        Foundation.pad(page, R.dimen.page_margin);
        scroll.addView(page);
        TextView title = Foundation.text(this, 16, false);
        title.setText(R.string.preview_title);
        page.addView(title);
        TextView exercise = Foundation.text(this, 30, true);
        exercise.setText(R.string.exercise);
        page.addView(exercise);
        LinearLayout latest = new LinearLayout(this);
        latest.setOrientation(LinearLayout.VERTICAL);
        latest.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        SessionSummaryView summary = new SessionSummaryView(this);
        SetInputView input = new SetInputView(this, new BigDecimal("100"), 8, new int[]{7, 8, 10, 15},
                (kg, reps, partial) -> {
                    latest.removeAllViews();
                    latest.addView(new SetRecordView(this, ++setCount, kg, reps, partial));
                    summary.show(setCount, getString(partial > 0 ? R.string.feedback_mixed
                            : R.string.feedback_full, reps - partial));
                });
        Foundation.add(page, input, R.dimen.space_medium);
        Foundation.add(page, latest, R.dimen.space_small);
        Foundation.add(page, summary, R.dimen.space_small);
        setContentView(scroll);
    }
}
