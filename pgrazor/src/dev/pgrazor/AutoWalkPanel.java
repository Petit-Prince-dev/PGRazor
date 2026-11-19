package dev.pgrazor;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The map's "Autowalk" tab: the walk in progress (with Arrêter), then a walk over the PokéStops and
 * gyms the game has loaded around the player, nearest first ({@link AutoWalkPlanner}).
 *
 * <p>While the tab is shown it renews the nearby snapshot ({@link Control#setAutoWalkPlanning}) and
 * refreshes every second; it stops both as soon as the tab is hidden or the map closes. Saved and
 * imported GPX walks live in the Lieux tab.
 */
final class AutoWalkPanel {
    private static final int LIMIT = AutoWalkPlanner.MAX_STOPS;

    private final Activity activity;
    private final Context ui;
    private final Runnable done;
    private final ScrollView scroll;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::poll;
    private final LinearLayout current;
    private final TextView currentTitle, currentDetail, nearbyStatus, countValue;
    private final Button stop, minus, plus, start;
    private final Switch loop;
    /** Stops asked for; 0 means every loaded stop. */
    private int requested;
    private int available;
    private boolean resumed, closed;

    AutoWalkPanel(Activity activity, Context ui, Runnable done) {
        this.activity = activity; this.ui = ui; this.done = done;
        LinearLayout[] column = new LinearLayout[1];
        scroll = HubUi.page(ui, column);
        LinearLayout body = column[0];

        // ----- The walk in progress
        HubUi.section(body, "En cours", null, null, null);
        current = HubUi.card(body);
        current.setPadding(dp(14), dp(12), dp(12), dp(12));
        LinearLayout currentLine = new LinearLayout(ui); currentLine.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(ui); texts.setOrientation(LinearLayout.VERTICAL);
        currentTitle = SettingsUi.text(ui, "", 14, SettingsUi.ink()); currentTitle.setTypeface(SettingsUi.medium());
        currentDetail = SettingsUi.text(ui, "", 12, SettingsUi.muted());
        texts.addView(currentTitle); texts.addView(currentDetail);
        currentLine.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        stop = HubUi.button(ui, "Arrêter", false);
        currentLine.addView(stop, new LinearLayout.LayoutParams(dp(96), dp(40)));
        current.addView(currentLine);
        stop.setOnClickListener(v -> stop());

        // ----- Nearby PokéStops and gyms
        HubUi.section(body, "Autour de toi", "Visite les PokéStops et arènes chargés par le jeu, du plus proche au plus loin.", null, null);
        LinearLayout card = HubUi.card(body);
        card.setPadding(dp(14), dp(10), dp(12), dp(14));
        nearbyStatus = SettingsUi.text(ui, "", 13, SettingsUi.muted()); nearbyStatus.setPadding(0, dp(2), 0, dp(8));
        card.addView(nearbyStatus);

        LinearLayout countRow = new LinearLayout(ui); countRow.setGravity(Gravity.CENTER_VERTICAL);
        countRow.addView(SettingsUi.text(ui, "Arrêts", 14, SettingsUi.ink()), new LinearLayout.LayoutParams(0, -2, 1));
        minus = stepper("−"); plus = stepper("+");
        countValue = SettingsUi.text(ui, "", 14, SettingsUi.accent()); countValue.setTypeface(SettingsUi.medium()); countValue.setGravity(Gravity.CENTER);
        countRow.addView(minus, new LinearLayout.LayoutParams(dp(40), dp(36)));
        countRow.addView(countValue, new LinearLayout.LayoutParams(dp(78), -2));
        countRow.addView(plus, new LinearLayout.LayoutParams(dp(40), dp(36)));
        card.addView(countRow, new LinearLayout.LayoutParams(-1, dp(44)));
        minus.setOnClickListener(v -> step(-1)); plus.setOnClickListener(v -> step(1));

        LinearLayout loopRow = new LinearLayout(ui); loopRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout loopTexts = new LinearLayout(ui); loopTexts.setOrientation(LinearLayout.VERTICAL);
        loopTexts.addView(SettingsUi.text(ui, "En boucle", 14, SettingsUi.ink()));
        loopTexts.addView(SettingsUi.text(ui, "Recommence au premier arrêt après le dernier", 12, SettingsUi.muted()));
        loopRow.addView(loopTexts, new LinearLayout.LayoutParams(0, -2, 1));
        loop = new Switch(ui); loop.setThumbTintList(SettingsUi.switchThumb()); loop.setTrackTintList(SettingsUi.switchTrack());
        loopRow.addView(loop);
        loopRow.setOnClickListener(v -> loop.toggle());
        card.addView(loopRow, new LinearLayout.LayoutParams(-1, dp(56)));

        start = HubUi.button(ui, "Démarrer l'autowalk", true); start.setTextSize(14);
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(-1, dp(44)); startParams.topMargin = dp(8);
        card.addView(start, startParams);
        start.setOnClickListener(v -> start());

        TextView tip = SettingsUi.text(ui, "Pour suivre un trajet enregistré, importé ou prédéfini, ouvre l'onglet Lieux.", 12, SettingsUi.faint());
        tip.setPadding(dp(4), dp(12), dp(4), 0);
        body.addView(tip);
        update();
    }

    View view() { return scroll; }

    /** The tab is shown: renew the nearby snapshot and refresh every second. */
    void resume() { if (closed || resumed) return; resumed = true; poll(); }
    /** The tab is hidden: stop asking the game for nearby places. */
    void pause() { resumed = false; main.removeCallbacks(poll); Control.setAutoWalkPlanning(false); }
    void close() { if (closed) return; closed = true; pause(); }

    private void poll() {
        if (closed || !resumed) return;
        Control.setAutoWalkPlanning(true);
        update();
        main.removeCallbacks(poll); main.postDelayed(poll, 1000);
    }

    private Button stepper(String sign) {
        Button b = HubUi.button(ui, sign, false); b.setTextSize(17); b.setPadding(0, 0, 0, 0);
        return b;
    }

    private void step(int delta) {
        if (available <= 0) return;
        int shown = requested == 0 ? available : requested;
        int next = shown + delta;
        requested = next >= available ? 0 : Math.max(1, next);
        update();
    }

    private void update() {
        // The walk in progress.
        MotionEngine.RouteProgress progress = Control.engine.routeProgress();
        boolean moving = progress.active || Control.engine.walking;
        current.setVisibility(View.VISIBLE);
        if (Control.engine.speedKmh <= 0) {
            set(currentTitle, moving ? "En pause" : "Aucune marche");
            set(currentDetail, "Tu es à l'arrêt : choisis une vitesse dans le menu R pour repartir.");
        } else if (progress.active) {
            set(currentTitle, String.format(Locale.FRANCE, "Arrêt %d sur %d", Math.min(progress.total, progress.visited + 1), progress.total));
            set(currentDetail, SettingsUi.speedLabel(Control.engine.speedKmh) + " · reste " + RouteStart.distance(progress.remainingMeters));
        } else if (Control.engine.walking) {
            set(currentTitle, "Marche en cours");
            set(currentDetail, SettingsUi.speedLabel(Control.engine.speedKmh));
        } else {
            set(currentTitle, "Aucune marche");
            set(currentDetail, "Vitesse : " + SettingsUi.speedLabel(Control.engine.speedKmh));
        }
        stop.setEnabled(moving); stop.setAlpha(moving ? 1f : .45f);

        // Nearby places.
        String snapshot = Control.mapPoiSnapshot();
        double[][] points = snapshot.isEmpty() ? null : parse(snapshot);
        available = points == null ? 0 : Math.min(AutoWalkPlanner.filter(points).length, LIMIT);
        if (requested > available) requested = 0;
        if (snapshot.isEmpty()) set(nearbyStatus, resumed ? "Recherche des lieux autour de toi…" : "");
        else if (points == null) set(nearbyStatus, "Les lieux proches sont indisponibles pour l'instant.");
        else if (available == 0) set(nearbyStatus, "Aucun PokéStop ni arène chargé ici. Déplace-toi un peu en jeu.");
        else set(nearbyStatus, available + (available == 1 ? " lieu trouvé autour de toi." : " lieux trouvés autour de toi."));
        set(countValue, available == 0 ? "—" : requested == 0 ? "Tous (" + available + ")" : String.valueOf(requested));
        boolean ready = available > 0;
        minus.setEnabled(ready && (requested == 0 ? available > 1 : requested > 1)); plus.setEnabled(ready && requested != 0);
        minus.setAlpha(minus.isEnabled() ? 1f : .4f); plus.setAlpha(plus.isEnabled() ? 1f : .4f);
        start.setEnabled(ready); start.setAlpha(ready ? 1f : .5f);
    }

    private void start() {
        if (closed) return;
        if (!Control.canEnable()) { toast("L'adaptateur de position n'est pas prêt."); return; }
        String snapshot = Control.mapPoiSnapshot();
        double[][] points = snapshot.isEmpty() ? null : parse(snapshot);
        if (points == null) { toast("Les lieux proches sont indisponibles pour l'instant."); return; }
        int maximum = Math.min(AutoWalkPlanner.filter(points).length, LIMIT);
        if (maximum == 0) { toast("Aucun lieu proche n'est encore chargé. Déplace-toi en jeu, puis réessaie."); return; }
        int count = requested == 0 ? maximum : Math.min(requested, maximum);
        AutoWalkPlanner.Plan plan;
        try { plan = AutoWalkPlanner.plan(Control.engine.latitude, Control.engine.longitude, points, count); }
        catch (IllegalArgumentException e) { toast(e.getMessage()); return; }
        if (plan.empty()) { toast("Aucun lieu proche n'est encore chargé. Déplace-toi en jeu, puis réessaie."); return; }
        boolean looping = loop.isChecked();
        try { Control.startAutoWalk(plan.route, looping); }
        catch (IllegalArgumentException e) { toast(e.getMessage()); return; }
        Control.event("autowalk-started", String.format(Locale.US,
            "requested=%d selected=%d available=%d loop=%s", plan.requested, plan.selected(), plan.available, looping));
        toast("Autowalk sur " + plan.selected() + (plan.selected() == 1 ? " arrêt" : " arrêts") + (looping ? ", en boucle." : "."));
        done.run();
    }

    private void stop() {
        if (closed) return;
        if (!Control.engine.walking && !Control.engine.routeProgress().active) { update(); return; }
        Control.engine.stop();
        Control.saveState();
        Control.event("autowalk-stopped", "User stopped the walk from the map");
        toast("Marche arrêtée.");
        update();
    }

    private static void set(TextView view, String text) { if (!text.contentEquals(view.getText())) view.setText(text); }
    private void toast(String message) { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show(); }
    private int dp(float value) { return HubUi.dp(ui, value); }

    /** Null means a parse error or an explicit error snapshot; an empty array means no points yet. */
    private static double[][] parse(String snapshot) {
        try {
            JSONObject root = new JSONObject(snapshot);
            if (root.has("error")) return null;
            JSONArray array = root.optJSONArray("points");
            if (array == null) return new double[0][];
            List<double[]> points = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject point = array.optJSONObject(i);
                if (point == null) continue;
                points.add(new double[]{point.optDouble("lat", Double.NaN), point.optDouble("lon", Double.NaN)});
            }
            return points.toArray(new double[points.size()][]);
        } catch (JSONException invalid) {
            return null;
        }
    }
}
