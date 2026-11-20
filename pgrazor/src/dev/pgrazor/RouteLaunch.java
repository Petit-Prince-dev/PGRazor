package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;

/**
 * Starting a walk, from the map or from Lieux: a short choice between walking to its first stop and
 * teleporting there (the button shows the cooldown it would start). Either way the walk then runs from
 * its first stop.
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
        AlertDialog dialog = new AlertDialog.Builder(ui).setTitle("Démarrer « " + name + " »").setMessage(message)
            .setNegativeButton("Marcher", (d, which) -> choice.start(false))
            .setPositiveButton(Cooldown.teleportLabel(stops[0][0], stops[0][1]), (d, which) -> choice.start(true))
            .create();
        DialogLifecycle.bind(activity, dialog, null);
        dialog.show();
        return dialog;
    }
}
