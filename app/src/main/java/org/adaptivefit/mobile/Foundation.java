package org.adaptivefit.mobile;

import android.content.Context;
import android.graphics.Typeface;
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

    static TextView text(Context context, int sp, boolean heading) {
        TextView text = new TextView(context);
        text.setTextSize(sp);
        text.setTextColor(context.getColor(R.color.ink));
        if (heading) {
            text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            if (Build.VERSION.SDK_INT >= 28) text.setAccessibilityHeading(true);
        }
        return text;
    }
}
