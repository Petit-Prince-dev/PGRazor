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
 * The quick menu opened by the R launcher. It is part of the HUD card itself: the launcher's line stays
 * on top and the menu unfolds right under it (walking speed + settings gear, then a 3-column grid of
 * icon tiles), so the same R button closes it. Nothing else closes it: no outside-tap dismissal.
 * Hidden entries (Affichage → Menu rapide) leave no gap: the grid is re-flowed whenever the visible set
 * changes. Map, walk and location actions are delegated to the host through {@link Listener}.
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

    private static final int COLUMNS = 3;

    private final Activity activity;
    private final Listener listener;
    private final LinearLayout card, grid, walk;
    private Runnable onToggle;
    private final TextView walkSpeed, walkName;
    private final MenuIcon walkIcon, gearIcon;
    private final View gear;
    private final List<View> tiles = new ArrayList<>();
    private final List<Integer> tileBits = new ArrayList<>();
    private final List<TextView> tileLabels = new ArrayList<>();
    private final List<MenuIcon> tileIcons = new ArrayList<>();
    private int appliedHidden = -1;

    /** Builds the menu; the host places {@link #view()} inside its card and calls {@link #toggle()}. */
    public QuickMenu(Activity activity, Listener listener) {
        this.activity = activity; this.listener = listener;
        card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, dp(6), 0, dp(4));

        // Header: walking speed (tap cycles it, the menu stays open) and the settings gear.
        LinearLayout header = new LinearLayout(activity); header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL);
        walk = new LinearLayout(activity); walk.setOrientation(LinearLayout.HORIZONTAL); walk.setGravity(Gravity.CENTER_VERTICAL);
        walk.setPadding(dp(10), 0, dp(10), 0); walk.setMinimumHeight(dp(36)); walk.setClickable(true); walk.setFocusable(true);
        walk.setContentDescription("Vitesse de marche. Touche pour changer.");
        walkIcon = new MenuIcon(activity, WALK); walk.addView(walkIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        walkName = new TextView(activity); walkName.setText("Vitesse"); walkName.setTextSize(12); walkName.setPadding(dp(8), 0, dp(6), 0);
        walk.addView(walkName, new LinearLayout.LayoutParams(0, -2, 1f));
        walkSpeed = new TextView(activity); walkSpeed.setTextSize(12); walkSpeed.setTypeface(SettingsUi.mono()); walkSpeed.setSingleLine(true);
        walk.addView(walkSpeed, new LinearLayout.LayoutParams(-2, -2));
        walk.setOnClickListener(v -> { listener.onCycleWalkSpeed(); updateWalk(); });
        header.addView(walk, new LinearLayout.LayoutParams(0, dp(36), 1f));
        LinearLayout gearBox = new LinearLayout(activity); gearBox.setGravity(Gravity.CENTER); gearBox.setClickable(true); gearBox.setFocusable(true);
        gearBox.setContentDescription("Réglages PGRazor");
        gearIcon = new MenuIcon(activity, SETTINGS); gearBox.addView(gearIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        gearBox.setOnClickListener(v -> { hide(); listener.onSettings(); });
        gear = gearBox;
        LinearLayout.LayoutParams gearParams = new LinearLayout.LayoutParams(dp(36), dp(36)); gearParams.leftMargin = dp(6);
        header.addView(gear, gearParams);
        card.addView(header);

        grid = new LinearLayout(activity); grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(-1, -2); gridParams.topMargin = dp(4);
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

        card.setVisibility(View.GONE);
        applyTheme();
    }

    /** One grid tile: a centred icon above a short label; the whole tile is the hit target. */
    private void tile(int kind, String label, Runnable action, int visibilityBit) {
        LinearLayout t = new LinearLayout(activity); t.setOrientation(LinearLayout.VERTICAL); t.setGravity(Gravity.CENTER);
        t.setMinimumHeight(dp(56)); t.setPadding(0, dp(7), 0, dp(5)); t.setClickable(true); t.setFocusable(true);
        t.setContentDescription(label);
        MenuIcon icon = new MenuIcon(activity, kind);
        t.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView name = new TextView(activity); name.setText(label); name.setTextSize(10.5f); name.setGravity(Gravity.CENTER);
        name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END); name.setPadding(0, dp(4), 0, 0);
        t.addView(name, new LinearLayout.LayoutParams(-1, -2));
        t.setOnClickListener(v -> action.run()); // the menu stays open: only the R button closes it
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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f); lp.setMargins(dp(1), dp(1), dp(1), dp(1));
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
    }
    public void onRefresh() { if (isShowing()) { updateVisibility(); updateWalk(); } }
    /** The menu's view, placed by the host in its card right under the launcher line. */
    public View view() { return card; }
    /** Called after the menu opens or closes, so the host can re-sync the card around it. */
    public void setOnToggle(Runnable onToggle) { this.onToggle = onToggle; }
    public boolean isShowing() { return card.getVisibility() == View.VISIBLE; }
    public void toggle() { if (isShowing()) hide(); else show(); }
    public void hide() {
        if (!isShowing()) return;
        card.setVisibility(View.GONE);
        if (onToggle != null) onToggle.run();
    }
    public void show() {
        updateVisibility();
        updateWalk();
        if (isShowing()) return;
        card.setVisibility(View.VISIBLE);
        if (onToggle != null) onToggle.run();
    }
    public void destroy() {
        onToggle = null; card.setVisibility(View.GONE);
        if (card.getParent() instanceof ViewGroup) ((ViewGroup) card.getParent()).removeView(card);
    }

    private RippleDrawable pressable(int color) {
        return new RippleDrawable(ColorStateList.valueOf(SettingsUi.rippleColor()), SettingsUi.rounded(activity, color, SettingsUi.RADIUS_CONTROL), SettingsUi.rounded(activity, 0xffffffff, SettingsUi.RADIUS_CONTROL));
    }

    public void applyTheme() {
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
