package org.adaptivefit.mobile;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.math.BigDecimal;

/** On-device regression for delayed success, failure, retry and duplicate results. */
public final class SetInputContractTest extends Instrumentation {
    private Activity activity;
    private SetInputView input;
    private SetInputView.SaveResult request;
    private int calls;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        start();
    }

    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            Intent intent = new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(intent);
            onUi(() -> {
                input = new SetInputView(activity, new BigDecimal("100"), 8, new int[]{7, 8},
                        (kg, total, segment, result) -> {
                            require(kg.compareTo(new BigDecimal("95")) == 0 && total == 7 && segment == (calls < 2 ? 3 : 0));
                            calls++;
                            request = result;
                        });
                activity.setContentView(input);
                byType(input, Button.class).performClick();
                byType(input, EditText.class).setText("95");
                byType(input, Switch.class).setChecked(true);
                byType(input, Spinner.class).setSelection(2);
                View choice = input.findViewWithTag(7);
                choice.performClick();
                choice.performClick();
                require(calls == 1 && !choice.isEnabled());
                require(hasVisibleText(input, activity.getString(R.string.saving)));
            });
            SetInputView.SaveResult first = request;
            first.complete(false);
            waitForIdleSync();
            onUi(() -> {
                require(hasVisibleText(input, activity.getString(R.string.save_failed)));
                require(byType(input, EditText.class).getVisibility() == View.VISIBLE);
                require(byType(input, Spinner.class).getSelectedItemPosition() == 2);
                input.findViewWithTag(7).performClick();
                require(calls == 2);
            });
            first.complete(true);
            waitForIdleSync();
            onUi(() -> require(!input.findViewWithTag(7).isEnabled()));
            android.os.SystemClock.sleep(android.view.ViewConfiguration.getDoubleTapTimeout() + 50L);
            request.complete(true);
            waitForIdleSync();
            onUi(() -> {
                require(!byType(input, Switch.class).isChecked());
                require(byType(input, EditText.class).getVisibility() == View.GONE);
                require(input.findViewWithTag(7).isEnabled());
                require(!hasVisibleText(input, activity.getString(R.string.save_failed)));
                input.findViewWithTag(7).performClick();
                require(calls == 3);
            });
            report.putString("stream", "PASS: save failure, retained draft, retry, stale result, confirmed reset");
            finish(Activity.RESULT_OK, report);
        } catch (Throwable failure) {
            report.putString("stream", android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, report);
        }
    }

    private void onUi(Runnable check) {
        java.util.concurrent.FutureTask<Void> task = new java.util.concurrent.FutureTask<>(check, null);
        runOnMainSync(task);
        try {
            task.get();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("Set input contract failed");
    }

    private static boolean hasVisibleText(View view, String text) {
        if (!view.isShown()) return false;
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                if (hasVisibleText(group.getChildAt(i), text)) return true;
        }
        return false;
    }

    private static <T extends View> T byType(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T result = byType(group.getChildAt(i), type);
                if (result != null) return result;
            }
        }
        return null;
    }
}
