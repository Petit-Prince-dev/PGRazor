package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The map's "Lieux" tab: every place and walk in one list. Favourites (saved from the map), the
 * hotspots, the walks saved or imported as GPX, and the ready-made walks ({@link PresetRoutes}).
 *
 * <p>A row unfolds its actions in place: show it on the map, walk there, teleport there, or start
 * a walk. A long press deletes a favourite or a saved walk. Storage
 * is read and written off the main thread; the list re-reads each time the panel is shown.
 */
final class PlacesPanel {
    /** What the map dialog does for the panel. Each move returns false when it was refused. */
    interface Host {
        void showOnMap(double latitude, double longitude);
        /** Shows a walk on the map; {@code stored} when it already is one of Mes trajets. */
        void openRoute(GpxRoute route, boolean stored);
        boolean teleport(double latitude, double longitude);
        boolean walk(double latitude, double longitude);
        boolean walkRoute(double[][] stops, boolean loop);
        /** Closes the map once a move was applied. */
        void done();
    }

    private final Activity activity;
    private final Context ui;
    private final Host host;
    private final Runnable reopen;
    private final ScrollView scroll;
    private final LinearLayout body;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<AlertDialog> children = new ArrayList<>();
    private final View[] unfolded = {null};
    private boolean closed;
    private int generation;

    /** {@code reopen} brings the map back on this tab after the system file picker closed it. */
    PlacesPanel(Activity activity, Context ui, Host host, Runnable reopen) {
        this.activity = activity; this.ui = ui; this.host = host; this.reopen = reopen;
        LinearLayout[] column = new LinearLayout[1];
        scroll = HubUi.page(ui, column); body = column[0];
        render(null, null, true);
    }

    View view() { return scroll; }

    /** Re-reads favourites and saved walks, then redraws the list. */
    void reload() {
        if (closed) return;
        final int round = ++generation;
        io.execute(() -> {
            SavedPlaces.Snapshot places;
            try { places = new SavedPlaces(activity).read(); } catch (RuntimeException e) { places = null; }
            List<GpxStore.Entry> walks;
            try { walks = new GpxStore(activity).list(); } catch (IOException | RuntimeException e) { walks = null; }
            final SavedPlaces.Snapshot p = places; final List<GpxStore.Entry> w = walks;
            main.post(() -> { if (!closed && round == generation) render(p, w, false); });
        });
    }

    private void render(SavedPlaces.Snapshot places, List<GpxStore.Entry> walks, boolean loading) {
        int y = scroll.getScrollY();
        body.removeAllViews(); unfolded[0] = null;

        // ----- Favourites
        HubUi.section(body, "Mes favoris", places == null || places.entries.isEmpty() ? null
            : places.entries.size() + "/" + SavedPlaces.LIMIT + " · appui long pour supprimer", null, null);
        if (loading) HubUi.empty(body, MenuIcon.FAVORITES, "Chargement…", "Lecture de tes lieux enregistrés.");
        else if (places == null) HubUi.empty(body, MenuIcon.FAVORITES, "Favoris illisibles", "Impossible de lire les lieux enregistrés.");
        else if (places.entries.isEmpty())
            HubUi.empty(body, MenuIcon.FAVORITES, "Aucun favori", "Touche un point sur la carte, puis ☆ dans la bulle.");
        else {
            LinearLayout card = HubUi.card(body);
            for (SavedPlaces.Entry entry : places.entries) {
                String detail = String.format(Locale.US, "%s · %.5f, %.5f", HubUi.away(entry.latitude, entry.longitude), entry.latitude, entry.longitude);
                View line = HubUi.row(card, "★", entry.name, detail, placeActions(entry.latitude, entry.longitude, entry.name), null, unfolded);
                line.setOnLongClickListener(v -> { confirmDeletePlace(entry); return true; });
            }
            if (places.damaged) caption("Certains favoris sont illisibles ; les autres sont affichés.");
        }

        // ----- Hotspots
        HubUi.section(body, "Hotspots", "Lieux animés, avec leur fuseau horaire", null, null);
        LinearLayout spots = HubUi.card(body);
        for (Hotspots.Spot spot : Hotspots.ALL) {
            String detail = spot.city + ", " + spot.country + " · " + HubUi.away(spot.latitude, spot.longitude);
            HubUi.row(spots, spot.utc.replace("UTC ", "UTC "), spot.name, detail, placeActions(spot.latitude, spot.longitude, spot.name), null, unfolded);
        }

        // ----- Saved / imported walks
        HubUi.section(body, "Mes trajets", walks == null || walks.isEmpty() ? null : walks.size() + " enregistrés · appui long pour supprimer",
            "Importer un GPX", this::importGpx);
        if (loading) HubUi.empty(body, MenuIcon.AUTOWALK, "Chargement…", "Lecture de tes trajets enregistrés.");
        else if (walks == null) HubUi.empty(body, MenuIcon.AUTOWALK, "Trajets illisibles", "Impossible de lire les trajets enregistrés.");
        else if (walks.isEmpty())
            HubUi.empty(body, MenuIcon.AUTOWALK, "Aucun trajet", "Sur la carte, reste appuyé pour commencer un trajet, touche pour ajouter des points, puis Enregistrer. Ou importe un fichier .gpx.");
        else {
            LinearLayout card = HubUi.card(body);
            for (GpxStore.Entry entry : walks) {
                Button see = HubUi.button(ui, "Voir", false), jump = HubUi.button(ui, "Téléporter", false), go = HubUi.button(ui, "Démarrer", true);
                see.setOnClickListener(v -> loadWalk(entry, route -> host.openRoute(route, true)));
                jump.setOnClickListener(v -> loadWalk(entry, route -> teleportThenWalk(route, false)));
                go.setOnClickListener(v -> loadWalk(entry, route -> startWalk(route, false)));
                View line = HubUi.row(card, "GPX", entry.name, entry.count + (entry.count == 1 ? " arrêt" : " arrêts"), HubUi.actions(ui, see, jump, go), null, unfolded);
                line.setOnLongClickListener(v -> { confirmDeleteWalk(entry); return true; });
            }
        }

        // ----- Ready-made walks
        HubUi.section(body, "Trajets prédéfinis", PresetRoutes.ALL.isEmpty() ? null : "Des balades prêtes à lancer", null, null);
        if (PresetRoutes.ALL.isEmpty())
            HubUi.empty(body, MenuIcon.HOTSPOT, "Bientôt disponibles", "Les trajets prédéfinis arriveront ici avec une prochaine mise à jour.");
        else {
            LinearLayout card = HubUi.card(body);
            for (PresetRoutes.Preset preset : PresetRoutes.ALL) {
                double[][] stops = preset.route.points();
                String detail = preset.place + " · " + stops.length + " arrêts" + (preset.loop ? " · boucle" : "") + " · " + HubUi.away(stops[0][0], stops[0][1]);
                Button see = HubUi.button(ui, "Voir", false), jump = HubUi.button(ui, "Téléporter", false), go = HubUi.button(ui, "Démarrer", true);
                see.setOnClickListener(v -> host.openRoute(preset.route, false));
                jump.setOnClickListener(v -> teleportThenWalk(preset.route, preset.loop));
                go.setOnClickListener(v -> startWalk(preset.route, preset.loop));
                HubUi.row(card, preset.loop ? "Boucle" : "Trajet", preset.route.name, detail, HubUi.actions(ui, see, jump, go), null, unfolded);
            }
        }
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private void caption(String text) {
        TextView line = SettingsUi.text(ui, text, 12, SettingsUi.muted()); line.setPadding(HubUi.dp(ui, 4), HubUi.dp(ui, 6), 0, 0);
        body.addView(line);
    }

    /** Voir / Marcher / Téléporter for one place. */
    private View placeActions(double latitude, double longitude, String name) {
        Button see = HubUi.button(ui, "Voir", false), walk = HubUi.button(ui, "Marcher", false), jump = HubUi.button(ui, "Téléporter", true);
        see.setOnClickListener(v -> host.showOnMap(latitude, longitude));
        walk.setOnClickListener(v -> {
            if (!host.walk(latitude, longitude)) return;
            Toast.makeText(activity, "Marche vers " + name, Toast.LENGTH_SHORT).show(); host.done();
        });
        jump.setOnClickListener(v -> { if (host.teleport(latitude, longitude)) host.done(); });
        return HubUi.actions(ui, see, walk, jump);
    }

    // ------------------------------------------------------------------ walks

    private interface RouteUse { void use(GpxRoute route); }

    private void loadWalk(GpxStore.Entry entry, RouteUse use) {
        io.execute(() -> {
            GpxRoute route; String error = null;
            try { route = new GpxStore(activity).load(entry.id); }
            catch (IOException | RuntimeException e) { route = null; error = e.getMessage(); }
            final GpxRoute loaded = route; final String failed = error;
            main.post(() -> {
                if (closed) return;
                if (loaded == null) { Toast.makeText(activity, failed == null ? "Impossible d'ouvrir ce trajet." : failed, Toast.LENGTH_LONG).show(); return; }
                use.use(loaded);
            });
        });
    }

    /**
     * Starts a walk from where the player is. When a later stop is closer than the first one, asks
     * whether to join the walk there; otherwise starts at once from the first stop.
     */
    private void startWalk(GpxRoute route, boolean loop) {
        double[][] stops = route.points();
        double lat = Control.engine.latitude, lon = Control.engine.longitude;
        int nearest = RouteStart.closest(stops, lat, lon);
        double toFirst = RouteStart.meters(lat, lon, stops[0][0], stops[0][1]);
        double toNearest = RouteStart.meters(lat, lon, stops[nearest][0], stops[nearest][1]);
        if (nearest == 0 || toNearest >= toFirst) { walk(route, stops, loop, 0); return; }
        String[] choices = {
            "Depuis l'arrêt le plus proche\nArrêt " + (nearest + 1) + " sur " + stops.length + ", à " + RouteStart.distance(toNearest),
            "Depuis le début\nÀ " + RouteStart.distance(toFirst)
        };
        AlertDialog choose = ChoiceList.attach(new AlertDialog.Builder(ui).setTitle("Démarrer « " + route.name + " »")
            .setNegativeButton("Annuler", null).create(), choices, which -> {
                if (which == 0) walk(route, RouteStart.from(stops, nearest, loop), loop, nearest);
                else walk(route, stops, loop, 0);
            });
        DialogLifecycle.bind(activity, choose, null);
        track(choose); choose.show();
    }

    /** Teleports to the first stop, then walks the whole route. */
    private void teleportThenWalk(GpxRoute route, boolean loop) {
        double[][] stops = route.points();
        if (host.teleport(stops[0][0], stops[0][1])) walk(route, stops, loop, 0);
    }

    private void walk(GpxRoute route, double[][] stops, boolean loop, int first) {
        if (!host.walkRoute(stops, loop)) return;
        Control.event("trajet-started", String.format(Locale.US, "name=%s stops=%d from=%d loop=%s", route.name, stops.length, first + 1, loop));
        Toast.makeText(activity, "En route : " + route.name + (first > 0 ? " (depuis l'arrêt " + (first + 1) + ")" : "") + (loop ? ", en boucle" : ""), Toast.LENGTH_SHORT).show();
        host.done();
    }

    /** Imports a .gpx and keeps it in "Mes trajets". The file picker closes the map, which then reopens here. */
    private void importGpx() {
        GpxImport.pick(activity, route -> {
            if (route == null) return;
            // Its own thread: the picker usually closed this panel (and its executor) meanwhile.
            new Thread(() -> {
                String error = null;
                try { new GpxStore(activity).save(route); } catch (IOException | RuntimeException e) { error = e.getMessage(); }
                final String failed = error;
                main.post(() -> {
                    Toast.makeText(activity, failed == null ? "Trajet ajouté : " + route.name : "Impossible d'enregistrer ce trajet : " + failed, Toast.LENGTH_LONG).show();
                    if (!closed) reload(); else if (reopen != null) GpxImport.whenResumed(activity, reopen);
                });
            }, "pgrazor-gpx-import").start();
        });
    }

    // ------------------------------------------------------------------ deleting

    private void confirmDeletePlace(SavedPlaces.Entry entry) {
        confirm("Supprimer « " + entry.name + " » ?", "Ce lieu sera retiré de tes favoris.", () -> new SavedPlaces(activity).remove(entry.id));
    }

    private void confirmDeleteWalk(GpxStore.Entry entry) {
        confirm("Supprimer « " + entry.name + " » ?", "Ce trajet sera retiré de tes trajets enregistrés.", () -> new GpxStore(activity).delete(entry.id));
    }

    private interface Write { void run() throws IOException; }

    private void confirm(String title, String message, Write delete) {
        AlertDialog dialog = new AlertDialog.Builder(ui).setTitle(title).setMessage(message)
            .setNegativeButton("Annuler", null).setPositiveButton("Supprimer", null).create();
        DialogLifecycle.bind(activity, dialog, null);
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            io.execute(() -> {
                boolean ok = true;
                try { delete.run(); } catch (IOException | RuntimeException e) { ok = false; }
                final boolean done = ok;
                main.post(() -> {
                    if (!done) Toast.makeText(activity, "Impossible de supprimer. Réessaie.", Toast.LENGTH_LONG).show();
                    if (dialog.isShowing()) dialog.dismiss();
                    reload();
                });
            });
        }));
        track(dialog); dialog.show();
    }

    // ------------------------------------------------------------------ saving a route

    /** Receives the name a route was saved under. */
    interface RouteSaved { void saved(String name); }

    /** Names a route drawn on the map and keeps it in Mes trajets. */
    static AlertDialog promptSaveRoute(Activity activity, Context ui, double[][] points, String suggested, RouteSaved after) {
        if (activity.isFinishing() || activity.isDestroyed()) return null;
        LinearLayout form = new LinearLayout(ui); form.setOrientation(LinearLayout.VERTICAL);
        int pad = HubUi.dp(ui, 22); form.setPadding(pad, HubUi.dp(ui, 4), pad, 0);
        form.addView(SettingsUi.text(ui, points.length + (points.length == 1 ? " point" : " points"), 13, SettingsUi.muted()));
        EditText name = new EditText(ui); name.setHint("Nom du trajet"); name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(GpxRoute.NAME_LIMIT)});
        if (suggested != null) { name.setText(suggested); name.setSelection(name.length()); }
        form.addView(name);
        AlertDialog dialog = new AlertDialog.Builder(ui).setTitle("Enregistrer le trajet").setView(form)
            .setNegativeButton("Annuler", null).setPositiveButton("Enregistrer", null).create();
        Handler main = new Handler(Looper.getMainLooper());
        DialogLifecycle.bind(activity, dialog, null);
        dialog.setOnShowListener(d -> {
            name.requestFocus();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                final GpxRoute route;
                try { route = new GpxRoute(name.getText().toString(), points); }
                catch (IllegalArgumentException e) { name.setError(e.getMessage()); return; }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                new Thread(() -> {
                    String error = null;
                    try { new GpxStore(activity).save(route); }
                    catch (IOException | RuntimeException e) { error = e.getMessage() == null ? "Impossible d'enregistrer ce trajet." : e.getMessage(); }
                    final String failed = error;
                    main.post(() -> {
                        if (!dialog.isShowing()) return;
                        if (failed != null) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); name.setError(failed); return; }
                        dialog.dismiss();
                        Toast.makeText(activity, "Trajet enregistré : " + route.name, Toast.LENGTH_SHORT).show();
                        if (after != null) after.saved(route.name);
                    });
                }, "pgrazor-route-save").start();
            });
        });
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
        return dialog;
    }

    private void track(AlertDialog dialog) { if (dialog != null) children.add(dialog); }

    void close() {
        if (closed) return;
        closed = true;
        for (AlertDialog dialog : children) if (dialog.isShowing()) dialog.dismiss();
        main.removeCallbacksAndMessages(null);
        io.shutdown(); // a write already asked for still finishes; its callback sees the panel closed
    }
}
