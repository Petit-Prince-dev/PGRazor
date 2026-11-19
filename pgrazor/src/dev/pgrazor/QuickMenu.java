package dev.pgrazor;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PGRazor's one and only menu, opened by the R launcher. It is part of the HUD card itself: the
 * launcher's line stays on top and the menu unfolds right under it, so the same R button closes it
 * (nothing else does: no outside-tap dismissal).
 *
 * <p>Layout, top to bottom: walking speed (tap to cycle) with a search button; the "Raccourcis" grid
 * (map, teleport, walks, feeds, screen); the "Réglages" grid, one tile per settings category, each
 * opening that category as a floating sheet; then the version and the Discord link. Shortcuts can be
 * hidden in Affichage → Raccourcis affichés (the grid re-flows, no gaps); settings tiles always show,
 * so every option of the mod stays reachable from here.
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
        /** Opens one settings category (index into the pages the settings sheet was built with). */
        void onSettingsPage(int index);
        /** Opens the settings sheet on its search field. */
        void onSearch();
        /** Asks to hide every PGRazor overlay (three fingers on screen show them again). */
        void onHideOverlays();
        /** "Éteindre l'écran": the game keeps running while the screen is off. */
        void onScreenOff();
    }

    static final int MAP = MenuIcon.MAP, WALK = MenuIcon.WALK, AUTOWALK = MenuIcon.AUTOWALK, FEEDS = MenuIcon.FEEDS, FAVORITES = MenuIcon.FAVORITES, TELEPORT = MenuIcon.TELEPORT,
        HIDE = MenuIcon.HIDE, ROUTES = MenuIcon.ROUTES, POWER = MenuIcon.POWER, HOTSPOT = MenuIcon.HOTSPOT, SEARCH = MenuIcon.SEARCH;

    /**
     * Names shown in Affichage → Raccourcis affichés. Append-only order: the persisted visibility bit is
     * the index; retired entries (old Accounts, Events, Automation, Research tiles, now settings
     * categories) stay as null so the saved bits keep their meaning.
     */
    static final String[] CUSTOMIZABLE = {"Carte", "Vitesse de marche", "Autowalk", "Routes du jeu", "Flux", "Favoris", "Hotspots", "Téléporter", null, null, null, null, "Masquer l'overlay", "Écran éteint"};

    private static final int COLUMNS = 3;

    private final Activity activity;
    private final Listener listener;
    private final LinearLayout card, shortcutGrid, settingsGrid, walk, header;
    private final TextView walkSpeed, walkName, shortcutHead, settingsHead, version, discord;
    private final MenuIcon walkIcon, searchIcon;
    private final View search;
    private final List<View> shortcuts = new ArrayList<>();
    private final List<Integer> shortcutBits = new ArrayList<>();
    private final List<View> settingTiles = new ArrayList<>();
    private final List<TextView> tileLabels = new ArrayList<>();
    private final List<MenuIcon> tileIcons = new ArrayList<>();
    private Runnable onToggle;
    private int appliedHidden = -1;

    /**
     * Builds the menu; the host places {@link #view()} inside its card and calls {@link #toggle()}.
     * {@code pageNames}/{@code pageIcons} describe the settings categories, in page order.
     */
    public QuickMenu(Activity activity, Listener listener, String[] pageNames, int[] pageIcons) {
        this.activity = activity; this.listener = listener;
        card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, dp(6), 0, dp(2));

        // Walking speed (tap cycles it, the menu stays open) and the settings search.
        header = new LinearLayout(activity); header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL);
        walk = new LinearLayout(activity); walk.setOrientation(LinearLayout.HORIZONTAL); walk.setGravity(Gravity.CENTER_VERTICAL);
        walk.setPadding(dp(10), 0, dp(10), 0); walk.setMinimumHeight(dp(36)); walk.setClickable(true); walk.setFocusable(true);
        walk.setContentDescription("Vitesse de marche. Touche pour changer.");
        walkIcon = new MenuIcon(activity, WALK); walk.addView(walkIcon, new LinearLayout.LayoutParams(dp(16), dp(16)));
        walkName = new TextView(activity); walkName.setText("Vitesse"); walkName.setTextSize(12); walkName.setPadding(dp(8), 0, dp(6), 0);
        walk.addView(walkName, new LinearLayout.LayoutParams(0, -2, 1f));
        walkSpeed = new TextView(activity); walkSpeed.setTextSize(12); walkSpeed.setTypeface(SettingsUi.mono()); walkSpeed.setSingleLine(true);
        walk.addView(walkSpeed, new LinearLayout.LayoutParams(-2, -2));
        walk.setOnClickListener(v -> { listener.onCycleWalkSpeed(); updateWalk(); });
        header.addView(walk, new LinearLayout.LayoutParams(0, dp(36), 1f));
        LinearLayout searchBox = new LinearLayout(activity); searchBox.setGravity(Gravity.CENTER); searchBox.setClickable(true); searchBox.setFocusable(true);
        searchBox.setContentDescription("Rechercher un réglage");
        searchIcon = new MenuIcon(activity, SEARCH); searchBox.addView(searchIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        searchBox.setOnClickListener(v -> listener.onSearch());
        search = searchBox;
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(dp(36), dp(36)); searchParams.leftMargin = dp(6);
        header.addView(search, searchParams);
        card.addView(header);

        shortcutHead = sectionHead("Raccourcis");
        shortcutGrid = new LinearLayout(activity); shortcutGrid.setOrientation(LinearLayout.VERTICAL);
        card.addView(shortcutGrid, new LinearLayout.LayoutParams(-1, -2));
        shortcut(MAP, "Carte", listener::onMap, 1);
        shortcut(TELEPORT, "Téléporter", listener::onTeleport, 128);
        shortcut(FAVORITES, "Favoris", listener::onFavorites, 32);
        shortcut(HOTSPOT, "Hotspots", listener::onHotspots, 64);
        shortcut(AUTOWALK, "Autowalk", listener::onAutoWalk, 4);
        shortcut(ROUTES, "Routes", listener::onGameRoutes, 8);
        shortcut(FEEDS, "Flux", listener::onFeeds, 16);
        shortcut(HIDE, "Masquer", listener::onHideOverlays, 4096);
        shortcut(POWER, "Écran off", listener::onScreenOff, 8192);

        settingsHead = sectionHead("Réglages");
        settingsGrid = new LinearLayout(activity); settingsGrid.setOrientation(LinearLayout.VERTICAL);
        card.addView(settingsGrid, new LinearLayout.LayoutParams(-1, -2));
        for (int i = 0; i < pageNames.length; i++) {
            final int index = i;
            settingTiles.add(tile(pageIcons[i], pageNames[i], () -> listener.onSettingsPage(index)));
        }
        flow(settingsGrid, settingTiles);

        // Footer: version on the left, the community link on the right.
        LinearLayout footer = new LinearLayout(activity); footer.setOrientation(LinearLayout.HORIZONTAL); footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(4), dp(4), 0, 0);
        version = new TextView(activity); version.setText(BuildVersion.NAME); version.setTextSize(10); version.setTypeface(SettingsUi.mono());
        version.setSingleLine(true); version.setEllipsize(TextUtils.TruncateAt.END);
        footer.addView(version, new LinearLayout.LayoutParams(0, -2, 1f));
        discord = new TextView(activity); discord.setText("Discord"); discord.setTextSize(11); discord.setTypeface(SettingsUi.medium());
        discord.setGravity(Gravity.CENTER); discord.setPadding(dp(8), dp(4), dp(8), dp(4)); discord.setClickable(true); discord.setFocusable(true);
        discord.setContentDescription("Rejoindre la communauté Discord de PGRazor");
        discord.setOnClickListener(v -> {
            try { activity.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://discord.gg/9QqfkDSQX3"))); }
            catch (android.content.ActivityNotFoundException unavailable) { Toast.makeText(activity, "Aucune appli pour ouvrir les liens Discord", Toast.LENGTH_SHORT).show(); }
        });
        footer.addView(discord, new LinearLayout.LayoutParams(-2, -2));
        card.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        card.setVisibility(View.GONE);
        applyTheme();
    }

    /** A small spaced caps label above a grid. */
    private TextView sectionHead(String label) {
        TextView head = new TextView(activity); head.setText(label.toUpperCase(Locale.FRANCE)); head.setTextSize(10);
        head.setTypeface(SettingsUi.medium()); head.setLetterSpacing(0.08f); head.setPadding(dp(4), dp(8), 0, dp(2));
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));
        return head;
    }

    private void shortcut(int kind, String label, Runnable action, int visibilityBit) {
        shortcuts.add(tile(kind, label, action)); shortcutBits.add(visibilityBit);
    }

    /** One grid tile: a centred icon above a short label; the whole tile is the hit target. */
    private View tile(int kind, String label, Runnable action) {
        LinearLayout t = new LinearLayout(activity); t.setOrientation(LinearLayout.VERTICAL); t.setGravity(Gravity.CENTER);
        t.setMinimumHeight(dp(52)); t.setPadding(0, dp(6), 0, dp(5)); t.setClickable(true); t.setFocusable(true);
        t.setContentDescription(label);
        MenuIcon icon = new MenuIcon(activity, kind);
        t.addView(icon, new LinearLayout.LayoutParams(dp(21), dp(21)));
        TextView name = new TextView(activity); name.setText(label); name.setTextSize(10.5f); name.setGravity(Gravity.CENTER);
        name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END); name.setPadding(0, dp(4), 0, 0);
        t.addView(name, new LinearLayout.LayoutParams(-1, -2));
        t.setOnClickListener(v -> action.run()); // the menu stays open: only the R button closes it
        tileLabels.add(name); tileIcons.add(icon);
        return t;
    }

    /** Re-flows tiles into rows of three, so a hidden entry never leaves a hole. */
    private void flow(LinearLayout grid, List<View> visible) {
        grid.removeAllViews();
        LinearLayout row = null; int inRow = 0;
        for (View tile : visible) {
            if (tile.getParent() instanceof ViewGroup) ((ViewGroup) tile.getParent()).removeView(tile);
            if (row == null || inRow == COLUMNS) {
                row = new LinearLayout(activity); row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(-1, -2)); inRow = 0;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f); lp.setMargins(dp(1), dp(1), dp(1), dp(1));
            row.addView(tile, lp); inRow++;
        }
        // Keep the last row's tiles the same width as the others.
        if (row != null) for (; inRow < COLUMNS; inRow++) row.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));
    }

    private void updateWalk() {
        String text = String.format(Locale.FRANCE, "%.1f km/h", Control.engine.speedKmh);
        if (!text.contentEquals(walkSpeed.getText())) walkSpeed.setText(text);
    }

    private void updateVisibility() {
        int hidden = Control.quickMenuHidden(); if (hidden == appliedHidden) return;
        appliedHidden = hidden;
        walk.setVisibility((hidden & 2) == 0 ? View.VISIBLE : View.INVISIBLE);
        List<View> visible = new ArrayList<>();
        for (int i = 0; i < shortcuts.size(); i++) if ((hidden & shortcutBits.get(i)) == 0) visible.add(shortcuts.get(i));
        for (View t : shortcuts) if (t.getParent() instanceof ViewGroup) ((ViewGroup) t.getParent()).removeView(t);
        flow(shortcutGrid, visible);
        int shown = visible.isEmpty() ? View.GONE : View.VISIBLE;
        shortcutGrid.setVisibility(shown); shortcutHead.setVisibility(shown);
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
        search.setBackground(pressable(SettingsUi.raised()));
        walkName.setTextColor(SettingsUi.muted());
        walkSpeed.setTextColor(SettingsUi.accent());
        walkIcon.accent = true; walkIcon.invalidate(); searchIcon.invalidate();
        for (TextView head : new TextView[]{shortcutHead, settingsHead}) head.setTextColor(SettingsUi.faint());
        for (View t : shortcuts) t.setBackground(pressable(0x00000000));
        for (View t : settingTiles) t.setBackground(pressable(0x00000000));
        for (TextView label : tileLabels) label.setTextColor(SettingsUi.ink());
        for (MenuIcon icon : tileIcons) icon.invalidate();
        version.setTextColor(SettingsUi.faint());
        discord.setTextColor(SettingsUi.accent()); discord.setBackground(pressable(0x00000000));
    }

    private int dp(float x) { return Math.round(x * activity.getResources().getDisplayMetrics().density); }
}
