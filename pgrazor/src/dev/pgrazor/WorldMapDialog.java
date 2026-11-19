package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Rect;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.CheckBox;
import org.json.JSONObject;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** In-activity map picker. Merely browsing/selecting does not change location. */
public final class WorldMapDialog {
    /**
     * A GPX route imported or picked for the route builder. The system file picker pauses the host
     * and dismisses this dialog, so the choice is stashed here and restored as the draft when the
     * route builder is reopened. Cleared when the route is started or the draft is cleared.
     */
    static volatile GpxRoute pendingRoute;

    public interface DestinationListener {
        /** Return true after applying the destination, or false to keep the picker open. */
        boolean onDestination(double latitude, double longitude);
    }
    public interface RouteListener {
        /** Coordinates are copied in visit order; loop repeats from the first stop. Return true only after accepting the route. */
        boolean onRoute(double[][] waypoints, boolean loop);
    }

    public static AlertDialog show(Activity activity, double latitude, double longitude, DestinationListener listener) {
        return show(activity, latitude, longitude, listener, null);
    }

    public static AlertDialog show(Activity activity, double latitude, double longitude, DestinationListener listener, DestinationListener walk) {
        return show(activity,latitude,longitude,listener,walk,null);
    }

    public static AlertDialog show(Activity activity, double latitude, double longitude, DestinationListener listener, DestinationListener walk, RouteListener route) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)
            throw new IllegalArgumentException("Coordonnées de carte invalides");
        Context ui = MapTheme.wrap(activity); // themed chrome; the map view itself keeps the real Activity
        // One full-screen column: header, search, map, pin line, layers, tools, then the action bar.
        // The actions are part of the layout (not the dialog's own button bar), so the map shrinks to
        // make room for them and they can never be pushed off screen.
        ResponsiveLayout layout = new ResponsiveLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 12);
        layout.setPadding(pad, dp(activity, 4), pad, dp(activity, 8));

        LinearLayout header = new LinearLayout(ui); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(ui, "Carte du monde", 17); title.setTypeface(SettingsUi.medium()); title.setSingleLine(true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button routeMode = toolButton(ui, "Route"); routeMode.setContentDescription("Ouvrir le créateur de route");
        routeMode.setVisibility(route == null ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams routeParams = new LinearLayout.LayoutParams(-2, dp(activity, 34)); routeParams.rightMargin = dp(activity, 6);
        header.addView(routeMode, routeParams);
        TextView close = label(ui, "✕", 18); close.setGravity(Gravity.CENTER); close.setClickable(true); close.setFocusable(true);
        close.setContentDescription("Fermer la carte"); close.setTag(DialogStyle.KEEP);
        header.addView(close, new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40)));
        layout.addView(header, new LinearLayout.LayoutParams(-1, dp(activity, 48)));

        Button search = toolButton(ui, "Chercher un lieu ou des coordonnées");
        search.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); search.setPadding(dp(activity, 14), 0, dp(activity, 14), 0);
        search.setTag(DialogStyle.KEEP);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(activity, 40)); searchParams.bottomMargin = dp(activity, 8);
        layout.addView(search, searchParams);

        TextView selected = label(ui, "", 12);
        TextView status = label(ui, "Chargement de la carte…", 11);
        MapView map = new MapView(activity, latitude, longitude, selected, status);
        layout.map = map;
        // The map fills the space left by the controls (see ResponsiveLayout); this is only a baseline height.
        layout.addView(map, new LinearLayout.LayoutParams(-1, dp(activity, 240)));

        // Pin coordinates, then the hint (route progress in route mode).
        selected.setTypeface(SettingsUi.mono()); selected.setPadding(0, dp(activity, 6), 0, 0);
        layout.addView(selected);
        TextView hint = label(ui, "", 11);
        layout.addView(hint);

        // Map layers on one line.
        LinearLayout layers = new LinearLayout(ui); layers.setGravity(Gravity.CENTER_VERTICAL);
        SharedPreferences preferences = AccountProfiles.preferences(activity, "pgrazor-map");
        String[] names = {"Stops", "Gyms", "Raids"}, labels = {"PokéStops", "Arènes", "Raids"};
        for (int i = 0; i < names.length; i++) {
            final int bit = 1 << i; CheckBox toggle = new CheckBox(ui); toggle.setText(labels[i]); toggle.setTextSize(12);
            boolean enabled = preferences.getBoolean(names[i], false); if (enabled) map.setPoiLayer(bit, true); toggle.setChecked(enabled);
            final String key = names[i];
            toggle.setOnCheckedChangeListener((b, on) -> { map.setPoiLayer(bit, on); preferences.edit().putBoolean(key, on).apply(); map.refreshPois(); });
            layers.addView(toggle, new LinearLayout.LayoutParams(0, dp(activity, 40), 1));
        }
        layout.addView(layers);

        // Map tools (route mode turns them into Ajouter / Annuler / Arrêts / GPX).
        LinearLayout tools = new LinearLayout(ui);
        Button globe = toolButton(ui, "Monde"), center = toolButton(ui, "Centrer"), retry = toolButton(ui, "Réessayer");
        Button gpx = route == null ? null : toolButton(ui, "GPX");
        if (gpx != null) { gpx.setContentDescription("Enregistrer, importer ou exporter une route GPX"); gpx.setVisibility(View.GONE); }
        for (Button b : gpx == null ? new Button[]{globe, center, retry} : new Button[]{globe, center, retry, gpx}) tools.addView(b, toolParams(activity));
        layout.addView(tools);
        // Places: saved spots, hotspots, and copying coordinates (each also centres the map).
        LinearLayout places = new LinearLayout(ui);
        Button saved = toolButton(ui, "Mes lieux"), hotspots = toolButton(ui, "Hotspots");
        Button current = toolButton(ui, "Ma position"), pin = toolButton(ui, "Copier l'épingle");
        current.setContentDescription("Centrer la carte sur ta position en jeu et copier ses coordonnées");
        pin.setContentDescription("Centrer la carte sur l'épingle et copier ses coordonnées");
        for (Button b : new Button[]{saved, hotspots, current, pin}) places.addView(b, toolParams(activity));
        layout.addView(places);
        // Route-only line: loop and clear.
        LinearLayout routeTools = new LinearLayout(ui); routeTools.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox loop = new CheckBox(ui); loop.setText("Route en boucle"); loop.setTextSize(12);
        loop.setContentDescription("Répéter la route depuis le premier arrêt après le dernier"); loop.setVisibility(View.GONE);
        routeTools.addView(loop, new LinearLayout.LayoutParams(0, dp(activity, 40), 1));
        Button clearRoute = toolButton(ui, "Effacer la route");
        clearRoute.setContentDescription("Effacer tous les arrêts du brouillon de route"); clearRoute.setVisibility(View.GONE);
        routeTools.addView(clearRoute, new LinearLayout.LayoutParams(dp(activity, 120), dp(activity, 36)));
        layout.addView(routeTools);

        // Status and the tile attribution share one small line.
        LinearLayout foot = new LinearLayout(ui); foot.setGravity(Gravity.CENTER_VERTICAL); foot.setPadding(0, dp(activity, 2), 0, dp(activity, 6));
        status.setSingleLine(true); status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        foot.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        TextView coverage = label(ui, "", 11); coverage.setVisibility(View.GONE);
        map.setPoiStatus(coverage);
        TextView attribution = label(ui, "", 10);
        attribution.setText(Html.fromHtml("© <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a>", Html.FROM_HTML_MODE_LEGACY));
        attribution.setMovementMethod(LinkMovementMethod.getInstance());
        foot.addView(attribution, new LinearLayout.LayoutParams(-2, -2));
        layout.addView(foot);
        layout.addView(coverage);

        // The action bar: Marcher ici (when walking is available) and the primary Téléporter.
        LinearLayout actions = new LinearLayout(ui);
        Button walkHere = toolButton(ui, "Marcher ici"), teleport = toolButton(ui, "Téléporter");
        walkHere.setTextSize(14); teleport.setTextSize(14); teleport.setTypeface(SettingsUi.medium()); walkHere.setTypeface(SettingsUi.medium());
        teleport.setTag(DialogStyle.KEEP); walkHere.setTag(DialogStyle.KEEP);
        walkHere.setVisibility(walk == null ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams walkParams = new LinearLayout.LayoutParams(0, dp(activity, 44), 1); walkParams.rightMargin = dp(activity, 8);
        actions.addView(walkHere, walkParams);
        actions.addView(teleport, new LinearLayout.LayoutParams(0, dp(activity, 44), 1));
        layout.addView(actions);

        map.refreshPois();
        final AlertDialog[] hotspotsDialog = {null}, cooldownDialog = {null}, searchDialog = {null};
        hotspots.setOnClickListener(v -> {
            if (hotspotsDialog[0] != null && hotspotsDialog[0].isShowing()) return;
            hotspotsDialog[0] = HotspotsDialog.show(activity, spot -> { map.select(spot.latitude, spot.longitude); });
        });
        search.setOnClickListener(v -> {
            if (searchDialog[0] != null && searchDialog[0].isShowing()) return;
            searchDialog[0] = MapSearchDialog.show(activity, MapProjection.latitude(map.centerY),
                MapProjection.longitude(map.centerX), (lat, lon) -> map.select(lat, lon));
        });
        PlaceUi placeUi = new PlaceUi(activity, ui, map, saved);
        saved.setOnClickListener(v -> placeUi.openLibrary());
        current.setOnClickListener(v -> {
            map.centerCurrentLocation();
            CoordinateClipboard.copy(activity, Control.engine.latitude, Control.engine.longitude, "Ta position est copiée");
        });
        pin.setOnClickListener(v -> { map.centerSelection(); CoordinateClipboard.copy(activity, map.latitude, map.longitude, "Position de l'épingle copiée"); });
        globe.setOnClickListener(v -> map.worldView());
        center.setOnClickListener(v -> map.centerSelection()); retry.setOnClickListener(v -> map.retry());

        RouteUi routes = route == null ? null : new RouteUi(activity, ui, map, routeMode, hint, globe, center, retry, gpx, clearRoute, loop, route, walk != null);
        if (routes != null) { routes.reopen = () -> WorldMapDialog.show(activity, Control.engine.latitude, Control.engine.longitude, listener, walk, route); routes.title = title; routes.action = teleport; routes.walkAction = walkHere; }
        else hint.setText(MAP_HINT);
        AlertDialog dialog = new AlertDialog.Builder(ui).setView(layout).create();
        close.setOnClickListener(v -> dialog.dismiss());
        map.poiLayer.setDestinationActions(walk == null ? null : (lat, lon) -> {
            if (!walk.onDestination(lat, lon)) return false;
            dialog.dismiss(); return true;
        }, (latitudePoi, longitudePoi) -> {
            if (cooldownDialog[0] != null && cooldownDialog[0].isShowing()) return false;
            cooldownDialog[0] = TeleportDialog.confirm(activity, latitudePoi, longitudePoi, (lat, lon) -> {
                if (!listener.onDestination(lat, lon)) return false;
                dialog.dismiss(); return true;
            });
            return false; // Keep details visible if the cooldown confirmation is cancelled.
        });
        teleport.setOnClickListener(v -> {
            if (routes != null && routes.editing) { routes.start(); return; }
            if (cooldownDialog[0] != null && cooldownDialog[0].isShowing()) return;
            cooldownDialog[0] = TeleportDialog.confirm(activity, map.latitude, map.longitude, (lat, lon) -> {
                if (!listener.onDestination(lat, lon)) return false;
                dialog.dismiss(); return true;
            });
        });
        if (walk != null) walkHere.setOnClickListener(v -> { if (walk.onDestination(map.latitude, map.longitude)) dialog.dismiss(); });
        DialogLifecycle.bind(activity, dialog, () -> { if (hotspotsDialog[0] != null) hotspotsDialog[0].dismiss(); if (cooldownDialog[0] != null) cooldownDialog[0].dismiss(); if (searchDialog[0] != null) searchDialog[0].dismiss(); if (routes != null) routes.close(); placeUi.close(); map.close(); });
        dialog.setOnShowListener(d -> {
            MapTheme.fullScreen(dialog); MapTheme.style(layout);
            // Colours after the generic pass: quiet secondary text, accent pin line, field-like search, filled primary.
            title.setTextColor(SettingsUi.ink()); close.setTextColor(SettingsUi.muted());
            close.setBackground(SettingsUi.ripple(SettingsUi.rounded(ui, 0x00000000, 20)));
            selected.setTextColor(SettingsUi.accent());
            for (TextView quiet : new TextView[]{hint, status, coverage}) quiet.setTextColor(SettingsUi.muted());
            attribution.setTextColor(SettingsUi.faint());
            search.setTextColor(SettingsUi.faint()); search.setBackground(SettingsUi.ripple(SettingsUi.fieldBackground(ui)));
            teleport.setTextColor(SettingsUi.accentInk()); teleport.setBackground(SettingsUi.ripple(SettingsUi.rounded(ui, SettingsUi.accentFill(), 12)));
            walkHere.setTextColor(SettingsUi.ink()); walkHere.setBackground(SettingsUi.ripple(SettingsUi.shape(ui, SettingsUi.raised(), 12, SettingsUi.border())));
            if (routes != null) routes.attach(dialog);
        });
        dialog.show();
        return dialog;
    }

    static final String MAP_HINT = "Touche la carte pour placer l'épingle · glisse pour bouger · pince pour zoomer.";

    private static LinearLayout.LayoutParams toolParams(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(c, 36), 1); lp.setMargins(dp(c, 2), dp(c, 2), dp(c, 2), dp(c, 2)); return lp;
    }
    /** A compact tool button; colours and background come from MapTheme.style when the dialog shows. */
    private static Button toolButton(Context c, String text) {
        Button b = new Button(c); b.setText(text); b.setAllCaps(false); b.setTextSize(12); b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END); b.setStateListAnimator(null);
        b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(dp(c, 6), 0, dp(c, 6), 0);
        return b;
    }

    private static int dp(Context c, float value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
    private static TextView label(Context c, String text, int size) { TextView v = new TextView(c); v.setText(text); v.setTextSize(size); return v; }

    /** Route mode reuses the map toolbar and dialog action rather than consuming another row. */
    private static final class RouteUi {
        /** Manual builder cap; imported GPX routes may be larger, up to {@link #MAX}. */
        static final int LIMIT=50;
        static final int MAX=GpxCodec.MAX_POINTS;
        final Activity activity;
        final Context ui;
        final MapView map;
        final Button mode,add,undo,stops,gpx,clearRoute;
        final TextView hint;
        final CheckBox loop;
        final RouteListener listener;
        final boolean hasWalk;
        final List<double[]> draft=new ArrayList<>();
        final List<AlertDialog> dialogs=new ArrayList<>();
        AlertDialog parent;
        /** The map's own title and action buttons (the dialog has no built-in title or button bar). */
        TextView title;
        Button action, walkAction;
        boolean editing;
        /** The current draft came from a GPX import/selection, so it may exceed the manual LIMIT. */
        boolean imported;
        String routeName="Route";
        Runnable reopen;
        boolean closed;
        RouteUi(Activity activity,Context ui,MapView map,Button mode,TextView hint,Button add,Button undo,Button stops,Button gpx,Button clearRoute,CheckBox loop,RouteListener listener,boolean hasWalk) {
            this.activity=activity;this.ui=ui;this.map=map;this.mode=mode;this.hint=hint;this.loop=loop;
            this.add=add;this.undo=undo;this.stops=stops;this.gpx=gpx;this.clearRoute=clearRoute;this.listener=listener;this.hasWalk=hasWalk;
            clearRoute.setOnClickListener(v -> clearDraft());
            mode.setOnClickListener(v -> {editing=!editing;if(editing)maybeLoadPending();refresh();});
            add.setOnClickListener(v -> {if(editing)addStop();else map.worldView();});
            undo.setOnClickListener(v -> {if(editing)undo();else map.centerSelection();});
            stops.setOnClickListener(v -> {if(editing)showStops();else map.retry();});
            gpx.setOnClickListener(v -> {if(editing)GpxRouteDialog.menu(activity,this::sourceRoute,this::onGpxChosen);});
        }
        void attach(AlertDialog parent) {this.parent=parent;if(pendingRoute!=null){editing=true;maybeLoadPending();}refresh();}
        void refresh() {
            if(draft.isEmpty())imported=false;
            boolean big=draft.size()>LIMIT;
            mode.setText(editing ? "Carte" : "Route");
            mode.setContentDescription(editing ? "Quitter le créateur de route sans démarrer" : "Ouvrir le créateur de route");
            hint.setText(!editing ? MAP_HINT
                : big ? "Route GPX : "+draft.size()+" arrêts. Touche Démarrer pour la parcourir dans l'ordre."
                : "Route : "+draft.size()+"/"+LIMIT+" arrêts. Touche la carte, puis Ajouter un arrêt.");
            add.setText(editing ? "Ajouter un arrêt" : "Monde"); add.setContentDescription(editing ? "Ajouter la destination choisie comme prochain arrêt" : "Afficher la carte du monde");
            undo.setText(editing ? "Annuler" : "Centrer"); undo.setContentDescription(editing ? "Annuler le dernier arrêt" : "Centrer sur la destination choisie");
            stops.setText(editing ? "Arrêts ("+draft.size()+")" : "Réessayer"); stops.setContentDescription(editing ? "Voir les arrêts dans l'ordre et effacer la route" : "Recharger les tuiles de la carte");
            gpx.setText("GPX"); gpx.setVisibility(editing ? View.VISIBLE : View.GONE);
            clearRoute.setVisibility(editing ? View.VISIBLE : View.GONE);clearRoute.setEnabled(!draft.isEmpty());
            add.setEnabled(!editing || draft.size()<LIMIT);undo.setEnabled(!editing || !draft.isEmpty());
            loop.setVisibility(editing ? View.VISIBLE : View.GONE); // route-only toggle; defaults to stopping at the last stop
            if(title!=null)title.setText(editing ? "Créer une route" : "Carte du monde");
            if(action!=null) {
                action.setText(editing ? "Démarrer la route" : "Téléporter");
                action.setContentDescription(editing ? "Démarrer la route dans l'ordre des arrêts" : "Se téléporter à la destination choisie");
                action.setEnabled(!editing || (draft.size()>=(imported?1:2) && draft.size()<=MAX));
                if(walkAction!=null && hasWalk)walkAction.setVisibility(editing ? View.GONE : View.VISIBLE);
            }
            map.setRoute(draft);
        }
        void addStop() {
            if(draft.size()>=LIMIT)return;
            double lat=map.latitude,lon=map.longitude;
            if(!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat)>90 || Math.abs(lon)>180) {
                Toast.makeText(activity,"Choisis des coordonnées valides pour cet arrêt.",Toast.LENGTH_SHORT).show();return;
            }
            draft.add(new double[]{lat,lon});refresh();
        }
        void undo() {if(!draft.isEmpty())draft.remove(draft.size()-1);refresh();}
        void clearDraft() {draft.clear();imported=false;routeName="Route";WorldMapDialog.pendingRoute=null;refresh();}
        void showStops() {
            AlertDialog.Builder builder=new AlertDialog.Builder(ui).setTitle("Arrêts de la route ("+draft.size()+(draft.size()>LIMIT?"":"/"+LIMIT)+")")
                .setNegativeButton("Fermer",null).setPositiveButton("Effacer la route",null);
            if(draft.isEmpty())builder.setMessage("Choisis une destination sur la carte et touche Ajouter un arrêt. Ajoute au moins deux arrêts avant de démarrer.");
            AlertDialog dialog=builder.create();dialogs.add(dialog);
            if(!draft.isEmpty()){
                String[] rows=new String[draft.size()];
                for(int i=0;i<rows.length;i++)rows[i]=String.format(Locale.US,"Stop %d\n%.6f, %.6f",i+1,draft.get(i)[0],draft.get(i)[1]);
                ChoiceList.attach(dialog,rows,index -> {double[] stop=draft.get(index);map.select(stop[0],stop[1]);});
            }
            dialog.setOnDismissListener(d -> dialogs.remove(dialog));
            dialog.setOnShowListener(d -> {
                Button clear=dialog.getButton(AlertDialog.BUTTON_POSITIVE);clear.setEnabled(!draft.isEmpty());
                clear.setOnClickListener(v -> {clearDraft();dialog.dismiss();});
            });
            dialog.show();
        }
        /** Load a chosen GPX route as the current draft; it stays an explicit Start, never auto-walks. */
        void onGpxChosen(GpxRoute route) {
            WorldMapDialog.pendingRoute=route; // survive the picker round-trip / dialog dismissal
            if(route==null)return;
            if(closed){if(reopen!=null)GpxRouteDialog.whenResumed(activity,reopen);return;}
            routeName=route.name;
            if(!editing)editing=true;
            apply(route.points());
            refresh();
            if(!draft.isEmpty())map.select(draft.get(0)[0],draft.get(0)[1]); // bring the route into view
        }
        /** Restore an imported/selected route when the builder is reopened, without clobbering a manual draft. */
        void maybeLoadPending() {
            GpxRoute pending=WorldMapDialog.pendingRoute;
            if(pending!=null && draft.isEmpty()){routeName=pending.name;apply(pending.points());if(!draft.isEmpty())map.select(draft.get(0)[0],draft.get(0)[1]);}
        }
        private void apply(double[][] points) {
            draft.clear();
            for(double[] p:points)draft.add(new double[]{p[0],p[1]});
            imported=true;
        }
        /** The current draft offered to the GPX menu for Save/Export; a placeholder name is renamed on Save. */
        GpxRoute sourceRoute() {
            if(draft.isEmpty())return null;
            try {GpxRoute current=new GpxRoute(routeName,toArray());WorldMapDialog.pendingRoute=current;return current;}
            catch(IllegalArgumentException invalid){return null;}
        }
        private double[][] toArray() {
            double[][] points=new double[draft.size()][2];
            for(int i=0;i<points.length;i++) {points[i][0]=draft.get(i)[0];points[i][1]=draft.get(i)[1];}
            return points;
        }
        void start() {
            if(draft.size()<(imported?1:2) || draft.size()>MAX)return;
            if(listener.onRoute(toArray(),loop.isChecked())){WorldMapDialog.pendingRoute=null;parent.dismiss();}
        }
        void close() {closed=true;for(AlertDialog dialog:new ArrayList<>(dialogs))dialog.dismiss();draft.clear();}
    }

    /** Child dialogs share the map's activity; selecting a saved place never invokes Teleport. */
    private static final class PlaceUi {
        final Activity activity;
        final Context ui;
        final MapView map;
        final Button opener;
        final Handler main = new Handler(Looper.getMainLooper());
        final ThreadPoolExecutor storage = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(8));
        final List<AlertDialog> dialogs = new ArrayList<>();
        SavedPlaces places;
        boolean closed;
        PlaceUi(Activity activity, Context ui, MapView map, Button opener) { this.activity = activity; this.ui = ui; this.map = map; this.opener = opener; }
        private SavedPlaces store() { if (places == null) places = new SavedPlaces(activity); return places; }
        private void show(AlertDialog dialog) {
            if (closed) return;
            dialogs.add(dialog);
            dialog.setOnDismissListener(d -> dialogs.remove(dialog));
            dialog.show();
        }
        void openLibrary() {
            if (closed) return;
            opener.setEnabled(false);
            storage.execute(() -> {
                try {
                    SavedPlaces.Snapshot snapshot = store().read();
                    main.post(() -> { if (!closed) { opener.setEnabled(true); showLibrary(snapshot); } });
                } catch (RuntimeException e) {
                    main.post(() -> { if (!closed) { opener.setEnabled(true); Toast.makeText(activity, "Impossible d'ouvrir les lieux enregistrés.", Toast.LENGTH_LONG).show(); } });
                }
            });
        }
        private void showLibrary(SavedPlaces.Snapshot snapshot) {
            AlertDialog.Builder builder = new AlertDialog.Builder(ui).setTitle("Lieux enregistrés (" + snapshot.entries.size() + "/50)")
                .setNegativeButton("Fermer", null).setPositiveButton("Enregistrer la sélection", null);
            final AlertDialog[] holder = new AlertDialog[1];
            if (snapshot.entries.isEmpty()) builder.setMessage(snapshot.damaged ? "Impossible de lire les lieux enregistrés. Tu peux enregistrer de nouvelles destinations ici." : "Aucun lieu enregistré. Enregistre la destination choisie pour la retrouver plus tard.");
            else {
                String[] names = new String[snapshot.entries.size()];
                for (int i = 0; i < names.length; i++) {
                    SavedPlaces.Entry entry = snapshot.entries.get(i);
                    names[i] = String.format(Locale.US, "%s\n%.6f, %.6f", entry.name, entry.latitude, entry.longitude);
                }
                ChoiceList.attach(holder[0] = builder.create(), names, which -> showPlace(snapshot.entries.get(which)));
            }
            if (holder[0] == null) holder[0] = builder.create();
            holder[0].setOnShowListener(d -> holder[0].getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> showSave(holder[0])));
            show(holder[0]);
            if (snapshot.damaged && !snapshot.entries.isEmpty()) Toast.makeText(activity, "Certains lieux enregistrés sont illisibles. Les entrées valides sont affichées.", Toast.LENGTH_LONG).show();
        }
        private void showSave(AlertDialog library) {
            final double latitude = map.latitude, longitude = map.longitude;
            LinearLayout form = new LinearLayout(ui); form.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(activity, 20); form.setPadding(pad, 0, pad, 0);
            form.addView(label(ui, String.format(Locale.US, "%.6f, %.6f", latitude, longitude), 14));
            EditText name = new EditText(ui); name.setHint("Nom du lieu (jusqu'à 40 caractères)"); name.setSingleLine(true);
            name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            name.setFilters(new InputFilter[] {new InputFilter.LengthFilter(SavedPlaces.NAME_LIMIT)});
            form.addView(name);
            AlertDialog dialog = new AlertDialog.Builder(ui).setTitle("Enregistrer le lieu choisi").setView(form)
                .setNegativeButton("Annuler", null).setPositiveButton("Enregistrer", null).create();
            dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                final SavedPlaces.Entry entry;
                try { entry = new SavedPlaces.Entry(name.getText().toString(), latitude, longitude); }
                catch (IllegalArgumentException e) { name.setError(e.getMessage()); return; }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                storage.execute(() -> {
                    try {
                        store().add(entry);
                        main.post(() -> {
                            if (!closed && dialog.isShowing()) {
                                dialog.dismiss(); library.dismiss(); Toast.makeText(activity, "Enregistré : " + entry.name, Toast.LENGTH_SHORT).show(); openLibrary();
                            }
                        });
                    } catch (IOException | RuntimeException e) {
                        main.post(() -> { if (!closed && dialog.isShowing()) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); name.setError(e.getMessage()); } });
                    }
                });
            }));
            show(dialog);
        }
        private void showPlace(SavedPlaces.Entry entry) {
            AlertDialog dialog = new AlertDialog.Builder(ui).setTitle(entry.name)
                .setMessage(String.format(Locale.US, "%.6f, %.6f\n\nSélectionner affiche cette destination sur la carte. Choisis ensuite Téléporter ou Marcher ici pour l'appliquer.", entry.latitude, entry.longitude))
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Choisir sur la carte", (d, which) -> map.select(entry.latitude, entry.longitude))
                .setNeutralButton("Supprimer", null).create();
            dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> confirmDelete(entry, dialog)));
            show(dialog);
        }
        private void confirmDelete(SavedPlaces.Entry entry, AlertDialog details) {
            AlertDialog confirm = new AlertDialog.Builder(ui).setTitle("Supprimer le lieu enregistré ?")
                .setMessage(entry.name + " sera retiré de tes lieux enregistrés. La position actuelle ne change pas.")
                .setNegativeButton("Annuler", null).setPositiveButton("Supprimer", null).create();
            confirm.setOnShowListener(d -> confirm.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                confirm.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                storage.execute(() -> {
                    try {
                        store().remove(entry.id);
                        main.post(() -> { if (!closed && confirm.isShowing()) { confirm.dismiss(); details.dismiss(); openLibrary(); } });
                    } catch (IOException | RuntimeException e) {
                        main.post(() -> {
                            if (!closed && confirm.isShowing()) {
                                confirm.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                Toast.makeText(activity, "Impossible de supprimer le lieu. Réessaie.", Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });
            }));
            show(confirm);
        }
        void close() {
            closed = true;
            for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
            main.removeCallbacksAndMessages(null);
            storage.shutdown(); // Finish any explicitly requested persistence write; callbacks ignore the closed UI.
        }
    }

    /** Give the map the space left after controls, rather than guessing from physical screen orientation. */
    private static final class ResponsiveLayout extends LinearLayout {
        View map;
        final int maximumMapHeight;
        ResponsiveLayout(Activity activity) { super(activity); maximumMapHeight = dp(activity, 420); }
        protected void onMeasure(int widthSpec, int heightSpec) {
            if (map != null) {
                int reserved = getPaddingTop() + getPaddingBottom();
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    if (child == map || child.getVisibility() == View.GONE) continue;
                    measureChildWithMargins(child, widthSpec, 0, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0);
                    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams)child.getLayoutParams();
                    reserved += child.getMeasuredHeight() + params.topMargin + params.bottomMargin;
                }
                // Fill the space left by the controls so the actions stay visible; never a fixed height that pushes them off screen.
                int mode = MeasureSpec.getMode(heightSpec);
                int available;
                if (mode == MeasureSpec.UNSPECIFIED) available = maximumMapHeight;
                else {
                    available = Math.max(0, MeasureSpec.getSize(heightSpec) - reserved);

                }
                map.getLayoutParams().height = available;
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    private static final class MapView extends View {
        private final TextView selectionLabel;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final int tileSize, slop;
        private final MapTileSource tiles;
        private final MapPoiLayer poiLayer;
        private List<double[]> route = Collections.emptyList();
        private volatile boolean closed;
        private double centerX, centerY, latitude, longitude;
        private int zoom = 14;
        private double zoomScale=1;
        private final ScaleGestureDetector pinch;
        private float downX, downY, previousX, previousY;
        private boolean dragged, multiTouch;
        private final Runnable locationRefresh = new Runnable() {
            public void run() {
                if(closed) return;
                invalidate(); // Refresh the live player marker without requesting new tiles.
                postDelayed(this, 250);
            }
        };

        MapView(Activity activity, double latitude, double longitude, TextView selection, TextView status) {
            super(activity);
            this.latitude = latitude; this.longitude = longitude;
            centerX = MapProjection.x(longitude); centerY = MapProjection.y(latitude);
            selectionLabel = selection;
            tileSize = dp(activity, 256); slop = ViewConfiguration.get(activity).getScaledTouchSlop();
            tiles = new MapTileSource(new MapTileSource.Host() {
                public int zoom() { return zoom; }
                public void onTilesChanged() { invalidate(); }
            }, status, new File(activity.getCacheDir(), "pgrazor-map-tiles-v1"));
            poiLayer = new MapPoiLayer(activity, MapTheme.wrap(activity), new MapPoiLayer.Projector() {
                public float screenX(double lon) { return projectX(lon); }
                public float screenY(double lat) { return projectY(lat); }
                public int width() { return getWidth(); }
                public int height() { return getHeight(); }
            }, new MapPoiLayer.Snapshots() {
                public void setOpen(boolean open) { Control.setMapPoiOpen(open); }
                public String read() { return Control.mapPoiSnapshot(); }
            }, this::invalidate);
            setContentDescription("Carte du monde. Le point bleu est ta position actuelle ; l'épingle rouge est la destination. Glisse pour te déplacer, pince pour zoomer, touche pour choisir la destination.");
            // Use the activity hardware renderer; tile I/O stays on the worker.
            pinch=new ScaleGestureDetector(activity,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                public boolean onScaleBegin(ScaleGestureDetector detector){multiTouch=true;return true;}
                public boolean onScale(ScaleGestureDetector detector){
                    double old=world(),minimum=Math.max(1,Math.min(getWidth(),getHeight()));
                    double next=Math.max(minimum,Math.min(tileSize*Math.pow(2,19),old*detector.getScaleFactor()));
                    double dx=detector.getFocusX()-getWidth()/2.0,dy=detector.getFocusY()-getHeight()/2.0;
                    double anchorX=MapProjection.offsetX(centerX,dx,old),anchorY=MapProjection.offsetY(centerY,dy,old);
                    zoom=Math.max(0,Math.min(19,(int)Math.floor(Math.log(next/tileSize)/Math.log(2))));
                    zoomScale=next/((zoom==0?minimum:tileSize)*(1<<zoom));
                    centerX=MapProjection.offsetX(anchorX,-dx,next);centerY=MapProjection.offsetY(anchorY,-dy,next);
                    viewportChanged();return true;
                }
            });
            updateSelection();
        }

        void setPoiLayer(int bit, boolean on) { poiLayer.setLayer(bit, on); }
        void setPoiStatus(TextView view) { poiLayer.setStatusView(view); }
        void refreshPois() { poiLayer.refresh(); }

        private void updateSelection() {
            selectionLabel.setText(String.format(Locale.US, "Épingle : %.6f, %.6f", latitude, longitude));
        }
        private double displayedTileSize() { return (zoom == 0 ? Math.max(1, Math.min(getWidth(), getHeight())) : tileSize)*zoomScale; }
        private double world() { return MapProjection.worldPixels(zoom, displayedTileSize()); }
        void zoom(int amount) { int next = Math.max(0, Math.min(19, zoom + amount)); if (next != zoom) { zoom = next; viewportChanged(); } }
        void worldView() { zoomScale=1; zoom = 0; centerX = .5; centerY = .5; viewportChanged(); }
        void centerCurrentLocation() {
            double lat=Control.engine.latitude,lon=Control.engine.longitude;
            if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)return;
            zoomScale=1;if(zoom==0)zoom=14;
            centerX=MapProjection.x(lon);centerY=MapProjection.y(lat);viewportChanged();
        }
        void centerSelection() { zoomScale=1; if (zoom == 0) zoom = 14; centerX = MapProjection.x(longitude); centerY = MapProjection.y(latitude); viewportChanged(); }
        void select(double latitude, double longitude) {
            this.latitude = latitude; this.longitude = longitude;
            zoomScale=1; zoom = 14; centerX = MapProjection.x(longitude); centerY = MapProjection.y(latitude);
            updateSelection(); viewportChanged();
        }
        void retry() { tiles.clearFailures(); viewportChanged(); }
        void setRoute(List<double[]> points) {
            List<double[]> copy=new ArrayList<>(points.size());
            for(double[] point:points)copy.add(new double[]{point[0],point[1]});
            route=copy;invalidate();
        }
        protected void onSizeChanged(int w, int h, int oldw, int oldh) { viewportChanged(); }

        private void viewportChanged() {
            if (closed || getWidth() <= 0 || getHeight() <= 0) return;
            if (zoom == 0 && zoomScale<=1) { centerX = .5; centerY = .5; }
            int count = 1 << zoom;
            double pixels = displayedTileSize();
            double originX = centerX * world() - getWidth() / 2.0;
            double originY = centerY * world() - getHeight() / 2.0;
            int left = zoom == 0 ? 0 : (int)Math.floor(originX / pixels), top = (int)Math.floor(originY / pixels);
            int right = zoom == 0 ? 0 : (int)Math.floor((originX + getWidth() - 1) / pixels), bottom = (int)Math.floor((originY + getHeight() - 1) / pixels);
            List<MapTileSource.Tile> placed = new ArrayList<>();
            for (int y = Math.max(0, top); y <= Math.min(count - 1, bottom); y++) {
                for (int x = left; x <= right && placed.size() < 32; x++) {
                    placed.add(new MapTileSource.Tile(zoom, Math.floorMod(x, count), y, (float)(x * pixels - originX), (float)(y * pixels - originY)));
                }
            }
            tiles.setViewport(placed);
            invalidate();
        }

        protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.rgb(225, 231, 234));
            paint.setStyle(Paint.Style.FILL);
            for (MapTileSource.Tile tile : tiles.tiles()) {
                Bitmap bitmap = tiles.bitmap(tile.key);
                float pixels = (float)displayedTileSize();
                RectF bounds = new RectF(tile.left, tile.top, tile.left + pixels, tile.top + pixels);
                if (bitmap != null) canvas.drawBitmap(bitmap, null, bounds, paint);
                else if(!drawParent(canvas,tile,bounds)) {
                    paint.setColor(Color.rgb(202, 211, 216)); paint.setStyle(Paint.Style.STROKE); canvas.drawRect(bounds, paint); paint.setStyle(Paint.Style.FILL);
                }
            }
            poiLayer.draw(canvas);
            drawRoute(canvas,false);
            float markerX = (float)(getWidth() / 2.0 + MapProjection.deltaX(MapProjection.x(longitude), centerX) * world());
            float markerY = (float)(getHeight() / 2.0 + (MapProjection.y(latitude) - centerY) * world());
            float radius = Math.max(8, tileSize / 28f);
            paint.setColor(Color.WHITE); canvas.drawCircle(markerX, markerY, radius + 3, paint);
            paint.setColor(Color.rgb(197, 35, 52)); canvas.drawCircle(markerX, markerY, radius, paint);
            paint.setColor(Color.WHITE); canvas.drawCircle(markerX, markerY, radius / 3, paint);
            drawRoute(canvas,true);
            drawCurrentLocation(canvas);
        }

        /** Independent of the destination: selecting or searching never changes this position. */
        private void drawCurrentLocation(Canvas canvas) {
            double lat=Control.engine.latitude,lon=Control.engine.longitude;
            if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)return;
            float x=projectX(lon),y=projectY(lat),radius=dp(getContext(),5);
            if(x < -radius*3 || x > getWidth()+radius*3 || y < -radius*3 || y > getHeight()+radius*3)return;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x403493fa);canvas.drawCircle(x,y,radius*2.5f,paint);
            paint.setColor(Color.WHITE);canvas.drawCircle(x,y,radius+dp(getContext(),2),paint);
            paint.setColor(0xff1674e8);canvas.drawCircle(x,y,radius,paint);
        }

        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if(!closed){removeCallbacks(locationRefresh);post(locationRefresh);}
        }

        private boolean drawParent(Canvas canvas,MapTileSource.Tile tile,RectF bounds){
            for(int z=tile.zoom-1;z>=Math.max(0,tile.zoom-5);z--){int factor=1<<(tile.zoom-z);Bitmap parent=tiles.bitmap(z+"-"+(tile.x/factor)+"-"+(tile.y/factor));if(parent==null)continue;
                int size=256/factor,x=(tile.x%factor)*size,y=(tile.y%factor)*size;
                canvas.drawBitmap(parent,new Rect(x,y,x+size,y+size),bounds,paint);return true;
            }return false;
        }
        private float projectX(double lon){return (float)(getWidth()/2.0+MapProjection.deltaX(MapProjection.x(lon),centerX)*world());}
        private float projectY(double lat){return (float)(getHeight()/2.0+(MapProjection.y(lat)-centerY)*world());}

        /** Each segment takes the shortest horizontal wrap, splitting at the dateline when needed. */
        private void drawRoute(Canvas canvas,boolean markers) {
            if(route.isEmpty() || getWidth()<=0 || getHeight()<=0)return;
            double pixels=world();float radius=Math.max(11,tileSize/20f);
            int saved=canvas.save();
            if(zoom==0)canvas.clipRect((float)((getWidth()-pixels)/2),(float)((getHeight()-pixels)/2),(float)((getWidth()+pixels)/2),(float)((getHeight()+pixels)/2));
            paint.setStyle(Paint.Style.FILL);paint.setStrokeWidth(Math.max(2,tileSize/90f));
            for(int i=0;i<route.size();i++) {
                double[] point=route.get(i);
                double pointX=MapProjection.x(point[1]);
                double x=getWidth()/2.0+MapProjection.deltaX(pointX,centerX)*pixels;
                double y=getHeight()/2.0+(MapProjection.y(point[0])-centerY)*pixels;
                if(!markers && i>0) {
                    double[] previous=route.get(i-1);
                    double previousX=MapProjection.x(previous[1]);
                    double start=getWidth()/2.0+MapProjection.deltaX(previousX,centerX)*pixels;
                    double end=start+MapProjection.deltaX(pointX,previousX)*pixels;
                    double startY=getHeight()/2.0+(MapProjection.y(previous[0])-centerY)*pixels;
                    int first=(int)Math.ceil(-Math.max(start,end)/pixels),last=(int)Math.floor((getWidth()-Math.min(start,end))/pixels);
                    paint.setColor(Color.rgb(25,103,190));
                    for(int repeat=first;repeat<=last && repeat<first+32;repeat++)canvas.drawLine((float)(start+repeat*pixels),(float)startY,(float)(end+repeat*pixels),(float)y,paint);
                }
                if(markers) {
                    int first=(int)Math.ceil((-radius-x)/pixels),last=(int)Math.floor((getWidth()+radius-x)/pixels);
                    for(int repeat=first;repeat<=last && repeat<first+32;repeat++) {
                        float shownX=(float)(x+repeat*pixels),shownY=(float)y;
                        if(shownY < -radius || shownY > getHeight()+radius)continue;
                        paint.setColor(Color.WHITE);canvas.drawCircle(shownX,shownY,radius+2,paint);
                        paint.setColor(Color.rgb(25,103,190));canvas.drawCircle(shownX,shownY,radius,paint);
                        paint.setTextSize(radius*1.1f);paint.setTextAlign(Paint.Align.CENTER);paint.setColor(Color.WHITE);
                        Paint.FontMetrics font=paint.getFontMetrics();canvas.drawText(Integer.toString(i+1),shownX,shownY-(font.ascent+font.descent)/2,paint);
                    }
                }
            }
            canvas.restoreToCount(saved);
        }

        public boolean onTouchEvent(MotionEvent event) {
            pinch.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    downX = previousX = event.getX(); downY = previousY = event.getY(); dragged = false; multiTouch = false; return true;
                case MotionEvent.ACTION_POINTER_DOWN: multiTouch = true; return true;
                case MotionEvent.ACTION_MOVE:
                    if (multiTouch) return true;
                    float x = event.getX(), y = event.getY();
                    if (Math.hypot(x - downX, y - downY) > slop) dragged = true;
                    if (dragged && (zoom != 0 || zoomScale>1)) {
                        centerX = MapProjection.offsetX(centerX, previousX - x, world());
                        centerY = MapProjection.offsetY(centerY, previousY - y, world());
                        viewportChanged();
                    }
                    previousX = x; previousY = y; return true;
                case MotionEvent.ACTION_UP:
                    boolean insideWorld = zoom != 0 || (Math.abs(event.getX() - getWidth() / 2.0) <= world() / 2 && Math.abs(event.getY() - getHeight() / 2.0) <= world() / 2);
                    if (!dragged && !multiTouch) {
                        // Browsing marker details never moves the destination; movement needs an explicit action.
                        List<JSONObject> hits = poiLayer.hasLayers() ? poiLayer.hits(event.getX(), event.getY()) : Collections.<JSONObject>emptyList();
                        if (!hits.isEmpty()) { performClick(); poiLayer.openDetails(hits); getParent().requestDisallowInterceptTouchEvent(false); return true; }
                        if (insideWorld) {
                            longitude = MapProjection.longitude(MapProjection.offsetX(centerX, event.getX() - getWidth() / 2.0, world()));
                            latitude = MapProjection.latitude(MapProjection.offsetY(centerY, event.getY() - getHeight() / 2.0, world()));
                            updateSelection(); invalidate(); performClick();
                        }
                    }
                    getParent().requestDisallowInterceptTouchEvent(false); return true;
                case MotionEvent.ACTION_CANCEL: getParent().requestDisallowInterceptTouchEvent(false); return true;
                default: return true;
            }
        }
        public boolean performClick() { super.performClick(); return true; }

        void close() {
            removeCallbacks(locationRefresh);
            if (closed) return;
            closed = true;
            poiLayer.close();
            tiles.close();
        }
        protected void onDetachedFromWindow() { close(); super.onDetachedFromWindow(); }
    }
}
