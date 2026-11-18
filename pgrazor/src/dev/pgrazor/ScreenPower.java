package dev.pgrazor;

import android.app.Activity;
import android.content.Context;
import android.view.Window;
import android.view.WindowManager;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Éteindre l'écran": the game keeps running while the screen looks off.
 *
 * Everywhere, Pad covers the game (and PGRazor's overlays) with a black veil, the window's
 * backlight goes to its minimum and the window keeps the phone awake, so Android never sleeps and the
 * game is never paused. Native rendering drops to a quarter resolution meanwhile (see
 * {@link Control#batteryConfig()}).
 *
 * On PGRazor Root the module's root helper ({@link RootDaemon}) also switches the display panel itself
 * off, the same way screen-mirroring tools do: the system still thinks the screen is on, so the game
 * keeps running. The game asks for it through a small request file in its own files folder; the
 * helper reads it as root. A double tap on the veil, leaving the game or the power button brings the
 * screen back.
 */
public final class ScreenPower {
    /** Request file in the game's files folder: "off <pid>" or "on". Read by {@link RootDaemon}. */
    static final String REQUEST = "pgrazor-screen";

    private static volatile boolean off;
    private static Activity owner;
    private static float savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
    private static boolean addedKeepScreenOn;
    private static final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "pgrazor-screen");
        thread.setDaemon(true);
        return thread;
    });

    private ScreenPower() {}

    /** True while the screen is "off" (veil shown, panel off on PGRazor Root). */
    public static boolean off() { return off; }

    /** Main thread only. */
    static void toggle(Activity activity) {
        if (off) turnOn(activity); else turnOff(activity);
    }

    /** Main thread only: shows the veil, dims the backlight and asks the root helper to cut the panel. */
    static void turnOff(Activity activity) {
        if (off || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        off = true;
        owner = activity;
        try {
            Window window = activity.getWindow();
            WindowManager.LayoutParams attributes = window.getAttributes();
            savedBrightness = attributes.screenBrightness;
            addedKeepScreenOn = (attributes.flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) == 0;
            attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF;
            window.setAttributes(attributes);
            if (addedKeepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (RuntimeException error) {
            Control.event("screen", "window dimming unavailable: " + error);
        }
        request(activity, "off " + android.os.Process.myPid());
        Control.event("screen", "OFF (" + (PgAssets.rooted() ? "panel via root helper + veil" : "veil") + ")");
    }

    /** Main thread only. Safe to call when already on (it re-sends "on" to the root helper). */
    static void turnOn(Activity activity) {
        boolean wasOff = off;
        off = false;
        Activity window = owner != null ? owner : activity;
        owner = null;
        if (wasOff && window != null) {
            try {
                Window w = window.getWindow();
                WindowManager.LayoutParams attributes = w.getAttributes();
                attributes.screenBrightness = savedBrightness;
                w.setAttributes(attributes);
                if (addedKeepScreenOn) w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } catch (RuntimeException error) {
                Control.event("screen", "window restore failed: " + error);
            }
        }
        addedKeepScreenOn = false;
        savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        if (activity != null || window != null) request(activity != null ? activity : window, "on");
        if (wasOff) Control.event("screen", "ON");
    }

    private static void request(Context context, String value) {
        if (!PgAssets.rooted() || context == null) return;
        final File folder = context.getApplicationContext().getFilesDir();
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
