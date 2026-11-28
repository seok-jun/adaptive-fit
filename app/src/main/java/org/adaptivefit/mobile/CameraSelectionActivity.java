package org.adaptivefit.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import ai.onnxruntime.OrtException;
import org.json.JSONException;

@SuppressWarnings("deprecation")
public final class CameraSelectionActivity extends Activity
        implements SurfaceHolder.Callback, DisplayManager.DisplayListener {
    static final String EXTRA_EXERCISE = "org.adaptivefit.mobile.CONFIRMED_EXERCISE";
    private static final int REQUEST_PERMISSION = 1;
    private enum Stage { PERMISSION, PREVIEW, FRAME, READING, SHAPE, CANDIDATE, FINISHED }
    private static final ThreadPoolExecutor SHAPE_WORKER = new ThreadPoolExecutor(0, 1,
            30, TimeUnit.SECONDS, new SynchronousQueue<>());

    private static final class Attempt {
        private final long deadline = SystemClock.elapsedRealtime() + 30_000;
        private volatile boolean ended;

        boolean isCurrent() { return !ended && SystemClock.elapsedRealtime() < deadline; }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> cancel(R.string.camera_unrecognized);
    private Stage stage = Stage.PERMISSION;
    private Attempt attempt;
    private boolean resumed;
    private Camera camera;
    private int sensorOrientation;
    private Camera.Size size;
    private LinearLayout page;
    private FrameLayout previewArea;
    private SurfaceView surface;
    private TextView message;
    private Button capture;

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (savedState != null) {
            cancel(0);
            return;
        }
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(getColor(R.color.canvas));
        int margin = Foundation.size(this, R.dimen.page_margin);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(margin + insets.getSystemWindowInsetLeft(), margin + insets.getSystemWindowInsetTop(),
                    margin + insets.getSystemWindowInsetRight(), margin + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(page);
        message = Foundation.text(this, R.style.FitBody, false);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        message.setText(R.string.camera_guide);
        page.addView(message);
        previewArea = new FrameLayout(this);
        page.addView(previewArea, new LinearLayout.LayoutParams(-1, 0, 1));
        surface = new SurfaceView(this);
        surface.setSecure(true);
        surface.getHolder().addCallback(this);
        previewArea.addView(surface);
        previewArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitPreview());
        capture = button(R.string.camera_capture, this::captureFrame);
        capture.setEnabled(false);
        button(R.string.camera_select_manually, () -> cancel(0));
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            stage = Stage.PREVIEW;
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_PERMISSION);
        }
    }

    private Button button(int label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(v -> action.run());
        Foundation.add(page, button, R.dimen.space_small);
        return button;
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != REQUEST_PERMISSION || stage != Stage.PERMISSION) return;
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
            cancel(R.string.camera_permission_denied);
            return;
        }
        stage = Stage.PREVIEW;
        openWhenReady();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        getSystemService(DisplayManager.class).registerDisplayListener(this, handler);
        openWhenReady();
    }

    @Override protected void onPause() {
        resumed = false;
        getSystemService(DisplayManager.class).unregisterDisplayListener(this);
        if (stage != Stage.PERMISSION) cancel(0);
        super.onPause();
    }

    @Override protected void onDestroy() {
        stage = Stage.FINISHED;
        if (attempt != null) attempt.ended = true;
        handler.removeCallbacks(timeout);
        releaseCamera();
        super.onDestroy();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { openWhenReady(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) { openWhenReady(); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if (camera != null) cancel(0);
    }

    @Override public void onDisplayAdded(int id) {}
    @Override public void onDisplayRemoved(int id) {}
    @Override public void onDisplayChanged(int id) {
        if (stage == Stage.PREVIEW && camera != null) {
            try {
                camera.setDisplayOrientation(frameRotation());
                fitPreview();
            } catch (RuntimeException failure) {
                cancel(R.string.camera_unavailable);
            }
        }
    }

    private void openWhenReady() {
        if (!resumed || stage != Stage.PREVIEW || camera != null || !surface.getHolder().getSurface().isValid()) return;
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int id = 0; id < Camera.getNumberOfCameras(); id++) {
                Camera.getCameraInfo(id, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    camera = Camera.open(id);
                    sensorOrientation = info.orientation;
                    break;
                }
            }
            if (camera == null) {
                cancel(R.string.camera_unavailable);
                return;
            }
            camera.setErrorCallback((error, source) -> {
                if (source == camera) cancel(R.string.camera_unavailable);
            });
            Camera.Parameters parameters = camera.getParameters();
            size = chooseSize(parameters.getSupportedPreviewSizes());
            parameters.setPreviewSize(size.width, size.height);
            parameters.setPreviewFormat(ImageFormat.NV21);
            List<String> focus = parameters.getSupportedFocusModes();
            if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            camera.setParameters(parameters);
            camera.setDisplayOrientation(frameRotation());
            camera.setPreviewDisplay(surface.getHolder());
            camera.startPreview();
            fitPreview();
            capture.setEnabled(true);
        } catch (IOException | RuntimeException failure) {
            cancel(R.string.camera_unavailable);
        }
    }

    private static Camera.Size chooseSize(List<Camera.Size> sizes) {
        Camera.Size best = sizes.get(0);
        long target = 1280L * 960;
        for (Camera.Size option : sizes) {
            if (Math.abs((long) option.width * option.height - target)
                    < Math.abs((long) best.width * best.height - target)) best = option;
        }
        return best;
    }

    private int frameRotation() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = (rotation - Surface.ROTATION_0) * 90;
        return (sensorOrientation - degrees + 360) % 360;
    }

    private void fitPreview() {
        if (size == null || previewArea.getWidth() == 0 || previewArea.getHeight() == 0) return;
        boolean swap = frameRotation() % 180 != 0;
        int width = swap ? size.height : size.width;
        int height = swap ? size.width : size.height;
        double scale = Math.min((double) previewArea.getWidth() / width, (double) previewArea.getHeight() / height);
        int w = (int) Math.round(width * scale);
        int h = (int) Math.round(height * scale);
        if (surface.getLayoutParams().width != w || surface.getLayoutParams().height != h) {
            surface.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        }
    }

    private void captureFrame() {
        if (stage != Stage.PREVIEW || camera == null) return;
        stage = Stage.FRAME;
        attempt = new Attempt();
        capture.setEnabled(false);
        message.setText(R.string.camera_reading);
        handler.postDelayed(timeout, 30_000);
        int rotation = frameRotation();
        try {
            camera.setOneShotPreviewCallback((pixels, source) -> {
                if (stage != Stage.FRAME || source != camera) return;
                stage = Stage.READING;
                int width = size.width, height = size.height;
                releaseCamera();
                surface.setVisibility(View.INVISIBLE);
                if (pixels == null) cancel(R.string.camera_unrecognized);
                else recognize(new WeakReference<>(this), pixels, width, height, rotation);
            });
        } catch (RuntimeException failure) {
            cancel(R.string.camera_unavailable);
        }
    }

    // OCR owns the frame until completion or an explicit transfer to the shape worker.
    private static void recognize(WeakReference<CameraSelectionActivity> owner, byte[] pixels,
            int width, int height, int rotation) {
        TextRecognizer recognizer = null;
        try {
            InputImage image = InputImage.fromByteArray(pixels, width, height, rotation, ImageFormat.NV21);
            recognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
            TextRecognizer active = recognizer;
            active.process(image).addOnCompleteListener(task -> {
                boolean transferred = false;
                try {
                    active.close();
                    CameraSelectionActivity screen = owner.get();
                    if (screen == null || screen.stage != Stage.READING) return;
                    if (!screen.attempt.isCurrent() || !task.isSuccessful()) {
                        screen.cancel(R.string.camera_unrecognized);
                        return;
                    }
                    ExerciseNameMatcher.Result result = screen.readName(task.getResult());
                    if (result == ExerciseNameMatcher.Result.NO_NAME) {
                        transferred = screen.startShape(owner, pixels, width, height, rotation);
                    } else {
                        screen.showCandidate(result.exercise);
                    }
                } finally {
                    if (!transferred) Arrays.fill(pixels, (byte) 0);
                }
            });
        } catch (RuntimeException failure) {
            if (recognizer != null) recognizer.close();
            Arrays.fill(pixels, (byte) 0);
            CameraSelectionActivity screen = owner.get();
            if (screen != null) screen.cancel(R.string.camera_unrecognized);
        }
    }

    private ExerciseNameMatcher.Result readName(Text text) {
        List<List<String>> blocks = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            List<String> lines = new ArrayList<>();
            for (Text.Line line : block.getLines()) lines.add(line.getText());
            blocks.add(lines);
        }
        return ExerciseNameMatcher.match(blocks, getResources().getStringArray(R.array.exercises));
    }

    private boolean startShape(WeakReference<CameraSelectionActivity> owner, byte[] pixels,
            int width, int height, int rotation) {
        stage = Stage.SHAPE;
        AssetManager assets = getApplicationContext().getAssets();
        String[] catalog = getResources().getStringArray(R.array.exercises);
        Attempt request = attempt;
        Handler ui = handler;
        try {
            SHAPE_WORKER.execute(() -> {
                String result = null;
                try {
                    result = EquipmentShapeClassifier.classify(assets, catalog, pixels, width,
                            height, rotation, request::isCurrent);
                } catch (IOException | JSONException | OrtException | RuntimeException | LinkageError unavailable) {
                    // The same manual selection remains available when local inference cannot run.
                } finally {
                    Arrays.fill(pixels, (byte) 0);
                }
                String candidate = result;
                ui.post(() -> {
                    CameraSelectionActivity screen = owner.get();
                    if (screen != null && screen.stage == Stage.SHAPE && screen.attempt == request) {
                        screen.showCandidate(candidate);
                    }
                });
            });
            return true;
        } catch (RejectedExecutionException busy) {
            cancel(R.string.camera_unrecognized);
            return false;
        }
    }

    private void showCandidate(String candidate) {
        if (candidate == null || !attempt.isCurrent()) {
            cancel(R.string.camera_unrecognized);
            return;
        }
        handler.removeCallbacks(timeout);
        attempt.ended = true;
        stage = Stage.CANDIDATE;
        page.removeAllViews();
        TextView heading = Foundation.text(this, R.style.FitTitle, true);
        heading.setText(candidate);
        page.addView(heading);
        button(R.string.camera_confirm, () -> {
            if (stage != Stage.CANDIDATE) return;
            stage = Stage.FINISHED;
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_EXERCISE, candidate));
            finish();
        });
        button(R.string.camera_select_manually, () -> cancel(0));
    }

    private void releaseCamera() {
        Camera active = camera;
        camera = null;
        size = null;
        if (active == null) return;
        try {
            active.setOneShotPreviewCallback(null);
            active.stopPreview();
        } catch (RuntimeException ignored) {
            // A disconnected camera may already have stopped its preview.
        } finally {
            active.release();
        }
    }

    private void cancel(int explanation) {
        if (stage == Stage.FINISHED) return;
        stage = Stage.FINISHED;
        if (attempt != null) attempt.ended = true;
        handler.removeCallbacks(timeout);
        releaseCamera();
        setResult(RESULT_CANCELED);
        if (explanation != 0) Toast.makeText(this, explanation, Toast.LENGTH_LONG).show();
        finish();
    }
}
