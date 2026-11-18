package dev.pgrazor;

import android.os.Build;
import android.os.IBinder;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * PGRazor Root helper. It never runs inside the game: the module's service.sh starts it as root at boot,
 *
 *   CLASSPATH=$MODDIR/pgrazor/classes.dex app_process /system/bin dev.pgrazor.RootDaemon $MODDIR
 *
 * and restarts it if it exits. Its one job is the screen.
 *
 * Screen. The game writes "off &lt;pid&gt;" or "on" to files/pgrazor-screen ({@link ScreenPower}). While the
 * request is "off" and that game process is alive, the display panel is switched off the way
 * screen-mirroring tools do it (SurfaceControl / DisplayControl, or DisplayManager.requestDisplayPower
 * on Android 15+): Android still considers the screen on, so nothing sleeps and the game keeps running.
 * "on", the game process exiting or the phone going to sleep and waking again bring it back.
 */
public final class RootDaemon {
    static final String GAME = "com.nianticlabs.pokemongo";
    private final File module, logFile, offMarker;
    private final File gameFiles = new File("/data/data/" + GAME + "/files");
    private String lastScreenRequest;
    private boolean panelOff, restoreWhenAwake;
    private long lastPanelCheck;

    private RootDaemon(File module) {
        this.module = module;
        this.logFile = new File(module, "pgrazor/daemon.log");
        this.offMarker = new File(module, "pgrazor/panel-off");
    }

    public static void main(String[] args) {
        File module = new File(args.length > 0 ? args[0] : "/data/adb/modules/pgrazor");
        new RootDaemon(module).run();
    }

    private void run() {
        log("start sdk=" + Build.VERSION.SDK_INT + " module=" + module + " version=" + installedVersion());
        while (true) {
            try {
                pollScreen();
                Thread.sleep(panelOff || restoreWhenAwake ? 200 : 300);
            } catch (Throwable error) {
                log("loop: " + error);
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }
    }

    // ----- Screen -----

    private void pollScreen() {
        String request = readSmall(new File(gameFiles, ScreenPower.REQUEST));
        if (request == null) request = "on";
        request = request.trim();
        boolean wantOff = request.startsWith("off") && gameAlive(request);
        boolean changed = !request.equals(lastScreenRequest);
        lastScreenRequest = request;
        long now = System.currentTimeMillis();
        if (wantOff) {
            restoreWhenAwake = false;
            if (!panelOff && (changed || now - lastPanelCheck > 2000)) {
                lastPanelCheck = now;
                if (interactive()) {
                    try { new FileOutputStream(offMarker).close(); } catch (IOException ignored) {}
                    boolean ok = setPanel(false);
                    panelOff = ok;
                    if (!ok) offMarker.delete();
                    log("panel off " + (ok ? "ok" : "failed"));
                }
            }
            return;
        }
        // "on", a dead game or no request: bring back a panel this helper switched off (also one left off by
        // an earlier helper process), but only while the phone is awake: switching the panel on during
        // sleep would light a sleeping screen.
        if (panelOff || restoreWhenAwake || offMarker.exists()) {
            if (interactive()) {
                boolean ok = setPanel(true);
                log("panel on " + (ok ? "ok" : "failed") + " (" + request + ")");
                panelOff = false;
                restoreWhenAwake = false;
                offMarker.delete();
            } else {
                panelOff = false;
                restoreWhenAwake = true;
            }
        }
    }

    /** "off <pid>": that pid is still the game. */
    private static boolean gameAlive(String request) {
        String[] parts = request.split("\\s+");
        if (parts.length < 2) return true;
        String cmdline = readSmall(new File("/proc/" + parts[1].replaceAll("[^0-9]", "") + "/cmdline"));
        return cmdline != null && cmdline.startsWith(GAME);
    }

    private static Object service(String name, String type) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
        if (binder == null) throw new IllegalStateException("no service " + name);
        return Class.forName(type + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private boolean interactive() {
        try {
            Object power = service("power", "android.os.IPowerManager");
            return (Boolean) power.getClass().getMethod("isInteractive").invoke(power);
        } catch (Throwable error) {
            return true;
        }
    }

    /** Switches the built-in display panel without changing Android's power state. */
    boolean setPanel(boolean on) {
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                Object display = service("display", "android.hardware.display.IDisplayManager");
                for (Method method : display.getClass().getMethods()) {
                    if (!method.getName().equals("requestDisplayPower") || method.getParameterTypes().length != 2) continue;
                    Class<?> state = method.getParameterTypes()[1];
                    Object value = state == boolean.class ? (Object) on : (Object) (on ? 2 : 1);  // Display.STATE_ON / STATE_OFF
                    Object result = method.invoke(display, 0, value);
                    if (!(result instanceof Boolean) || (Boolean) result) return true;
                }
            } catch (Throwable error) {
                log("requestDisplayPower: " + error);
            }
        }
        try {
            Class<?> surface = Class.forName("android.view.SurfaceControl");
            Method mode = surface.getMethod("setDisplayPowerMode", IBinder.class, int.class);
            boolean all = true;
            for (IBinder token : displayTokens(surface)) {
                Object result = mode.invoke(null, token, on ? 2 : 0);  // POWER_MODE_NORMAL / POWER_MODE_OFF
                if (result instanceof Boolean && !(Boolean) result) all = false;
            }
            return all;
        } catch (Throwable error) {
            log("setDisplayPowerMode: " + error);
            return false;
        }
    }

    private static List<IBinder> displayTokens(Class<?> surface) throws Exception {
        List<IBinder> tokens = new ArrayList<>();
        try {
            long[] ids = (long[]) surface.getMethod("getPhysicalDisplayIds").invoke(null);
            Method token = surface.getMethod("getPhysicalDisplayToken", long.class);
            for (long id : ids) tokens.add((IBinder) token.invoke(null, id));
            return tokens;
        } catch (NoSuchMethodException moved) {
            // Android 14 moved these to services.jar's DisplayControl.
        }
        if (Build.VERSION.SDK_INT >= 34) {
            Class<?> factory = Class.forName("com.android.internal.os.ClassLoaderFactory");
            Method create = factory.getDeclaredMethod("createClassLoader", String.class, String.class, String.class, ClassLoader.class, int.class, boolean.class, String.class);
            ClassLoader loader = (ClassLoader) create.invoke(null, "/system/framework/services.jar", null, null, ClassLoader.getSystemClassLoader(), 0, true, null);
            Class<?> control = loader.loadClass("com.android.server.display.DisplayControl");
            Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
            load.setAccessible(true);
            load.invoke(Runtime.getRuntime(), control, "android_servers");
            long[] ids = (long[]) control.getMethod("getPhysicalDisplayIds").invoke(null);
            Method token = control.getMethod("getPhysicalDisplayToken", long.class);
            for (long id : ids) tokens.add((IBinder) token.invoke(null, id));
            return tokens;
        }
        try {
            tokens.add((IBinder) surface.getMethod("getInternalDisplayToken").invoke(null));
        } catch (NoSuchMethodException old) {
            tokens.add((IBinder) surface.getMethod("getBuiltInDisplay", int.class).invoke(null, 0));
        }
        return tokens;
    }

    private String installedVersion() {
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(new File(module, "module.prop"))) { props.load(new InputStreamReader(in, StandardCharsets.UTF_8)); }
        catch (IOException error) { return ""; }
        return props.getProperty("version", "").trim();
    }

    // ----- Small I/O helpers -----

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        for (int n; (n = in.read(buffer)) > 0; ) {
            out.write(buffer, 0, n);
            if (out.size() > limit) throw new IOException("réponse trop longue");
        }
        return out.toByteArray();
    }

    /** Reads a small text file; null when it is missing or unreadable. */
    static String readSmall(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return new String(readAll(in, 65536), StandardCharsets.UTF_8);
        } catch (IOException error) {
            return null;
        }
    }

    private synchronized void log(String line) {
        try {
            if (logFile.length() > 256 * 1024) logFile.renameTo(new File(logFile.getPath() + ".1"));
            try (Writer out = new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8)) {
                out.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " " + line + "\n");
            }
        } catch (IOException ignored) {}
    }
}
