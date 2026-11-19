package dev.pgrazor;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

/**
 * The building blocks of the map's Lieux and Autowalk tabs: section headings, cards, list rows that
 * unfold their actions, and buttons. Everything is coloured here from the current theme, so the views
 * are tagged {@link DialogStyle#KEEP}.
 */
final class HubUi {
    private HubUi() {}

    /** A scrolling page with comfortable side margins; add content to the returned column. */
    static ScrollView page(Context c, LinearLayout[] column) {
        ScrollView scroll = new ScrollView(c); scroll.setFillViewport(true); scroll.setTag(DialogStyle.KEEP);
        scroll.setBackgroundColor(SettingsUi.surface());
        LinearLayout body = new LinearLayout(c); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(c, 14), dp(c, 2), dp(c, 14), dp(c, 24));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        column[0] = body;
        return scroll;
    }

    /** Small caps heading, one line of help, and an optional link on the right (e.g. "Importer"). */
    static void section(LinearLayout body, String name, String help, String link, Runnable action) {
        Context c = body.getContext();
        LinearLayout top = new LinearLayout(c); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(c, 4), dp(c, 16), 0, 0);
        TextView head = SettingsUi.text(c, name.toUpperCase(Locale.FRANCE), 11, SettingsUi.faint());
        head.setTypeface(SettingsUi.medium()); head.setLetterSpacing(0.08f);
        top.addView(head, new LinearLayout.LayoutParams(0, -2, 1));
        if (link != null) {
            TextView go = SettingsUi.text(c, link, 12, SettingsUi.accent()); go.setTypeface(SettingsUi.medium());
            go.setPadding(dp(c, 10), dp(c, 4), dp(c, 6), dp(c, 4)); go.setClickable(true); go.setFocusable(true);
            go.setBackground(SettingsUi.ripple(SettingsUi.rounded(c, 0x00000000, 8)));
            go.setOnClickListener(v -> action.run());
            top.addView(go);
        }
        body.addView(top);
        if (help != null) {
            TextView line = SettingsUi.text(c, help, 12, SettingsUi.muted()); line.setPadding(dp(c, 4), dp(c, 2), 0, 0);
            body.addView(line);
        }
        View gap = new View(c); body.addView(gap, new LinearLayout.LayoutParams(-1, dp(c, 8)));
    }

    static LinearLayout card(LinearLayout body) {
        Context c = body.getContext();
        LinearLayout card = new LinearLayout(c); card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(SettingsUi.cardBackground(c)); card.setPadding(dp(c, 2), dp(c, 2), dp(c, 2), dp(c, 2));
        body.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    /** A quiet centred message inside a card: an icon, a short title and one line. */
    static void empty(LinearLayout body, int icon, String title, String note) {
        Context c = body.getContext();
        LinearLayout box = card(body); box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(c, 18), dp(c, 18), dp(c, 18), dp(c, 18));
        MenuIcon glyph = new MenuIcon(c, icon); glyph.disabled = true;
        box.addView(glyph, new LinearLayout.LayoutParams(dp(c, 26), dp(c, 26)));
        TextView head = SettingsUi.text(c, title, 14, SettingsUi.ink()); head.setTypeface(SettingsUi.medium());
        head.setGravity(Gravity.CENTER); head.setPadding(0, dp(c, 8), 0, dp(c, 2));
        box.addView(head);
        TextView line = SettingsUi.text(c, note, 12, SettingsUi.muted()); line.setGravity(Gravity.CENTER);
        box.addView(line);
    }

    /**
     * One list row: a badge, a name over a detail line, and a chevron. With {@code actions} the tap unfolds
     * them under the row (one row open at a time across the page); otherwise the tap runs {@code tap}.
     * Returns the row itself, so the caller can add a long press.
     */
    static View row(LinearLayout card, String badge, String name, String detail, View actions, Runnable tap, View[] unfolded) {
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
        tag.setSingleLine(true); tag.setEllipsize(TextUtils.TruncateAt.END);
        tag.setBackground(SettingsUi.accentPill(c)); tag.setPadding(dp(c, 3), dp(c, 4), dp(c, 3), dp(c, 4));
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
        if (actions == null) { line.setOnClickListener(v -> tap.run()); return line; }
        actions.setVisibility(View.GONE);
        card.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        line.setOnClickListener(v -> {
            boolean open = actions.getVisibility() != View.VISIBLE;
            if (unfolded[0] != null && unfolded[0] != actions) unfolded[0].setVisibility(View.GONE);
            actions.setVisibility(open ? View.VISIBLE : View.GONE);
            unfolded[0] = open ? actions : null;
        });
        return line;
    }

    /** The unfolded action strip of a row: equal-width buttons, the last one primary. */
    static LinearLayout actions(Context c, Button... buttons) {
        LinearLayout strip = new LinearLayout(c); strip.setPadding(dp(c, 12), 0, dp(c, 12), dp(c, 12));
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(c, 40), 1);
            if (i < buttons.length - 1) lp.rightMargin = dp(c, 8);
            strip.addView(buttons[i], lp);
        }
        return strip;
    }

    static Button button(Context c, String text, boolean primary) {
        Button b = new Button(c); b.setText(text); b.setAllCaps(false); b.setTextSize(13); b.setTypeface(SettingsUi.medium());
        b.setStateListAnimator(null); b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(c, 6), 0, dp(c, 6), 0); b.setSingleLine(true); b.setEllipsize(TextUtils.TruncateAt.END);
        paint(b, primary);
        return b;
    }

    static void paint(Button b, boolean primary) {
        Context c = b.getContext();
        b.setTextColor(primary ? SettingsUi.accentInk() : SettingsUi.ink());
        b.setBackground(SettingsUi.ripple(primary ? SettingsUi.rounded(c, SettingsUi.accentFill(), SettingsUi.RADIUS_CONTROL)
            : SettingsUi.shape(c, SettingsUi.raised(), SettingsUi.RADIUS_CONTROL, SettingsUi.border())));
    }

    /** "à 85 m" / "à 1,2 km" / "à 9 540 km" from the player's current position. */
    static String away(double latitude, double longitude) {
        double meters = RouteStart.meters(Control.engine.latitude, Control.engine.longitude, latitude, longitude);
        return Double.isFinite(meters) ? "à " + length(meters) : "";
    }

    /** "85 m" / "1,2 km" / "9 540 km". */
    static String length(double meters) {
        if (meters < 1000) return Math.round(meters) + " m";
        if (meters < 100000) return String.format(Locale.FRANCE, "%.1f km", meters / 1000);
        return String.format(Locale.FRANCE, "%,d km", Math.round(meters / 1000));
    }

    static int dp(Context c, float value) { return SettingsUi.dp(c, value); }
}
