package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * Imports a {@code .gpx} file chosen in the system picker ({@link GpxDocuments}). Reading runs off the
 * main thread and is bounded by {@link GpxCodec#MAX_POINTS}; a file holding several routes asks which
 * one. The route is only handed to the caller, never walked.
 */
final class GpxImport {
    private GpxImport() {}

    /** Receives the imported route, on the main thread. */
    interface Selection { void onRoute(GpxRoute route); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void pick(Activity activity, Selection selection) {
        if (!alive(activity) || selection == null) return;
        GpxDocuments.open(activity, new GpxDocuments.Callback() {
            public void onPicked(Uri uri) { read(activity, uri, selection); }
            public void onCancelled() { }
        });
    }

    /** The picker returns before the game has focus again; runs {@code action} once it has (within 5 s). */
    static void whenResumed(Activity activity, Runnable action) {
        final long until = android.os.SystemClock.uptimeMillis() + 5000;
        MAIN.post(new Runnable() { public void run() {
            if (!alive(activity)) return;
            if (activity.hasWindowFocus()) { action.run(); return; }
            if (android.os.SystemClock.uptimeMillis() < until) MAIN.postDelayed(this, 100);
        }});
    }

    private static void read(Activity activity, Uri uri, Selection selection) {
        Thread worker = new Thread(() -> {
            List<GpxRoute> routes = null;
            String error = null;
            try {
                String fallback = displayName(activity, uri);
                InputStream in = activity.getContentResolver().openInputStream(uri);
                if (in == null) throw new IOException("Impossible d'ouvrir le fichier choisi.");
                try { routes = GpxCodec.read(in, fallback); }
                finally { in.close(); }
            } catch (IOException | RuntimeException e) {
                error = e.getMessage() == null || e.getMessage().trim().isEmpty() ? "Impossible de lire ce fichier GPX." : e.getMessage();
            }
            final List<GpxRoute> result = routes;
            final String failed = error;
            MAIN.post(() -> {
                if (!alive(activity)) return;
                if (result == null) { note(activity, failed); return; }
                if (result.isEmpty()) { note(activity, "Aucune route trouvée dans ce fichier GPX."); return; }
                if (result.size() == 1) { selection.onRoute(result.get(0)); return; }
                String[] names = new String[result.size()];
                for (int i = 0; i < names.length; i++) {
                    GpxRoute route = result.get(i);
                    names[i] = String.format(Locale.FRANCE, "%s\n%d points", route.name, route.size());
                }
                AlertDialog dialog = ChoiceList.attach(new AlertDialog.Builder(MapTheme.wrap(activity)).setTitle("Quelle route importer ?")
                    .setNegativeButton("Annuler", null).create(), names, which -> selection.onRoute(result.get(which)));
                DialogLifecycle.bind(activity, dialog, null);
                dialog.show();
            });
        }, "pgrazor-gpx-import");
        worker.setDaemon(true);
        worker.start();
    }

    /** A readable name from the content URI (off the main thread), without its extension. */
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

    private static boolean alive(Activity a) { return a != null && !a.isFinishing() && !a.isDestroyed(); }
    private static void note(Activity a, String m) { if (alive(a) && m != null) Toast.makeText(a, m, Toast.LENGTH_LONG).show(); }
}
