package com.winlator.cmod;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.widget.FrameRating;
import com.winlator.cmod.widget.TouchpadView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class AutoTuner {
    private static final String TAG = "AutoTuner";
    private static final String COORD_FILE_PATH = "/sdcard/Download/autotuner_coordinates.txt";
    private static final long SAMPLE_INTERVAL_MS = 1000;
    private static final long MEASURE_DURATION_MS = 23000; // 5с вперёд + 5с поворот + прыжки (12с) + 1с хвост
    private static final int BASE_W = 1280;
    private static final int BASE_H = 800;
    private static final long KEY_TAP_MS = 150;

    private static class SessionState {
        static boolean isRunning = false;
        static List<TunerProfile> candidates = new ArrayList<>();
        static List<Result> results = new ArrayList<>();
        static int currentIndex = 0;
    }

    public static boolean isRunning() { return SessionState.isRunning; }

    private static class TunerProfile {
        final String name;
        final String fexPreset;
        final String screenSize;

        TunerProfile(String name, String fexPreset, String screenSize) {
            this.name = name;
            this.fexPreset = fexPreset;
            this.screenSize = screenSize;
        }
    }

    public static class Result {
        String profileName;
        float averageFps;
        public Result(String name, float fps) { this.profileName = name; this.averageFps = fps; }
    }

    private final Context context;
    private final Context appContext;
    private final Container container;
    private final Shortcut shortcut;
    private final FrameRating frameRating;
    private final TouchpadView touchpadView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float sumFps = 0;
    private int fpsCount = 0;
    private long measureStartTime = 0;
    private int liveFpsStreak = 0;
    private int liveFpsAttempts = 0;
    private boolean measurementFinished = false;

    public AutoTuner(Context context, Container container, Shortcut shortcut,
                     FrameRating frameRating, TouchpadView touchpadView) {
        this.context = context;
        this.appContext = context.getApplicationContext();
        this.container = container;
        this.shortcut = shortcut;
        this.frameRating = frameRating;
        this.touchpadView = touchpadView;
    }

    public void startAutoTuning() {
        Log.d(TAG, "=== START ===");
        SessionState.candidates.clear();
        SessionState.results.clear();
        SessionState.currentIndex = 0;
        SessionState.isRunning = true;

        // Кандидаты: Средний / Производительность / Extreme.
        // Разрешение фиксированное 1280x800 для честного сравнения.
        SessionState.candidates.add(new TunerProfile("Средний", "INTERMEDIATE", "1280x800"));
        SessionState.candidates.add(new TunerProfile("Производительность", "PERFORMANCE", "1280x800"));
        SessionState.candidates.add(new TunerProfile("Extreme", "EXTREME", "1280x800"));

        applyProfileAndLaunch();
    }

    public void applyProfileAndLaunch() {
        if (SessionState.currentIndex >= SessionState.candidates.size()) {
            finishTuning();
            return;
        }

        TunerProfile profile = SessionState.candidates.get(SessionState.currentIndex);
        Log.d(TAG, "Apply: " + profile.name + " / fex=" + profile.fexPreset + " / " + profile.screenSize);

        if (shortcut != null) {
            shortcut.putExtra("fexcorePreset", profile.fexPreset);
            shortcut.putExtra("screenSize", profile.screenSize);
            shortcut.saveData();
        }

        Toast.makeText(appContext, "Тест " + (SessionState.currentIndex + 1)
                + "/" + SessionState.candidates.size() + ": " + profile.name, Toast.LENGTH_SHORT).show();
        Toast.makeText(appContext, "Применяю профиль, перезапускаю игру...", Toast.LENGTH_SHORT).show();

        XServerDisplayActivity act = asActivity();
        if (act != null) {
            act.hideTunerPanel();
            act.restartForAutoTuner();
        } else {
            Log.e(TAG, "Context is not XServerDisplayActivity, cannot restart");
            finishTuning();
        }
    }

    public void onWinStarted() {
        if (!SessionState.isRunning) return;
        Log.d(TAG, "Game window detected, waiting for live rendering (FPS > 10)...");
        liveFpsStreak = 0;
        liveFpsAttempts = 0;
        pollForLiveRendering(this::executeMacroAndMeasure, 20000);
    }

    private void pollForLiveRendering(Runnable onConfirmed, long stabilizeMs) {
        handler.postDelayed(() -> {
            if (!SessionState.isRunning) return;
            float fps = frameRating != null ? frameRating.getLastFps() : 0;
            liveFpsAttempts++;
            if (fps >= 10f) {
                liveFpsStreak++;
                Log.d(TAG, "Live render candidate: " + fps + " FPS (streak " + liveFpsStreak + "/3)");
                if (liveFpsStreak >= 3) {
                    Log.d(TAG, "Live rendering confirmed, stabilizing " + (stabilizeMs / 1000) + "s...");
                    handler.postDelayed(onConfirmed, stabilizeMs);
                    return;
                }
            } else {
                liveFpsStreak = 0;
            }
            if (liveFpsAttempts >= 120) {
                Log.w(TAG, "Live render watchdog: forcing next step");
                onConfirmed.run();
                return;
            }
            pollForLiveRendering(onConfirmed, stabilizeMs);
        }, 1000);
    }

    private void executeMacroAndMeasure() {
        Log.d(TAG, "Executing macro...");
        try {
            File file = new File(COORD_FILE_PATH);
            if (!file.exists() || file.length() == 0) {
                Log.e(TAG, "Macro file missing/empty — skipping to intro skip");
                injectEscTwice();
                return;
            }
            TunerProfile profile = SessionState.candidates.get(SessionState.currentIndex);
            int[] size = parseScreenSize(profile.screenSize);
            float scaleX = size[0] / (float) BASE_W;
            float scaleY = size[1] / (float) BASE_H;

            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            long totalDelay = 0;
            int stepCount = 0;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length >= 3) {
                    try {
                        final int x = Math.round(Integer.parseInt(parts[0].trim()) * scaleX);
                        final int y = Math.round(Integer.parseInt(parts[1].trim()) * scaleY);
                        final long delay = Long.parseLong(parts[2].trim());
                        final int step = ++stepCount;
                        handler.postDelayed(() -> {
                            Log.d(TAG, "Tap " + step + ": " + x + "," + y);
                            if (touchpadView != null) touchpadView.injectTapAtXServer(x, y, 80);
                        }, totalDelay);
                        totalDelay += delay;
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Parse error: " + line, e);
                    }
                }
            }
            reader.close();
            // После макроса: 5с на загрузку/заставку, затем пропускаем её Esc x2
            handler.postDelayed(this::injectEscTwice, totalDelay + 5000);
        } catch (Exception e) {
            Log.e(TAG, "Macro error", e);
            injectEscTwice();
        }
    }

    private void injectEscTwice() {
        Log.d(TAG, "Skipping intro: Esc x2 (interval 1s)...");
        XServerDisplayActivity act = asActivity();
        if (act == null) {
            liveFpsStreak = 0;
            liveFpsAttempts = 0;
            pollForLiveRendering(this::startMovementScenario, 5000);
            return;
        }
        act.tunerInjectEsc();
        handler.postDelayed(act::tunerInjectEsc, 1000);
        liveFpsStreak = 0;
        liveFpsAttempts = 0;
        handler.postDelayed(() -> pollForLiveRendering(this::startMovementScenario, 5000), 2500);
    }

    private void startMovementScenario() {
        XServerDisplayActivity act = asActivity();
        if (act == null) {
            Log.e(TAG, "Activity lost — aborting test iteration");
            SessionState.isRunning = false;
            return;
        }
        Log.d(TAG, "=== MOVEMENT: forward 5s (W) ===");
        startMeasurement();
        act.tunerKeyHold(android.view.KeyEvent.KEYCODE_W, 5000);

        handler.postDelayed(() -> {
            if (!SessionState.isRunning) return;
            Log.d(TAG, "=== MOVEMENT: rotate 5s (mouse) ===");
            act.tunerRotate(5000);
        }, 5000);

        handler.postDelayed(() -> {
            if (!SessionState.isRunning) return;
            Log.d(TAG, "=== MOVEMENT: 5 jumps every 3s (Space) ===");
            for (int i = 0; i < 5; i++) {
                final int jump = i + 1;
                handler.postDelayed(() -> {
                    if (!SessionState.isRunning) return;
                    Log.d(TAG, "Jump " + jump + "/5");
                    XServerDisplayActivity a = asActivity();
                    if (a != null) a.tunerKeyHold(android.view.KeyEvent.KEYCODE_SPACE, KEY_TAP_MS);
                }, i * 3000L);
            }
        }, 10000);

        handler.postDelayed(this::finishMeasurement, MEASURE_DURATION_MS);
    }

    private void startMeasurement() {
        Log.d(TAG, "Measuring FPS (sampling every " + (SAMPLE_INTERVAL_MS / 1000) + "s)...");
        sumFps = 0;
        fpsCount = 0;
        measurementFinished = false;
        measureStartTime = System.currentTimeMillis();
        if (frameRating != null) frameRating.startTunerDisplay();

        final Runnable measureRunnable = new Runnable() {
            @Override
            public void run() {
                if (!SessionState.isRunning) return;
                long elapsed = System.currentTimeMillis() - measureStartTime;
                if (frameRating != null) {
                    float fps = frameRating.getLastFps();
                    if (fps > 0) {
                        frameRating.pushTunerSample(fps);
                        sumFps += fps;
                        fpsCount++;
                        if (fpsCount % 10 == 0) {
                            float interim = sumFps / fpsCount;
                            Log.d(TAG, "INTERIM: " + (elapsed / 1000) + "s, avg="
                                    + interim + " FPS (" + fpsCount + " samples)");
                        }
                    }
                }
                handler.postDelayed(this, SAMPLE_INTERVAL_MS);
            }
        };
        handler.post(measureRunnable);
    }

    private void finishMeasurement() {
        if (!SessionState.isRunning || measurementFinished) return;
        measurementFinished = true;
        float avg = fpsCount > 0 ? (sumFps / fpsCount) : 0;
        if (frameRating != null) frameRating.finishTunerDisplay(avg);
        TunerProfile profile = SessionState.candidates.get(SessionState.currentIndex);
        Log.d(TAG, "RESULT: " + profile.name + " = " + avg + " FPS (" + fpsCount + " samples)");
        SessionState.results.add(new Result(profile.name, avg));
        SessionState.currentIndex++;
        handler.postDelayed(this::applyProfileAndLaunch, 3000);
    }

    private void finishTuning() {
        SessionState.isRunning = false;
        Log.d(TAG, "=== DONE ===");
        Result best = null;
        for (Result r : SessionState.results) {
            Log.d(TAG, "Result: " + r.profileName + " -> " + r.averageFps);
            if (best == null || r.averageFps > best.averageFps) best = r;
        }
        if (best != null) {
            Log.d(TAG, "WINNER: " + best.profileName);
            Toast.makeText(appContext, "Победитель: " + best.profileName
                    + " (" + best.averageFps + " FPS)", Toast.LENGTH_LONG).show();
            for (TunerProfile p : SessionState.candidates) {
                if (p.name.equals(best.profileName) && shortcut != null) {
                    shortcut.putExtra("fexcorePreset", p.fexPreset);
                    shortcut.putExtra("screenSize", p.screenSize);
                    shortcut.saveData();
                    Log.d(TAG, "Winner applied: fex=" + p.fexPreset + " / " + p.screenSize);
                    break;
                }
            }
        }
        XServerDisplayActivity act = asActivity();
        if (act != null) act.onAutoTunerFinished();
    }

    private XServerDisplayActivity asActivity() {
        return (context instanceof XServerDisplayActivity) ? (XServerDisplayActivity) context : null;
    }

    private int[] parseScreenSize(String s) {
        try {
            String[] p = s.split("x");
            return new int[]{ Integer.parseInt(p[0]), Integer.parseInt(p[1]) };
        } catch (Exception e) {
            return new int[]{ BASE_W, BASE_H };
        }
    }

    public void clearTimers() {
        handler.removeCallbacksAndMessages(null);
    }

    public void stop() {
        clearTimers();
        SessionState.isRunning = false;
    }
}
