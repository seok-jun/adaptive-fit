package org.adaptivefit.mobile;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A stable section on the exercise screen. Updating it leaves the actual-input draft in place. */
final class DefaultCoachView extends LinearLayout {
    private final boolean cardio;
    private final TextView target, feedback;

    DefaultCoachView(Context context, boolean cardio) {
        super(context);
        this.cardio = cardio;
        setOrientation(VERTICAL);
        target = Foundation.text(context, R.style.FitHeading, true);
        target.setTag("default_target");
        target.setVisibility(GONE);
        Foundation.add(this, target, R.dimen.space_small);
        feedback = Foundation.text(context, R.style.FitBody, false);
        feedback.setTag("default_feedback");
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Foundation.add(this, feedback, R.dimen.space_small);
    }

    void show(DefaultCoach.State state) {
        DailyCoach.Target plan = state.target();
        if (plan != null) {
            target.setText(cardio ? getContext().getString(R.string.daily_minutes_target, plan.minutes())
                    : getContext().getString(R.string.daily_lift_target, plan.sets(), plan.kg().toPlainString(), plan.reps()));
        }
        target.setVisibility(plan == null ? GONE : VISIBLE);
        if (state.feedback() != null) {
            StringBuilder text = new StringBuilder(state.feedback());
            if (!state.citations().isEmpty()) {
                text.append("\n").append(getResources().getString(R.string.daily_coach_citations));
                for (String citation : state.citations()) text.append("\n").append(citation);
            }
            feedback.setText(text);
        } else {
            int message = switch (state.status()) {
                case READY -> 0;
                case LOADING -> R.string.default_coach_requesting;
                case NO_PROVIDER -> R.string.daily_coach_no_provider;
                case NO_GOAL -> R.string.daily_coach_need_goal;
                case NO_RECORDS -> cardio ? R.string.daily_coach_no_cardio : R.string.daily_coach_no_weight;
                case FAILED -> R.string.default_coach_failed;
            };
            feedback.setText(message == 0 ? "" : getResources().getString(message));
        }
        feedback.setVisibility(feedback.length() == 0 ? GONE : VISIBLE);
    }
}
