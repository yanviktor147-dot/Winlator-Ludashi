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
    private static final int MEASURE_TIMEOUT_MS = 30000;

    private static class SessionState {
        static boolean isRunning = false;
        static List<TunerProfile> candidates = new ArrayList<>();
        static List<Result> results = new ArrayList<>();
        static int currentIndex = 0;
    }

    public static boolean isRunning() { return SessionState.isRunning; }

    public static class TunerProfile {
        String name;
        String box64Preset;
        String screenSize;
        public TunerProfile(String name, String box64Preset, String screenSize) {
            this.name = name; this.box64Preset = box64Preset; this.screenSize = screenSize;
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

        SessionState.candidates.add(new TunerProfile("Сток (Compatibility)", "COMPATIBILITY", "1280x720"));
        SessionState.candidates.add(new TunerProfile("Performance", "PERFORMANCE", "1280x720"));
        SessionState.candidates.add(new TunerProfile("Performance + 960x540", "PERFORMANCE", "960x540"));

        applyProfileAndLaunch();
    }

    public void applyProfileAndLaunch() {
        if (SessionState.currentIndex >= SessionState.candidates.size()) {
            finishTuning();
            return;
        }

        TunerProfile profile = SessionState.candidates.get(SessionState.currentIndex);
        Log.d(TAG, "Apply: " + profile.name + " / " + profile.box64Preset + " / " + profile.screenSize);

        if (shortcut != null) {
            shortcut.putExtra("box64Preset", profile.box64Preset);
            shortcut.putExtra("screenSize", profile.screenSize);
            shortcut.saveData();
        }

        Toast.makeText(appContext, "Тест " + (SessionState.currentIndex + 1)
                + "/" + SessionState.candidates.size() + ": " + profile.name, Toast.LENGTH_SHORT).show();

        if (context instanceof XServerDisplayActivity) {
            ((XServerDisplayActivity) context).restartForAutoTuner();
        } else {
            Log.e(TAG, "Context is not XServerDisplayActivity, cannot restart");
            finishTuning();
        }
    }

    public void onWinStarted() {
        if (!SessionState.isRunning) return;
        Log.d(TAG, "Game started, warming up 15s...");
        handler.postDelayed(this::executeMacroAndMeasure, 15000);
    }

    private void executeMacroAndMeasure() {
        Log.d(TAG, "Executing macro...");
        try {
            File file = new File(COORD_FILE_PATH);
            if (!file.exists() || file.length() == 0) {
                Log.e(TAG, "Macro file missing/empty");
                startMeasurement();
                return;
            }
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            long totalDelay = 0;
            int stepCount = 0;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length >= 3) {
                    try {
                        final int x = Integer.parseInt(parts[0].trim());
                        final int y = Integer.parseInt(parts[1].trim());
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
            handler.postDelayed(this::startMeasurement, totalDelay + 2000);
        } catch (Exception e) {
            Log.e(TAG, "Macro error", e);
            startMeasurement();
        }
    }

    private void startMeasurement() {
        Log.d(TAG, "Measuring FPS (20s)...");
        sumFps = 0;
        fpsCount = 0;
        measureStartTime = System.currentTimeMillis();

        final Runnable measureRunnable = new Runnable() {
            @Override
            public void run() {
                if (!SessionState.isRunning) return;
                long elapsed = System.currentTimeMillis() - measureStartTime;
                if (frameRating != null) {
                    float fps = frameRating.getLastFps();
                    if (fps > 0) { sumFps += fps; fpsCount++; }
                }
                if (fpsCount >= 20 || elapsed >= MEASURE_TIMEOUT_MS) {
                    if (fpsCount < 20) Log.w(TAG, "Watchdog: only " + fpsCount + " samples");
                    finishMeasurement();
                } else {
                    handler.postDelayed(this, 1000);
                }
            }
        };
        handler.post(measureRunnable);
    }

    private void finishMeasurement() {
        float avg = fpsCount > 0 ? (sumFps / fpsCount) : 0;
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
                    shortcut.putExtra("box64Preset", p.box64Preset);
                    shortcut.putExtra("screenSize", p.screenSize);
                    shortcut.saveData();
                    break;
                }
            }
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
