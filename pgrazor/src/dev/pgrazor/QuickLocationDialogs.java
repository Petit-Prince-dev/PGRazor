package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.widget.Toast;
import java.util.Locale;

/**
 * The "Favoris" list of the {@link QuickMenu}: the places saved from the World map. A tap goes through
 * the cooldown confirmation, then the host's {@link TeleportTarget} (which validates readiness and calls
 * {@link Control#teleport}). The store is read off the UI thread.
 */
public final class QuickLocationDialogs {
    private QuickLocationDialogs() {}

    /** Applies a destination; returns false when it was rejected so the modal can stay open. */
    public interface TeleportTarget { boolean teleport(double latitude, double longitude); }

    /** Lists saved places; tapping one opens the cooldown confirmation. */
    public static void showFavorites(Activity activity, TeleportTarget target) {
        if (activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()) return;
        new Thread(() -> {
            SavedPlaces.Snapshot snapshot;
            try { snapshot = new SavedPlaces(activity).read(); } catch (RuntimeException e) { snapshot = null; }
            final SavedPlaces.Snapshot snap = snapshot;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()) return;
                AlertDialog.Builder b = new AlertDialog.Builder(themed(activity)).setTitle("Favoris").setNegativeButton("Fermer", null);
                if (snap == null) { b.setMessage("Impossible de lire les lieux enregistrés."); show(activity,b.create()); return; }
                if (snap.entries.isEmpty()) {
                    b.setMessage(snap.damaged ? "Impossible de lire les lieux enregistrés." : "Aucun lieu enregistré. Ajoute des lieux depuis la carte du monde.");
                    show(activity,b.create()); return;
                }
                String[] names = new String[snap.entries.size()];
                for (int i = 0; i < names.length; i++) {
                    SavedPlaces.Entry e = snap.entries.get(i);
                    names[i] = String.format(Locale.US, "%s\n%.6f, %.6f", e.name, e.latitude, e.longitude);
                }
                show(activity,ChoiceList.attach(b.create(),names,which -> TeleportDialog.confirm(activity,snap.entries.get(which).latitude,snap.entries.get(which).longitude,target::teleport)));
                if (snap.damaged) Toast.makeText(activity, "Certains lieux enregistrés sont illisibles. Les entrées valides sont affichées.", Toast.LENGTH_LONG).show();
            });
        }, "pgrazor-favorites-read").start();
    }

    private static void show(Activity activity,AlertDialog dialog){
        DialogLifecycle.bind(activity,dialog,null);dialog.show();
    }

    private static Context themed(Activity a) {
        return new ContextThemeWrapper(a, Control.darkTheme() ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert);
    }
    private static int dp(Context c, float x) { return Math.round(x * c.getResources().getDisplayMetrics().density); }
}
