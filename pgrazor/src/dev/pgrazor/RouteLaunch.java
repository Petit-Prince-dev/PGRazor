package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Starting a walk, from the map or from Lieux: a short choice between walking to its first stop and
 * teleporting there (the button shows the cooldown it would start). Either way the walk then runs from
 * its first stop. Only ✕ (or Back) closes it: a touch beside it does nothing.
 */
final class RouteLaunch {
    private RouteLaunch() {}

    /** The choice made: {@code teleport} to the first stop first, or walk there. */
    interface Choice { void start(boolean teleport); }

    static AlertDialog ask(Activity activity, Context ui, String name, double[][] stops, boolean loop, Choice choice) {
        if (activity.isFinishing() || activity.isDestroyed() || stops == null || stops.length == 0) return null;
        double away = RouteStart.meters(Control.engine.latitude, Control.engine.longitude, stops[0][0], stops[0][1]);
        String message = stops.length + (stops.length == 1 ? " arrêt" : " arrêts") + (loop ? ", en boucle" : "")
            + (Double.isFinite(away) ? "\nPremier arrêt à " + HubUi.length(away) : "");

        // The title line, with ✕ in its corner.
        LinearLayout head = new LinearLayout(ui); head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(SettingsUi.dp(ui, 24), SettingsUi.dp(ui, 14), SettingsUi.dp(ui, 10), 0);
        TextView title = SettingsUi.text(ui, "Démarrer « " + name + " »", 17, SettingsUi.ink()); title.setTypeface(SettingsUi.medium());
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = SettingsUi.text(ui, "✕", 16, SettingsUi.muted()); close.setGravity(Gravity.CENTER);
        close.setClickable(true); close.setFocusable(true); close.setContentDescription("Fermer");
        close.setBackground(SettingsUi.ripple(SettingsUi.rounded(ui, 0x00000000, 20)));
        head.addView(close, new LinearLayout.LayoutParams(SettingsUi.dp(ui, 40), SettingsUi.dp(ui, 40)));

        AlertDialog dialog = new AlertDialog.Builder(ui).setCustomTitle(head).setMessage(message)
            .setNegativeButton("Marcher", (d, which) -> choice.start(false))
            .setPositiveButton(Cooldown.teleportLabel(stops[0][0], stops[0][1]), (d, which) -> choice.start(true))
            .create();
        dialog.setCanceledOnTouchOutside(false);
        close.setOnClickListener(v -> dialog.dismiss());
        DialogLifecycle.bind(activity, dialog, null);
        dialog.show();
        return dialog;
    }
}
