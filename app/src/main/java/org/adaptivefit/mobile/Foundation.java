package org.adaptivefit.mobile;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.widget.Button;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Foundation {
    private Foundation() {}

    static int size(Context context, int resource) {
        return context.getResources().getDimensionPixelSize(resource);
    }

    static void pad(View view, int resource) {
        int pixels = size(view.getContext(), resource);
        view.setPadding(pixels, pixels, pixels, pixels);
    }

    static void add(LinearLayout column, View child, int spacing) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = size(column.getContext(), spacing);
        column.addView(child, params);
    }

    static void card(LinearLayout column) {
        Context context = column.getContext();
        column.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(context.getColor(R.color.surface));
        shape.setCornerRadius(size(context, R.dimen.space_medium));
        column.setBackground(shape);
        pad(column, R.dimen.space_medium);
    }

    static TextView text(Context context, int style, boolean heading) {
        TextView text = new TextView(context);
        text.setTextAppearance(style);
        if (heading && Build.VERSION.SDK_INT >= 28) text.setAccessibilityHeading(true);
        return text;
    }

    static CharSequence measurement(String value) {
        SpannableString result = new SpannableString(value);
        Matcher units = Pattern.compile("kg|회|×").matcher(value);
        while (units.find()) {
            result.setSpan(new RelativeSizeSpan(0.65f), units.start(), units.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return result;
    }

    private static GradientDrawable fill(Context context, int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(size(context, R.dimen.radius_control));
        shape.setColor(context.getColor(color));
        return shape;
    }

    static void repAction(Button button, boolean recommended) {
        Context context = button.getContext();
        int[] disabled = {-android.R.attr.state_enabled};
        StateListDrawable surface = new StateListDrawable();
        surface.addState(disabled, fill(context, R.color.disabled_surface));
        surface.addState(new int[]{}, fill(context, recommended ? R.color.active : R.color.action_secondary));
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(context.getColor(
                recommended ? R.color.action_secondary : R.color.muted)), surface, null));
        button.setTextAppearance(R.style.FitHeading);
        button.setTextColor(new ColorStateList(new int[][]{disabled, {}}, new int[]{
                context.getColor(R.color.inactive), context.getColor(recommended ? R.color.surface : R.color.ink)}));
        button.setStateListAnimator(null);
        button.setMinHeight(size(context, R.dimen.rep_touch));
        pad(button, R.dimen.space_small);
    }
}
