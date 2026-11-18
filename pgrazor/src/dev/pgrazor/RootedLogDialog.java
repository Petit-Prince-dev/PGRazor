package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * PGRazor Root run log, shown as an in-game dialog on the game's own activity. The patched APK uses
 * {@link LogActivity}, a private activity it declares in its manifest; the untouched game APK has no
 * such entry, so the same run log is surfaced here with {@link MapTheme} styling instead.
 *
 * <p>Viewing mirrors {@link LogActivity}: it shows the last 64&nbsp;KiB of {@link Control#currentLog()}
 * and offers a Refresh. Export cannot use the Storage Access Framework (there is no activity-result
 * callback for a dialog hosted by the game), so a verified full copy is written to the game's
 * app-visible external files directory through {@link LogExport#copyToExternal}, with the saved path
 * reported back; this needs no new storage permission.
 */
final class RootedLogDialog {
    private static final long MAX_TAIL = 65536;

    private RootedLogDialog() {}

    static void show(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Context ui = MapTheme.wrap(activity);
        int pad = MapTheme.dp(activity, 16);

        LinearLayout body = new LinearLayout(ui);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, pad, pad, pad);

        TextView hint = new TextView(ui);
        hint.setTextSize(13);
        body.addView(hint);

        ScrollView scroll = new ScrollView(ui);
        TextView text = new TextView(ui);
        text.setTextIsSelectable(true);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(12);
        text.setPadding(0, pad / 2, 0, pad / 2);
        scroll.addView(text);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout actions = new LinearLayout(ui);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(actions);
        Button refresh = new Button(ui);
        refresh.setText("Actualiser");
        actions.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1));
        Button save = new Button(ui);
        save.setText("Enregistrer .txt");
        actions.addView(save, new LinearLayout.LayoutParams(0, -2, 1));
        Button bundle=new Button(ui);
        bundle.setText("Enregistrer log + réglages (.zip)");
        body.addView(bundle);

        Runnable reload = () -> load(hint, text);
        refresh.setOnClickListener(v -> reload.run());

        AlertDialog dialog = new AlertDialog.Builder(ui)
                .setTitle("PGRazor · session en cours")
                .setView(body)
                .setPositiveButton("Fermer", null)
                .create();

        // Export off the main thread; the copy re-reads and digests the whole file.
        Context app = activity.getApplicationContext();
        Handler handler = new Handler(Looper.getMainLooper());
        View.OnClickListener export = v -> {
            File file = Control.currentLog();
            if (file == null) { Toast.makeText(activity, "Aucun fichier log à enregistrer", Toast.LENGTH_SHORT).show(); return; }
            save.setEnabled(false);
            bundle.setEnabled(false);
            boolean includeSettings=v==bundle;
            new Thread(() -> {
                try {
                    File saved = LogExport.copyToExternal(app, file,includeSettings);
                    handler.post(() -> {
                        if (dialog.isShowing()) {save.setEnabled(true);bundle.setEnabled(true);}
                        Toast.makeText(app, "Enregistré dans " + saved.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    });
                } catch (IOException error) {
                    handler.post(() -> {
                        if (dialog.isShowing()) {save.setEnabled(true);bundle.setEnabled(true);}
                        Toast.makeText(app, "Échec de l'enregistrement : " + error.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }, "pgrazor-log-export").start();
        };
        save.setOnClickListener(export);
        bundle.setOnClickListener(export);

        dialog.setOnShowListener(d -> {
            MapTheme.fullScreen(dialog);
            MapTheme.style(body);
            MapTheme.styleButtons(dialog);
            reload.run();
        });
        DialogLifecycle.bind(activity, dialog, null);
        dialog.show();
    }

    private static void load(TextView hint, TextView text) {
        File file = Control.currentLog();
        if (file == null) {
            hint.setText("");
            text.setText("Aucun fichier log disponible pour l'instant. Le logcat Android peut contenir des erreurs de démarrage.");
            return;
        }
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long start = Math.max(0, input.length() - MAX_TAIL);
            input.seek(start);
            byte[] bytes = new byte[(int) (input.length() - start)];
            input.readFully(bytes);
            hint.setText((start > 0 ? "Affichage des derniers 64 Kio. " : "")
                    + "Enregistrer exporte le segment de log actuel avec les diagnostics de démarrage sur cet appareil. Le ZIP inclut aussi un settings.json sur une ligne ; cet instantané exclut les jetons de connexion, les données de compte et les positions enregistrées.");
            text.setText(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException error) {
            hint.setText("");
            text.setText("Impossible de lire le log : " + error.getMessage());
        }
    }
}
