package dev.pgrazor;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.InsetDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

/**
 * One look for every PGRazor pop-up, applied after the dialog has built and styled itself: a rounded
 * card on the theme surface (full-screen dialogs keep their solid surface), a clean title, a filled
 * accent primary button with quiet secondary buttons, and theme colours on the body's fields, choices
 * and plain text. Text a dialog coloured on purpose (muted, accent, warning…) keeps its colour.
 */
public final class DialogStyle {
    private DialogStyle() {}

    /** Windows that draw their own chrome (the feed's game-style cards) and must not be restyled. */
    private static final java.util.Map<Window, Boolean> OWN_STYLE = new java.util.WeakHashMap<>();
    /** Tag a view with this to leave it (and its children) exactly as its dialog styled it. */
    public static final Object KEEP = new Object();
    static void keep(Window window) { if (window != null) OWN_STYLE.put(window, true); }

    /** Styles the dialog each time it is shown; safe to call before {@code show()}. */
    public static void attach(Dialog dialog) {
        if (!(dialog instanceof AlertDialog)) return;
        Window window = dialog.getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        if (decor.isAttachedToWindow()) decor.post(() -> apply((AlertDialog) dialog));
        decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            // Posted, so it runs after the dialog's own show listener (which often styles its body).
            public void onViewAttachedToWindow(View v) { v.post(() -> apply((AlertDialog) dialog)); }
            public void onViewDetachedFromWindow(View v) {}
        });
    }

    static void apply(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window == null || !dialog.isShowing() || OWN_STYLE.containsKey(window)) return;
        Context c = dialog.getContext();
        boolean full = window.getAttributes().width == ViewGroup.LayoutParams.MATCH_PARENT;
        if (!full) window.setBackgroundDrawable(new InsetDrawable(
            SettingsUi.shape(c, SettingsUi.surface(), 18, SettingsUi.border()), dp(c, 16), dp(c, 24), dp(c, 16), dp(c, 24)));

        TextView title = find(dialog, "alertTitle", TextView.class);
        if (title != null) { title.setTextSize(17); title.setTypeface(SettingsUi.medium()); title.setTextColor(SettingsUi.ink()); }
        TextView message = find(dialog, "message", TextView.class);
        if (message != null) { message.setTextSize(14); message.setTextColor(SettingsUi.muted()); message.setLineSpacing(0, 1.2f); }

        for (String panel : new String[]{"contentPanel", "customPanel"}) {
            View body = find(dialog, panel, View.class);
            if (body != null) recolor(body);
        }
        // A body painted with the square surface colour would poke out of the rounded card's corners.
        ViewGroup custom = find(dialog, "custom", ViewGroup.class);
        if (!full && custom != null && custom.getChildCount() > 0) {
            View root = custom.getChildAt(0);
            if (root.getBackground() instanceof android.graphics.drawable.ColorDrawable
                && ((android.graphics.drawable.ColorDrawable) root.getBackground()).getColor() == SettingsUi.surface()) root.setBackground(null);
        }

        View bar = find(dialog, "buttonPanel", View.class);
        if (bar != null) bar.setPadding(dp(c, 12), dp(c, 4), dp(c, 12), dp(c, 12));
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button b = dialog.getButton(which);
            if (b == null || b.getText() == null || b.getText().length() == 0) continue;
            boolean primary = which == AlertDialog.BUTTON_POSITIVE;
            b.setAllCaps(false); b.setTextSize(14); b.setTypeface(SettingsUi.medium()); b.setStateListAnimator(null);
            b.setMinHeight(dp(c, 38)); b.setMinimumHeight(dp(c, 38)); b.setMinWidth(0); b.setMinimumWidth(0);
            b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
            b.setBackground(SettingsUi.ripple(primary
                ? SettingsUi.rounded(c, SettingsUi.accentFill(), 10)
                : SettingsUi.shape(c, SettingsUi.raised(), 10, SettingsUi.border())));
            b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{SettingsUi.faint(), primary ? SettingsUi.accentInk() : SettingsUi.ink()}));
            if (b.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) b.getLayoutParams();
                lp.setMargins(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4)); b.setLayoutParams(lp);
            }
        }
    }

    /** Theme colours for a dialog body that may not have styled itself. */
    private static void recolor(View view) {
        if (view.getTag() == KEEP) return;
        if (view instanceof EditText) {
            EditText field = (EditText) view;
            field.setTextColor(SettingsUi.ink()); field.setHintTextColor(SettingsUi.faint());
            field.setBackgroundTintList(ColorStateList.valueOf(SettingsUi.accent()));
        } else if (view instanceof CompoundButton) {
            CompoundButton choice = (CompoundButton) view;
            choice.setButtonTintList(SettingsUi.choiceTint());
            if (!deliberate(choice.getCurrentTextColor())) choice.setTextColor(SettingsUi.textColors());
            if (view instanceof Switch) { ((Switch) view).setThumbTintList(SettingsUi.switchThumb()); ((Switch) view).setTrackTintList(SettingsUi.switchTrack()); }
        } else if (view instanceof android.widget.CheckedTextView) {
            android.widget.CheckedTextView item = (android.widget.CheckedTextView) view;
            item.setCheckMarkTintList(SettingsUi.choiceTint());
            if (!deliberate(item.getCurrentTextColor())) item.setTextColor(SettingsUi.textColors());
        } else if (view instanceof Button && !(view instanceof SettingsUi.RowButton)) {
            Button button = (Button) view;
            button.setAllCaps(false); button.setStateListAnimator(null);
            if (!deliberate(button.getCurrentTextColor())) button.setTextColor(SettingsUi.textColors());
            MapTheme.setButtonBackground(button);
        } else if (view instanceof TextView && !(view instanceof SettingsUi.RowButton)) {
            TextView text = (TextView) view;
            if (!deliberate(text.getCurrentTextColor())) text.setTextColor(SettingsUi.textColors());
            text.setLinkTextColor(SettingsUi.accent());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) recolor(group.getChildAt(i));
        }
    }

    /** True for a colour the dialog chose from the palette (kept), false for a platform default (replaced). */
    private static boolean deliberate(int color) {
        for (int c : new int[]{SettingsUi.ink(), SettingsUi.muted(), SettingsUi.faint(), SettingsUi.accent(), SettingsUi.accentInk(),
            SettingsUi.success(), SettingsUi.warn(), SettingsUi.danger()}) if (c == color) return true;
        return false;
    }

    private static <T extends View> T find(AlertDialog dialog, String name, Class<T> type) {
        int id = dialog.getContext().getResources().getIdentifier(name, "id", "android");
        if (id == 0 || dialog.getWindow() == null) return null;
        View view = dialog.getWindow().findViewById(id);
        return type.isInstance(view) ? type.cast(view) : null;
    }

    private static int dp(Context c, float value) { return SettingsUi.dp(c, value); }
}
