package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.util.Locale;

/**
 * Quick-teleport modals for the {@link QuickMenu}. Both delegate the actual move to a
 * {@link TeleportTarget} (the host validates readiness and calls {@link Control#teleport}, or
 * {@link Control#walkTo} for the coordinate modal's Walk button),
 * and reuse the shared {@link SavedPlaces} store. Persistence reads run off the UI thread.
 */
public final class QuickLocationDialogs {
    private QuickLocationDialogs() {}

    /** Applies a destination; returns false when it was rejected so the modal can stay open. */
    public interface TeleportTarget { boolean teleport(double latitude, double longitude); }

    /** A simple latitude/longitude modal (no speed field): Teleport there, or Walk there at the current speed. */
    public static void showTeleport(Activity activity, TeleportTarget target, TeleportTarget walk) {
        if (activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()) return;
        Context ctx = themed(activity);
        LinearLayout form = new LinearLayout(ctx); form.setOrientation(LinearLayout.VERTICAL);
        int p = dp(ctx, 22); form.setPadding(p, dp(ctx, 8), p, dp(ctx, 8));
        // Where you are now, with a button to copy it.
        final double hereLat = Control.engine.latitude, hereLon = Control.engine.longitude;
        LinearLayout here = new LinearLayout(ctx); here.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.TextView hereText = new android.widget.TextView(ctx);
        hereText.setText("Tu es à\n" + CoordinateClipboard.text(hereLat, hereLon)); hereText.setTextSize(14);
        here.addView(hereText, new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.Button copy = new android.widget.Button(ctx); copy.setText("Copier"); copy.setAllCaps(false); copy.setTextSize(13);
        copy.setContentDescription("Copier tes coordonnées actuelles");
        MapTheme.setButtonBackground(copy);
        copy.setOnClickListener(v -> CoordinateClipboard.copy(activity, hereLat, hereLon, "Ta position est copiée"));
        here.addView(copy, new LinearLayout.LayoutParams(dp(ctx, 76), dp(ctx, 48)));
        android.widget.Button favorite = new android.widget.Button(ctx); favorite.setText("Favori"); favorite.setAllCaps(false); favorite.setTextSize(13);
        favorite.setContentDescription("Ajouter ta position actuelle aux favoris");
        MapTheme.setButtonBackground(favorite);
        favorite.setOnClickListener(v -> saveFavorite(activity, hereLat, hereLon));
        LinearLayout.LayoutParams favoriteLp = new LinearLayout.LayoutParams(dp(ctx, 92), dp(ctx, 48)); favoriteLp.leftMargin = dp(ctx, 6);
        here.addView(favorite, favoriteLp);
        LinearLayout.LayoutParams hereLp = new LinearLayout.LayoutParams(-1, -2); hereLp.bottomMargin = dp(ctx, 8);
        form.addView(here, hereLp);
        EditText coordinates = input(ctx, "Latitude, longitude");
        form.addView(coordinates);
        AlertDialog dialog = new AlertDialog.Builder(ctx).setTitle("Aller aux coordonnées").setView(form)
            .setNegativeButton("Annuler", null).setNeutralButton("Marcher", null).setPositiveButton("Téléporter", null).create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                double[] point;
                try { point=CoordinateInput.parse(coordinates.getText().toString()); }
                catch (IllegalArgumentException e) { coordinates.setError(e.getMessage()); return; }
                TeleportDialog.confirm(activity,point[0],point[1],(lat,lon)->{
                    if(!target.teleport(lat,lon))return false;
                    dialog.dismiss();return true;
                });
            });
            // Walking needs no cooldown check: it starts from where you are at the current speed.
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                double[] point;
                try { point=CoordinateInput.parse(coordinates.getText().toString()); }
                catch (IllegalArgumentException e) { coordinates.setError(e.getMessage()); return; }
                if(walk.teleport(point[0],point[1])){
                    dialog.dismiss();
                    Toast.makeText(activity,"Marche vers "+CoordinateClipboard.text(point[0],point[1]),Toast.LENGTH_SHORT).show();
                }
            });
        });
        show(activity,dialog);
    }

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

    /** Names and saves a location as a favorite (the World map's saved places). */
    private static void saveFavorite(Activity activity, double latitude, double longitude) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Context ctx = themed(activity);
        LinearLayout form = new LinearLayout(ctx); form.setOrientation(LinearLayout.VERTICAL);
        int p = dp(ctx, 22); form.setPadding(p, dp(ctx, 8), p, dp(ctx, 8));
        android.widget.TextView where = new android.widget.TextView(ctx); where.setText(CoordinateClipboard.text(latitude, longitude)); where.setTextSize(14);
        form.addView(where);
        EditText name = input(ctx, "Nom du lieu (jusqu'à " + SavedPlaces.NAME_LIMIT + " caractères)");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(SavedPlaces.NAME_LIMIT)});
        form.addView(name);
        AlertDialog dialog = new AlertDialog.Builder(ctx).setTitle("Ajouter aux favoris").setView(form)
            .setNegativeButton("Annuler", null).setPositiveButton("Enregistrer", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final SavedPlaces.Entry entry;
            try { entry = new SavedPlaces.Entry(name.getText().toString(), latitude, longitude); }
            catch (IllegalArgumentException e) { name.setError(e.getMessage()); return; }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            new Thread(() -> {
                String error = null;
                try { new SavedPlaces(activity).add(entry); }
                catch (java.io.IOException | RuntimeException e) { error = e.getMessage() == null ? "Impossible d'enregistrer ce lieu." : e.getMessage(); }
                final String failed = error;
                activity.runOnUiThread(() -> {
                    if (!dialog.isShowing()) return;
                    if (failed != null) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); name.setError(failed); return; }
                    dialog.dismiss();
                    Toast.makeText(activity, "Enregistré : " + entry.name + " dans les favoris", Toast.LENGTH_SHORT).show();
                });
            }, "pgrazor-favorites-save").start();
        }));
        show(activity, dialog);
    }

    private static EditText input(Context ctx, String hint) {
        EditText e = new EditText(ctx); e.setSingleLine(true); e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return e;
    }
    private static Context themed(Activity a) {
        return new ContextThemeWrapper(a, Control.darkTheme() ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert);
    }
    private static int dp(Context c, float x) { return Math.round(x * c.getResources().getDisplayMetrics().density); }
}
