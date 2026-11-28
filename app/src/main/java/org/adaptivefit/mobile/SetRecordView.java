package org.adaptivefit.mobile;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.math.BigDecimal;

/** Read-only row for an actual set, including partial repetitions within the total. */
public final class SetRecordView extends LinearLayout {
    public SetRecordView(Context context, int index, BigDecimal kg, int count, int partial) {
        super(context);
        Foundation.card(this);
        TextView status = Foundation.text(context, R.style.FitBody, false);
        status.setTextColor(context.getColor(R.color.active));
        status.setText(context.getString(R.string.saved, index));
        addView(status);
        TextView total = Foundation.text(context, R.style.FitMetric, true);
        total.setText(Foundation.measurement(context.getString(R.string.result, kg.toPlainString(), count)));
        addView(total);
        if (partial > 0) {
            TextView parts = Foundation.text(context, R.style.FitBody, false);
            parts.setText(context.getString(R.string.mixed, count - partial, partial));
            addView(parts);
        }
    }
}
