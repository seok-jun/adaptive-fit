package org.adaptivefit.mobile;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Summary after a workout. The caller supplies the number of actual sets. */
public final class SessionSummaryView extends LinearLayout {
    private final TextView heading;
    private final TextView body;
    private final TextView feedback;

    public SessionSummaryView(Context context) {
        super(context);
        Foundation.card(this);
        heading = Foundation.text(context, R.style.FitHeading, true);
        heading.setText(R.string.summary);
        addView(heading);
        body = Foundation.text(context, R.style.FitBody, false);
        body.setTag("feedback_body");
        body.setTextColor(context.getColor(R.color.muted));
        Foundation.add(this, body, R.dimen.space_small);
        feedback = Foundation.text(context, R.style.FitBody, false);
        feedback.setTag("feedback_ai");
        feedback.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        Foundation.add(this, feedback, R.dimen.space_small);
        show(0, "");
    }

    void showEvidence(CharSequence actuals, CharSequence message) {
        heading.setText(R.string.feedback_actuals);
        body.setText(actuals);
        showFeedback(message);
    }

    void showFeedback(CharSequence message) {
        feedback.setVisibility(message.length() == 0 ? GONE : VISIBLE);
        feedback.setText(message);
    }

    public void show(int count, CharSequence message) {
        body.setText(count == 0 ? getContext().getString(R.string.empty_summary)
                : getContext().getString(R.string.summary_count, count));
        feedback.setVisibility(count > 0 ? VISIBLE : GONE);
        feedback.setText(message);
    }
}
