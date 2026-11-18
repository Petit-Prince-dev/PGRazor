package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Properties;
import org.json.JSONObject;

/**
 * Shiny Scanner, Java half: the settings and the latest shiny found. The native
 * scanner (shiny_scan.cpp) does the checking and reports each one as a "shiny-scan" event; this class only
 * decides whether it may run and shows what it found. Nothing is thrown unless the player presses Catch it.
 */
public final class ShinyScanner {
    private ShinyScanner() {}
    /** A found shiny is shown for this long, or until the player moves this far from it. */
    static final long FOUND_TTL_MS = 15 * 60 * 1000L;
    static final double FOUND_FAR_METERS = 300;

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ShinyScanPreferences prefs = new ShinyScanPreferences();
    private static long session = System.currentTimeMillis() & 0x3fffffffL;
    private static String nativeStatus = "";
    private static int checked, shinies;
    private static int foundSpecies, foundCp; private static int[] foundIv = new int[3];
    private static double foundLat, foundLon; private static long foundAt;
    private static volatile Context app;
    // The found shiny's place on the screen, and a popup waiting to be shown for it.
    private static volatile float targetX, targetY; private static volatile long targetAt;
    private static String[] popup;       // {title, message, "found" or "result"}
    private static String foundText = ""; // the found popup's message, to show it again from the badge
    private static long catchSeq;
    private static boolean catching, gone;
    private static volatile JSONObject names;

    public static void read(Properties p) { synchronized (LOCK) { prefs.read(p); session++; nativeStatus = ""; foundAt = 0; } }
    public static void write(Properties p) { synchronized (LOCK) { prefs.write(p); } }
    public static boolean enabled() { synchronized (LOCK) { return prefs.enabled; } }

    /** Why the scanner is waiting although it is on; null when nothing else is in its way. */
    private static String blocked() {
        if (Control.vgpEnabled()) return "En pause pendant le VGP+";
        if (ShundoSniper.active()) return "En pause pendant le Shundo Sniper";
        if (AutoRaid.running()) return "En pause pendant l'Auto-Raid";
        return null;
    }
    /** Read by the native transport (string slot 23). */
    public static String config() {
        // Asks other features first, never while holding LOCK.
        boolean allowed = blocked() == null, count = Control.encounterCounter();
        int encounters = EncounterCounter.total();
        synchronized (LOCK) { return prefs.config(session, allowed, encounters, count, catchSeq); }
    }

    public static void setEnabled(boolean on) {
        synchronized (LOCK) {
            if (prefs.enabled == on) return;
            prefs.enabled = on; session++; nativeStatus = ""; checked = shinies = 0;
            if (!on) { foundAt = 0; popup = null; catching = gone = false; }
        }
        Control.saveSettings();
    }

    static void setTarget(boolean visible, double x, double y) {
        if (!visible || !Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1) { targetAt = 0; return; }
        targetX = (float) x; targetY = (float) y; targetAt = SystemClock.elapsedRealtime();
    }
    /** Whether the shiny symbol should be drawn: the scanner is on and its place was reported in the last second. */
    static boolean targetVisible() {
        long at = targetAt;
        return at != 0 && SystemClock.elapsedRealtime() - at < 1000 && enabled();
    }
    static float targetX() { return targetX; }
    static float targetY() { return targetY; }
    /** A popup that has not been shown yet as {title, message, "found" or "result"}; null when there is none. Shown once. */
    static String[] takePopup() { synchronized (LOCK) { String[] next = popup; popup = null; return next; } }
    /** Shows the found shiny's popup again (from the map badge). */
    static void showFound() { synchronized (LOCK) { if (foundAt != 0) popup = new String[]{"✨ Shiny trouvé", foundText, "found"}; } }
    /** The player asked to catch the found shiny: the native scanner feeds a berry and throws. */
    static void requestCatch() {
        String blocked = blocked();
        synchronized (LOCK) {
            if (foundAt == 0 || catching) return;
            if (blocked != null) { popup = new String[]{"Capture impossible pour l'instant", blocked + ". Arrête-le, puis touche le badge shiny.", "result"}; return; }
            catchSeq++; catching = true;
        }
    }
    static boolean catching() { synchronized (LOCK) { return catching; } }

    /** One "shiny-scan" event from the native scanner. */
    public static void publish(String detail) {
        boolean shiny = false, notify = false, stop = false; int species = 0; String notice = "";
        try {
            JSONObject o = new JSONObject(detail);
            String phase = o.optString("phase");
            // Every encounter the game made counts, and so does a check that was sent but never answered.
            if ("encounter".equals(phase) || o.optBoolean("sent")) EncounterCounter.count(o.optString("id"));
            synchronized (LOCK) {
                if (o.optLong("session") != session) return;
                if ("gone".equals(phase)) { gone = true; return; }
                // Caught or fled through the game's own encounter: nothing left to show.
                if ("closed".equals(phase)) { foundAt = 0; catching = gone = false; return; }
                if ("catch".equals(phase)) {
                    String result = o.optString("result"), said = NativeText.fr(o.optString("message"));
                    String who = "Shiny " + name(foundSpecies);
                    if ("throwing".equals(result)) { catching = true; return; }
                    if ("opened".equals(result)) { catching = false; return; }  // the game takes it from here
                    catching = false;
                    if ("caught".equals(result)) { foundAt = 0; popup = new String[]{"✨ Shiny capturé", who + ". " + said + ".", "result"}; }
                    else if ("fled".equals(result)) { foundAt = 0; popup = new String[]{"Le shiny s'est enfui", who + ". " + said + ".", "result"}; }
                    else popup = new String[]{"La capture n'a pas abouti", who + ". " + said + ". Touche le badge shiny pour réessayer.", "result"};
                    return;
                }
                if ("status".equals(phase) || "error".equals(phase)) { nativeStatus = NativeText.fr(o.optString("message")); return; }
                if (!"checked".equals(phase) && !"failed".equals(phase)) return;
                if (!"checked".equals(phase)) return;
                checked++;
                if (o.optBoolean("shiny")) {
                    species = o.optInt("species"); double lat = o.optDouble("lat"), lon = o.optDouble("lon");
                    if (species > 0 && Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180) {
                        shiny = true; shinies++; notify = prefs.notify; stop = prefs.stopWalking; catching = gone = false;
                        foundSpecies = species; foundCp = o.optInt("cp"); foundLat = lat; foundLon = lon; foundAt = SystemClock.elapsedRealtime();
                        org.json.JSONArray iv = o.optJSONArray("iv");
                        for (int i = 0; i < 3; i++) foundIv[i] = iv == null ? -1 : iv.optInt(i, -1);
                        foundText = "Shiny " + name(species) + (foundCp > 0 ? " · PC " + foundCp : "")
                            + (foundIv[0] >= 0 && foundIv[1] >= 0 && foundIv[2] >= 0 ? " · IV " + foundIv[0] + "/" + foundIv[1] + "/" + foundIv[2] : "")
                            + "\n\n" + (stop ? "Marche arrêtée. " : "") + "Marqué d'un ✨ sur la carte."
                            + "\nCapturer : ouvre la rencontre" + (prefs.hold ? ", ou lance pour toi s'il a quitté la carte." : ".")
                            + "\nIgnorer : le laisse partir.";
                        notice = foundText.substring(0, foundText.indexOf('\n'));
                        popup = new String[]{"✨ Shiny trouvé", foundText, "found"};
                    }
                }
            }
        } catch (Exception invalid) {
            Control.event("shiny-scan-diagnostic", "Invalid scanner event: " + invalid.getClass().getSimpleName());
            return;
        }
        if (shiny) {
            final String text = "Shiny " + name(species) + " à proximité !";
            Control.event("shiny-scan-found", text);
            if (notify) notifyPhone(notice);
            if (stop) MAIN.post(ShinyScanner::stopWalking);
        }
    }

    /** Ends the current walk, autowalk route or in-game Route so the player stays next to the shiny. */
    private static void stopWalking() {
        try {
            if (Control.engine.walking) Control.event("walk-cancelled", "Shiny found");
            Control.engine.stop();
            if (Control.gameRoutesRunning()) Control.stopGameRoutes();
            Control.saveState();
        } catch (RuntimeException error) { Control.event("shiny-scan-diagnostic", "Could not stop walking: " + error.getClass().getSimpleName()); }
    }
    private static boolean mayNotify(Context context) {
        return android.os.Build.VERSION.SDK_INT < 33
            || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    /** A phone notification with sound for a found shiny; does nothing without the notification permission. */
    private static void notifyPhone(String text) {
        Context context = app;
        if (context == null || !mayNotify(context)) return;
        try {
            android.app.NotificationManager manager = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new android.app.NotificationChannel("pgrazor-shiny", "PGRazor : shiny trouvé", android.app.NotificationManager.IMPORTANCE_HIGH));
            manager.notify(7302, new android.app.Notification.Builder(context, "pgrazor-shiny").setSmallIcon(android.R.drawable.star_on)
                .setContentTitle("✨ Shiny trouvé").setContentText(text).setAutoCancel(true).build());
        } catch (RuntimeException error) { Control.event("shiny-scan-notification-error", error.toString()); }
    }
    private static void askToNotify(Activity activity) {
        if (!mayNotify(activity)) activity.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7302);
    }

    static String name(int species) {
        JSONObject table = names;
        String name = table == null ? "" : table.optString(String.valueOf(species), "");
        return name.isEmpty() ? "#" + species : name;
    }
    /** Remembers the app context for the found toast and loads the species names once, off the UI thread. */
    static void prepare(Context context) {
        if (context == null || app != null) return;
        final Context application = context.getApplicationContext();
        app = application;
        new Thread(() -> names = PokemonSpeciesDialog.readNames(application), "pgrazor-shiny-names").start();
    }

    private static double meters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6371000 * Math.asin(Math.min(1, Math.sqrt(a)));
    }
    /** The latest shiny for the map badge, e.g. "✨ Shiny Ferroseed · CP 512 · 40 m"; empty when there is none to show. */
    static String foundLabel() {
        int species, cp; double lat, lon; long at; int[] iv; boolean held, left, throwing;
        synchronized (LOCK) {
            if (!prefs.enabled || foundAt == 0) return "";
            species = foundSpecies; cp = foundCp; lat = foundLat; lon = foundLon; at = foundAt; iv = foundIv.clone();
            held = prefs.hold; left = gone; throwing = catching;
        }
        double here = Control.currentLatitude(), hereLon = Control.currentLongitude();
        double away = Double.isFinite(here) && Double.isFinite(hereLon) ? meters(here, hereLon, lat, lon) : Double.NaN;
        long age = SystemClock.elapsedRealtime() - at;
        // A held shiny stays until it is caught or dismissed; otherwise the badge goes after a while or once far away.
        if (!held && !throwing && (age < 0 || age > FOUND_TTL_MS || away > FOUND_FAR_METERS)) { dismissFound(); return ""; }
        String label = "✨ Shiny " + name(species) + (cp > 0 ? " · PC " + cp : "");
        if (iv[0] >= 0 && iv[1] >= 0 && iv[2] >= 0) label += " · " + iv[0] + "/" + iv[1] + "/" + iv[2];
        if (throwing) label += " · capture…";
        else if (left) label += " · a quitté la carte";
        else if (!Double.isNaN(away)) label += " · " + Math.round(away) + " m";
        return label;
    }
    /** Lets the found shiny go: the badge goes and a new session tells the native scanner to stop holding it. */
    static void dismissFound() { synchronized (LOCK) { if (foundAt == 0) return; foundAt = 0; catching = gone = false; session++; } }

    /** One line for the dialog and the settings summary. */
    public static String status() {
        String blocked = blocked();
        int encounters = EncounterCounter.total();
        synchronized (LOCK) {
            if (!prefs.enabled) return "Off";
            String counts = checked + " vérifiés, " + shinies + (shinies == 1 ? " shiny" : " shiny") + " · "
                + encounters + (prefs.dailyLimit > 0 ? "/" + prefs.dailyLimit : "") + " en 24 h";
            if (blocked != null) return blocked + " · " + counts;
            return (nativeStatus.isEmpty() ? "En attente de la carte" : nativeStatus) + " · " + counts;
        }
    }

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        prepare(activity); EncounterCounter.prepare(activity);
        Context ui = MapTheme.wrap(activity);
        LinearLayout body = new LinearLayout(ui); body.setOrientation(LinearLayout.VERTICAL);
        int pad = MapTheme.dp(ui, 16); body.setPadding(pad, pad, pad, pad);

        TextView description = new TextView(ui);
        description.setText("Vérifie si les Pokémon sauvages à moins de 80 m sont shiny. Chaque vérification utilise une rencontre.");
        body.addView(description);
        final TextView status = new TextView(ui); body.addView(status);
        final CheckBox on = new CheckBox(ui); on.setText("Scanner les shiny"); body.addView(on);
        final CheckBox notifyBox = new CheckBox(ui); notifyBox.setText("Notification sur le téléphone"); body.addView(notifyBox);
        final CheckBox stopBox = new CheckBox(ui); stopBox.setText("Arrêter la marche quand trouvé"); body.addView(stopBox);
        final CheckBox holdBox = new CheckBox(ui); holdBox.setText("Le garder (pause des vérifications jusqu'à capture ou abandon)"); body.addView(holdBox);
        final Button speed = button(body), species = button(body), limit = button(body);
        TextView note = new TextView(ui);
        note.setText("Se met en pause pendant le VGP+, le Shundo Sniper ou l'Auto-Raid.");
        body.addView(note);

        final boolean[] refreshing = {false};
        final Runnable update = () -> {
            refreshing[0] = true;
            status.setText(status());
            synchronized (LOCK) {
                on.setChecked(prefs.enabled); notifyBox.setChecked(prefs.notify); stopBox.setChecked(prefs.stopWalking); holdBox.setChecked(prefs.hold);
                speed.setText("Vitesse : " + ShinyScanPreferences.speedSummary(prefs.speed));
                species.setText("Espèces : " + speciesSummary(prefs.species));
                limit.setText("Limite de rencontres : " + ShinyScanPreferences.limitSummary(prefs.dailyLimit));
            }
            refreshing[0] = false;
        };
        on.setOnCheckedChangeListener((v, checked) -> { if (!refreshing[0]) { setEnabled(checked); if (checked && notifyBox.isChecked()) askToNotify(activity); update.run(); } });
        notifyBox.setOnCheckedChangeListener((v, checked) -> {
            if (refreshing[0]) return;
            synchronized (LOCK) { prefs.notify = checked; }
            Control.saveSettings(); if (checked) askToNotify(activity); update.run();
        });
        stopBox.setOnCheckedChangeListener((v, checked) -> { if (refreshing[0]) return; synchronized (LOCK) { prefs.stopWalking = checked; } Control.saveSettings(); update.run(); });
        holdBox.setOnCheckedChangeListener((v, checked) -> { if (refreshing[0]) return; synchronized (LOCK) { prefs.hold = checked; } Control.saveSettings(); update.run(); });
        speed.setOnClickListener(v -> {
            String[] labels = new String[ShinyScanPreferences.SPEED_LABELS.length];
            String[] hints = {"1 vérification toutes les 2 s", "Environ 1 par seconde", "Jusqu'à 3 par seconde"};
            for (int i = 0; i < labels.length; i++) labels[i] = ShinyScanPreferences.SPEED_LABELS[i] + "\n" + hints[i];
            choose(activity, "Vitesse de scan", labels, index -> { synchronized (LOCK) { prefs.speed = index; } Control.saveSettings(); update.run(); });
        });
        limit.setOnClickListener(v -> {
            final int[] choices = ShinyScanPreferences.LIMIT_CHOICES;
            String[] labels = new String[choices.length];
            for (int i = 0; i < choices.length; i++)
                labels[i] = choices[i] == 0 ? "Sans limite" : choices[i] + " en 24 heures";
            choose(activity, "Limite de rencontres", labels, index -> { synchronized (LOCK) { prefs.dailyLimit = choices[index]; } Control.saveSettings(); update.run(); });
        });
        species.setOnClickListener(v -> PokemonSpeciesDialog.show(activity, "Espèces à vérifier", "species", AutoRaidPreferences.MAX_BOSSES,
            () -> { synchronized (LOCK) { return prefs.species; } },
            value -> { String list = AutoRaidPreferences.normalizeBosses(value); synchronized (LOCK) { prefs.species = list; } Control.saveSettings(); },
            update, "Toutes les espèces (aucune sélection)", null));

        update.run();
        ScrollView scroll = new ScrollView(ui); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(ui).setTitle("Shiny Scanner").setView(scroll)
            .setPositiveButton("OK", null).create();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable refresh = new Runnable() {
            public void run() { if (dialog.isShowing()) { status.setText(status()); handler.postDelayed(this, 1000); } }
        };
        DialogLifecycle.bind(activity, dialog, () -> handler.removeCallbacks(refresh));
        dialog.setOnShowListener(d -> {
            MapTheme.style(body); MapTheme.styleButtons(dialog);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(MapTheme.surface()));
            handler.post(refresh);
        });
        dialog.show();
    }

    private interface Choice { void pick(int index); }
    private static void choose(Activity a, String title, String[] labels, Choice choice) {
        Context ui = MapTheme.wrap(a);
        AlertDialog d = new AlertDialog.Builder(ui).setTitle(title).setNegativeButton("Annuler", null).create();
        ChoiceList.attach(d, labels, choice::pick);
        DialogLifecycle.bind(a, d, null);
        d.setOnShowListener(x -> { MapTheme.styleButtons(d);
            d.getWindow().setBackgroundDrawable(new ColorDrawable(MapTheme.surface()));
        });
        d.show();
    }
    private static String speciesSummary(String list) {
        if (list == null || list.isEmpty()) return "Toutes les espèces";
        String[] ids = list.split(",");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(3, ids.length); i++) {
            if (i > 0) out.append(", ");
            try { out.append(name(Integer.parseInt(ids[i]))); } catch (NumberFormatException e) { out.append('#').append(ids[i]); }
        }
        if (ids.length > 3) out.append(" +").append(ids.length - 3).append(" de plus");
        return out.toString();
    }
    private static Button button(LinearLayout body) {
        Button b = new Button(body.getContext()); b.setAllCaps(false); body.addView(b); return b;
    }
}
