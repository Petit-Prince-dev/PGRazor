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
import android.view.inputmethod.InputMethodManager;
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
    public interface DestinationListener {
        /** Return true after applying the destination, or false to keep the picker open. */
        boolean onDestination(double latitude, double longitude);
    }
    interface LongPress { void at(double latitude, double longitude); }
    /** A touch on the map: the point, and what is there ("PokéStop", "Arène", "Raid") or null for a plain spot. */
    interface Pick { void at(double latitude, double longitude, String kind); }
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
     * Carte (the map; a long press adds a route point), Lieux (favourites, hotspots, saved and ready-made walks)
     * and Autowalk. Opened from elsewhere just to look at a spot, it is the map alone.
     */
    public static AlertDialog show(Activity activity, double latitude, double longitude, DestinationListener listener, DestinationListener walk, RouteListener route, int tab) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)
            throw new IllegalArgumentException("Coordonnées de carte invalides");
        Context ui = MapTheme.wrap(activity); // themed chrome; the map view itself keeps the real Activity
        final boolean hub = walk != null && route != null;
        final AlertDialog[] self = {null}, cooldownDialog = {null}, searchDialog = {null};

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

        // ----- Page Carte: search, the map (with its card and the ◎ button on it), layers, route bar, status.
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

        TextView status = label(ui, "Chargement de la carte…", 11);
        MapView map = new MapView(activity, latitude, longitude, status);
        // The map, with on top of it the ◎ button (back to the player) and the card of the touched point.
        android.widget.FrameLayout mapFrame = new android.widget.FrameLayout(activity);
        mapFrame.addView(map, new android.widget.FrameLayout.LayoutParams(-1, -1));
        TextView current = label(ui, "◎", 20); current.setGravity(Gravity.CENTER); current.setTag(DialogStyle.KEEP);
        current.setClickable(true); current.setFocusable(true); current.setContentDescription("Centrer la carte sur ta position en jeu");
        android.widget.FrameLayout.LayoutParams currentParams = new android.widget.FrameLayout.LayoutParams(dp(activity, 42), dp(activity, 42), Gravity.TOP | Gravity.END);
        currentParams.setMargins(0, dp(activity, 10), dp(activity, 10), 0);
        mapFrame.addView(current, currentParams);
        SpotCard card = new SpotCard(activity, ui, walk != null);
        mapFrame.addView(card.view, card.params());
        layout.map = mapFrame;
        // The map fills the space left by the controls (see ResponsiveLayout); this is only a baseline height.
        layout.addView(mapFrame, new LinearLayout.LayoutParams(-1, dp(activity, 240)));
        TextView hint = label(ui, "", 11); hint.setPadding(0, dp(activity, 6), 0, 0);
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

        // The route drawn with long presses: what it is, then undo, save and walk it.
        LinearLayout routeBar = new LinearLayout(ui); routeBar.setOrientation(LinearLayout.VERTICAL); routeBar.setTag(DialogStyle.KEEP);
        routeBar.setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 10), dp(activity, 10)); routeBar.setVisibility(View.GONE);
        LinearLayout routeHead = new LinearLayout(ui); routeHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView routeSummary = label(ui, "", 13); routeSummary.setTypeface(SettingsUi.medium()); routeSummary.setSingleLine(true);
        routeSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        routeHead.addView(routeSummary, new LinearLayout.LayoutParams(0, -2, 1));
        TextView clearRoute = chip(ui, "Effacer"); clearRoute.setContentDescription("Effacer tous les points de la route");
        routeHead.addView(clearRoute);
        routeBar.addView(routeHead);
        Button undo = HubUi.button(ui, "↶ Annuler", false), saveRoute = HubUi.button(ui, "Enregistrer", false), startRoute = HubUi.button(ui, "Démarrer", true);
        undo.setContentDescription("Retirer le dernier point");
        LinearLayout routeButtons = HubUi.actions(ui, undo, saveRoute, startRoute); routeButtons.setPadding(0, dp(activity, 6), 0, 0);
        routeBar.addView(routeButtons);
        LinearLayout.LayoutParams routeBarParams = new LinearLayout.LayoutParams(-1, -2); routeBarParams.topMargin = dp(activity, 4); routeBarParams.bottomMargin = dp(activity, 4);
        layout.addView(routeBar, routeBarParams);

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

        map.refreshPois();
        map.onPick = card::show;
        search.setOnClickListener(v -> {
            if (searchDialog[0] != null && searchDialog[0].isShowing()) return;
            searchDialog[0] = MapSearchDialog.show(activity, MapProjection.latitude(map.centerY),
                MapProjection.longitude(map.centerX), (lat, lon) -> { map.select(lat, lon); card.show(lat, lon, null); });
        });
        current.setOnClickListener(v -> map.centerCurrentLocation());

        RouteDraft draft = route == null ? null : new RouteDraft(activity, ui, map, routeBar, routeSummary, undo, saveRoute, startRoute, clearRoute, route);
        if (draft != null) map.onLongPress = draft::add;
        hint.setText(draft == null ? MAP_HINT : ROUTE_HINT);

        // ----- Pages Lieux and Autowalk (hub only).
        final Tabs pager = hub ? new Tabs(ui, tabs, new View[3]) : null;
        final PlacesPanel places = !hub ? null : new PlacesPanel(activity, ui, new PlacesPanel.Host() {
            public void showOnMap(double lat, double lon) { pager.select(TAB_MAP); map.select(lat, lon); card.show(lat, lon, null); }
            public void openRoute(GpxRoute chosen, boolean stored) { pager.select(TAB_MAP); draft.load(chosen, stored); }
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

        AlertDialog dialog = new AlertDialog.Builder(ui).setView(root).create();
        self[0] = dialog;
        if (draft != null) { draft.parent = dialog; draft.saved = places == null ? null : places::reload; }
        close.setOnClickListener(v -> dialog.dismiss());
        card.saved = places == null ? null : places::reload;
        card.teleport.setOnClickListener(v -> {
            if (cooldownDialog[0] != null && cooldownDialog[0].isShowing()) return;
            cooldownDialog[0] = TeleportDialog.confirm(activity, card.latitude, card.longitude, (lat, lon) -> {
                if (!listener.onDestination(lat, lon)) return false;
                dialog.dismiss(); return true;
            });
        });
        if (walk != null) card.walk.setOnClickListener(v -> { if (walk.onDestination(card.latitude, card.longitude)) dialog.dismiss(); });
        // Opened just to look at a spot (not the hub), its card is up from the start.
        if (!hub) card.show(latitude, longitude, null);
        DialogLifecycle.bind(activity, dialog, () -> {
            for (AlertDialog child : new AlertDialog[]{cooldownDialog[0], searchDialog[0]}) if (child != null) child.dismiss();
            card.close();
            if (draft != null) draft.close();
            if (places != null) places.close();
            if (walker != null) walker.close();
            map.close();
        });
        dialog.setOnShowListener(d -> {
            MapTheme.fullScreen(dialog); MapTheme.style(layout);
            root.setBackgroundColor(SettingsUi.surface()); top.setBackgroundColor(SettingsUi.surface());
            tabBar.setBackground(SettingsUi.shape(ui, SettingsUi.raised(), 12, SettingsUi.border()));
            // Colours after the generic pass: quiet secondary text, field-like search, the map's own controls.
            for (TextView quiet : new TextView[]{hint, status, coverage}) quiet.setTextColor(SettingsUi.muted());
            attribution.setTextColor(SettingsUi.faint());
            search.setTextColor(SettingsUi.faint()); search.setBackground(SettingsUi.ripple(SettingsUi.fieldBackground(ui)));
            current.setTextColor(SettingsUi.ink()); current.setBackground(SettingsUi.ripple(SettingsUi.shape(ui, SettingsUi.surface(), 21, SettingsUi.border())));
            current.setElevation(dp(activity, 3));
            card.paint();
            paintChip(ui, clearRoute);
            routeBar.setBackground(SettingsUi.cardBackground(ui)); routeSummary.setTextColor(SettingsUi.ink());
            HubUi.paint(undo, false); HubUi.paint(saveRoute, false); HubUi.paint(startRoute, true);
            if (pager != null) pager.select(tab >= 0 ? tab : lastTab);
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

    static final String MAP_HINT = "Touche un point pour ses actions · glisse pour bouger · pince pour zoomer.";
    static final String ROUTE_HINT = "Touche : actions du point · appui long : point de route · pince : zoom";

    /**
     * The card on the map for the point just touched: what it is (PokéStop, Arène, Raid, or a plain spot with
     * its coordinates), how far, then Marcher ici and Téléporter. A plain spot also offers ☆ Favori, which turns
     * the card into a name field saved straight to the favourites (the card then sits at the top of the map, clear
     * of the keyboard). Hidden until a touch; ✕ hides it.
     */
    private static final class SpotCard {
        final Activity activity;
        final Context ui;
        final LinearLayout view, buttons, form;
        final TextView title, detail, dismiss;
        final Button favorite, walk, teleport, cancel, save;
        final EditText name;
        final boolean canWalk;
        final Handler main = new Handler(Looper.getMainLooper());
        double latitude, longitude;
        /** Runs after a favourite is saved, so Lieux lists it. */
        Runnable saved;
        private boolean closed;

        SpotCard(Activity activity, Context ui, boolean canWalk) {
            this.activity = activity; this.ui = ui; this.canWalk = canWalk;
            view = new LinearLayout(ui); view.setOrientation(LinearLayout.VERTICAL); view.setTag(DialogStyle.KEEP);
            view.setPadding(dp(ui, 14), dp(ui, 10), dp(ui, 6), dp(ui, 12)); view.setClickable(true); view.setVisibility(View.GONE);
            LinearLayout head = new LinearLayout(ui); head.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout texts = new LinearLayout(ui); texts.setOrientation(LinearLayout.VERTICAL);
            title = label(ui, "", 16); title.setTypeface(SettingsUi.medium()); title.setSingleLine(true);
            detail = label(ui, "", 12); detail.setSingleLine(true); detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            detail.setClickable(true); detail.setContentDescription("Copier les coordonnées");
            detail.setOnClickListener(v -> CoordinateClipboard.copy(activity, latitude, longitude, "Coordonnées copiées"));
            texts.addView(title); texts.addView(detail);
            head.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            dismiss = label(ui, "✕", 16); dismiss.setGravity(Gravity.CENTER); dismiss.setClickable(true); dismiss.setContentDescription("Fermer");
            dismiss.setOnClickListener(v -> hide());
            head.addView(dismiss, new LinearLayout.LayoutParams(dp(ui, 40), dp(ui, 40)));
            view.addView(head);

            favorite = HubUi.button(ui, "☆ Favori", false); walk = HubUi.button(ui, "Marcher ici", false); teleport = HubUi.button(ui, "Téléporter", true);
            buttons = HubUi.actions(ui, favorite, walk, teleport); buttons.setPadding(0, dp(ui, 8), dp(ui, 8), 0);
            walk.setVisibility(canWalk ? View.VISIBLE : View.GONE);
            favorite.setOnClickListener(v -> edit());
            view.addView(buttons);

            form = new LinearLayout(ui); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(0, dp(ui, 8), dp(ui, 8), 0); form.setVisibility(View.GONE);
            name = new EditText(ui); name.setHint("Nom du favori"); name.setSingleLine(true); name.setTextSize(14);
            name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            name.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(SavedPlaces.NAME_LIMIT)});
            name.setPadding(dp(ui, 12), 0, dp(ui, 12), 0);
            name.setOnEditorActionListener((v, action, event) -> { save(); return true; });
            form.addView(name, new LinearLayout.LayoutParams(-1, dp(ui, 44)));
            cancel = HubUi.button(ui, "Annuler", false); save = HubUi.button(ui, "Enregistrer", true);
            LinearLayout formButtons = HubUi.actions(ui, cancel, save); formButtons.setPadding(0, dp(ui, 8), 0, 0);
            cancel.setOnClickListener(v -> browse());
            save.setOnClickListener(v -> save());
            form.addView(formButtons);
            view.addView(form);
        }

        android.widget.FrameLayout.LayoutParams params() {
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            lp.setMargins(dp(ui, 8), dp(ui, 8), dp(ui, 8), dp(ui, 8));
            return lp;
        }

        /** Shows the card for a touched point; {@code kind} names a PokéStop, Arène or Raid, null for a plain spot. */
        void show(double latitude, double longitude, String kind) {
            if (closed) return;
            this.latitude = latitude; this.longitude = longitude;
            title.setText(kind == null ? "Point choisi" : kind);
            String away = HubUi.away(latitude, longitude);
            detail.setText(String.format(Locale.US, "%.5f, %.5f", latitude, longitude) + (away.isEmpty() ? "" : " · " + away));
            favorite.setVisibility(kind == null ? View.VISIBLE : View.GONE);
            browse();
            view.setVisibility(View.VISIBLE);
        }

        void hide() { browse(); view.setVisibility(View.GONE); }

        /** Back to the actions: the name field closes and the card returns to the bottom of the map. */
        private void browse() {
            keyboard(false);
            form.setVisibility(View.GONE); buttons.setVisibility(View.VISIBLE); name.setError(null); save.setEnabled(true);
            place(Gravity.BOTTOM);
        }

        private void edit() {
            buttons.setVisibility(View.GONE); form.setVisibility(View.VISIBLE);
            name.setText(""); place(Gravity.TOP);
            name.requestFocus(); keyboard(true);
        }

        private void save() {
            if (form.getVisibility() != View.VISIBLE || !save.isEnabled()) return;
            final SavedPlaces.Entry entry;
            try { entry = new SavedPlaces.Entry(name.getText().toString(), latitude, longitude); }
            catch (IllegalArgumentException e) { name.setError(e.getMessage()); return; }
            save.setEnabled(false);
            new Thread(() -> {
                String error = null;
                try { new SavedPlaces(activity).add(entry); }
                catch (IOException | RuntimeException e) { error = e.getMessage() == null ? "Impossible d'enregistrer ce lieu." : e.getMessage(); }
                final String failed = error;
                main.post(() -> {
                    if (closed) return;
                    if (failed != null) { save.setEnabled(true); name.setError(failed); return; }
                    Toast.makeText(activity, "Ajouté aux favoris : " + entry.name, Toast.LENGTH_SHORT).show();
                    title.setText("☆ " + entry.name); favorite.setVisibility(View.GONE);
                    browse();
                    if (saved != null) saved.run();
                });
            }, "pgrazor-favorite-save").start();
        }

        private void place(int gravity) {
            android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) view.getLayoutParams();
            if (lp != null && lp.gravity != gravity) { lp.gravity = gravity; view.setLayoutParams(lp); }
        }

        private void keyboard(boolean open) {
            InputMethodManager input = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (input == null) return;
            if (open) name.post(() -> input.showSoftInput(name, InputMethodManager.SHOW_IMPLICIT));
            else input.hideSoftInputFromWindow(name.getWindowToken(), 0);
        }

        void paint() {
            view.setBackground(SettingsUi.cardBackground(ui)); view.setElevation(dp(ui, 6));
            title.setTextColor(SettingsUi.ink()); detail.setTextColor(SettingsUi.muted()); dismiss.setTextColor(SettingsUi.muted());
            dismiss.setBackground(SettingsUi.ripple(SettingsUi.rounded(ui, 0x00000000, 20)));
            name.setTextColor(SettingsUi.ink()); name.setHintTextColor(SettingsUi.faint()); name.setBackground(SettingsUi.fieldBackground(ui));
            HubUi.paint(favorite, false); HubUi.paint(walk, false); HubUi.paint(teleport, true);
            HubUi.paint(cancel, false); HubUi.paint(save, true);
        }

        void close() { closed = true; main.removeCallbacksAndMessages(null); }
    }

    /** A small accent text button on the map page (Effacer). */
    private static TextView chip(Context c, String text) {
        TextView t = label(c, text, 12); t.setTypeface(SettingsUi.medium()); t.setSingleLine(true); t.setGravity(Gravity.CENTER);
        t.setClickable(true); t.setFocusable(true); t.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        return t;
    }
    private static void paintChip(Context c, TextView chip) {
        chip.setTextColor(SettingsUi.accent()); chip.setBackground(SettingsUi.ripple(SettingsUi.rounded(c, 0x00000000, 8)));
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

    /**
     * The route drawn on the map: each long press adds a point. The bar under the map takes the last
     * point back, saves the route (it then shows in Lieux, Mes trajets) or walks it in order.
     */
    private static final class RouteDraft {
        final Activity activity;
        final Context ui;
        final MapView map;
        final View bar;
        final TextView summary;
        final Button undo, save, start;
        final RouteListener listener;
        final List<double[]> points = new ArrayList<>();
        AlertDialog parent, saveDialog;
        /** Runs after a save, so Lieux shows the new walk. */
        Runnable saved;
        /** Name of the walk opened from Lieux, offered again when saving. */
        String name;
        /** Something to save: false right after a saved walk is opened or this one is saved. */
        boolean changed;
        RouteDraft(Activity activity, Context ui, MapView map, View bar, TextView summary, Button undo, Button save, Button start, TextView clear, RouteListener listener) {
            this.activity = activity; this.ui = ui; this.map = map; this.bar = bar; this.summary = summary;
            this.undo = undo; this.save = save; this.start = start; this.listener = listener;
            undo.setOnClickListener(v -> undo());
            clear.setOnClickListener(v -> { points.clear(); name = null; changed = false; refresh(); });
            save.setOnClickListener(v -> save());
            start.setOnClickListener(v -> start());
            refresh();
        }
        void add(double latitude, double longitude) {
            if (points.size() >= GpxCodec.MAX_POINTS) { Toast.makeText(activity, "Une route peut avoir au plus " + GpxCodec.MAX_POINTS + " points.", Toast.LENGTH_SHORT).show(); return; }
            points.add(new double[]{latitude, longitude}); changed = true; refresh();
        }
        void undo() {
            if (points.isEmpty()) return;
            points.remove(points.size() - 1); changed = true;
            if (points.isEmpty()) { name = null; changed = false; }
            refresh();
        }
        /** Shows a walk from Lieux on the map, framed whole; {@code stored} when it is already one of Mes trajets. */
        void load(GpxRoute route, boolean stored) {
            points.clear();
            for (double[] point : route.points()) points.add(point);
            name = route.name; changed = !stored; refresh();
            if (!points.isEmpty()) { map.pin(points.get(0)[0], points.get(0)[1]); map.fit(points); }
        }
        void refresh() {
            bar.setVisibility(points.isEmpty() ? View.GONE : View.VISIBLE);
            double meters = 0;
            for (int i = 1; i < points.size(); i++) meters += RouteStart.meters(points.get(i - 1)[0], points.get(i - 1)[1], points.get(i)[0], points.get(i)[1]);
            summary.setText((name == null ? "Route" : name) + " · " + points.size() + (points.size() == 1 ? " point" : " points")
                + (points.size() > 1 ? " · " + HubUi.length(meters) : ""));
            save.setEnabled(changed); save.setAlpha(changed ? 1f : .5f); save.setText(changed ? "Enregistrer" : "Enregistrée");
            map.setRoute(points);
        }
        void save() {
            if (points.isEmpty() || (saveDialog != null && saveDialog.isShowing())) return;
            saveDialog = PlacesPanel.promptSaveRoute(activity, ui, toArray(), name, savedName -> {
                name = savedName; changed = false; refresh();
                if (saved != null) saved.run();
            });
        }
        void start() {
            if (points.isEmpty() || !listener.onRoute(toArray(), false)) return;
            Toast.makeText(activity, "En route : " + points.size() + (points.size() == 1 ? " point" : " points"), Toast.LENGTH_SHORT).show();
            if (parent != null) parent.dismiss();
        }
        private double[][] toArray() {
            double[][] copy = new double[points.size()][];
            for (int i = 0; i < copy.length; i++) copy[i] = new double[]{points.get(i)[0], points.get(i)[1]};
            return copy;
        }
        void close() { if (saveDialog != null) saveDialog.dismiss(); }
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
        private boolean dragged, multiTouch, longPressed;
        /** Set by the hub: a finger held still on the map adds a route point there. */
        LongPress onLongPress;
        /** A tap: on a marker it picks that PokéStop/Arène/Raid, elsewhere the spot under the finger. */
        Pick onPick;
        private final Runnable longPress = new Runnable() {
            public void run() {
                if (closed || dragged || multiTouch || onLongPress == null) return;
                boolean insideWorld = zoom != 0 || (Math.abs(downX - getWidth() / 2.0) <= world() / 2 && Math.abs(downY - getHeight() / 2.0) <= world() / 2);
                if (!insideWorld) return;
                longPressed = true;
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                onLongPress.at(MapProjection.latitude(MapProjection.offsetY(centerY, downY - getHeight() / 2.0, world())),
                    MapProjection.longitude(MapProjection.offsetX(centerX, downX - getWidth() / 2.0, world())));
            }
        };
        private final Runnable locationRefresh = new Runnable() {
            public void run() {
                if(closed) return;
                invalidate(); // Refresh the live player marker without requesting new tiles.
                postDelayed(this, 250);
            }
        };

        MapView(Activity activity, double latitude, double longitude, TextView status) {
            super(activity);
            this.latitude = latitude; this.longitude = longitude;
            centerX = MapProjection.x(longitude); centerY = MapProjection.y(latitude);
            tileSize = dp(activity, 256); slop = ViewConfiguration.get(activity).getScaledTouchSlop();
            tiles = new MapTileSource(new MapTileSource.Host() {
                public int zoom() { return zoom; }
                public void onTilesChanged() { invalidate(); }
            }, status, new File(activity.getCacheDir(), "pgrazor-map-tiles-v1"));
            poiLayer = new MapPoiLayer(activity, new MapPoiLayer.Projector() {
                public float screenX(double lon) { return projectX(lon); }
                public float screenY(double lat) { return projectY(lat); }
                public int width() { return getWidth(); }
                public int height() { return getHeight(); }
            }, new MapPoiLayer.Snapshots() {
                public void setOpen(boolean open) { Control.setMapPoiOpen(open); }
                public String read() { return Control.mapPoiSnapshot(); }
            }, this::invalidate);
            setContentDescription("Carte du monde. Le point bleu est ta position actuelle ; l'épingle rouge est le point choisi. Glisse pour te déplacer, pince pour zoomer, touche un point pour ses actions.");
        }

        void setPoiLayer(int bit, boolean on) { poiLayer.setLayer(bit, on); }
        void setPoiStatus(TextView view) { poiLayer.setStatusView(view); }
        void refreshPois() { poiLayer.refresh(); }

        private double displayedTileSize() { return (zoom == 0 ? Math.max(1, Math.min(getWidth(), getHeight())) : tileSize)*zoomScale; }
        private double world() { return MapProjection.worldPixels(zoom, displayedTileSize()); }
        void zoom(int amount) { int next = Math.max(0, Math.min(19, zoom + amount)); if (next != zoom) { zoom = next; viewportChanged(); } }
        void centerCurrentLocation() {
            double lat=Control.engine.latitude,lon=Control.engine.longitude;
            if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)return;
            zoomScale=1;if(zoom==0)zoom=14;
            centerX=MapProjection.x(lon);centerY=MapProjection.y(lat);viewportChanged();
        }
        /** Moves the pin without moving the view. */
        void pin(double latitude, double longitude) { this.latitude = latitude; this.longitude = longitude; invalidate(); }
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
            viewportChanged();
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
                    track(event, -1); downX = focusX; downY = focusY; dragged = false; multiTouch = false; longPressed = false;
                    removeCallbacks(longPress);
                    if (onLongPress != null) postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN: multiTouch = true; removeCallbacks(longPress); track(event, -1); return true;
                case MotionEvent.ACTION_POINTER_UP: track(event, event.getActionIndex()); return true;
                case MotionEvent.ACTION_MOVE: {
                    float lastX = focusX, lastY = focusY, lastSpread = spread;
                    track(event, -1);
                    if (!dragged && (multiTouch || Math.hypot(focusX - downX, focusY - downY) > slop)) { dragged = true; removeCallbacks(longPress); }
                    if (!dragged) return true;
                    if (event.getPointerCount() > 1 && lastSpread > 0 && spread > 0) moveAndZoom(lastX, lastY, focusX, focusY, spread / lastSpread);
                    else if (zoom != 0 || zoomScale > 1) moveAndZoom(lastX, lastY, focusX, focusY, 1);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    removeCallbacks(longPress);
                    if (longPressed) { getParent().requestDisallowInterceptTouchEvent(false); return true; }
                    boolean insideWorld = zoom != 0 || (Math.abs(event.getX() - getWidth() / 2.0) <= world() / 2 && Math.abs(event.getY() - getHeight() / 2.0) <= world() / 2);
                    if (!dragged && !multiTouch) {
                        // A tap only picks a point (a marker first); moving there needs a button on the card.
                        List<JSONObject> hits = poiLayer.hasLayers() ? poiLayer.hits(event.getX(), event.getY()) : Collections.<JSONObject>emptyList();
                        JSONObject marker = hits.isEmpty() ? null : hits.get(0);
                        double lat = marker == null ? Double.NaN : marker.optDouble("lat", Double.NaN), lon = marker == null ? Double.NaN : marker.optDouble("lon", Double.NaN);
                        if (marker != null && Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180) {
                            latitude = lat; longitude = lon; invalidate(); performClick();
                            if (onPick != null) onPick.at(lat, lon, MapPoiLayer.kind(marker));
                        } else if (insideWorld) {
                            longitude = MapProjection.longitude(MapProjection.offsetX(centerX, event.getX() - getWidth() / 2.0, world()));
                            latitude = MapProjection.latitude(MapProjection.offsetY(centerY, event.getY() - getHeight() / 2.0, world()));
                            invalidate(); performClick();
                            if (onPick != null) onPick.at(latitude, longitude, null);
                        }
                    }
                    getParent().requestDisallowInterceptTouchEvent(false); return true;
                case MotionEvent.ACTION_CANCEL: removeCallbacks(longPress); getParent().requestDisallowInterceptTouchEvent(false); return true;
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
            removeCallbacks(locationRefresh); removeCallbacks(longPress);
            if (closed) return;
            closed = true;
            poiLayer.close();
            tiles.close();
        }
        protected void onDetachedFromWindow() { close(); super.onDetachedFromWindow(); }
    }
}
