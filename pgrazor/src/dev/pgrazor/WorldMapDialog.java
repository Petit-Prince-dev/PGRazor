package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Rect;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
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
    /** A finger held still on the map, at that point (a marker's own point when on one). */
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

    /** What {@link #show} opens over the map: nothing, Lieux or Autowalk. */
    public static final int TAB_MAP = 0, TAB_PLACES = 1, TAB_WALK = 2;

    public static AlertDialog show(Activity activity, double latitude, double longitude, DestinationListener listener, DestinationListener walk, RouteListener route) {
        return show(activity, latitude, longitude, listener, walk, route, TAB_MAP);
    }

    /**
     * The world map, edge to edge, with everything floating on it: the search and ✕ at the top, Calques (and, in
     * the travel hub, Lieux and Autowalk) on the right, ◎ and the route bar at the bottom. A touch opens the
     * point's bubble; a finger held on the map starts a route, and every touch then adds a point. Lieux and
     * Autowalk open as panels over the map. Opened from elsewhere just to look at a spot, it has no panels.
     */
    public static AlertDialog show(Activity activity, double latitude, double longitude, DestinationListener listener, DestinationListener walk, RouteListener route, int tab) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)
            throw new IllegalArgumentException("Coordonnées de carte invalides");
        Context ui = MapTheme.wrap(activity); // themed chrome; the map view itself keeps the real Activity
        final boolean hub = walk != null && route != null;
        final AlertDialog[] self = {null};

        FullRoot root = new FullRoot(activity);
        TextView status = label(ui, "", 12);
        MapView map = new MapView(activity, latitude, longitude, status);
        root.addView(map, new android.widget.FrameLayout.LayoutParams(-1, -1));

        // ----- The controls on the map (kept clear of a display cutout by FullRoot).
        android.widget.FrameLayout controls = new android.widget.FrameLayout(activity);
        root.controls = controls; root.safe(0, 0, 0, 0);
        root.addView(controls, new android.widget.FrameLayout.LayoutParams(-1, -1));
        LinearLayout topRow = new LinearLayout(ui); topRow.setGravity(Gravity.CENTER_VERTICAL);
        // The search field takes the start of this row once it exists (see MapSearchBar below).
        TextView close = floating(ui, "✕", 17); close.setContentDescription("Fermer la carte");
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)); closeParams.leftMargin = dp(activity, 8);
        topRow.addView(close, closeParams);
        controls.addView(topRow, new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // On the right, under the search: Calques (its list unfolds under it), then Lieux and Autowalk.
        LinearLayout side = new LinearLayout(ui); side.setOrientation(LinearLayout.VERTICAL); side.setGravity(Gravity.END);
        android.widget.FrameLayout.LayoutParams sideParams = new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        sideParams.topMargin = dp(activity, 54);
        controls.addView(side, sideParams);
        LinearLayout layersButton = pill(ui, MenuIcon.MAP, "Calques");
        side.addView(layersButton, pillParams(ui, 0));
        LinearLayout layersCard = new LinearLayout(ui); layersCard.setOrientation(LinearLayout.VERTICAL); layersCard.setTag(DialogStyle.KEEP);
        layersCard.setPadding(dp(activity, 6), dp(activity, 12), dp(activity, 6), dp(activity, 6)); layersCard.setClickable(true); layersCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dp(activity, 230), -2); cardParams.topMargin = dp(activity, 8);
        side.addView(layersCard, cardParams);
        TextView layersHead = label(ui, "AFFICHER SUR LA CARTE", 11); layersHead.setTypeface(SettingsUi.medium()); layersHead.setLetterSpacing(0.08f);
        layersHead.setPadding(dp(activity, 12), 0, dp(activity, 12), dp(activity, 6));
        layersCard.addView(layersHead);
        // One row per layer: the game's own marker, its name, and a switch.
        SharedPreferences preferences = AccountProfiles.preferences(activity, "pgrazor-map");
        String[] layerKeys = {"Stops", "Gyms", "Raids"}, layerNames = {"PokéStops", "Arènes", "Raids"};
        String[] layerIcons = {"stop.png", "gym.png", "raid-legendary.png"};
        for (int i = 0; i < layerKeys.length; i++) {
            final int bit = 1 << i; final String key = layerKeys[i];
            LinearLayout row = new LinearLayout(ui); row.setGravity(Gravity.CENTER_VERTICAL); row.setClickable(true); row.setFocusable(true);
            row.setPadding(dp(activity, 10), 0, dp(activity, 12), 0);
            row.setBackground(SettingsUi.ripple(SettingsUi.rounded(ui, 0x00000000, 12)));
            android.widget.ImageView icon = new android.widget.ImageView(ui); icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            try (java.io.InputStream in = PgAssets.open(activity, "pgrazor/map-icons/" + layerIcons[i])) { icon.setImageBitmap(android.graphics.BitmapFactory.decodeStream(in)); }
            catch (IOException | RuntimeException missing) { icon.setVisibility(View.INVISIBLE); }
            row.addView(icon, new LinearLayout.LayoutParams(dp(activity, 26), dp(activity, 26)));
            TextView name = label(ui, layerNames[i], 15); name.setTypeface(SettingsUi.medium()); name.setTextColor(SettingsUi.ink());
            name.setPadding(dp(activity, 12), 0, dp(activity, 8), 0);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            Switch toggle = new Switch(ui, preferences.getBoolean(key, false));
            row.addView(toggle, new LinearLayout.LayoutParams(dp(activity, 38), dp(activity, 22)));
            if (toggle.on) map.setPoiLayer(bit, true);
            row.setContentDescription(layerNames[i]);
            row.setOnClickListener(v -> {
                boolean on = !toggle.on; toggle.set(on);
                map.setPoiLayer(bit, on); preferences.edit().putBoolean(key, on).apply(); map.refreshPois();
            });
            layersCard.addView(row, new LinearLayout.LayoutParams(-1, dp(activity, 48)));
        }
        LinearLayout placesButton = pill(ui, MenuIcon.FAVORITES, "Lieux"), walkButton = pill(ui, MenuIcon.AUTOWALK, "Autowalk");
        if (hub) { side.addView(placesButton, pillParams(ui, 8)); side.addView(walkButton, pillParams(ui, 8)); }
        layersButton.setOnClickListener(v -> {
            boolean open = layersCard.getVisibility() != View.VISIBLE;
            layersCard.setVisibility(open ? View.VISIBLE : View.GONE); paintFloating(ui, layersButton, open);
        });
        final Runnable closeLayers = () -> { if (layersCard.getVisibility() == View.VISIBLE) { layersCard.setVisibility(View.GONE); paintFloating(ui, layersButton, false); } };

        // At the bottom: ◎ on the right, a map problem when there is one, then the route bar while drawing a route.
        LinearLayout bottom = new LinearLayout(ui); bottom.setOrientation(LinearLayout.VERTICAL); bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        controls.addView(bottom, new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        TextView current = floating(ui, "◎", 20); current.setContentDescription("Centrer la carte sur ta position en jeu");
        LinearLayout.LayoutParams currentParams = new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)); currentParams.gravity = Gravity.END;
        currentParams.bottomMargin = dp(activity, 10);
        bottom.addView(current, currentParams);
        status.setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8)); status.setVisibility(View.GONE);
        status.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            public void onTextChanged(CharSequence text, int start, int before, int count) {}
            public void afterTextChanged(android.text.Editable text) { status.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE); }
        });
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-2, -2); statusParams.bottomMargin = dp(activity, 8);
        bottom.addView(status, statusParams);
        LinearLayout routeBar = new LinearLayout(ui); routeBar.setOrientation(LinearLayout.VERTICAL); routeBar.setTag(DialogStyle.KEEP);
        routeBar.setPadding(dp(activity, 14), dp(activity, 6), dp(activity, 6), dp(activity, 12)); routeBar.setVisibility(View.GONE);
        routeBar.setClickable(true);
        LinearLayout routeHead = new LinearLayout(ui); routeHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView routeSummary = label(ui, "", 13); routeSummary.setTypeface(SettingsUi.medium()); routeSummary.setSingleLine(true);
        routeSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        routeHead.addView(routeSummary, new LinearLayout.LayoutParams(0, -2, 1));
        TextView exitRoute = label(ui, "✕", 16); exitRoute.setGravity(Gravity.CENTER); exitRoute.setClickable(true);
        exitRoute.setContentDescription("Fermer le trajet");
        routeHead.addView(exitRoute, new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40)));
        routeBar.addView(routeHead);
        Button undo = HubUi.button(ui, "↶ Annuler", false), saveRoute = HubUi.button(ui, "Enregistrer", false), startRoute = HubUi.button(ui, "Démarrer", true);
        undo.setContentDescription("Retirer le dernier point");
        LinearLayout routeButtons = HubUi.actions(ui, undo, saveRoute, startRoute); routeButtons.setPadding(0, dp(activity, 6), dp(activity, 8), 0);
        routeBar.addView(routeButtons);
        bottom.addView(routeBar, new LinearLayout.LayoutParams(-1, -2));

        // The bubble shares the map's coordinates, so it sits on the root, above the controls.
        Bubble bubble = new Bubble(activity, ui, map, walk != null);
        root.addView(bubble.view, new android.widget.FrameLayout.LayoutParams(dp(activity, 288), -2));

        // Two modes: exploring (a touch opens the bubble) and drawing a route, started by holding a finger on the
        // map; every touch then adds a point until ✕ closes the route.
        RouteDraft draft = route == null ? null : new RouteDraft(activity, ui, map, routeSummary, routeButtons, undo, saveRoute, startRoute, route);
        final boolean[] routing = {false};
        final Runnable applyMode = () -> {
            routeBar.setVisibility(routing[0] ? View.VISIBLE : View.GONE);
            if (routing[0]) bubble.hide();
        };
        exitRoute.setOnClickListener(v -> { routing[0] = false; if (draft != null) draft.clear(); applyMode.run(); });
        // The search: suggestions unfold under the bar as you type; a choice centres the map and opens its bubble.
        MapSearchBar searchBar = new MapSearchBar(activity, ui, () -> new double[]{MapProjection.latitude(map.centerY), MapProjection.longitude(map.centerX)},
            (lat, lon) -> { map.centerOn(lat, lon); if (!routing[0]) bubble.show(lat, lon, null); });
        topRow.addView(searchBar.input, 0, new LinearLayout.LayoutParams(0, dp(activity, 44), 1));
        android.widget.FrameLayout.LayoutParams suggestionParams = new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        suggestionParams.topMargin = dp(activity, 52);
        controls.addView(searchBar.suggestions, suggestionParams);
        if (draft != null) map.onLongPress = (lat, lon) -> {
            closeLayers.run(); searchBar.dismiss();
            if (!routing[0]) { routing[0] = true; applyMode.run(); }
            draft.add(lat, lon);
        };
        map.onPick = (lat, lon, kind) -> {
            if (layersCard.getVisibility() == View.VISIBLE) { closeLayers.run(); return; }
            if (searchBar.dismiss()) return;
            if (routing[0] && draft != null) draft.add(lat, lon); else bubble.show(lat, lon, kind);
        };
        map.refreshPois();
        current.setOnClickListener(v -> map.centerCurrentLocation());

        // ----- Lieux and Autowalk: panels over the map (hub only); ‹ or Back returns to the map.
        final View[] panel = {null};
        final View[] panelViews = new View[3];
        final AutoWalkPanel[] walkerRef = {null};
        final Runnable closePanel = () -> {
            if (panel[0] == null) return;
            panel[0].setVisibility(View.GONE);
            if (panel[0] == panelViews[TAB_WALK] && walkerRef[0] != null) walkerRef[0].pause();
            panel[0] = null;
        };
        final PlacesPanel places = !hub ? null : new PlacesPanel(activity, ui, new PlacesPanel.Host() {
            public void showOnMap(double lat, double lon) { closePanel.run(); routing[0] = false; applyMode.run(); map.centerOn(lat, lon); bubble.show(lat, lon, null); }
            public void openRoute(GpxRoute chosen, boolean stored) { closePanel.run(); routing[0] = true; applyMode.run(); draft.load(chosen, stored); }
            public boolean teleport(double lat, double lon) { return listener.onDestination(lat, lon); }
            public boolean walk(double lat, double lon) { return walk.onDestination(lat, lon); }
            public boolean walkRoute(double[][] points, boolean looping) { return route.onRoute(points, looping); }
            public void done() { if (self[0] != null) self[0].dismiss(); }
        }, () -> WorldMapDialog.show(activity, Control.engine.latitude, Control.engine.longitude, listener, walk, route, TAB_PLACES));
        final AutoWalkPanel walker = !hub ? null : new AutoWalkPanel(activity, ui, () -> { if (self[0] != null) self[0].dismiss(); });
        walkerRef[0] = walker;
        final TextView[] panelHeads = new TextView[3];
        final Runnable[] openPanel = new Runnable[3];
        if (hub) {
            String[] titles = {null, "Lieux", "Autowalk"};
            View[] contents = {null, places.view(), walker.view()};
            for (int index = TAB_PLACES; index <= TAB_WALK; index++) {
                LinearLayout page = new LinearLayout(ui); page.setOrientation(LinearLayout.VERTICAL); page.setTag(DialogStyle.KEEP);
                page.setClickable(true); page.setVisibility(View.GONE);
                LinearLayout head = new LinearLayout(ui); head.setGravity(Gravity.CENTER_VERTICAL);
                head.setPadding(dp(activity, 6), dp(activity, 6), dp(activity, 12), dp(activity, 4));
                TextView back = label(ui, "‹", 26); back.setGravity(Gravity.CENTER); back.setClickable(true); back.setContentDescription("Retour à la carte");
                back.setOnClickListener(v -> closePanel.run());
                head.addView(back, new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)));
                TextView title = label(ui, titles[index], 18); title.setTypeface(SettingsUi.medium());
                head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                page.addView(head);
                page.addView(contents[index], new LinearLayout.LayoutParams(-1, 0, 1));
                root.addView(page, new android.widget.FrameLayout.LayoutParams(-1, -1));
                root.panels.add(page);
                panelViews[index] = page; panelHeads[index] = title;
                final int which = index;
                openPanel[index] = () -> {
                    closeLayers.run(); searchBar.dismiss(); closePanel.run();
                    panel[0] = panelViews[which]; panel[0].setVisibility(View.VISIBLE);
                    if (which == TAB_PLACES) places.reload(); else walker.resume();
                };
            }
            placesButton.setOnClickListener(v -> openPanel[TAB_PLACES].run());
            walkButton.setOnClickListener(v -> openPanel[TAB_WALK].run());
        }

        AlertDialog dialog = new AlertDialog.Builder(ui).setView(root).create();
        self[0] = dialog;
        if (draft != null) { draft.parent = dialog; draft.saved = places == null ? null : places::reload; draft.finished = () -> { routing[0] = false; applyMode.run(); }; }
        close.setOnClickListener(v -> dialog.dismiss());
        // Back closes a panel first, then the map.
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != android.view.KeyEvent.KEYCODE_BACK || panel[0] == null) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) closePanel.run();
            return true;
        });
        bubble.saved = places == null ? null : places::reload;
        bubble.teleport.setOnClickListener(v -> { if (listener.onDestination(bubble.latitude, bubble.longitude)) dialog.dismiss(); });
        if (walk != null) bubble.walk.setOnClickListener(v -> { if (walk.onDestination(bubble.latitude, bubble.longitude)) dialog.dismiss(); });
        // Opened just to look at a spot (not the hub), its bubble is up from the start.
        if (!hub) bubble.show(latitude, longitude, null);
        DialogLifecycle.bind(activity, dialog, () -> {
            searchBar.close();
            bubble.close();
            if (draft != null) draft.close();
            if (places != null) places.close();
            if (walker != null) walker.close();
            map.close();
        });
        dialog.setOnShowListener(d -> {
            MapTheme.fullScreen(dialog);
            MapTheme.immersive(dialog, root::safe);
            root.setBackgroundColor(SettingsUi.surface());
            paintFloating(ui, searchBar.input, false); searchBar.paint();
            paintFloating(ui, close, false); paintFloating(ui, current, false);
            paintFloating(ui, layersButton, false); paintFloating(ui, placesButton, false); paintFloating(ui, walkButton, false);
            layersCard.setBackground(SettingsUi.cardBackground(ui)); layersCard.setElevation(dp(activity, 4));
            layersHead.setTextColor(SettingsUi.faint());
            status.setTextColor(SettingsUi.ink()); status.setBackground(SettingsUi.shape(ui, SettingsUi.surface(), 16, SettingsUi.border()));
            status.setElevation(dp(activity, 3));
            bubble.paint();
            exitRoute.setTextColor(SettingsUi.muted()); exitRoute.setBackground(SettingsUi.ripple(SettingsUi.rounded(ui, 0x00000000, 20)));
            routeBar.setBackground(SettingsUi.cardBackground(ui)); routeBar.setElevation(dp(activity, 4)); routeSummary.setTextColor(SettingsUi.ink());
            HubUi.paint(undo, false); HubUi.paint(saveRoute, false); HubUi.paint(startRoute, true);
            for (View page : root.panels) page.setBackgroundColor(SettingsUi.surface());
            for (TextView head : panelHeads) if (head != null) {
                head.setTextColor(SettingsUi.ink());
                TextView back = (TextView) ((LinearLayout) head.getParent()).getChildAt(0);
                back.setTextColor(SettingsUi.ink()); back.setBackground(SettingsUi.ripple(SettingsUi.rounded(ui, 0x00000000, 22)));
            }
            applyMode.run();
            if (hub && (tab == TAB_PLACES || tab == TAB_WALK)) openPanel[tab].run();
        });
        dialog.show();
        return dialog;
    }

    /**
     * The window's root: as tall as the window, the map under everything. The controls and the panels are padded
     * clear of a display cutout (the map itself runs under it).
     */
    private static final class FullRoot extends android.widget.FrameLayout {
        View controls;
        final List<View> panels = new ArrayList<>();
        private final int gap;
        FullRoot(Context c) { super(c); gap = dp(c, 10); safe(0, 0, 0, 0); }
        void safe(int left, int top, int right, int bottom) {
            if (controls != null) controls.setPadding(gap + left, gap + top, gap + right, gap + bottom);
            for (View page : panels) page.setPadding(left, top, right, bottom);
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int size = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? getResources().getDisplayMetrics().heightPixels : MeasureSpec.getSize(heightSpec);
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
        }
    }

    /** A floating button with an icon and a short name (Calques, Lieux, Autowalk). */
    private static LinearLayout pill(Context c, int icon, String text) {
        LinearLayout pill = new LinearLayout(c); pill.setGravity(Gravity.CENTER_VERTICAL); pill.setTag(DialogStyle.KEEP);
        pill.setPadding(dp(c, 12), 0, dp(c, 16), 0); pill.setClickable(true); pill.setFocusable(true); pill.setContentDescription(text);
        MenuIcon glyph = new MenuIcon(c, icon);
        pill.addView(glyph, new LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)));
        TextView name = label(c, text, 13); name.setTypeface(SettingsUi.medium()); name.setPadding(dp(c, 8), 0, 0, 0);
        pill.addView(name);
        return pill;
    }
    private static LinearLayout.LayoutParams pillParams(Context c, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(c, 40)); lp.topMargin = dp(c, top); lp.gravity = Gravity.END; return lp;
    }
    /** A small on/off switch: an accent track with the knob on the right when on, a quiet track otherwise. */
    private static final class Switch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        boolean on;
        /** Where the knob is, 0 (off) to 1 (on); it slides between the two. */
        private float knob;
        Switch(Context c, boolean on) {
            super(c); this.on = on; knob = on ? 1 : 0;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void set(boolean value) {
            on = value;
            android.animation.ValueAnimator slide = android.animation.ValueAnimator.ofFloat(knob, value ? 1 : 0).setDuration(140);
            slide.addUpdateListener(a -> { knob = (float) a.getAnimatedValue(); invalidate(); });
            slide.start();
        }
        @Override protected void onDraw(Canvas canvas) {
            float h = getHeight(), w = getWidth(), r = h / 2, inset = Math.max(2, h * .14f);
            track.set(0, 0, w, h);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(blend(SettingsUi.raised(), SettingsUi.accentFill(), knob));
            canvas.drawRoundRect(track, r, r, paint);
            if (knob < 1) {
                float stroke = Math.max(1, h / 20);
                track.inset(stroke / 2, stroke / 2);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setColor(SettingsUi.border());
                paint.setAlpha(Math.round(255 * (1 - knob))); canvas.drawRoundRect(track, r, r, paint); paint.setAlpha(255);
                paint.setStyle(Paint.Style.FILL);
            }
            paint.setColor(blend(SettingsUi.muted(), 0xffffffff, knob));
            canvas.drawCircle(r + (w - 2 * r) * knob, r, r - inset, paint);
        }
        private static int blend(int from, int to, float t) {
            return Color.argb(Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t),
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
        }
    }

    /**
     * The bubble over the touched point, its tail on the point: what is there (PokéStop, Arène, Raid, or a plain
     * spot with its coordinates) and how far, then Marcher and Téléporter. A plain spot also has ☆, which turns the
     * bubble into a name field saved straight to the favourites. It follows the map as it moves; ✕ closes it.
     */
    private static final class Bubble {
        final Activity activity;
        final Context ui;
        final MapView map;
        final LinearLayout view, buttons, form;
        final BubbleBackground background;
        final TextView title, detail, dismiss;
        final Button favorite, walk, teleport, cancel, save;
        final EditText name;
        final Handler main = new Handler(Looper.getMainLooper());
        /** Keeps Téléporter's cooldown current while the bubble is open. */
        private final Runnable cooldown = new Runnable() {
            public void run() {
                main.removeCallbacks(this);
                if (!shown || closed) return;
                teleport.setText(teleportLabel(Cooldown.remainingAt(latitude, longitude)));
                main.postDelayed(this, 1000);
            }
        };
        final int tail;
        double latitude, longitude;
        /** Runs after a favourite is saved, so Lieux lists it. */
        Runnable saved;
        private boolean shown, closed;

        Bubble(Activity activity, Context ui, MapView map, boolean canWalk) {
            this.activity = activity; this.ui = ui; this.map = map;
            tail = dp(ui, 10);
            background = new BubbleBackground(ui, tail);
            view = new LinearLayout(ui); view.setOrientation(LinearLayout.VERTICAL); view.setTag(DialogStyle.KEEP);
            view.setBackground(background); view.setClickable(true); view.setVisibility(View.GONE);
            pad();
            // The texts are centred across the whole bubble; ✕ sits in the corner over them.
            android.widget.FrameLayout head = new android.widget.FrameLayout(ui);
            LinearLayout texts = new LinearLayout(ui); texts.setOrientation(LinearLayout.VERTICAL); texts.setGravity(Gravity.CENTER_HORIZONTAL);
            texts.setPadding(dp(ui, 30), dp(ui, 4), dp(ui, 30), 0);
            title = label(ui, "", 15); title.setTypeface(SettingsUi.medium()); title.setSingleLine(true); title.setGravity(Gravity.CENTER);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            detail = label(ui, "", 12); detail.setSingleLine(true); detail.setEllipsize(android.text.TextUtils.TruncateAt.END); detail.setGravity(Gravity.CENTER);
            detail.setClickable(true); detail.setContentDescription("Copier les coordonnées");
            detail.setOnClickListener(v -> CoordinateClipboard.copy(activity, latitude, longitude, "Coordonnées copiées"));
            texts.addView(title, new LinearLayout.LayoutParams(-1, -2)); texts.addView(detail, new LinearLayout.LayoutParams(-1, -2));
            head.addView(texts, new android.widget.FrameLayout.LayoutParams(-1, -2));
            dismiss = label(ui, "✕", 14); dismiss.setGravity(Gravity.CENTER); dismiss.setClickable(true); dismiss.setContentDescription("Fermer");
            dismiss.setOnClickListener(v -> hide());
            head.addView(dismiss, new android.widget.FrameLayout.LayoutParams(dp(ui, 30), dp(ui, 30), Gravity.TOP | Gravity.END));
            view.addView(head);

            favorite = HubUi.button(ui, "☆", false); favorite.setTextSize(16); favorite.setContentDescription("Ajouter aux favoris");
            walk = HubUi.button(ui, "Marcher", false); teleport = HubUi.button(ui, "Téléporter", true);
            buttons = new LinearLayout(ui); buttons.setPadding(0, dp(ui, 10), 0, 0);
            LinearLayout.LayoutParams starParams = new LinearLayout.LayoutParams(dp(ui, 42), dp(ui, 38)); starParams.rightMargin = dp(ui, 6);
            buttons.addView(favorite, starParams);
            LinearLayout.LayoutParams walkParams = new LinearLayout.LayoutParams(0, dp(ui, 38), 1); walkParams.rightMargin = dp(ui, 6);
            buttons.addView(walk, walkParams);
            buttons.addView(teleport, new LinearLayout.LayoutParams(0, dp(ui, 38), 1));
            walk.setVisibility(canWalk ? View.VISIBLE : View.GONE);
            favorite.setOnClickListener(v -> edit());
            view.addView(buttons);

            form = new LinearLayout(ui); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(0, dp(ui, 10), 0, 0); form.setVisibility(View.GONE);
            name = new EditText(ui); name.setHint("Nom du favori"); name.setSingleLine(true); name.setTextSize(14); name.setGravity(Gravity.CENTER);
            name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            name.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(SavedPlaces.NAME_LIMIT)});
            name.setPadding(dp(ui, 12), 0, dp(ui, 12), 0);
            name.setOnEditorActionListener((v, action, event) -> { save(); return true; });
            form.addView(name, new LinearLayout.LayoutParams(-1, dp(ui, 40)));
            cancel = HubUi.button(ui, "Annuler", false); save = HubUi.button(ui, "Enregistrer", true);
            LinearLayout formButtons = HubUi.actions(ui, cancel, save); formButtons.setPadding(0, dp(ui, 8), 0, 0);
            cancel.setOnClickListener(v -> browse());
            save.setOnClickListener(v -> save());
            form.addView(formButtons);
            view.addView(form);

            // Placed against the point whenever the map moves or the bubble changes size.
            view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> position());
            map.onViewport = this::position;
        }

        /** Opens the bubble on a point; {@code kind} names a PokéStop, Arène or Raid, null for a plain spot (pinned). */
        void show(double latitude, double longitude, String kind) {
            if (closed) return;
            this.latitude = latitude; this.longitude = longitude;
            title.setText(kind == null ? "Point choisi" : kind);
            String away = HubUi.away(latitude, longitude);
            detail.setText(String.format(Locale.US, "%.5f, %.5f", latitude, longitude) + (away.isEmpty() ? "" : " · " + away));
            favorite.setVisibility(kind == null ? View.VISIBLE : View.GONE);
            if (kind == null) map.pin(latitude, longitude); else map.unpin();
            browse();
            shown = true; view.setVisibility(View.VISIBLE);
            cooldown.run();
            position();
        }

        void hide() {
            if (!shown) return;
            keyboard(false);
            shown = false; view.setVisibility(View.GONE); map.unpin(); main.removeCallbacks(cooldown);
        }

        /** Keeps the tail on the point: above it, or under it when there is no room above; hidden while off screen. */
        void position() {
            if (!shown || closed) return;
            View frame = (View) view.getParent();
            if (frame == null) return;
            int w = view.getWidth(), h = view.getHeight(), fw = frame.getWidth(), fh = frame.getHeight();
            if (w == 0 || h == 0 || fw == 0 || fh == 0) return; // called again once laid out
            float px = map.projectX(longitude), py = map.projectY(latitude);
            view.setVisibility(px >= 0 && px <= fw && py >= 0 && py <= fh ? View.VISIBLE : View.INVISIBLE);
            float lift = dp(ui, 14), margin = dp(ui, 8);
            boolean below = py - lift - h < margin;
            if (below != background.flipped) { background.flipped = below; pad(); } // relayout, then back here
            float x = Math.max(margin, Math.min(fw - w - margin, px - w / 2f));
            view.setX(x); view.setY(below ? py + lift : py - lift - h);
            background.tipX = px - x; background.invalidateSelf();
        }

        private void pad() {
            view.setPadding(dp(ui, 8), dp(ui, 6) + (background.flipped ? tail : 0), dp(ui, 8), dp(ui, 10) + (background.flipped ? 0 : tail));
        }

        /** Back to the actions. */
        private void browse() {
            keyboard(false);
            form.setVisibility(View.GONE); buttons.setVisibility(View.VISIBLE); name.setError(null); save.setEnabled(true);
        }

        /** The name field; the point moves up the map so the bubble stays clear of the keyboard. */
        private void edit() {
            buttons.setVisibility(View.GONE); form.setVisibility(View.VISIBLE);
            name.setText(""); name.requestFocus(); keyboard(true);
            view.post(() -> { if (shown) map.placeAt(latitude, longitude, dp(ui, 70) + view.getHeight() + dp(ui, 14)); });
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

        private void keyboard(boolean open) {
            InputMethodManager input = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (input == null) return;
            if (open) name.post(() -> input.showSoftInput(name, InputMethodManager.SHOW_IMPLICIT));
            else input.hideSoftInputFromWindow(name.getWindowToken(), 0);
        }

        void paint() {
            title.setTextColor(SettingsUi.ink()); detail.setTextColor(SettingsUi.muted()); dismiss.setTextColor(SettingsUi.muted());
            dismiss.setBackground(SettingsUi.ripple(SettingsUi.rounded(ui, 0x00000000, 18)));
            name.setTextColor(SettingsUi.ink()); name.setHintTextColor(SettingsUi.faint()); name.setBackground(SettingsUi.fieldBackground(ui));
            HubUi.paint(favorite, false); HubUi.paint(walk, false); HubUi.paint(teleport, true);
            HubUi.paint(cancel, false); HubUi.paint(save, true);
            favorite.setTextColor(SettingsUi.accent());
        }

        void close() { closed = true; main.removeCallbacksAndMessages(null); }

        /** "Téléporter" when it is free, else with the cooldown it would start: "Téléporter · 12m05", "· 1h04". */
        static String teleportLabel(long millis) {
            long seconds = (Math.max(0, millis) + 999) / 1000;
            if (seconds == 0) return "Téléporter";
            String wait = seconds >= 3600 ? String.format(Locale.US, "%dh%02d", seconds / 3600, seconds / 60 % 60)
                : seconds >= 60 ? String.format(Locale.US, "%dm%02d", seconds / 60, seconds % 60) : seconds + "s";
            return "Téléporter · " + wait;
        }
    }

    /** A rounded card with a small tail pointing at {@link #tipX}: under the card, or above it when {@link #flipped}. */
    private static final class BubbleBackground extends android.graphics.drawable.Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path(), tip = new Path();
        private final RectF card = new RectF();
        private final float radius, tail, half;
        float tipX;
        boolean flipped;
        BubbleBackground(Context c, int tail) {
            this.tail = tail; radius = dp(c, 16); half = dp(c, 9);
            fill.setColor(SettingsUi.surface());
            stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(Math.max(1, dp(c, 1))); stroke.setColor(SettingsUi.border());
        }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds(); float edge = stroke.getStrokeWidth() / 2;
            card.set(b.left + edge, b.top + edge + (flipped ? tail : 0), b.right - edge, b.bottom - edge - (flipped ? 0 : tail));
            float x = Math.max(card.left + radius + half, Math.min(card.right - radius - half, b.left + tipX));
            path.reset(); path.addRoundRect(card, radius, radius, Path.Direction.CW);
            tip.reset();
            if (flipped) { tip.moveTo(x - half, card.top + 1); tip.lineTo(x, b.top + edge); tip.lineTo(x + half, card.top + 1); }
            else { tip.moveTo(x - half, card.bottom - 1); tip.lineTo(x, b.bottom - edge); tip.lineTo(x + half, card.bottom - 1); }
            tip.close();
            path.op(tip, Path.Op.UNION);
            canvas.drawPath(path, fill); canvas.drawPath(path, stroke);
        }
        @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); stroke.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { fill.setColorFilter(filter); stroke.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** A control floating on the map: a pill with a soft shadow. */
    private static TextView floating(Context c, String text, int size) {
        TextView t = label(c, text, size); t.setTypeface(SettingsUi.medium()); t.setSingleLine(true); t.setGravity(Gravity.CENTER);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setClickable(true); t.setFocusable(true); t.setTag(DialogStyle.KEEP);
        return t;
    }
    /** Selected: filled with the accent; otherwise the surface colour with a thin border. */
    private static void paintFloating(Context c, View view, boolean on) {
        if (view instanceof TextView) ((TextView) view).setTextColor(on ? SettingsUi.accentInk() : SettingsUi.ink());
        else if (view instanceof LinearLayout) {
            LinearLayout pill = (LinearLayout) view;
            for (int i = 0; i < pill.getChildCount(); i++) if (pill.getChildAt(i) instanceof TextView) ((TextView) pill.getChildAt(i)).setTextColor(on ? SettingsUi.accentInk() : SettingsUi.ink());
        }
        view.setBackground(SettingsUi.ripple(on ? SettingsUi.rounded(c, SettingsUi.accentFill(), 22) : SettingsUi.shape(c, SettingsUi.surface(), 22, SettingsUi.border())));
        view.setElevation(dp(c, 3));
    }

    private static int dp(Context c, float value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
    private static TextView label(Context c, String text, int size) { TextView v = new TextView(c); v.setText(text); v.setTextSize(size); return v; }

    /**
     * The route being drawn: a finger held on the map starts it, then every touch adds a point. The bar at the
     * bottom takes the last point back, saves the route (it then shows in Lieux, Mes trajets) or walks it in order.
     */
    private static final class RouteDraft {
        final Activity activity;
        final Context ui;
        final MapView map;
        final View buttons;
        final TextView summary;
        final Button undo, save, start;
        final RouteListener listener;
        final List<double[]> points = new ArrayList<>();
        AlertDialog parent, saveDialog;
        /** Runs after a save, so Lieux shows the new walk. */
        Runnable saved;
        /** Runs after a save, to leave route mode. */
        Runnable finished;
        /** Name of the walk opened from Lieux, offered again when saving. */
        String name;
        /** Something to save: false right after a saved walk is opened or this one is saved. */
        boolean changed;
        RouteDraft(Activity activity, Context ui, MapView map, TextView summary, View buttons, Button undo, Button save, Button start, RouteListener listener) {
            this.activity = activity; this.ui = ui; this.map = map; this.summary = summary; this.buttons = buttons;
            this.undo = undo; this.save = save; this.start = start; this.listener = listener;
            undo.setOnClickListener(v -> undo());
            save.setOnClickListener(v -> save());
            start.setOnClickListener(v -> start());
            refresh();
        }
        void add(double latitude, double longitude) {
            if (points.size() >= GpxCodec.MAX_POINTS) { Toast.makeText(activity, "Une route peut avoir au plus " + GpxCodec.MAX_POINTS + " points.", Toast.LENGTH_SHORT).show(); return; }
            points.add(new double[]{latitude, longitude}); changed = true; refresh();
        }
        /** Forgets the route (the saved copy, if any, stays in Mes trajets). */
        void clear() { points.clear(); name = null; changed = false; refresh(); }
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
            if (!points.isEmpty()) map.fit(points);
        }
        void refresh() {
            boolean empty = points.isEmpty();
            buttons.setVisibility(empty ? View.GONE : View.VISIBLE);
            double meters = 0;
            for (int i = 1; i < points.size(); i++) meters += RouteStart.meters(points.get(i - 1)[0], points.get(i - 1)[1], points.get(i)[0], points.get(i)[1]);
            summary.setText(empty ? "Touche la carte pour ajouter des points" : (name == null ? "Route" : name) + " · " + points.size() + (points.size() == 1 ? " point" : " points")
                + (points.size() > 1 ? " · " + HubUi.length(meters) : ""));
            save.setEnabled(changed); save.setAlpha(changed ? 1f : .5f); save.setText(changed ? "Enregistrer" : "Enregistrée");
            map.setRoute(points);
        }
        void save() {
            if (points.isEmpty() || (saveDialog != null && saveDialog.isShowing())) return;
            // Once saved, the route leaves the map: it is now in Lieux, Mes trajets.
            saveDialog = PlacesPanel.promptSaveRoute(activity, ui, toArray(), name, savedName -> {
                clear();
                if (saved != null) saved.run();
                if (finished != null) finished.run();
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

    private static final class MapView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final int tileSize, slop;
        private final MapTileSource tiles;
        private final MapPoiLayer poiLayer;
        private List<double[]> route = Collections.emptyList();
        private volatile boolean closed;
        private double centerX, centerY, latitude, longitude;
        /** The red pin on {@link #latitude}, {@link #longitude}: shown for a plain spot picked in the bubble. */
        private boolean pinned;
        private int zoom = 14;
        private double zoomScale=1;
        /** Gesture state: where the touch began, and the fingers' midpoint and spread on the last event. */
        private float downX, downY, focusX, focusY, spread;
        private boolean dragged, multiTouch, longPressed;
        /** A finger held still on the map; set when routes are available. */
        LongPress onLongPress;
        private final Runnable longPress = new Runnable() {
            public void run() {
                if (closed || dragged || multiTouch || onLongPress == null) return;
                double[] point = pointAt(downX, downY);
                if (point == null) return;
                longPressed = true;
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                onLongPress.at(point[0], point[1]);
            }
        };
        /** A tap: on a marker it picks that PokéStop/Arène/Raid, elsewhere the spot under the finger. */
        Pick onPick;
        /** Runs after every move or zoom, so what floats on the map can follow it. */
        Runnable onViewport;
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
            tiles = new MapTileSource(this::invalidate, status, new File(activity.getCacheDir(), "pgrazor-map-tiles-v1"));
            poiLayer = new MapPoiLayer(activity, new MapPoiLayer.Projector() {
                public float screenX(double lon) { return projectX(lon); }
                public float screenY(double lat) { return projectY(lat); }
                public int width() { return getWidth(); }
                public int height() { return getHeight(); }
            }, new MapPoiLayer.Snapshots() {
                public void setOpen(boolean open) { Control.setMapPoiOpen(open); }
                public String read() { return Control.mapPoiSnapshot(); }
            }, this::invalidate);
            setContentDescription("Carte du monde. Le point bleu est ta position actuelle. Glisse pour te déplacer, pince pour zoomer, touche un point pour ses actions.");
        }

        void setPoiLayer(int bit, boolean on) { poiLayer.setLayer(bit, on); }
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
        /** Shows the pin on a point without moving the view; {@link #unpin} hides it. */
        void pin(double latitude, double longitude) { this.latitude = latitude; this.longitude = longitude; pinned = true; invalidate(); }
        void unpin() { if (pinned) { pinned = false; invalidate(); } }
        /** Moves the view (same zoom) so the point sits at height {@code y}, horizontally centred. */
        void placeAt(double latitude, double longitude, float y) {
            if (closed || getHeight() <= 0) return;
            centerX = MapProjection.x(longitude); centerY = MapProjection.y(latitude) - (y - getHeight() / 2.0) / world();
            viewportChanged();
        }
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
        /** Centres the view on a point at street level. */
        void centerOn(double latitude, double longitude) {
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
            if (onViewport != null) onViewport.run();
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
            if (pinned) drawPin(canvas);
            drawRoute(canvas,true);
            drawCurrentLocation(canvas);
        }

        private void drawPin(Canvas canvas) {
            float markerX = (float)(getWidth() / 2.0 + MapProjection.deltaX(MapProjection.x(longitude), centerX) * world());
            float markerY = (float)(getHeight() / 2.0 + (MapProjection.y(latitude) - centerY) * world());
            float radius = Math.max(8, tileSize / 28f);
            paint.setColor(Color.WHITE); canvas.drawCircle(markerX, markerY, radius + 3, paint);
            paint.setColor(Color.rgb(197, 35, 52)); canvas.drawCircle(markerX, markerY, radius, paint);
            paint.setColor(Color.WHITE); canvas.drawCircle(markerX, markerY, radius / 3, paint);
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
                    if (!dragged && !multiTouch && !longPressed) {
                        // A tap only picks a point (a marker first); moving there needs a button in the bubble.
                        double[] point = pointAt(event.getX(), event.getY());
                        if (point != null) { performClick(); if (onPick != null) onPick.at(point[0], point[1], point.length > 2 ? KINDS[(int) point[2]] : null); }
                    }
                    getParent().requestDisallowInterceptTouchEvent(false); return true;
                case MotionEvent.ACTION_CANCEL: removeCallbacks(longPress); getParent().requestDisallowInterceptTouchEvent(false); return true;
                default: return true;
            }
        }
        private static final String[] KINDS = {"PokéStop", "Arène", "Raid"};
        /**
         * The map point under (x, y): a visible marker's own point when one is there ({lat, lon, kind index}),
         * else the spot itself ({lat, lon}); null outside the world at the widest zoom.
         */
        private double[] pointAt(float x, float y) {
            List<JSONObject> hits = poiLayer.hasLayers() ? poiLayer.hits(x, y) : Collections.<JSONObject>emptyList();
            JSONObject marker = hits.isEmpty() ? null : hits.get(0);
            if (marker != null) {
                double lat = marker.optDouble("lat", Double.NaN), lon = marker.optDouble("lon", Double.NaN);
                if (Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180) {
                    String kind = MapPoiLayer.kind(marker);
                    return new double[]{lat, lon, kind.equals("PokéStop") ? 0 : kind.equals("Arène") ? 1 : 2};
                }
            }
            boolean insideWorld = zoom != 0 || (Math.abs(x - getWidth() / 2.0) <= world() / 2 && Math.abs(y - getHeight() / 2.0) <= world() / 2);
            if (!insideWorld) return null;
            return new double[]{MapProjection.latitude(MapProjection.offsetY(centerY, y - getHeight() / 2.0, world())),
                MapProjection.longitude(MapProjection.offsetX(centerX, x - getWidth() / 2.0, world()))};
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
