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

/**
 * The world map, and around it the travel hub (map, places, autowalk). Merely browsing or selecting
 * never changes the location: every move is an explicit Teleport, Walk or Start.
 */
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

    /** The hub's tabs; {@link #show} with -1 reopens the tab used last. */
    public static final int TAB_MAP = 0, TAB_PLACES = 1, TAB_WALK = 2;
    private static int lastTab = TAB_MAP;

    public static AlertDialog show(Activity activity, double latitude, double longitude, DestinationListener listener, DestinationListener walk, RouteListener route) {
        return show(activity, latitude, longitude, listener, walk, route, -1);
    }

    /**
     * With walking and routes available this is the travel hub: one full-screen window with three tabs,
     * Carte (the map and the route builder), Lieux (favourites, hotspots, saved and ready-made walks)
     * and Autowalk. Opened from elsewhere just to look at a spot, it is the map alone.
     */
    public static AlertDialog show(Activity activity, double latitude, double longitude, DestinationListener listener, DestinationListener walk, RouteListener route, int tab) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)
            throw new IllegalArgumentException("Coordonnées de carte invalides");
        Context ui = MapTheme.wrap(activity); // themed chrome; the map view itself keeps the real Activity
        final boolean hub = walk != null && route != null;
        final AlertDialog[] self = {null}, cooldownDialog = {null}, searchDialog = {null}, favoriteDialog = {null};

        // The whole window: a top bar (tabs, or a title), then the pages filling the rest.
        HubRoot root = new HubRoot(activity); root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(ui); top.setGravity(Gravity.CENTER_VERTICAL); top.setTag(DialogStyle.KEEP);
        top.setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 4), dp(activity, 6));
        LinearLayout tabBar = new LinearLayout(ui); tabBar.setPadding(dp(activity, 3), dp(activity, 3), dp(activity, 3), dp(activity, 3));
        TextView[] tabs = new TextView[3];
        if (hub) {
            String[] names = {"Carte", "Lieux", "Autowalk"};
            for (int i = 0; i < tabs.length; i++) {
                TextView t = label(ui, names[i], 13); t.setGravity(Gravity.CENTER); t.setTypeface(SettingsUi.medium());
                t.setClickable(true); t.setFocusable(true); t.setSingleLine(true);
                tabs[i] = t; tabBar.addView(t, new LinearLayout.LayoutParams(0, -1, 1));
            }
            top.addView(tabBar, new LinearLayout.LayoutParams(0, dp(activity, 40), 1));
        } else {
            TextView title = label(ui, "Carte du monde", 17); title.setTypeface(SettingsUi.medium()); title.setSingleLine(true);
            title.setTextColor(SettingsUi.ink()); title.setPadding(dp(activity, 4), 0, 0, 0);
            top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        }
        TextView close = label(ui, "✕", 18); close.setGravity(Gravity.CENTER); close.setClickable(true); close.setFocusable(true);
        close.setContentDescription("Fermer"); close.setTextColor(SettingsUi.muted());
        close.setBackground(SettingsUi.ripple(SettingsUi.rounded(ui, 0x00000000, 20)));
        top.addView(close, new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));
        android.widget.FrameLayout pages = new android.widget.FrameLayout(activity);
        root.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));

        // ----- Page Carte: search, map, pin line, layers, tools, route builder, status, then the action bar.
        // The actions are part of the layout (not a dialog button bar), so the map shrinks to make room
        // for them and they can never be pushed off screen.
        ResponsiveLayout layout = new ResponsiveLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 12);
        layout.setPadding(pad, 0, pad, dp(activity, 8));
        pages.addView(layout, new android.widget.FrameLayout.LayoutParams(-1, -1));

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

        // Pin coordinates (tap: centre on the pin and copy them), then the hint (route progress in route mode).
        selected.setTypeface(SettingsUi.mono()); selected.setPadding(0, dp(activity, 6), 0, dp(activity, 2));
        selected.setClickable(true); selected.setFocusable(true); selected.setContentDescription("Centrer sur l'épingle et copier ses coordonnées");
        layout.addView(selected);
        TextView hint = label(ui, "", 11);
        layout.addView(hint);

        // Map layers on one line.
        LinearLayout layers = new LinearLayout(ui); layers.setGravity(Gravity.CENTER_VERTICAL);
        SharedPreferences preferences = AccountProfiles.preferences(activity, "pgrazor-map");
        String[] layerKeys = {"Stops", "Gyms", "Raids"}, layerNames = {"PokéStops", "Arènes", "Raids"};
        for (int i = 0; i < layerKeys.length; i++) {
            final int bit = 1 << i; CheckBox toggle = new CheckBox(ui); toggle.setText(layerNames[i]); toggle.setTextSize(12);
            boolean enabled = preferences.getBoolean(layerKeys[i], false); if (enabled) map.setPoiLayer(bit, true); toggle.setChecked(enabled);
            final String key = layerKeys[i];
            toggle.setOnCheckedChangeListener((b, on) -> { map.setPoiLayer(bit, on); preferences.edit().putBoolean(key, on).apply(); map.refreshPois(); });
            layers.addView(toggle, new LinearLayout.LayoutParams(0, dp(activity, 40), 1));
        }
        layout.addView(layers);

        // Tools: back to my position, keep the pin as a favourite, build a route.
        LinearLayout tools = new LinearLayout(ui);
        Button current = toolButton(ui, "Ma position"), save = toolButton(ui, "Enregistrer"), routeMode = toolButton(ui, "Créer une route");
        current.setContentDescription("Centrer la carte sur ta position en jeu");
        save.setContentDescription("Ajouter l'épingle à tes favoris");
        tools.addView(current, toolParams(activity)); tools.addView(save, toolParams(activity));
        if (route != null) tools.addView(routeMode, toolParams(activity));
        layout.addView(tools);
        // Route builder tools, shown only while a route is being built: stops, GPX, loop and clear.
        LinearLayout routePanel = new LinearLayout(ui); routePanel.setOrientation(LinearLayout.VERTICAL); routePanel.setVisibility(View.GONE);
        LinearLayout routeButtons = new LinearLayout(ui);
        Button add = toolButton(ui, "Ajouter un arrêt"), undo = toolButton(ui, "Annuler"), stops = toolButton(ui, "Arrêts"), gpx = toolButton(ui, "GPX");
        add.setContentDescription("Ajouter l'épingle comme prochain arrêt"); undo.setContentDescription("Annuler le dernier arrêt");
        stops.setContentDescription("Voir les arrêts dans l'ordre"); gpx.setContentDescription("Enregistrer, importer ou exporter une route GPX");
        for (Button b : new Button[]{add, undo, stops, gpx}) routeButtons.addView(b, toolParams(activity));
        routePanel.addView(routeButtons);
        LinearLayout routeTools = new LinearLayout(ui); routeTools.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox loop = new CheckBox(ui); loop.setText("Route en boucle"); loop.setTextSize(12);
        loop.setContentDescription("Répéter la route depuis le premier arrêt après le dernier");
        routeTools.addView(loop, new LinearLayout.LayoutParams(0, dp(activity, 40), 1));
        Button clearRoute = toolButton(ui, "Effacer la route");
        clearRoute.setContentDescription("Effacer tous les arrêts du brouillon de route");
        routeTools.addView(clearRoute, new LinearLayout.LayoutParams(dp(activity, 120), dp(activity, 36)));
        routePanel.addView(routeTools);
        layout.addView(routePanel);

        // Status and the tile attribution share one small line (failed tiles are retried on their own).
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
        search.setOnClickListener(v -> {
            if (searchDialog[0] != null && searchDialog[0].isShowing()) return;
            searchDialog[0] = MapSearchDialog.show(activity, MapProjection.latitude(map.centerY),
                MapProjection.longitude(map.centerX), (lat, lon) -> map.select(lat, lon));
        });
        current.setOnClickListener(v -> map.centerCurrentLocation());
        selected.setOnClickListener(v -> { map.centerSelection(); CoordinateClipboard.copy(activity, map.latitude, map.longitude, "Coordonnées de l'épingle copiées"); });

        RouteUi routes = route == null ? null : new RouteUi(activity, ui, map, routeMode, hint, routePanel, add, undo, stops, gpx, clearRoute, loop, route, walk != null);
        if (routes != null) { routes.reopen = () -> WorldMapDialog.show(activity, Control.engine.latitude, Control.engine.longitude, listener, walk, route, TAB_MAP); routes.action = teleport; routes.walkAction = walkHere; }
        else hint.setText(MAP_HINT);

        // ----- Pages Lieux and Autowalk (hub only).
        final Tabs pager = hub ? new Tabs(ui, tabs, new View[3]) : null;
        final PlacesPanel places = !hub ? null : new PlacesPanel(activity, ui, new PlacesPanel.Host() {
            public void showOnMap(double lat, double lon) { pager.select(TAB_MAP); map.select(lat, lon); }
            public void openRoute(GpxRoute chosen) { pager.select(TAB_MAP); routes.load(chosen); }
            public boolean teleport(double lat, double lon) { return listener.onDestination(lat, lon); }
            public boolean walk(double lat, double lon) { return walk.onDestination(lat, lon); }
            public boolean walkRoute(double[][] points, boolean looping) { return route.onRoute(points, looping); }
            public void done() { if (self[0] != null) self[0].dismiss(); }
        }, () -> WorldMapDialog.show(activity, Control.engine.latitude, Control.engine.longitude, listener, walk, route, TAB_PLACES));
        final AutoWalkPanel walker = !hub ? null : new AutoWalkPanel(activity, ui, () -> { if (self[0] != null) self[0].dismiss(); });
        if (hub) {
            pages.addView(places.view(), new android.widget.FrameLayout.LayoutParams(-1, -1));
            pages.addView(walker.view(), new android.widget.FrameLayout.LayoutParams(-1, -1));
            pager.pages[TAB_MAP] = layout; pager.pages[TAB_PLACES] = places.view(); pager.pages[TAB_WALK] = walker.view();
            pager.onSelect = index -> { if (index == TAB_PLACES) places.reload(); if (index == TAB_WALK) walker.resume(); else walker.pause(); };
            for (int i = 0; i < tabs.length; i++) { final int index = i; tabs[i].setOnClickListener(v -> pager.select(index)); }
        }
        save.setOnClickListener(v -> {
            if (favoriteDialog[0] != null && favoriteDialog[0].isShowing()) return;
            favoriteDialog[0] = PlacesPanel.promptSave(activity, ui, map.latitude, map.longitude, places == null ? null : places::reload);
        });

        AlertDialog dialog = new AlertDialog.Builder(ui).setView(root).create();
        self[0] = dialog;
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
        DialogLifecycle.bind(activity, dialog, () -> {
            for (AlertDialog child : new AlertDialog[]{cooldownDialog[0], searchDialog[0], favoriteDialog[0]}) if (child != null) child.dismiss();
            if (routes != null) routes.close();
            if (places != null) places.close();
            if (walker != null) walker.close();
            map.close();
        });
        dialog.setOnShowListener(d -> {
            MapTheme.fullScreen(dialog); MapTheme.style(layout);
            root.setBackgroundColor(SettingsUi.surface()); top.setBackgroundColor(SettingsUi.surface());
            tabBar.setBackground(SettingsUi.shape(ui, SettingsUi.raised(), 12, SettingsUi.border()));
            // Colours after the generic pass: quiet secondary text, accent pin line, field-like search, filled primary.
            selected.setTextColor(SettingsUi.accent()); selected.setBackground(SettingsUi.ripple(SettingsUi.rounded(ui, 0x00000000, 8)));
            for (TextView quiet : new TextView[]{hint, status, coverage}) quiet.setTextColor(SettingsUi.muted());
            attribution.setTextColor(SettingsUi.faint());
            search.setTextColor(SettingsUi.faint()); search.setBackground(SettingsUi.ripple(SettingsUi.fieldBackground(ui)));
            teleport.setTextColor(SettingsUi.accentInk()); teleport.setBackground(SettingsUi.ripple(SettingsUi.rounded(ui, SettingsUi.accentFill(), 12)));
            walkHere.setTextColor(SettingsUi.ink()); walkHere.setBackground(SettingsUi.ripple(SettingsUi.shape(ui, SettingsUi.raised(), 12, SettingsUi.border())));
            if (routes != null) routes.attach(dialog);
            if (pager != null) {
                // A route being built (GPX picked, or reopened after the file picker) always shows the map.
                boolean building = routes != null && routes.editing;
                pager.select(building ? TAB_MAP : tab >= 0 ? tab : lastTab);
            }
        });
        dialog.show();
        return dialog;
    }

    /** The hub's tab strip: one page visible at a time, the chosen tab filled with the accent. */
    private static final class Tabs {
        interface OnSelect { void selected(int index); }
        final Context ui;
        final TextView[] labels;
        final View[] pages;
        OnSelect onSelect;
        Tabs(Context ui, TextView[] labels, View[] pages) { this.ui = ui; this.labels = labels; this.pages = pages; }
        void select(int index) {
            lastTab = index;
            for (int i = 0; i < labels.length; i++) {
                boolean on = i == index;
                if (pages[i] != null) pages[i].setVisibility(on ? View.VISIBLE : View.GONE);
                labels[i].setTextColor(on ? SettingsUi.accentInk() : SettingsUi.muted());
                labels[i].setBackground(on ? SettingsUi.rounded(ui, SettingsUi.accentFill(), 10) : SettingsUi.ripple(SettingsUi.rounded(ui, 0x00000000, 10)));
                labels[i].setSelected(on);
            }
            if (onSelect != null) onSelect.selected(index);
        }
    }

    /** Always measured to the full window height, so the pages fill it and the map page can size itself. */
    private static final class HubRoot extends LinearLayout {
        HubRoot(Context c) { super(c); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int size = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? getResources().getDisplayMetrics().heightPixels : MeasureSpec.getSize(heightSpec);
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
        }
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
        final View panel;
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
        RouteUi(Activity activity,Context ui,MapView map,Button mode,TextView hint,View panel,Button add,Button undo,Button stops,Button gpx,Button clearRoute,CheckBox loop,RouteListener listener,boolean hasWalk) {
            this.activity=activity;this.ui=ui;this.map=map;this.mode=mode;this.hint=hint;this.loop=loop;this.panel=panel;
            this.add=add;this.undo=undo;this.stops=stops;this.gpx=gpx;this.clearRoute=clearRoute;this.listener=listener;this.hasWalk=hasWalk;
            clearRoute.setOnClickListener(v -> clearDraft());
            mode.setOnClickListener(v -> {editing=!editing;if(editing)maybeLoadPending();refresh();});
            add.setOnClickListener(v -> addStop());
            undo.setOnClickListener(v -> undo());
            stops.setOnClickListener(v -> showStops());
            gpx.setOnClickListener(v -> {if(editing)GpxRouteDialog.menu(activity,this::sourceRoute,this::onGpxChosen);});
        }
        void attach(AlertDialog parent) {this.parent=parent;if(pendingRoute!=null){editing=true;maybeLoadPending();}refresh();}
        void refresh() {
            if(draft.isEmpty())imported=false;
            boolean big=draft.size()>LIMIT;
            mode.setText(editing ? "Quitter la route" : "Créer une route");
            mode.setContentDescription(editing ? "Quitter le créateur de route sans démarrer" : "Ouvrir le créateur de route");
            hint.setText(!editing ? MAP_HINT
                : big ? "Route GPX : "+draft.size()+" arrêts. Touche Démarrer pour la parcourir dans l'ordre."
                : "Route : "+draft.size()+"/"+LIMIT+" arrêts. Touche la carte, puis Ajouter un arrêt.");
            panel.setVisibility(editing ? View.VISIBLE : View.GONE); // the loop toggle defaults to stopping at the last stop
            stops.setText("Arrêts ("+draft.size()+")");
            clearRoute.setEnabled(!draft.isEmpty());
            add.setEnabled(draft.size()<LIMIT);undo.setEnabled(!draft.isEmpty());
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
        /** Opens a walk in the builder to look at or change it; nothing starts until Démarrer la route. */
        void load(GpxRoute route) {
            if(closed||route==null)return;
            routeName=route.name;editing=true;
            apply(route.points());
            refresh();
            if(!draft.isEmpty()){map.pin(draft.get(0)[0],draft.get(0)[1]);map.fit(draft);}
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
        /** Gesture state: where the touch began, and the fingers' midpoint and spread on the last event. */
        private float downX, downY, focusX, focusY, spread;
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
        void centerCurrentLocation() {
            double lat=Control.engine.latitude,lon=Control.engine.longitude;
            if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)return;
            zoomScale=1;if(zoom==0)zoom=14;
            centerX=MapProjection.x(lon);centerY=MapProjection.y(lat);viewportChanged();
        }
        void centerSelection() { zoomScale=1; if (zoom == 0) zoom = 14; centerX = MapProjection.x(longitude); centerY = MapProjection.y(latitude); viewportChanged(); }
        /** Moves the pin without moving the view. */
        void pin(double latitude, double longitude) { this.latitude = latitude; this.longitude = longitude; updateSelection(); invalidate(); }
        /** Frames every point (a whole route), never closer than street level. */
        void fit(List<double[]> points) {
            if (points.isEmpty() || closed) return;
            if (getWidth() <= 0 || getHeight() <= 0) { post(() -> fit(points)); return; }
            double minX = 1, maxX = 0, minY = 1, maxY = 0;
            for (double[] point : points) {
                double x = MapProjection.x(point[1]), y = MapProjection.y(point[0]);
                minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
            double minimum = Math.max(1, Math.min(getWidth(), getHeight()));
            double next = Math.min(.8 * getWidth() / Math.max(maxX - minX, 1e-9), .8 * getHeight() / Math.max(maxY - minY, 1e-9));
            next = Math.max(minimum, Math.min(tileSize * Math.pow(2, 17), next));
            zoom = Math.max(0, Math.min(19, (int) Math.floor(Math.log(next / tileSize) / Math.log(2))));
            zoomScale = next / ((zoom == 0 ? minimum : tileSize) * (1 << zoom));
            centerX = (minX + maxX) / 2; centerY = (minY + maxY) / 2;
            viewportChanged();
        }
        void select(double latitude, double longitude) {
            this.latitude = latitude; this.longitude = longitude;
            zoomScale=1; zoom = 14; centerX = MapProjection.x(longitude); centerY = MapProjection.y(latitude);
            updateSelection(); viewportChanged();
        }
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

        /**
         * One finger drags; two fingers zoom around their midpoint and pan with it in the same gesture.
         * Every event re-reads the fingers, so lifting or adding one never makes the map jump.
         */
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    track(event, -1); downX = focusX; downY = focusY; dragged = false; multiTouch = false; return true;
                case MotionEvent.ACTION_POINTER_DOWN: multiTouch = true; track(event, -1); return true;
                case MotionEvent.ACTION_POINTER_UP: track(event, event.getActionIndex()); return true;
                case MotionEvent.ACTION_MOVE: {
                    float lastX = focusX, lastY = focusY, lastSpread = spread;
                    track(event, -1);
                    if (!dragged && (multiTouch || Math.hypot(focusX - downX, focusY - downY) > slop)) dragged = true;
                    if (!dragged) return true;
                    if (event.getPointerCount() > 1 && lastSpread > 0 && spread > 0) moveAndZoom(lastX, lastY, focusX, focusY, spread / lastSpread);
                    else if (zoom != 0 || zoomScale > 1) moveAndZoom(lastX, lastY, focusX, focusY, 1);
                    return true;
                }
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
        /** Midpoint and mean distance from it of the fingers still down, leaving out {@code skip} (a finger lifting). */
        private void track(MotionEvent event, int skip) {
            float sumX = 0, sumY = 0; int count = 0;
            for (int i = 0; i < event.getPointerCount(); i++) if (i != skip) { sumX += event.getX(i); sumY += event.getY(i); count++; }
            if (count == 0) return;
            focusX = sumX / count; focusY = sumY / count;
            float distance = 0;
            for (int i = 0; i < event.getPointerCount(); i++) if (i != skip) distance += (float) Math.hypot(event.getX(i) - focusX, event.getY(i) - focusY);
            spread = count > 1 ? distance / count : 0;
        }
        /** Keeps the map point that was under (fromX, fromY) under (toX, toY), scaled by {@code factor}. */
        private void moveAndZoom(float fromX, float fromY, float toX, float toY, double factor) {
            double old = world(), minimum = Math.max(1, Math.min(getWidth(), getHeight()));
            double next = Math.max(minimum, Math.min(tileSize * Math.pow(2, 19), old * factor));
            double anchorX = MapProjection.offsetX(centerX, fromX - getWidth() / 2.0, old);
            double anchorY = MapProjection.offsetY(centerY, fromY - getHeight() / 2.0, old);
            if (next != old) {
                zoom = Math.max(0, Math.min(19, (int) Math.floor(Math.log(next / tileSize) / Math.log(2))));
                zoomScale = next / ((zoom == 0 ? minimum : tileSize) * (1 << zoom));
            }
            centerX = MapProjection.offsetX(anchorX, -(toX - getWidth() / 2.0), next);
            centerY = MapProjection.offsetY(anchorY, -(toY - getHeight() / 2.0), next);
            viewportChanged();
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
