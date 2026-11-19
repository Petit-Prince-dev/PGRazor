package dev.pgrazor;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;

/**
 * PGRazor's one and only menu, opened by the R launcher. It is part of the HUD card itself: the
 * launcher's line stays on top and the menu unfolds right under it, so the same R button closes it
 * (nothing else does: no outside-tap dismissal).
 *
 * <p>Layout: the walking speed (tap to cycle), then one line per option: Carte (the travel hub: map,
 * places and autowalk), the feeds, then one line per settings category, each opening that category as
 * a floating sheet. Every option of the mod is reachable from here.
 */
public final class QuickMenu {
    public interface Listener {
        /** The travel hub: map, places (favourites, hotspots, walks) and autowalk. */
        void onMap();
        /** Cycles the walk speed and returns the new value (km/h); the header refreshes from it. */
        double onCycleWalkSpeed();
        void onFeeds();
        /** Opens one settings category (index into the pages the settings sheet was built with). */
        void onSettingsPage(int index);
    }

    private final Activity activity;
    private final Listener listener;
    private final LinearLayout card, list, walk;
    private final TextView walkSpeed, walkName;
    private final MenuIcon walkIcon;
    private final List<View> tiles = new ArrayList<>();
    private final List<TextView> tileLabels = new ArrayList<>();
    private final List<MenuIcon> tileIcons = new ArrayList<>();
    private Runnable onToggle;

    /**
     * Builds the menu; the host places {@link #view()} inside its card and calls {@link #toggle()}.
     * {@code pageNames}/{@code pageIcons} describe the settings categories, in page order.
     */
    public QuickMenu(Activity activity, Listener listener, String[] pageNames, int[] pageIcons) {
        this.activity = activity; this.listener = listener;
        card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, dp(6), 0, dp(2));

        // Walking speed: tap cycles it, the menu stays open.
        walk = new LinearLayout(activity); walk.setOrientation(LinearLayout.HORIZONTAL); walk.setGravity(Gravity.CENTER_VERTICAL);
        walk.setPadding(dp(10), 0, dp(10), 0); walk.setClickable(true); walk.setFocusable(true);
        walk.setContentDescription("Vitesse de marche. Touche pour changer.");
        walkIcon = new MenuIcon(activity, MenuIcon.WALK); walk.addView(walkIcon, new LinearLayout.LayoutParams(dp(16), dp(16)));
        walkName = new TextView(activity); walkName.setText("Vitesse"); walkName.setTextSize(12); walkName.setPadding(dp(8), 0, dp(6), 0);
        walk.addView(walkName, new LinearLayout.LayoutParams(0, -2, 1f));
        walkSpeed = new TextView(activity); walkSpeed.setTextSize(12); walkSpeed.setTypeface(SettingsUi.mono()); walkSpeed.setSingleLine(true);
        walk.addView(walkSpeed, new LinearLayout.LayoutParams(-2, -2));
        walk.setOnClickListener(v -> { listener.onCycleWalkSpeed(); updateWalk(); });
        LinearLayout.LayoutParams walkParams = new LinearLayout.LayoutParams(-1, dp(36)); walkParams.bottomMargin = dp(6);
        card.addView(walk, walkParams);

        // Everything else, one option per line: moving around first, then the settings categories.
        list = new LinearLayout(activity); list.setOrientation(LinearLayout.VERTICAL);
        card.addView(list, new LinearLayout.LayoutParams(-1, -2));
        line(MenuIcon.MAP, "Carte", listener::onMap);
        line(MenuIcon.FEEDS, "Flux", listener::onFeeds);
        for (int i = 0; i < pageNames.length; i++) {
            final int index = i;
            line(pageIcons[i], pageNames[i], () -> listener.onSettingsPage(index));
        }

        card.setVisibility(View.GONE);
        applyTheme();
    }

    /** One option: its icon, then its name in large type to the right; the whole line is the hit target. */
    private void line(int kind, String label, Runnable action) {
        LinearLayout t = new LinearLayout(activity); t.setOrientation(LinearLayout.HORIZONTAL); t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(10), 0, dp(10), 0); t.setClickable(true); t.setFocusable(true);
        t.setContentDescription(label);
        MenuIcon icon = new MenuIcon(activity, kind);
        t.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView name = new TextView(activity); name.setText(label); name.setTextSize(15); name.setTypeface(SettingsUi.medium());
        name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END); name.setPadding(dp(14), 0, 0, 0);
        t.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        t.setOnClickListener(v -> action.run()); // the menu stays open: only the R button closes it
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(42)); lp.topMargin = dp(1);
        list.addView(t, lp);
        tiles.add(t); tileLabels.add(name); tileIcons.add(icon);
    }

    private void updateWalk() {
        String text = SettingsUi.speedLabel(Control.engine.speedKmh);
        if (!text.contentEquals(walkSpeed.getText())) walkSpeed.setText(text);
    }

    public void onRefresh() { if (isShowing()) updateWalk(); }
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
        walkName.setTextColor(SettingsUi.muted());
        walkSpeed.setTextColor(SettingsUi.accent());
        walkIcon.accent = true; walkIcon.invalidate();
        for (View t : tiles) t.setBackground(pressable(0x00000000));
        for (TextView label : tileLabels) label.setTextColor(SettingsUi.ink());
        for (MenuIcon icon : tileIcons) icon.invalidate();
    }

    private int dp(float x) { return Math.round(x * activity.getResources().getDisplayMetrics().density); }
}
