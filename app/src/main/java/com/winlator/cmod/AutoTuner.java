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

    // --- все тайминги теста в одном месте (менять только здесь) ---
    private static final long WARMUP_MS = 20000;      // прогрев после появления живого рендера
    private static final long MACRO_TO_ESC_MS = 5000; // от тапа макроса до Esc
    private static final long WORLD_LOAD_MS = 30000;  // ожидание загрузки мира после Esc
    private static final long WALK_MS = 5000;         // короткая ходьба на месте моста
    private static final long ROTATE_MS = 5000;       // поворот обзора
    private static final int JUMP_COUNT = 5;
    private static final long JUMP_INTERVAL_MS = 3000;
    private static final long MEASURE_MS = 23000;     // замер FPS
    private static final long SAMPLE_INTERVAL_MS = 1000;

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
        SessionState.candidates.clear();   // каждый новый сеанс начинается с чистого списка
        SessionState.results.clear();      // прошлогодние результаты не таскаем
        SessionState.currentIndex = 0;
        SessionState.isRunning = true;

        SessionState.candidates.add(new TunerProfile("Средний", FEXCorePreset.INTERMEDIATE, "1280x800"));
        SessionState.candidates.add(new TunerProfile("Производительность", FEXCorePreset.PERFORMANCE, "1280x800"));
        SessionState.candidates.add(new TunerProfile("Extreme", FEXCorePreset.EXTREME, "1280x800"));

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
        updateStatus("запуск…", -1f);

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
        updateStatus("Жду живого рендера…", -1f);
        liveFpsStreak = 0;
        pollForLiveRendering();
    }

    private void pollForLiveRendering() {
        handler.postDelayed(() -> {
            if (!SessionState.isRunning) return;
            float fps = frameRating != null ? frameRating.getLastFps() : 0;
            if (fps >= 10f) {
                liveFpsStreak++;
                Log.d(TAG, "Live render candidate: " + fps + " FPS (streak " + liveFpsStreak + "/3)");
                if (liveFpsStreak >= 3) {
                    Log.d(TAG, "Live rendering confirmed, warming up...");
                    runPhaseProgress("Прогрев " + (WARMUP_MS / 1000) + " секунд…", WARMUP_MS,
                            this::executeMacroAndMeasure);
                    return;
                }
            } else {
                liveFpsStreak = 0;
            }
            pollForLiveRendering();
        }, 1000);
    }

    private void executeMacroAndMeasure() {
        Log.d(TAG, "Executing macro...");
        updateStatus("Выполняю макрос…", -1f);
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
            handler.postDelayed(this::injectEscTwice, totalDelay + MACRO_TO_ESC_MS);
        } catch (Exception e) {
            Log.e(TAG, "Macro error", e);
            injectEscTwice();
        }
    }

    private void injectEscTwice() {
        Log.d(TAG, "Skipping intro: Esc x2 (interval 1s)...");
        XServerDisplayActivity act = asActivity();
        if (act != null) {
            act.tunerInjectEsc();
            handler.postDelayed(act::tunerInjectEsc, 1000);
        }
        Log.d(TAG, "Waiting " + (WORLD_LOAD_MS / 1000) + "s for world load...");
        runPhaseProgress("Пропускаю заставку, жду загрузку мира…", WORLD_LOAD_MS, this::startJumps);
    }

    private void afterMovement() {
        XServerDisplayActivity act = asActivity();
        if (act == null) { startMeasurement(); return; }
        Log.d(TAG, "=== MOVEMENT: rotate " + (ROTATE_MS / 1000) + "s ===");
        act.tunerRotate(ROTATE_MS);
        handler.postDelayed(this::startMeasurement, ROTATE_MS + 100);
    }

    private void startJumps() {
        XServerDisplayActivity act = asActivity();
        if (act == null) { startMeasurement(); return; }
        Log.d(TAG, "=== MOVEMENT: " + JUMP_COUNT + " jumps ===");
        for (int j = 0; j < JUMP_COUNT; j++) {
            final long delay = j * JUMP_INTERVAL_MS;
            handler.postDelayed(() -> act.tunerKeyHold(KeyEvent.KEYCODE_SPACE, 150), delay);
        }
        runPhaseProgress("Прыжки на месте…", JUMP_COUNT * JUMP_INTERVAL_MS, this::startWalk);
    }

    private void startWalk() {
        XServerDisplayActivity act = asActivity();
        if (act == null) { startMeasurement(); return; }
        Log.d(TAG, "=== MOVEMENT: forward " + (WALK_MS / 1000) + "s (W) ===");
        act.tunerKeyHold(KeyEvent.KEYCODE_W, WALK_MS);
        // Сам жмём E: первое окно вылезает сразу после начала движения (3 с), второе следом (+2 с)
        long[] ePressTimes = {3000, 5000};
        for (long t : ePressTimes) {
            handler.postDelayed(() -> {
                Log.d(TAG, "Pressing E at " + t + "ms");
                act.tunerKeyHold(KeyEvent.KEYCODE_E, 100);
            }, t);
        }
        runPhaseProgress("Иду вперёд…", WALK_MS, this::afterMovement);
    }

    private void startMeasurement() {
        Log.d(TAG, "Measuring FPS (" + (MEASURE_MS / 1000) + "s)...");
        sumFps = 0;
        fpsCount = 0;
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
                    }
                }
                float avg = fpsCount > 0 ? sumFps / fpsCount : 0;
                updateStatus(String.format(Locale.US, "Замер: %.1f FPS (%dс)", avg, elapsed / 1000),
                        Math.min(1f, elapsed / (float) MEASURE_MS));
                if (elapsed >= MEASURE_MS) {
                    if (fpsCount < 10) Log.w(TAG, "Watchdog: only " + fpsCount + " samples");
                    finishMeasurement();
                } else {
                    handler.postDelayed(this, SAMPLE_INTERVAL_MS);
                }
            }
        };
        handler.post(measureRunnable);
    }

    private void finishMeasurement() {
        float avg = fpsCount > 0 ? (sumFps / fpsCount) : 0;
        if (frameRating != null) frameRating.finishTunerDisplay(avg);
        TunerProfile profile = SessionState.candidates.get(SessionState.currentIndex);
        Log.d(TAG, "RESULT: " + profile.name + " = " + avg + " FPS (" + fpsCount + " samples)");
        SessionState.results.add(new Result(profile.name, avg));
        updateStatus("готово", -1f);
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
            updateStatus("Победитель: " + best.profileName + " — "
                    + String.format(Locale.US, "%.1f FPS", best.averageFps), -1f);
            for (TunerProfile p : SessionState.candidates) {
                if (p.name.equals(best.profileName) && shortcut != null) {
                    shortcut.putExtra("fexcorePreset", p.fexPreset);
                    shortcut.putExtra("screenSize", p.screenSize);
                    shortcut.saveData();
                    break;
                }
            }
        }
        // Панель больше не показываем: через 3 секунды игра закрывается сама
        XServerDisplayActivity act = asActivity();
        if (act != null) handler.postDelayed(act::closeAfterTuner, 3000);
    }

    // --- фаза с живым прогресс-баром: текст + процент заполнения раз в секунду ---
    private void runPhaseProgress(String text, long totalMs, Runnable end) {
        final long start = System.currentTimeMillis();
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!SessionState.isRunning) return;
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed >= totalMs) {
                    end.run();
                    return;
                }
                updateStatus(text + " (" + (elapsed / 1000) + "с)",
                        Math.min(1f, elapsed / (float) totalMs));
                handler.postDelayed(this, 1000);
            }
        };
        updateStatus(text, 0f);
        handler.postDelayed(tick, 1000);
    }

    private XServerDisplayActivity asActivity() {
        return (context instanceof XServerDisplayActivity) ? (XServerDisplayActivity) context : null;
    }

    private void updateStatus(String text, float progress) {
        XServerDisplayActivity act = asActivity();
        if (act != null) act.showTunerStatus(rebuildPanel(text), progress);
    }

    // Собирает текст инфо-панели: заголовок, список кандидатов с результатами, лидер
    private String rebuildPanel(String stageText) {
        StringBuilder sb = new StringBuilder();
        int n = SessionState.candidates.size();
        boolean allDone = SessionState.currentIndex >= n;

        if (!allDone && SessionState.isRunning) {
            sb.append("Тест ").append(SessionState.currentIndex + 1).append("/").append(n)
              .append(": ").append(SessionState.candidates.get(SessionState.currentIndex).name).append('\n');
        }

        for (int i = 0; i < n; i++) {
            TunerProfile p = SessionState.candidates.get(i);
            Result done = null;
            for (Result r : SessionState.results) {
                if (r.profileName.equals(p.name)) { done = r; break; }
            }
            if (done != null) {
                sb.append("✓ ").append(p.name).append(" — ")
                  .append(String.format(Locale.US, "%.1f FPS", done.averageFps)).append('\n');
            } else if (SessionState.isRunning && i == SessionState.currentIndex) {
                sb.append("▶ ").append(p.name).append(": ").append(stageText).append('\n');
            } else {
                sb.append("· ").append(p.name).append('\n');
            }
        }

        if (allDone) {
            sb.append(stageText); // строка «Победитель: …»
        } else if (!SessionState.results.isEmpty()) {
            Result leader = null;
            for (Result r : SessionState.results) {
                if (leader == null || r.averageFps > leader.averageFps) leader = r;
            }
            if (leader != null) {
                sb.append("Лидер: ").append(leader.profileName).append(" (")
                  .append(String.format(Locale.US, "%.1f FPS", leader.averageFps)).append(")");
            }
        }
        return sb.toString();
    }

    public void clearTimers() {
        handler.removeCallbacksAndMessages(null);
    }

    public void stop() {
        clearTimers();
        SessionState.isRunning = false;
    }
}
