package dev.pgrazor;

import android.os.Build;
import android.os.IBinder;
import android.system.Os;
import android.system.StructStat;
import java.io.*;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * PGRazor Root helper. It never runs inside the game: the module's service.sh starts it as root at boot,
 *
 *   CLASSPATH=$MODDIR/pgrazor/classes.dex app_process /system/bin dev.pgrazor.RootDaemon $MODDIR
 *
 * and restarts it if it exits. It has two jobs:
 *
 * Screen. The game writes "off &lt;pid&gt;" or "on" to files/pgrazor-screen ({@link ScreenPower}). While the
 * request is "off" and that game process is alive, the display panel is switched off the way
 * screen-mirroring tools do it (SurfaceControl / DisplayControl, or DisplayManager.requestDisplayPower
 * on Android 15+): Android still considers the screen on, so nothing sleeps and the game keeps running.
 * "on", the game process exiting or the phone going to sleep and waking again bring it back.
 *
 * Updates. When pgrazor/update-repo names a GitHub repository ("owner/name"), its latest release is
 * checked a few minutes after boot, every six hours, at each game start (the game drops
 * files/pgrazor-update-request) and on demand from the settings. A newer release whose game target
 * matches the installed game is downloaded, checked against its SHA256SUMS.txt and hot-swapped into the
 * live module: the game loads the new DEX and payload at its next cold start, without reinstalling the
 * module. Only a changed Zygisk loader needs a reboot. files/pgrazor-update-config "auto=0" turns the
 * automatic checks off (manual checks still run). The outcome is written to files/pgrazor-update-status
 * (JSON) for the settings screen.
 */
public final class RootDaemon {
    static final String GAME = "com.nianticlabs.pokemongo";
    static final String UPDATE_REQUEST = "pgrazor-update-request", UPDATE_CONFIG = "pgrazor-update-config", UPDATE_STATUS = "pgrazor-update-status";
    static final String MODULE_ASSET = "PGRazor-Root-module.zip", SUMS_ASSET = "SHA256SUMS.txt", INFO_ASSET = "root-build-info.json";
    private static final long FIRST_CHECK_MS = 3 * 60_000L, CHECK_EVERY_MS = 6 * 3600_000L;
    /** Module files an update may replace; anything else in the release ZIP is ignored. */
    private static final String[] UPDATABLE = {"pgrazor/classes.dex", "pgrazor/payload.zip", "module.prop", "service.sh", "customize.sh",
        "pgrazor/update-repo", "pgrazor/toggle-v1", "zygisk/arm64-v8a.so", "zygisk/x86_64.so"};

    private final File module, logFile, offMarker;
    private final File gameFiles = new File("/data/data/" + GAME + "/files");
    private volatile boolean updating, restartAfterUpdate;
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
        long started = System.currentTimeMillis(), nextCheck = started + FIRST_CHECK_MS;
        while (true) {
            try {
                pollScreen();
                long now = System.currentTimeMillis();
                File request = new File(gameFiles, UPDATE_REQUEST);
                boolean manual = request.exists();
                if (manual) request.delete();
                if (!updating && (manual || (now >= nextCheck && autoUpdates()))) {
                    nextCheck = now + CHECK_EVERY_MS;
                    startUpdate(manual);
                }
                // A finished update restarts the helper on the new DEX once the panel is back on.
                if (restartAfterUpdate && !updating && !panelOff) { log("restart on the updated module"); System.exit(0); }
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

    // ----- Updates -----

    private boolean autoUpdates() {
        String config = readSmall(new File(gameFiles, UPDATE_CONFIG));
        return config == null || !config.contains("auto=0");
    }

    private void startUpdate(boolean manual) {
        updating = true;
        Thread worker = new Thread(() -> {
            try { update(manual); }
            catch (Throwable error) { log("update: " + error); status("error", "Échec de la vérification : " + error.getMessage(), null); }
            finally { updating = false; }
        }, "pgrazor-update");
        worker.setDaemon(true);
        worker.start();
    }

    private void update(boolean manual) throws Exception {
        String repo = readSmall(new File(module, "pgrazor/update-repo"));
        repo = repo == null ? "" : repo.trim();
        if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            status("off", "Aucun dépôt GitHub de mise à jour n'est configuré dans ce module.", null);
            return;
        }
        String installed = installedVersion();
        status("checking", "Recherche d'une mise à jour sur " + repo + "…", null);
        JSONObject release = new JSONObject(text("https://api.github.com/repos/" + repo + "/releases/latest"));
        String tag = release.getString("tag_name");
        Map<String, String> assets = new HashMap<>();
        JSONArray list = release.optJSONArray("assets");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject asset = list.getJSONObject(i);
            assets.put(asset.getString("name"), asset.getString("browser_download_url"));
        }
        if (tag.equals(installed)) {
            status("ok", "PGRazor est à jour (" + installed + ").", tag);
            log("up to date " + tag + (manual ? " (manual)" : ""));
            return;
        }
        for (String name : new String[]{MODULE_ASSET, SUMS_ASSET, INFO_ASSET})
            if (!assets.containsKey(name)) throw new IOException("la release " + tag + " n'a pas " + name);

        JSONObject target = new JSONObject(text(assets.get(INFO_ASSET))).getJSONObject("target");
        long wanted = target.getLong("versionCode");
        long game = installedGameVersionCode();
        if (game > 0 && game != wanted) {
            status("waiting", "La version " + tag + " vise Pokémon GO " + target.optString("versionName") + " (" + wanted + "), mais le jeu installé est " + game + ". Mets le jeu à la bonne version.", tag);
            log("skip " + tag + ": target " + wanted + " installed game " + game);
            return;
        }
        String expected = null;
        for (String line : text(assets.get(SUMS_ASSET)).split("\n")) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length == 2 && parts[1].trim().equals(MODULE_ASSET)) expected = parts[0].toLowerCase(Locale.ROOT);
        }
        if (expected == null || !expected.matches("[0-9a-f]{64}")) throw new IOException("somme SHA-256 du module absente");

        status("checking", "Téléchargement de " + tag + "…", tag);
        File work = new File(module, "pgrazor/.update");
        deleteTree(work);
        if (!work.mkdirs()) throw new IOException("dossier temporaire impossible");
        File zip = new File(work, MODULE_ASSET);
        String actual = download(assets.get(MODULE_ASSET), zip);
        if (!actual.equals(expected)) throw new IOException("somme SHA-256 incorrecte pour " + MODULE_ASSET);

        boolean reboot = false;
        int replaced = 0;
        try (ZipFile archive = new ZipFile(zip)) {
            if (archive.getEntry("pgrazor/classes.dex") == null || archive.getEntry("pgrazor/payload.zip") == null || archive.getEntry("module.prop") == null)
                throw new IOException("module incomplet");
            Properties props = new Properties();
            try (InputStream in = archive.getInputStream(archive.getEntry("module.prop"))) { props.load(new InputStreamReader(in, StandardCharsets.UTF_8)); }
            if (!"pgrazor".equals(props.getProperty("id"))) throw new IOException("ce n'est pas le module pgrazor");
            // Stage every file first, then swap them in with renames.
            Map<File, File> staged = new LinkedHashMap<>();
            for (String name : UPDATABLE) {
                ZipEntry entry = archive.getEntry(name);
                if (entry == null || entry.isDirectory()) continue;
                File live = new File(module, name), temp = new File(work, name.replace('/', '_'));
                try (InputStream in = archive.getInputStream(entry); OutputStream out = new FileOutputStream(temp)) { copy(in, out); }
                if (live.isFile() && sha256(live).equals(sha256(temp))) { temp.delete(); continue; }
                if (name.startsWith("zygisk/")) reboot = true;
                Os.chmod(temp.getPath(), name.endsWith(".sh") ? 0755 : 0644);
                staged.put(temp, live);
            }
            for (Map.Entry<File, File> move : staged.entrySet()) {
                File live = move.getValue();
                if (!live.getParentFile().isDirectory() && !live.getParentFile().mkdirs()) throw new IOException("dossier " + live.getParent());
                Os.rename(move.getKey().getPath(), live.getPath());
                replaced++;
            }
        }
        deleteTree(work);
        String message = "Mise à jour installée : " + installed + " → " + tag + ". Relance le jeu pour l'utiliser."
            + (reboot ? " Redémarre le téléphone pour finir (le chargeur Zygisk a changé)." : "");
        status("updated", message, tag);
        log("updated " + installed + " -> " + tag + " files=" + replaced + " reboot=" + reboot);
        restartAfterUpdate = true;
    }

    private String installedVersion() {
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(new File(module, "module.prop"))) { props.load(new InputStreamReader(in, StandardCharsets.UTF_8)); }
        catch (IOException error) { return ""; }
        return props.getProperty("version", "").trim();
    }

    private static long installedGameVersionCode() {
        try {
            Process process = new ProcessBuilder("dumpsys", "package", GAME).redirectErrorStream(true).start();
            String output;
            try (InputStream in = process.getInputStream()) { output = new String(readAll(in, 4 << 20), StandardCharsets.UTF_8); }
            process.waitFor();
            Matcher match = Pattern.compile("versionCode=(\\d+)").matcher(output);
            return match.find() ? Long.parseLong(match.group(1)) : -1;
        } catch (Exception error) {
            return -1;
        }
    }

    /** Writes the JSON status into the game's files folder, owned and labelled like that folder so the game can read it. */
    private void status(String state, String message, String latest) {
        try {
            if (!gameFiles.isDirectory()) return;
            JSONObject json = new JSONObject().put("state", state).put("message", message).put("installed", installedVersion())
                .put("latest", latest == null ? "" : latest).put("time", System.currentTimeMillis());
            File temp = new File(gameFiles, UPDATE_STATUS + ".tmp"), target = new File(gameFiles, UPDATE_STATUS);
            try (OutputStream out = new FileOutputStream(temp)) { out.write(json.toString().getBytes(StandardCharsets.UTF_8)); }
            StructStat folder = Os.stat(gameFiles.getPath());
            Os.chown(temp.getPath(), folder.st_uid, folder.st_gid);
            Os.chmod(temp.getPath(), 0644);
            try { Os.setxattr(temp.getPath(), "security.selinux", Os.getxattr(gameFiles.getPath(), "security.selinux"), 0); }
            catch (Exception label) { log("status label: " + label); }
            Os.rename(temp.getPath(), target.getPath());
        } catch (Exception error) {
            log("status: " + error);
        }
    }

    // ----- Small I/O helpers -----

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "PGRazor-Root-updater");
        connection.setRequestProperty("Accept", url.startsWith("https://api.github.com/") ? "application/vnd.github+json" : "application/octet-stream");
        int code = connection.getResponseCode();
        if (code / 100 != 2) { connection.disconnect(); throw new IOException("HTTP " + code + " pour " + url); }
        return connection;
    }

    private static String text(String url) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream()) { return new String(readAll(in, 2 << 20), StandardCharsets.UTF_8); }
        finally { connection.disconnect(); }
    }

    /** Downloads to a file (at most 256 MB) and returns its SHA-256. */
    private static String download(String url, File file) throws Exception {
        HttpURLConnection connection = open(url);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[65536];
            long total = 0;
            for (int n; (n = in.read(buffer)) > 0; ) {
                total += n;
                if (total > (256L << 20)) throw new IOException("module trop gros");
                digest.update(buffer, 0, n);
                out.write(buffer, 0, n);
            }
        } finally { connection.disconnect(); }
        return hex(digest.digest());
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        for (int n; (n = in.read(buffer)) > 0; ) {
            out.write(buffer, 0, n);
            if (out.size() > limit) throw new IOException("réponse trop longue");
        }
        return out.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[65536];
        long total = 0;
        for (int n; (n = in.read(buffer)) > 0; ) {
            total += n;
            if (total > (256L << 20)) throw new IOException("fichier trop gros");
            out.write(buffer, 0, n);
        }
    }

    /** Reads a small text file; null when it is missing or unreadable. */
    static String readSmall(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return new String(readAll(in, 65536), StandardCharsets.UTF_8);
        } catch (IOException error) {
            return null;
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
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
