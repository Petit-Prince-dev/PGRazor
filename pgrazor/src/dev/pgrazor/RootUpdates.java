package dev.pgrazor;

import android.content.Context;
import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import org.json.JSONObject;

/**
 * Game side of PGRazor Root's automatic updates. {@link RootDaemon} (root, outside the game) does the
 * work; the game only drops request/config files in its own files folder and shows the status JSON the
 * helper writes back.
 */
final class RootUpdates {
    private RootUpdates() {}

    private static File folder(Context context) { return context.getApplicationContext().getFilesDir(); }
    /** When this game process started: only updates installed after it are announced in game. */
    private static final long STARTED = System.currentTimeMillis();
    private static long announced;

    /** At each game start: ask the helper to check now when automatic updates are on. */
    static void onGameStart(Context context) {
        if (!PgAssets.rooted() || !automatic(context)) return;
        requestCheck(context);
    }

    static void requestCheck(Context context) {
        ScreenPower.write(folder(context), RootDaemon.UPDATE_REQUEST, Long.toString(System.currentTimeMillis()));
    }

    static boolean automatic(Context context) {
        String config = RootDaemon.readSmall(new File(folder(context), RootDaemon.UPDATE_CONFIG));
        return config == null || !config.contains("auto=0");
    }

    static void setAutomatic(Context context, boolean on) {
        ScreenPower.write(folder(context), RootDaemon.UPDATE_CONFIG, on ? "auto=1" : "auto=0");
        Control.event("root-update", "automatic " + (on ? "ON" : "OFF"));
    }

    /**
     * Once per installed update: the message to show in game ("relance le jeu…"), or null. The helper
     * installs while the game runs; the new code is used from the next game start.
     */
    static String announcement(Context context) {
        String raw = RootDaemon.readSmall(new File(folder(context), RootDaemon.UPDATE_STATUS));
        if (raw == null) return null;
        try {
            JSONObject json = new JSONObject(raw);
            long time = json.optLong("time");
            if (!"updated".equals(json.optString("state")) || time <= STARTED || time <= announced) return null;
            announced = time;
            return "PGRazor " + json.optString("latest") + " est installé. Relance le jeu pour l'utiliser.";
        } catch (Exception error) {
            return null;
        }
    }

    /** One readable line for the settings screen. */
    static String status(Context context) {
        String raw = RootDaemon.readSmall(new File(folder(context), RootDaemon.UPDATE_STATUS));
        String running = "Version en cours : " + BuildVersion.NAME + ".\n";
        if (raw == null) return running + "Le service root n'a pas encore répondu (il démarre quelques minutes après le démarrage du téléphone).";
        try {
            JSONObject json = new JSONObject(raw);
            String message = json.optString("message");
            long time = json.optLong("time");
            String when = time > 0 ? " (" + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(time)) + ")" : "";
            return running + message + when;
        } catch (Exception error) {
            return running + "État de mise à jour illisible.";
        }
    }
}
