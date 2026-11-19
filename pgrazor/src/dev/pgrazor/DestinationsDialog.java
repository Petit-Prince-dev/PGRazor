package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Destinations": the hotspots and the ready-made GPS walks ({@link PresetRoutes}) in one full-screen
 * list. From the R menu a row unfolds its actions in place (walk, teleport, start); from the World map
 * a row only places the pin or loads the walk into the route builder.
 *
 * <p>The list is the whole window under a fixed header, with no button bar under it, so nothing can be
 * pushed off screen.
 */
final class DestinationsDialog {
    private DestinationsDialog() {}

    /** Starts a walk along these stops; false when it was refused, so the list stays open. */
    interface RouteStarter { boolean start(double[][] stops, boolean loop); }
    /** Receives a ready-made walk to edit on the World map. */
    interface RouteLoader { void load(GpxRoute route); }

    /** From the R menu: walk or teleport to a hotspot, start a ready-made walk. */
    static AlertDialog show(Activity activity, QuickLocationDialogs.TeleportTarget teleport,
                            QuickLocationDialogs.TeleportTarget walk, RouteStarter route) {
        return build(activity, teleport, walk, route, null, null);
    }

    /** From the World map: a hotspot places the pin; a walk opens in the route builder (or shows its start). */
    static AlertDialog pick(Activity activity, QuickLocationDialogs.TeleportTarget select, RouteLoader loader) {
        return build(activity, null, null, null, select, loader);
    }

    private static AlertDialog build(Activity activity, QuickLocationDialogs.TeleportTarget teleport,
                                     QuickLocationDialogs.TeleportTarget walk, RouteStarter route,
                                     QuickLocationDialogs.TeleportTarget select, RouteLoader loader) {
        if (activity.isFinishing() || activity.isDestroyed()) return null;
        Context ui = MapTheme.wrap(activity);
        boolean picking = select != null;
        List<AlertDialog> children = new ArrayList<>();
        View[] unfolded = {null};

        FillLayout root = new FillLayout(ui); root.setOrientation(LinearLayout.VERTICAL); root.setTag(DialogStyle.KEEP);
        root.setBackgroundColor(SettingsUi.surface());

        LinearLayout header = new LinearLayout(ui); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(ui, 18), dp(ui, 4), dp(ui, 6), dp(ui, 4));
        LinearLayout titles = new LinearLayout(ui); titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = SettingsUi.text(ui, "Destinations", 18, SettingsUi.ink()); title.setTypeface(SettingsUi.medium());
        titles.addView(title);
        titles.addView(SettingsUi.text(ui, picking ? "Choisis un lieu ou un trajet pour la carte" : "Hotspots et trajets GPS", 12, SettingsUi.muted()));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = SettingsUi.text(ui, "✕", 18, SettingsUi.muted()); close.setGravity(Gravity.CENTER);
        close.setClickable(true); close.setFocusable(true); close.setContentDescription("Fermer");
        close.setBackground(SettingsUi.ripple(SettingsUi.rounded(ui, 0x00000000, 20)));
        header.addView(close, new LinearLayout.LayoutParams(dp(ui, 44), dp(ui, 44)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(ui, 60)));

        ScrollView scroll = new ScrollView(ui); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(ui); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(ui, 14), 0, dp(ui, 14), dp(ui, 20));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        AlertDialog dialog = new AlertDialog.Builder(ui).setView(root).create();
        close.setOnClickListener(v -> dialog.dismiss());

        // ----- Hotspots
        section(body, "Hotspots", Hotspots.ALL.size() + " lieux animés, avec leur fuseau horaire");
        LinearLayout spots = card(body);
        for (Hotspots.Spot spot : Hotspots.ALL) {
            String badge = spot.utc.replace("UTC ", "UTC ");
            if (picking) {
                row(spots, badge, spot.name, spot.city + " · " + spot.country, null, () -> {
                    if (select.teleport(spot.latitude, spot.longitude)) dialog.dismiss();
                }, unfolded);
            } else {
                LinearLayout actions = actions(ui);
                Button walkThere = button(ui, "Marcher", false), teleportThere = button(ui, "Téléporter", true);
                walkThere.setOnClickListener(v -> { if (walk.teleport(spot.latitude, spot.longitude)) {
                    dialog.dismiss(); Toast.makeText(activity, "Marche vers " + spot.name, Toast.LENGTH_SHORT).show(); } });
                teleportThere.setOnClickListener(v -> children.add(TeleportDialog.confirm(activity, spot.latitude, spot.longitude, (lat, lon) -> {
                    if (!teleport.teleport(lat, lon)) return false;
                    dialog.dismiss(); return true;
                })));
                addPair(ui, actions, walkThere, teleportThere);
                row(spots, badge, spot.name, spot.city + " · " + spot.country, actions, null, unfolded);
            }
        }

        // ----- Ready-made GPS walks
        section(body, "Trajets GPS", "Des balades toutes prêtes, à lancer en un geste");
        if (PresetRoutes.ALL.isEmpty()) {
            LinearLayout empty = card(body); empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setPadding(dp(ui, 18), dp(ui, 22), dp(ui, 18), dp(ui, 22));
            MenuIcon icon = new MenuIcon(ui, MenuIcon.AUTOWALK); icon.disabled = true;
            empty.addView(icon, new LinearLayout.LayoutParams(dp(ui, 28), dp(ui, 28)));
            TextView soon = SettingsUi.text(ui, "Bientôt disponibles", 14, SettingsUi.ink()); soon.setTypeface(SettingsUi.medium());
            soon.setGravity(Gravity.CENTER); soon.setPadding(0, dp(ui, 8), 0, dp(ui, 2));
            empty.addView(soon);
            TextView note = SettingsUi.text(ui, "Les trajets prédéfinis arriveront ici avec une prochaine mise à jour.", 12, SettingsUi.muted());
            note.setGravity(Gravity.CENTER);
            empty.addView(note);
        } else {
            LinearLayout walks = card(body);
            for (PresetRoutes.Preset preset : PresetRoutes.ALL) {
                double[][] stops = preset.route.points();
                String detail = preset.place + " · " + stops.length + " arrêts" + (preset.loop ? " · en boucle" : "");
                if (picking) {
                    row(walks, preset.loop ? "Boucle" : "Trajet", preset.route.name, detail, null, () -> {
                        if (loader != null) { loader.load(preset.route); dialog.dismiss(); }
                        else if (select.teleport(stops[0][0], stops[0][1])) dialog.dismiss();
                    }, unfolded);
                } else {
                    LinearLayout actions = actions(ui);
                    Button start = button(ui, "Aller au départ", false), go = button(ui, "Démarrer", true);
                    start.setOnClickListener(v -> children.add(TeleportDialog.confirm(activity, stops[0][0], stops[0][1], (lat, lon) -> teleport.teleport(lat, lon))));
                    go.setOnClickListener(v -> { if (route.start(preset.route.points(), preset.loop)) {
                        dialog.dismiss(); Toast.makeText(activity, "Trajet lancé : " + preset.route.name, Toast.LENGTH_SHORT).show(); } });
                    addPair(ui, actions, start, go);
                    row(walks, preset.loop ? "Boucle" : "Trajet", preset.route.name, detail, actions, null, unfolded);
                }
            }
        }

        DialogLifecycle.bind(activity, dialog, () -> { for (AlertDialog child : children) if (child != null) child.dismiss(); });
        dialog.setOnShowListener(d -> { DialogStyle.keep(dialog.getWindow()); MapTheme.fullScreen(dialog); });
        dialog.show();
        return dialog;
    }

    /** A small caps heading and one line of help above a card. */
    private static void section(LinearLayout body, String name, String help) {
        Context c = body.getContext();
        TextView head = SettingsUi.text(c, name.toUpperCase(Locale.FRANCE), 11, SettingsUi.faint());
        head.setTypeface(SettingsUi.medium()); head.setLetterSpacing(0.08f); head.setPadding(dp(c, 4), dp(c, 14), 0, 0);
        body.addView(head);
        TextView line = SettingsUi.text(c, help, 12, SettingsUi.muted()); line.setPadding(dp(c, 4), dp(c, 2), 0, dp(c, 8));
        body.addView(line);
    }

    private static LinearLayout card(LinearLayout body) {
        Context c = body.getContext();
        LinearLayout card = new LinearLayout(c); card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(SettingsUi.cardBackground(c)); card.setPadding(dp(c, 2), dp(c, 2), dp(c, 2), dp(c, 2));
        body.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    /**
     * One list row: a badge, a name over a detail line, and a chevron. With {@code actions} the tap unfolds
     * them under the row (one row open at a time); otherwise the tap runs {@code tap}.
     */
    private static void row(LinearLayout card, String badge, String name, String detail, View actions, Runnable tap, View[] unfolded) {
        Context c = card.getContext();
        if (card.getChildCount() > 0) {
            View rule = new View(c); rule.setBackgroundColor(SettingsUi.border());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 0.7f))); lp.setMargins(dp(c, 14), 0, dp(c, 14), 0);
            card.addView(rule, lp);
        }
        LinearLayout line = new LinearLayout(c); line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(c, 12), dp(c, 10), dp(c, 10), dp(c, 10)); line.setMinimumHeight(dp(c, 58));
        line.setClickable(true); line.setFocusable(true); line.setContentDescription(name + ", " + detail);
        line.setBackground(SettingsUi.ripple(SettingsUi.rounded(c, 0x00000000, SettingsUi.RADIUS_CARD)));
        TextView tag = SettingsUi.text(c, badge, 11, SettingsUi.accent()); tag.setTypeface(SettingsUi.mono()); tag.setGravity(Gravity.CENTER);
        tag.setSingleLine(true); tag.setBackground(SettingsUi.accentPill(c)); tag.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
        line.addView(tag, new LinearLayout.LayoutParams(dp(c, 62), -2));
        LinearLayout texts = new LinearLayout(c); texts.setOrientation(LinearLayout.VERTICAL); texts.setPadding(dp(c, 12), 0, dp(c, 8), 0);
        TextView title = SettingsUi.text(c, name, 14, SettingsUi.ink()); title.setTypeface(SettingsUi.medium());
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        TextView sub = SettingsUi.text(c, detail, 12, SettingsUi.muted()); sub.setSingleLine(true); sub.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(sub);
        line.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        TextView chevron = SettingsUi.text(c, actions == null ? "›" : "⌄", 18, SettingsUi.faint());
        line.addView(chevron, new LinearLayout.LayoutParams(-2, -2));
        card.addView(line, new LinearLayout.LayoutParams(-1, -2));
        if (actions == null) { line.setOnClickListener(v -> tap.run()); return; }
        actions.setVisibility(View.GONE);
        card.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        line.setOnClickListener(v -> {
            boolean open = actions.getVisibility() != View.VISIBLE;
            if (unfolded[0] != null && unfolded[0] != actions) unfolded[0].setVisibility(View.GONE);
            actions.setVisibility(open ? View.VISIBLE : View.GONE);
            unfolded[0] = open ? actions : null;
        });
    }

    private static LinearLayout actions(Context c) {
        LinearLayout actions = new LinearLayout(c); actions.setPadding(dp(c, 12), 0, dp(c, 12), dp(c, 12));
        return actions;
    }

    private static void addPair(Context c, LinearLayout actions, Button first, Button second) {
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(c, 40), 1); left.rightMargin = dp(c, 8);
        actions.addView(first, left);
        actions.addView(second, new LinearLayout.LayoutParams(0, dp(c, 40), 1));
    }

    private static Button button(Context c, String text, boolean primary) {
        Button b = new Button(c); b.setText(text); b.setAllCaps(false); b.setTextSize(13); b.setTypeface(SettingsUi.medium());
        b.setStateListAnimator(null); b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(c, 8), 0, dp(c, 8), 0); b.setSingleLine(true);
        b.setTextColor(primary ? SettingsUi.accentInk() : SettingsUi.ink());
        b.setBackground(SettingsUi.ripple(primary ? SettingsUi.rounded(c, SettingsUi.accentFill(), SettingsUi.RADIUS_CONTROL)
            : SettingsUi.shape(c, SettingsUi.raised(), SettingsUi.RADIUS_CONTROL, SettingsUi.border())));
        return b;
    }

    /** Never measured without a bound: the list then scrolls inside the screen instead of growing past it. */
    private static final class FillLayout extends LinearLayout {
        FillLayout(Context c) { super(c); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED)
                heightSpec = MeasureSpec.makeMeasureSpec(getResources().getDisplayMetrics().heightPixels, MeasureSpec.AT_MOST);
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    private static int dp(Context c, float value) { return SettingsUi.dp(c, value); }
}
