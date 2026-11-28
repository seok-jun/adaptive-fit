package org.adaptivefit.mobile;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.BooleanSupplier;

final class EquipmentShapeClassifier {
    private static final int SIDE = 224;
    private static final int VECTOR_LENGTH = 512;
    private static final int PROMPT_COUNT = 10;
    private static final float[] MEAN = {0.48145466f, 0.4578275f, 0.40821073f};
    private static final float[] STD = {0.26862954f, 0.26130258f, 0.27577711f};

    private EquipmentShapeClassifier() {}

    static String classify(AssetManager assets, String[] catalog, byte[] nv21, int width,
            int height, int rotation, BooleanSupplier current) throws IOException, OrtException, JSONException {
        if (!current.getAsBoolean()) return null;
        float[] input = imageTensor(nv21, width, height, rotation);
        try {
            if (!current.getAsBoolean()) return null;
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            env.setTelemetry(false);
            try (AssetFileDescriptor file = assets.openFd("shape/vision_model_q4.onnx");
                 FileInputStream stream = file.createInputStream();
                 OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                ByteBuffer model = stream.getChannel().map(FileChannel.MapMode.READ_ONLY,
                        file.getStartOffset(), file.getDeclaredLength());
                options.setIntraOpNumThreads(2);
                options.setInterOpNumThreads(1);
                try (OrtSession session = env.createSession(model, options);
                     OnnxTensor pixels = OnnxTensor.createTensor(env, FloatBuffer.wrap(input),
                             new long[]{1, 3, SIDE, SIDE})) {
                    if (!current.getAsBoolean()) return null;
                    try (OrtSession.Result output = session.run(Collections.singletonMap("pixel_values", pixels))) {
                        if (!current.getAsBoolean()) return null;
                        float[][] vectors = (float[][]) output.get("image_embeds").get().getValue();
                        if (vectors.length != 1 || vectors[0].length != VECTOR_LENGTH) return null;
                        return match(assets, catalog, vectors[0]);
                    }
                }
            }
        } finally {
            Arrays.fill(input, 0);
        }
    }

    private static String match(AssetManager assets, String[] catalog, float[] image) throws IOException, JSONException {
        double norm = 0;
        for (float value : image) norm += (double) value * value;
        norm = Math.sqrt(norm);
        if (!Double.isFinite(norm) || norm == 0) return null;
        byte[] bytes = new byte[PROMPT_COUNT * VECTOR_LENGTH * Float.BYTES];
        try (DataInputStream stream = new DataInputStream(assets.open("shape/shape-text-embeddings.bin"))) {
            stream.readFully(bytes);
        }
        FloatBuffer text = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        int winner = -1;
        double best = Double.NEGATIVE_INFINITY, runnerUp = Double.NEGATIVE_INFINITY;
        for (int row = 0; row < PROMPT_COUNT; row++) {
            double dot = 0;
            for (float value : image) dot += (double) value * text.get();
            double score = dot / norm;
            if (!Double.isFinite(score)) return null;
            if (score > best) {
                runnerUp = best;
                best = score;
                winner = row;
            } else {
                runnerUp = Math.max(runnerUp, score);
            }
        }
        if (best < 0.26 || best - runnerUp < 0.01) return null;
        StringBuilder metadata = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                assets.open("shape/provenance.json"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) metadata.append(line);
        }
        JSONArray prompts = new JSONObject(metadata.toString()).getJSONArray("prompts");
        if (prompts.length() != PROMPT_COUNT || prompts.getJSONObject(winner).isNull("exercise")) return null;
        String exercise = prompts.getJSONObject(winner).getString("exercise");
        return Arrays.asList(catalog).contains(exercise) ? exercise : null;
    }

    // Sample the upright center crop directly from NV21 without allocating a full-size RGB copy.
    private static float[] imageTensor(byte[] nv21, int width, int height, int rotation) {
        if (width < 2 || height < 2 || (width & 1) != 0 || (height & 1) != 0
                || nv21.length < (long) width * height * 3 / 2) {
            throw new IllegalArgumentException("Invalid camera frame");
        }
        boolean swap = rotation == 90 || rotation == 270;
        int side = Math.min(width, height);
        int left = ((swap ? height : width) - side) / 2;
        int top = ((swap ? width : height) - side) / 2;
        float[] tensor = new float[3 * SIDE * SIDE];
        for (int y = 0; y < SIDE; y++) {
            double v = top + (y + 0.5) * side / SIDE - 0.5;
            for (int x = 0; x < SIDE; x++) {
                double u = left + (x + 0.5) * side / SIDE - 0.5;
                double sx, sy;
                switch (rotation) {
                    case 0: sx = u; sy = v; break;
                    case 90: sx = v; sy = height - 1 - u; break;
                    case 180: sx = width - 1 - u; sy = height - 1 - v; break;
                    case 270: sx = width - 1 - v; sy = u; break;
                    default: throw new IllegalArgumentException("Invalid camera orientation");
                }
                sx = Math.max(0, Math.min(width - 1, sx));
                sy = Math.max(0, Math.min(height - 1, sy));
                int x0 = (int) sx, y0 = (int) sy;
                int x1 = Math.min(x0 + 1, width - 1), y1 = Math.min(y0 + 1, height - 1);
                int a = rgb(nv21, width, height, x0, y0), b = rgb(nv21, width, height, x1, y0);
                int c = rgb(nv21, width, height, x0, y1), d = rgb(nv21, width, height, x1, y1);
                float dx = (float) (sx - x0), dy = (float) (sy - y0);
                for (int channel = 0; channel < 3; channel++) {
                    int shift = (2 - channel) * 8;
                    float upper = ((a >> shift) & 255) * (1 - dx) + ((b >> shift) & 255) * dx;
                    float lower = ((c >> shift) & 255) * (1 - dx) + ((d >> shift) & 255) * dx;
                    float value = (upper * (1 - dy) + lower * dy) / 255f;
                    tensor[channel * SIDE * SIDE + y * SIDE + x] = (value - MEAN[channel]) / STD[channel];
                }
            }
        }
        return tensor;
    }

    private static int rgb(byte[] frame, int width, int height, int x, int y) {
        int luma = 298 * Math.max(0, (frame[y * width + x] & 255) - 16);
        int chroma = width * height + (y / 2) * width + (x & ~1);
        int v = (frame[chroma] & 255) - 128, u = (frame[chroma + 1] & 255) - 128;
        int r = clamp((luma + 409 * v + 128) >> 8);
        int g = clamp((luma - 100 * u - 208 * v + 128) >> 8);
        int b = clamp((luma + 516 * u + 128) >> 8);
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
}
