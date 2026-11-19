package dev.pgrazor;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The quick menu opened by the R launcher: a compact card with a header strip (walking speed, tap to
 * cycle, and the settings gear) above a 3-column grid of icon tiles. Hidden entries (Affichage →
 * Menu rapide) leave no gap: the grid is re-flowed whenever the visible set changes. Map, walk and
 * location actions are delegated to the host through {@link Listener}.
 */
public final class QuickMenu {
    public interface Listener {
        void onMap();
        /** Cycles the walk speed and returns the new value (km/h); the header refreshes from it. */
        double onCycleWalkSpeed();
        void onAutoWalk();
        void onGameRoutes();
        void onFeeds();
        void onFavorites();
        void onHotspots();
        void onTeleport();
        void onSettings();
        /** Asks to hide every PGRazor overlay (three fingers on screen show them again). */
        void onHideOverlays();
        /** "Éteindre l'écran": the game keeps running while the screen is off. */
        void onScreenOff();
    }

    static final int MAP = MenuIcon.MAP, WALK = MenuIcon.WALK, AUTOWALK = MenuIcon.AUTOWALK, FEEDS = MenuIcon.FEEDS, FAVORITES = MenuIcon.FAVORITES, TELEPORT = MenuIcon.TELEPORT,
        ACCOUNTS = MenuIcon.ACCOUNTS, SETTINGS = MenuIcon.SETTINGS, EVENTS = MenuIcon.EVENTS, AUTOMATION = MenuIcon.AUTOMATION, HIDE = MenuIcon.HIDE, ROUTES = MenuIcon.ROUTES, RESEARCH = MenuIcon.RESEARCH, POWER = MenuIcon.POWER;

    /** Names shown in Affichage → Menu rapide. Append-only order: the persisted visibility bit is the index. */
    static final String[] CUSTOMIZABLE = {"Carte", "Vitesse de marche", "Autowalk", "Routes du jeu", "Flux", "Favoris", "Hotspots", "Téléporter", null, "Événements", "Tâches auto", "Études", "Masquer l'overlay", "Écran éteint"};

    private static final int CARD_WIDTH = 228, COLUMNS = 3;

    private final Activity activity;
    private final Listener listener;
    private final View anchor;
    private final FrameLayout root;
    private final LinearLayout card, grid, walk;
    private final ScrollView viewport;
    private final TextView walkSpeed, walkName;
    private final MenuIcon walkIcon, gearIcon;
    private final View gear;
    private final List<View> tiles = new ArrayList<>();
    private final List<Integer> tileBits = new ArrayList<>();
    private final List<TextView> tileLabels = new ArrayList<>();
    private final List<MenuIcon> tileIcons = new ArrayList<>();
    private int appliedHidden = -1;

    public QuickMenu(Activity activity, ViewGroup parent, View anchor, Listener listener) {
        this.activity = activity; this.anchor = anchor; this.listener = listener;
        root = new FrameLayout(activity);
        // A transparent scrim catches outside taps to dismiss without dimming the game.
        View scrim = new View(activity); scrim.setClickable(true); scrim.setOnClickListener(v -> hide());
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8)); card.setElevation(dp(12));

        // Header: walking speed (tap cycles it, the menu stays open) and the settings gear.
        LinearLayout header = new LinearLayout(activity); header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL);
        walk = new LinearLayout(activity); walk.setOrientation(LinearLayout.HORIZONTAL); walk.setGravity(Gravity.CENTER_VERTICAL);
        walk.setPadding(dp(10), 0, dp(10), 0); walk.setMinimumHeight(dp(40)); walk.setClickable(true); walk.setFocusable(true);
        walk.setContentDescription("Vitesse de marche. Touche pour changer.");
        walkIcon = new MenuIcon(activity, WALK); walk.addView(walkIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        walkName = new TextView(activity); walkName.setText("Vitesse"); walkName.setTextSize(13); walkName.setPadding(dp(8), 0, dp(6), 0);
        walk.addView(walkName, new LinearLayout.LayoutParams(0, -2, 1f));
        walkSpeed = new TextView(activity); walkSpeed.setTextSize(13); walkSpeed.setTypeface(SettingsUi.mono()); walkSpeed.setSingleLine(true);
        walk.addView(walkSpeed, new LinearLayout.LayoutParams(-2, -2));
        walk.setOnClickListener(v -> { listener.onCycleWalkSpeed(); updateWalk(); });
        header.addView(walk, new LinearLayout.LayoutParams(0, dp(40), 1f));
        LinearLayout gearBox = new LinearLayout(activity); gearBox.setGravity(Gravity.CENTER); gearBox.setClickable(true); gearBox.setFocusable(true);
        gearBox.setContentDescription("Réglages PGRazor");
        gearIcon = new MenuIcon(activity, SETTINGS); gearBox.addView(gearIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        gearBox.setOnClickListener(v -> { hide(); listener.onSettings(); });
        gear = gearBox;
        LinearLayout.LayoutParams gearParams = new LinearLayout.LayoutParams(dp(40), dp(40)); gearParams.leftMargin = dp(6);
        header.addView(gear, gearParams);
        card.addView(header);

        grid = new LinearLayout(activity); grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(-1, -2); gridParams.topMargin = dp(6);
        card.addView(grid, gridParams);

        tile(MAP, "Carte", listener::onMap, 1);
        tile(TELEPORT, "Téléporter", listener::onTeleport, 128);
        tile(FAVORITES, "Favoris", listener::onFavorites, 32);
        tile(AUTOWALK, "Autowalk", listener::onAutoWalk, 4);
        tile(ROUTES, "Routes", listener::onGameRoutes, 8);
        tile(MAP, "Hotspots", listener::onHotspots, 64);
        tile(FEEDS, "Flux", listener::onFeeds, 16);
        tile(EVENTS, "Événements", () -> dev.pgrazor.events.EventPanel.showSelector(activity, Control.darkTheme()), 512);
        tile(RESEARCH, "Études", () -> ResearchSettings.show(activity), 2048);
        tile(AUTOMATION, "Tâches auto", () -> AutomationPanel.show(activity), 1024);
        tile(HIDE, "Masquer", listener::onHideOverlays, 4096);
        tile(POWER, "Écran off", listener::onScreenOff, 8192);

        viewport = new ScrollView(activity); viewport.setFillViewport(false); viewport.setVerticalScrollBarEnabled(false);
        viewport.addView(card, new ScrollView.LayoutParams(-1, -2));
        root.addView(viewport, new FrameLayout.LayoutParams(dp(CARD_WIDTH), -2));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (isShowing() && (r - l != or - ol || b - t != ob - ot)) position(); });
        root.setOnApplyWindowInsetsListener((v, insets) -> { if (isShowing()) viewport.post(this::position); return insets; });

        parent.addView(root, new FrameLayout.LayoutParams(-1, -1));
        root.setVisibility(View.GONE);
        applyTheme();
    }

    /** One grid tile: a centred icon above a short label; the whole tile is the hit target. */
    private void tile(int kind, String label, Runnable action, int visibilityBit) {
        LinearLayout t = new LinearLayout(activity); t.setOrientation(LinearLayout.VERTICAL); t.setGravity(Gravity.CENTER);
        t.setMinimumHeight(dp(60)); t.setPadding(dp(2), dp(8), dp(2), dp(6)); t.setClickable(true); t.setFocusable(true);
        t.setContentDescription(label);
        MenuIcon icon = new MenuIcon(activity, kind);
        t.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView name = new TextView(activity); name.setText(label); name.setTextSize(11); name.setGravity(Gravity.CENTER);
        name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END); name.setPadding(0, dp(5), 0, 0);
        t.addView(name, new LinearLayout.LayoutParams(-1, -2));
        t.setOnClickListener(v -> { hide(); action.run(); });
        tiles.add(t); tileBits.add(visibilityBit); tileLabels.add(name); tileIcons.add(icon);
    }

    /** Re-flows the visible tiles into rows of three, so a hidden entry never leaves a hole. */
    private void layoutGrid(int hidden) {
        grid.removeAllViews();
        for (View t : tiles) if (t.getParent() instanceof ViewGroup) ((ViewGroup) t.getParent()).removeView(t);
        LinearLayout row = null; int inRow = 0;
        for (int i = 0; i < tiles.size(); i++) {
            if ((hidden & tileBits.get(i)) != 0) continue;
            if (row == null || inRow == COLUMNS) {
                row = new LinearLayout(activity); row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(-1, -2)); inRow = 0;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f); lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(tiles.get(i), lp); inRow++;
        }
        // Keep the last row's tiles the same width as the others.
        if (row != null) for (; inRow < COLUMNS; inRow++) row.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));
        grid.setVisibility(grid.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void updateWalk() {
        String text = String.format(Locale.FRANCE, "%.1f km/h", Control.engine.speedKmh);
        if (!text.contentEquals(walkSpeed.getText())) walkSpeed.setText(text);
    }

    private void updateVisibility() {
        int hidden = Control.quickMenuHidden(); if (hidden == appliedHidden) return;
        appliedHidden = hidden;
        walk.setVisibility((hidden & 2) == 0 ? View.VISIBLE : View.INVISIBLE);
        layoutGrid(hidden);
        if (isShowing()) card.post(this::position);
    }
    public void onRefresh() { if (isShowing()) { updateVisibility(); updateWalk(); } }
    public void applyScale(float scale) { viewport.setPivotX(0); viewport.setPivotY(0); viewport.setScaleX(scale); viewport.setScaleY(scale); if (isShowing()) card.post(this::position); }
    public boolean isShowing() { return root.getVisibility() == View.VISIBLE; }
    public void toggle() { if (isShowing()) hide(); else show(); }
    public void hide() { root.setVisibility(View.GONE); }
    public void show() {
        updateVisibility();
        updateWalk();
        root.setVisibility(View.VISIBLE); root.bringToFront();
        card.post(this::position);
    }
    private void position() {
        if (root.getWidth() <= 0 || root.getHeight() <= 0) return;
        android.graphics.Rect safe = UiViewport.bounds(root);
        int margin = dp(8); safe.inset(Math.min(margin, safe.width() / 4), Math.min(margin, safe.height() / 4));
        float scale = Math.max(.01f, viewport.getScaleX());
        int width = UiViewportPolicy.menuExtent(dp(CARD_WIDTH), safe.width(), scale);
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = UiViewportPolicy.menuExtent(card.getMeasuredHeight(), safe.height(), scale);
        ViewGroup.LayoutParams lp = viewport.getLayoutParams();
        if (lp.width != width || lp.height != height) { lp.width = width; lp.height = height; viewport.setLayoutParams(lp); }
        int cw = Math.round(width * scale), ch = Math.round(height * scale), gap = dp(8);
        // The anchor may sit inside a scaled card: measure it in window coordinates, relative to this menu's layer.
        int[] at = new int[2], origin = new int[2]; anchor.getLocationInWindow(at); root.getLocationInWindow(origin);
        float anchorScale = 1f; for (View v = anchor; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) anchorScale *= v.getScaleY();
        float ax = at[0] - origin[0], ay = at[1] - origin[1], ah = anchor.getHeight() * anchorScale;
        float x = ax, y = ay + ah + gap;
        if (y + ch > safe.bottom) y = ay - ch - gap;
        viewport.setX(UiViewportPolicy.clamp(x, safe.left, safe.right, cw));
        viewport.setY(UiViewportPolicy.clamp(y, safe.top, safe.bottom, ch));
    }
    public void destroy() {
        hide();
        if (root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
    }

    private RippleDrawable pressable(int color) {
        return new RippleDrawable(ColorStateList.valueOf(SettingsUi.rippleColor()), SettingsUi.rounded(activity, color, SettingsUi.RADIUS_CONTROL), SettingsUi.rounded(activity, 0xffffffff, SettingsUi.RADIUS_CONTROL));
    }

    public void applyTheme() {
        card.setBackground(SettingsUi.shape(activity, SettingsUi.surface(), SettingsUi.RADIUS_CARD, SettingsUi.border()));
        walk.setBackground(pressable(SettingsUi.raised()));
        gear.setBackground(pressable(SettingsUi.raised()));
        walkName.setTextColor(SettingsUi.muted());
        walkSpeed.setTextColor(SettingsUi.accent());
        walkIcon.accent = true; walkIcon.invalidate(); gearIcon.invalidate();
        for (View t : tiles) t.setBackground(pressable(0x00000000));
        for (TextView label : tileLabels) label.setTextColor(SettingsUi.ink());
        for (MenuIcon icon : tileIcons) icon.invalidate();
    }

    private int dp(float x) { return Math.round(x * activity.getResources().getDisplayMetrics().density); }
}
