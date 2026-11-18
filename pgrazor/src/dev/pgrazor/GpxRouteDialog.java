package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/**
 * Shared GPX route manager that the Auto-walk and World-map dialogs delegate to. It owns the small
 * dialogs for importing a {@code .gpx} file, listing/selecting/deleting saved routes, saving the
 * current route under a name, and exporting it.
 *
 * <p>It never moves the avatar: a chosen route is handed to the caller's {@link Selection}, which
 * decides when (if ever) to Start. File I/O ({@link GpxCodec} and {@link GpxStore}, both bounded by
 * {@link GpxCodec#MAX_POINTS}) runs off the main thread and only touches the UI again while the host
 * Activity is alive. Importing/exporting uses {@link GpxDocuments}; its picker round-trip may dismiss
 * the calling dialog, so the chosen route is also passed to the caller's own holder and restored when
 * the dialog is reopened.</p>
 */
public final class GpxRouteDialog {
    private GpxRouteDialog() {}

    /** Receives a route the user imported or picked. Always main-thread; must be lifecycle-safe (no-op if closed). */
    public interface Selection { void onRoute(GpxRoute route); }
    /** Supplies the route currently built by the caller, for Save/Export; null when there is nothing to save. */
    public interface Source { GpxRoute route(); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int NAME_LIMIT = GpxRoute.NAME_LIMIT;

    // ---- entry points --------------------------------------------------------------------

    /** Import a {@code .gpx} via the system picker; {@code where} names the caller for the confirmation toast. */
    public static void importGpx(Activity activity, String where, Selection selection) {
        if (!alive(activity) || selection == null) return;
        GpxDocuments.open(activity, new GpxDocuments.Callback() {
            public void onPicked(Uri uri) { readImport(activity, uri, where, selection); }
            public void onCancelled() { }
        });
    }

    /** List saved routes; selecting one loads and delivers it. The list also offers delete. */
    public static void chooseSaved(Activity activity, Selection selection) {
        if (!alive(activity) || selection == null) return;
        loadList(activity, selection);
    }

    /** Save the caller's current route under a user-entered name. */
    public static void saveRoute(Activity activity, Source source) {
        if (!alive(activity)) return;
        GpxRoute current = source == null ? null : source.route();
        if (current == null) { note(activity, "Crée une route avec au moins un arrêt avant d'enregistrer."); return; }
        promptSave(activity, current);
    }

    /** Export the caller's current route to a {@code .gpx} the user chooses. */
    public static void exportRoute(Activity activity, Source source) {
        if (!alive(activity)) return;
        final GpxRoute current = source == null ? null : source.route();
        if (current == null) { note(activity, "Crée une route avec au moins un arrêt avant d'exporter."); return; }
        GpxDocuments.create(activity, fileName(current.name), new GpxDocuments.Callback() {
            public void onPicked(Uri uri) { writeExport(activity, uri, current); }
            public void onCancelled() { }
        });
    }

    /** The World-map GPX menu: Save route, Import GPX, Saved routes, Export route. */
    public static void menu(Activity activity, Source source, Selection selection) {
        if (!alive(activity)) return;
        String[] items = {"Enregistrer la route", "Importer un GPX", "Routes enregistrées", "Exporter la route"};
        AlertDialog dialog = ChoiceList.attach(new AlertDialog.Builder(MapTheme.wrap(activity)).setTitle("Routes GPX")
            .setNegativeButton("Fermer", null).create(), items, which -> {
                switch (which) {
                    case 0: saveRoute(activity, source); break;
                    case 1: importGpx(activity, "le créateur de route", selection); break;
                    case 2: chooseSaved(activity, selection); break;
                    case 3: exportRoute(activity, source); break;
                }
            });
        present(activity, dialog);
    }

    // ---- import --------------------------------------------------------------------------

    private static void readImport(Activity activity, Uri uri, String where, Selection selection) {
        if (!alive(activity)) return;
        run(() -> {
            List<GpxRoute> routes = null;
            String error = null;
            try {
                String fallback = displayName(activity, uri);
                InputStream in = activity.getContentResolver().openInputStream(uri);
                if (in == null) throw new IOException("Impossible d'ouvrir le fichier choisi.");
                try { routes = GpxCodec.read(in, fallback); }
                finally { in.close(); }
            } catch (IOException | RuntimeException e) { error = message(e, "Impossible de lire ce fichier GPX."); }
            final List<GpxRoute> result = routes;
            final String err = error;
            MAIN.post(() -> {
                if (!alive(activity)) return;
                if (result == null) { note(activity, err); return; }
                if (result.isEmpty()) { note(activity, "Aucune route trouvée dans ce fichier GPX."); return; }
                if (result.size() == 1) deliverImported(activity, result.get(0), where, selection);
                else chooseImported(activity, result, where, selection);
            });
        });
    }

    private static void chooseImported(Activity activity, List<GpxRoute> routes, String where, Selection selection) {
        String[] names = new String[routes.size()];
        for (int i = 0; i < names.length; i++) {
            GpxRoute r = routes.get(i);
            names[i] = String.format(Locale.US, "%s\n%d stops", title(r.name, i), r.points().length);
        }
        AlertDialog dialog = ChoiceList.attach(new AlertDialog.Builder(MapTheme.wrap(activity)).setTitle("Choisis une route (" + routes.size() + ")")
            .setNegativeButton("Annuler", null).create(), names, which -> deliverImported(activity, routes.get(which), where, selection));
        present(activity, dialog);
    }

    private static void deliverImported(Activity activity, GpxRoute route, String where, Selection selection) {
        int count = route.points().length;
        selection.onRoute(route); // never auto-starts; only hands the route to the caller
        note(activity, String.format(Locale.US, "« %s » importée (%d %s). Prête dans %s.",
            route.name, count, count == 1 ? "stop" : "stops", where));
    }

    // ---- export --------------------------------------------------------------------------

    private static void writeExport(Activity activity, Uri uri, GpxRoute route) {
        if (!alive(activity)) return;
        run(() -> {
            String error = null;
            try {
                OutputStream out = activity.getContentResolver().openOutputStream(uri,"wt");
                if (out == null) throw new IOException("Impossible d'ouvrir le fichier de destination.");
                try { GpxCodec.write(out, route); }
                finally { out.close(); }
            } catch (IOException | RuntimeException e) { error = message(e, "Impossible d'exporter cette route."); }
            final String err = error;
            MAIN.post(() -> { if (alive(activity)) note(activity, err == null ? "Exported “" + route.name + "”." : err); });
        });
    }

    // ---- saved routes --------------------------------------------------------------------

    private static void loadList(Activity activity, Selection selection) {
        run(() -> {
            List<GpxStore.Entry> entries = null;
            String error = null;
            try { entries = new GpxStore(activity).list(); }
            catch (IOException | RuntimeException e) { error = message(e, "Impossible d'ouvrir les routes enregistrées."); }
            final List<GpxStore.Entry> result = entries;
            final String err = error;
            MAIN.post(() -> {
                if (!alive(activity)) return;
                if (result == null) { note(activity, err); return; }
                showList(activity, result, selection);
            });
        });
    }

    private static void showList(Activity activity, List<GpxStore.Entry> entries, Selection selection) {
        AlertDialog.Builder builder = new AlertDialog.Builder(MapTheme.wrap(activity))
            .setTitle("Routes enregistrées (" + entries.size() + ")").setNegativeButton("Fermer", null);
        if (entries.isEmpty()) { builder.setMessage("Aucune route enregistrée. Importe un fichier GPX, ou enregistre une route créée sur la carte."); present(activity, builder.create()); return; }
        String[] rows = new String[entries.size()];
        for (int i = 0; i < rows.length; i++) {
            GpxStore.Entry e = entries.get(i);
            rows[i] = String.format(Locale.US, "%s\n%d stops", e.name, e.count);
        }
        present(activity, ChoiceList.attach(builder.create(), rows, which -> showEntry(activity, entries.get(which), selection)));
    }

    private static void showEntry(Activity activity, GpxStore.Entry entry, Selection selection) {
        AlertDialog dialog = new AlertDialog.Builder(MapTheme.wrap(activity)).setTitle(entry.name)
            .setMessage(String.format(Locale.US, "%d arrêts.\n\n« Utiliser cette route » la charge en brouillon ; démarre-la toi-même ensuite.", entry.count))
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Utiliser cette route", null)
            .setNeutralButton("Supprimer", null).create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> loadRoute(activity, entry, selection, dialog));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> confirmDelete(activity, entry, dialog, selection));
        });
        present(activity, dialog);
    }

    private static void loadRoute(Activity activity, GpxStore.Entry entry, Selection selection, AlertDialog detail) {
        detail.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        run(() -> {
            GpxRoute route = null;
            String error = null;
            try { route = new GpxStore(activity).load(entry.id); }
            catch (IOException | RuntimeException e) { error = message(e, "Impossible d'ouvrir cette route."); }
            final GpxRoute result = route;
            final String err = error;
            MAIN.post(() -> {
                if (!alive(activity)) return;
                if (result == null) {
                    if (detail.isShowing()) detail.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    note(activity, err);
                    return;
                }
                selection.onRoute(result);
                if (detail.isShowing()) detail.dismiss();
                note(activity, String.format(Locale.US, "Selected “%s” (%d stops).", result.name, result.points().length));
            });
        });
    }

    private static void confirmDelete(Activity activity, GpxStore.Entry entry, AlertDialog detail, Selection selection) {
        AlertDialog confirm = new AlertDialog.Builder(MapTheme.wrap(activity)).setTitle("Supprimer la route enregistrée ?")
            .setMessage("“" + entry.name + " » sera supprimée. Une marche en cours n'est pas affectée.")
            .setNegativeButton("Annuler", null).setPositiveButton("Supprimer", null).create();
        confirm.setOnShowListener(d -> confirm.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            confirm.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            run(() -> {
                String error = null;
                try { new GpxStore(activity).delete(entry.id); }
                catch (IOException | RuntimeException e) { error = message(e, "Impossible de supprimer cette route."); }
                final String err = error;
                MAIN.post(() -> {
                    if (!alive(activity)) return;
                    if (err != null) {
                        if (confirm.isShowing()) confirm.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        note(activity, err);
                        return;
                    }
                    if (confirm.isShowing()) confirm.dismiss();
                    if (detail.isShowing()) detail.dismiss();
                    note(activity, "Deleted “" + entry.name + "”.");
                    loadList(activity, selection); // reopen the refreshed list
                });
            });
        }));
        present(activity, confirm);
    }

    // ---- save ----------------------------------------------------------------------------

    private static void promptSave(Activity activity, GpxRoute current) {
        Context ui = MapTheme.wrap(activity);
        LinearLayout form = new LinearLayout(ui);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = MapTheme.dp(ui, 20);
        form.setPadding(pad, 0, pad, 0);
        int count = current.points().length;
        form.addView(label(ui, count + (count == 1 ? " arrêt" : " arrêts") + " sera enregistrée.", 14));
        EditText name = new EditText(ui);
        name.setHint("Nom de la route");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(NAME_LIMIT)});
        name.setText(current.name);
        form.addView(name);
        AlertDialog dialog = new AlertDialog.Builder(ui).setTitle("Enregistrer la route").setView(form)
            .setNegativeButton("Annuler", null).setPositiveButton("Enregistrer", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final GpxRoute toSave;
            try { toSave = new GpxRoute(name.getText().toString(), current.points()); }
            catch (IllegalArgumentException e) { name.setError(message(e, "Entre un nom de route.")); return; }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            run(() -> {
                String error = null;
                try { new GpxStore(activity).save(toSave); }
                catch (IOException | RuntimeException e) { error = message(e, "Impossible d'enregistrer la route."); }
                final String err = error;
                MAIN.post(() -> {
                    if (!alive(activity)) return;
                    if (err != null) {
                        if (dialog.isShowing()) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        name.setError(err);
                        return;
                    }
                    if (dialog.isShowing()) dialog.dismiss();
                    note(activity, "Saved “" + toSave.name + "”.");
                });
            });
        }));
        present(activity, dialog);
    }

    /** File results arrive before resume; reopen only after this activity has focus. */
    static void whenResumed(Activity activity,Runnable action) {
        final long until=android.os.SystemClock.uptimeMillis()+5000;
        MAIN.post(new Runnable(){public void run(){
            if(!alive(activity))return;
            if(activity.hasWindowFocus()){action.run();return;}
            if(android.os.SystemClock.uptimeMillis()<until)MAIN.postDelayed(this,100);
        }});
    }

    // ---- helpers -------------------------------------------------------------------------

    private static boolean alive(Activity a) { return a != null && !a.isFinishing() && !a.isDestroyed(); }

    /** One bounded off-main worker per operation; the codec/store cap the stream, so each run is short. */
    private static void run(Runnable io) {
        Thread thread = new Thread(io, "pgrazor-gpx-io");
        thread.setDaemon(true);
        thread.start();
    }

    private static void present(Activity activity, AlertDialog dialog) {
        if (!alive(activity)) return;
        DialogLifecycle.bind(activity, dialog, null); // scoped to the foreground activity like the other map dialogs
        dialog.show();
    }

    /** Resolves a readable name from the content URI (off-main); extension stripped. */
    private static String displayName(Activity activity, Uri uri) {
        String name = null;
        Cursor cursor = null;
        try {
            cursor = activity.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (RuntimeException ignored) {
        } finally { if (cursor != null) cursor.close(); }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) return "Route importée";
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.trim();
        return name.isEmpty() ? "Route importée" : name;
    }

    /** A safe, bounded {@code .gpx} file name suggestion for the create picker. */
    private static String fileName(String name) {
        String base = name == null ? "route" : name.trim();
        if (base.isEmpty()) base = "route";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < base.length() && out.length() < 40; i++) {
            char ch = base.charAt(i);
            out.append(Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' ? ch : '-');
        }
        return out + ".gpx";
    }

    private static String title(String name, int index) {
        return name == null || name.trim().isEmpty() ? "Route " + (index + 1) : name.trim();
    }

    private static String message(Throwable e, String fallback) {
        String m = e == null ? null : e.getMessage();
        return m == null || m.trim().isEmpty() ? fallback : m;
    }

    private static void note(Activity a, String m) {
        if (alive(a) && m != null) Toast.makeText(a, m, Toast.LENGTH_LONG).show();
    }

    private static TextView label(Context c, String text, int size) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextSize(size);
        return v;
    }
}
