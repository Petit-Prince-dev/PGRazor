package dev.pgrazor;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Éteindre l'écran": the game keeps running while the screen looks off.
 *
 * Everywhere, Pad covers the game (and PGRazor's overlays) with a black veil, the status and navigation
 * bars are hidden, PGRazor's own toasts are held back, the window's backlight goes to its minimum and
 * the window keeps the phone awake, so Android never sleeps and the game is never paused. Native
 * rendering drops to a quarter resolution meanwhile (see {@link Control#batteryConfig()}).
 *
 * On PGRazor Root the module's root helper ({@link RootDaemon}) also switches the display panel itself
 * off, the same way screen-mirroring tools do: the system still thinks the screen is on, so the game
 * keeps running, and nothing at all is visible (no status bar, no notification). The game asks for it
 * through a small request file in its own files folder; the helper reads it as root, keeps the panel
 * off while asked to and reports back in {@link #STATE}. A double tap on the veil, a volume key (root:
 * the touchscreen is often off with the panel), leaving the game or the power button brings the
 * screen back.
 */
public final class ScreenPower {
    /** Request file in the game's files folder: "off <pid>" or "on". Read by {@link RootDaemon}. */
    static final String REQUEST = "pgrazor-screen";
    /** Written back by {@link RootDaemon}: "ready", "off ok", "off failed" or "on", then a timestamp. */
    static final String STATE = "pgrazor-screen-state";
    /** The panel request waits for the backlight change to settle, so Android does not power it back on. */
    private static final long PANEL_DELAY_MS = 700, CHECK_DELAY_MS = 3500;

    private static volatile boolean off;
    private static volatile String rootIssue = "";
    private static Activity owner;
    private static float savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
    private static boolean addedKeepScreenOn;
    private static int hiddenBars, savedBarsBehavior = -1;
    private static long generation, offRequestedAt, lastWakePoll;
    private static boolean wakePollRunning;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "pgrazor-screen");
        thread.setDaemon(true);
        return thread;
    });

    private ScreenPower() {}

    /** True while the screen is "off" (veil shown, panel off on PGRazor Root). */
    public static boolean off() { return off; }

    /** Why the panel itself stayed on (shown on the veil), or empty. */
    static String rootIssue() { return rootIssue; }

    /** Main thread only. */
    static void toggle(Activity activity) {
        if (off) turnOn(activity); else turnOff(activity);
    }

    /** Main thread only: shows the veil, hides the bars, dims the backlight and asks the root helper to cut the panel. */
    static void turnOff(Activity activity) {
        if (off || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        off = true;
        rootIssue = "";
        owner = activity;
        final long run = ++generation;
        try {
            Window window = activity.getWindow();
            WindowManager.LayoutParams attributes = window.getAttributes();
            savedBrightness = attributes.screenBrightness;
            addedKeepScreenOn = (attributes.flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) == 0;
            attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF;
            window.setAttributes(attributes);
            if (addedKeepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            hideBars(window);
        } catch (RuntimeException error) {
            Control.event("screen", "window dimming unavailable: " + error);
        }
        Control.event("screen", "OFF (" + (PgAssets.rooted() ? "panel via root helper + veil" : "veil") + ")");
        if (!PgAssets.rooted()) return;
        final File folder = activity.getApplicationContext().getFilesDir();
        final long requestedAt = System.currentTimeMillis() + PANEL_DELAY_MS;
        offRequestedAt = requestedAt;
        main.postDelayed(() -> { if (off && run == generation) request(folder, "off " + android.os.Process.myPid()); }, PANEL_DELAY_MS);
        main.postDelayed(() -> { if (off && run == generation) checkRootHelper(folder, requestedAt); }, PANEL_DELAY_MS + CHECK_DELAY_MS);
    }

    /** Main thread only. Safe to call when already on (it re-sends "on" to the root helper). */
    static void turnOn(Activity activity) {
        boolean wasOff = off;
        off = false;
        rootIssue = "";
        generation++;
        Activity window = owner != null ? owner : activity;
        owner = null;
        if (wasOff && window != null) {
            try {
                Window w = window.getWindow();
                WindowManager.LayoutParams attributes = w.getAttributes();
                attributes.screenBrightness = savedBrightness;
                w.setAttributes(attributes);
                if (addedKeepScreenOn) w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                showBars(w);
            } catch (RuntimeException error) {
                Control.event("screen", "window restore failed: " + error);
            }
        }
        addedKeepScreenOn = false;
        savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        Activity context = activity != null ? activity : window;
        if (PgAssets.rooted() && context != null) request(context.getApplicationContext().getFilesDir(), "on");
        if (wasOff) Control.event("screen", "ON");
    }

    private static void hideBars(Window window) {
        hiddenBars = 0;
        savedBarsBehavior = -1;
        if (Build.VERSION.SDK_INT < 30) return;
        WindowInsetsController controller = window.getInsetsController();
        if (controller == null) return;
        WindowInsets insets = window.getDecorView().getRootWindowInsets();
        int bars = WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars();
        if (insets != null) {
            bars = 0;
            if (insets.isVisible(WindowInsets.Type.statusBars())) bars |= WindowInsets.Type.statusBars();
            if (insets.isVisible(WindowInsets.Type.navigationBars())) bars |= WindowInsets.Type.navigationBars();
        }
        if (bars == 0) return;
        savedBarsBehavior = controller.getSystemBarsBehavior();
        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(bars);
        hiddenBars = bars;
    }

    private static void showBars(Window window) {
        int bars = hiddenBars;
        hiddenBars = 0;
        if (Build.VERSION.SDK_INT < 30 || bars == 0) return;
        WindowInsetsController controller = window.getInsetsController();
        if (controller == null) return;
        controller.show(bars);
        if (savedBarsBehavior >= 0) controller.setSystemBarsBehavior(savedBarsBehavior);
        savedBarsBehavior = -1;
    }

    /**
     * Main thread, called often while the screen is off: the root helper reports "woken" when a volume key
     * or a double tap brought the panel back (the touchscreen is often off with the panel, so the veil
     * never sees the taps). The veil then goes away too.
     */
    static void pollWake(Activity activity) {
        if (!off || !PgAssets.rooted() || activity == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastWakePoll < 400 || wakePollRunning) return;
        lastWakePoll = now;
        wakePollRunning = true;
        final long run = generation, since = offRequestedAt;
        final File folder = activity.getApplicationContext().getFilesDir();
        writer.execute(() -> {
            String state = RootDaemon.readSmall(new File(folder, STATE));
            boolean woken = false;
            if (state != null && state.startsWith("woken")) {
                try { woken = Long.parseLong(state.trim().substring(state.trim().lastIndexOf(' ') + 1)) >= since - 1000; }
                catch (RuntimeException ignored) {}
            }
            final boolean wake = woken;
            main.post(() -> {
                wakePollRunning = false;
                if (wake && off && run == generation) turnOn(activity);
            });
        });
    }

    /** A few seconds after the request: did the root helper really switch the panel off? */
    private static void checkRootHelper(File folder, long requestedAt) {
        writer.execute(() -> {
            String read = RootDaemon.readSmall(new File(folder, STATE));
            final String state = read == null ? "" : read.trim();
            long at = 0;
            try { at = Long.parseLong(state.substring(state.lastIndexOf(' ') + 1)); } catch (RuntimeException ignored) {}
            boolean fresh = at >= requestedAt - 1000;
            String issue;
            if (fresh && state.startsWith("off ok")) issue = "";
            else if (fresh && state.startsWith("off failed"))
                issue = "La coupure de la dalle par root a échoué sur ce téléphone : seul le voile noir est actif.";
            else if (!state.isEmpty())
                issue = "Le service root tourne mais n'a pas coupé la dalle : seul le voile noir est actif.";
            else
                issue = "Le service root ne répond pas (redémarre le téléphone si le module vient d'être installé) : seul le voile noir est actif.";
            main.post(() -> {
                if (!off) return;
                rootIssue = issue;
                Control.event("screen", "root helper state: " + (state.isEmpty() ? "none" : state));
            });
        });
    }

    private static void request(File folder, String value) {
        writer.execute(() -> write(folder, REQUEST, value));
    }

    /** Atomically replaces a small request file in the game's files folder. */
    static void write(File folder, String name, String value) {
        File target = new File(folder, name), temp = new File(folder, name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception error) {
            Control.event("screen", "request write failed: " + error);
            return;
        }
        if (!temp.renameTo(target)) Control.event("screen", "request rename failed: " + name);
    }
}
