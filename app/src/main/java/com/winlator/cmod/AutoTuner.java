package com.winlator.cmod;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.fexcore.FEXCorePreset;
import com.winlator.cmod.widget.FrameRating;
import com.winlator.cmod.widget.TouchpadView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AutoTuner {
    private static final String TAG = "AutoTuner";
    private static final String COORD_FILE_PATH = "/sdcard/Download/autotuner_coordinates.txt";
    private static final long SAMPLE_INTERVAL_MS = 1000;
    private static final long MEASURE_DURATION_MS = 23000; // ~23 отсчёта по чистому геймплею
    private static final long WORLD_LOAD_WAIT_MS = 25000;  // ожидание загрузки мира после Esc

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
        final String profileName;
        final String fexPreset;
        final String screenSize;
        final float averageFps;
        Result(String profileName, String fexPreset, String screenSize, float averageFps) {
            this.profileName = profileName;
            this.fexPreset = fexPreset;
            this.screenSize = screenSize;
            this.averageFps = averageFps;
        }
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
    private final List<Runnable> pendingTimers = new ArrayList<>();

    public AutoTuner(Context context, Container container, Shortcut shortcut,
                     FrameRating frameRating, TouchpadView touchpadView) {
        this.context = context;
        this.appContext = context.getApplicationContext();
        this.container = container;
        this.shortcut = shortcut;
        this.frameRating = frameRating;
        this.touchpadView = touchpadView;
    }

    public void clearTimers() {
        handler.removeCallbacksAndMessages(null);
        synchronized (pendingTimers) { pendingTimers.clear(); }
    }

    private void post(long delayMs, Runnable r) {
        synchronized (pendingTimers) { pendingTimers.add(r); }
        handler.postDelayed(r, delayMs);
    }

    private XServerDisplayActivity asActivity() {
        return (context instanceof XServerDisplayActivity) ? (XServerDisplayActivity) context : null;
    }

    private void updateStatus(String text) {
        XServerDisplayActivity act = asActivity();
        if (act != null) act.showTunerStatus(text);
    }

    public void startAutoTuning() {
        Log.d(TAG, "=== START ===");
        clearTimers();
        SessionState.candidates.clear();
        SessionState.results.clear();
        SessionState.currentIndex = 0;
        SessionState.isRunning = true;

        SessionState.candidates.add(new TunerProfile("Средний", FEXCorePreset.INTERMEDIATE, "1280x800"));
        SessionState.candidates.add(new TunerProfile("Производительность", FEXCorePreset.PERFORMANCE, "1280x800"));
        SessionState.candidates.add(new TunerProfile("Extreme", FEXCorePreset.EXTREME, "1280x800"));

        applyProfileAndLaunch();
    }

    public void applyProfileAndLaunch() {
        if (!SessionState.isRunning) return;
        if (SessionState.currentIndex >= SessionState.candidates.size()) {
            finishTuning();
            return;
        }

        TunerProfile profile = SessionState.candidates.get(SessionState.currentIndex);
        Log.d(TAG, "Apply: " + profile.name + " / fex=" + profile.fexPreset + " / " + profile.screenSize);
        updateStatus("Тест " + (SessionState.currentIndex + 1) + "/" + SessionState.candidates.size()
                + ": " + profile.name);

        if (shortcut != null) {
            shortcut.putExtra("fexcorePreset", profile.fexPreset);
            shortcut.putExtra("screenSize", profile.screenSize);
            shortcut.saveData();
        }

        Toast.makeText(appContext, "Тест " + (SessionState.currentIndex + 1)
                + "/" + SessionState.candidates.size() + ": " + profile.name, Toast.LENGTH_SHORT).show();

        XServerDisplayActivity act = asActivity();
        if (act != null) {
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
        pollForLiveRendering();
    }

    // Детектор живого рендера — только для меню (до макроса)
    private void pollForLiveRendering() {
        post(1000, () -> {
            if (!SessionState.isRunning) return;
            float fps = frameRating != null ? frameRating.getLastFps() : 0;
            if (fps >= 10f) {
                liveFpsStreak++;
                Log.d(TAG, "Live render candidate: " + fps + " FPS (streak " + liveFpsStreak + "/3)");
                if (liveFpsStreak >= 3) {
                    Log.d(TAG, "Live rendering confirmed, warming up 20s...");
                    updateStatus("Игра запустилась, прогреваю 20 секунд…");
                    post(20000, this::executeMacroAndMeasure);
                    return;
                }
            } else {
                liveFpsStreak = 0;
            }
            pollForLiveRendering();
        });
    }

    private void executeMacroAndMeasure() {
        if (!SessionState.isRunning) return;
        Log.d(TAG, "Executing macro...");
        try {
            File file = new File(COORD_FILE_PATH);
            if (!file.exists() || file.length() == 0) {
                Log.e(TAG, "Macro file missing/empty");
                injectEscTwice();
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
                        post(totalDelay, () -> {
                            Log.d(TAG, "Tap " + step + ": " + x + "," + y);
                            if (touchpadView != null) touchpadView.injectTapAtXServer(x, y, 80);
                        });
                        totalDelay += delay;
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Parse error: " + line, e);
                    }
                }
            }
            reader.close();
            post(totalDelay + 5000, this::injectEscTwice);
        } catch (Exception e) {
            Log.e(TAG, "Macro error", e);
            injectEscTwice();
        }
    }

    private void injectEscTwice() {
        if (!SessionState.isRunning) return;
        Log.d(TAG, "Skipping intro: Esc x2 (interval 1s)...");
        updateStatus("Пропускаю заставку, жду загрузку мира…");
        XServerDisplayActivity act = asActivity();
        if (act != null) {
            act.tunerInjectEsc();
            post(1000, act::tunerInjectEsc);
        }
        Log.d(TAG, "Waiting " + (WORLD_LOAD_WAIT_MS / 1000) + "s for world load...");
        post(WORLD_LOAD_WAIT_MS, this::startMovementScenario);
    }

    private void startMovementScenario() {
        if (!SessionState.isRunning) return;
        Log.d(TAG, "=== MOVEMENT: forward 5s (W) ===");
        updateStatus("Двигаюсь вперёд 5 секунд…");
        XServerDisplayActivity act = asActivity();
        if (act != null) act.tunerKeyHold(KeyEvent.KEYCODE_W, 5000);
        post(6000, this::startRotate);
    }

    private void startRotate() {
        if (!SessionState.isRunning) return;
        Log.d(TAG, "=== MOVEMENT: rotate 5s (mouse) ===");
        updateStatus("Поворачиваю камеру 5 секунд…");
        XServerDisplayActivity act = asActivity();
        if (act != null) act.tunerRotate(5000);
        post(5500, this::startJumps);
    }

    private void startJumps() {
        if (!SessionState.isRunning) return;
        Log.d(TAG, "=== MOVEMENT: 5 jumps ===");
        updateStatus("Прыгаю…");
        XServerDisplayActivity act = asActivity();
        for (int i = 1; i <= 5; i++) {
            final int jumpNo = i;
            final long at = (i - 1) * 3000L;
            post(at, () -> {
                if (!SessionState.isRunning) return;
                Log.d(TAG, "Jump " + jumpNo);
                if (act != null) act.tunerKeyHold(KeyEvent.KEYCODE_SPACE, 150);
            });
        }
        post(5 * 3000L + 1000, this::startMeasurement);
    }

    private void startMeasurement() {
        if (!SessionState.isRunning) return;
        Log.d(TAG, "Measuring FPS (" + (MEASURE_DURATION_MS / 1000) + "s)...");
        sumFps = 0;
        fpsCount = 0;
        measureStartTime = System.currentTimeMillis();
        if (frameRating != null) frameRating.startTunerDisplay();

        final Runnable measureRunnable = new Runnable() {
            @Override
            public void run() {
                if (!SessionState.isRunning) return;
                long elapsed = System.currentTimeMillis() - measureStartTime;
                if (elapsed >= MEASURE_DURATION_MS) {
                    finishMeasurement();
                    return;
                }
                if (frameRating != null) {
                    float fps = frameRating.getLastFps();
                    if (fps > 0) {
                        frameRating.pushTunerSample(fps);
                        sumFps += fps;
                        fpsCount++;
                        updateStatus(String.format(Locale.US, "Замер: %.1f FPS (%dс)", fps, elapsed / 1000));
                    }
                }
                handler.postDelayed(this, SAMPLE_INTERVAL_MS);
            }
        };
        handler.postDelayed(measureRunnable, SAMPLE_INTERVAL_MS);
    }

    private void finishMeasurement() {
        if (!SessionState.isRunning) return;
        TunerProfile profile = SessionState.candidates.get(SessionState.currentIndex);
        float avg = fpsCount > 0 ? sumFps / fpsCount : 0f;
        Log.d(TAG, "RESULT: " + profile.name + " = " + String.format(Locale.US, "%.2f", avg)
                + " FPS (" + fpsCount + " samples)");
        SessionState.results.add(new Result(profile.name, profile.fexPreset, profile.screenSize, avg));
        updateStatus("Результат: " + profile.name + " — "
                + String.format(Locale.US, "%.1f FPS", avg));
        SessionState.currentIndex++;
        post(3000, this::applyProfileAndLaunch);
    }

    private void finishTuning() {
        Log.d(TAG, "=== DONE ===");
        SessionState.isRunning = false;

        if (SessionState.results.isEmpty()) {
            Log.e(TAG, "No results to pick winner from");
            updateStatus("Ошибка: нет результатов");
            return;
        }

        Result best = SessionState.results.get(0);
        for (Result r : SessionState.results) {
            if (r.averageFps > best.averageFps) best = r;
        }
        Log.d(TAG, "WINNER: " + best.profileName + " (" + best.fexPreset + " / "
                + best.screenSize + ") avg=" + String.format(Locale.US, "%.2f", best.averageFps));

        if (shortcut != null) {
            shortcut.putExtra("fexcorePreset", best.fexPreset);
            shortcut.putExtra("screenSize", best.screenSize);
            shortcut.saveData();
        }

        Toast.makeText(appContext, "Победитель: " + best.profileName + " — "
                + String.format(Locale.US, "%.1f FPS", best.averageFps), Toast.LENGTH_LONG).show();
        updateStatus("Победитель: " + best.profileName + " — "
                + String.format(Locale.US, "%.1f FPS", best.averageFps));

        // Игра больше не нужна — закрываем через 3 секунды (тост успеет показаться)
        XServerDisplayActivity act = asActivity();
        if (act != null) post(3000, act::closeAfterTuner);
    }
}
