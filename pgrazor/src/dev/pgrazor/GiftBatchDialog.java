package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * One-shot "Send &amp; Open Gifts" dialog. Drives the coordinator-owned {@link GiftBatchSettings}
 * runtime: a single pass sends available gifts and then opens received gifts, with the native
 * coordinator handling each eligible friend once. Chrome follows {@link MapTheme}; polling and the
 * dialog stay scoped through {@link DialogLifecycle}, refreshing only the visible dialog once a second.
 */
public final class GiftBatchDialog {
    private GiftBatchDialog() {}

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Context ui = MapTheme.wrap(activity);
        LinearLayout body = new LinearLayout(ui); body.setOrientation(LinearLayout.VERTICAL);
        int pad = MapTheme.dp(ui, 16); body.setPadding(pad, pad, pad, pad);

        TextView description = new TextView(ui);
        description.setText("Envoie tes cadeaux disponibles aux amis éligibles, puis ouvre les cadeaux reçus. "
            + "Chaque ami est traité une fois. Les limites quotidiennes du jeu pour l'envoi et l'ouverture s'appliquent, certains "
            + "peuvent être ignorés. Un seul passage maintenant — pas de programmation récurrente.");
        body.addView(description);

        CheckBox fullBag = new CheckBox(ui);
        fullBag.setText("Ouvrir même si le sac est plein");
        fullBag.setChecked(GiftBatchSettings.fullBagDefault());
        body.addView(fullBag);
        TextView fullBagCaption = new TextView(ui);
        fullBagCaption.setText("Utilise le réglage du jeu pour sac plein. Ouvrir avec un sac plein peut donner moins de récompenses.");
        body.addView(fullBagCaption);

        TextView status = new TextView(ui); status.setText(GiftBatchSettings.status()); body.addView(status);
        Button start = new Button(ui); start.setAllCaps(false); start.setText("Démarrer"); body.addView(start);
        Button stop = new Button(ui); stop.setAllCaps(false); stop.setText("Arrêter"); body.addView(stop);

        ScrollView scroll = new ScrollView(ui); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(ui).setTitle("Envoyer & ouvrir les cadeaux").setView(scroll)
            .setPositiveButton("Fermer", null).create();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable update = () -> {
            boolean busy = GiftBatchSettings.running();
            SettingsUi.enabled(fullBag, !busy);
            start.setEnabled(!busy);
            stop.setEnabled(busy && !GiftBatchSettings.stopPending());
            status.setText(GiftBatchSettings.status());
        };
        Runnable refresh = new Runnable() {
            public void run() { if (dialog.isShowing()) { update.run(); handler.postDelayed(this, 1000); } }
        };
        start.setOnClickListener(v -> {
            try { GiftBatchSettings.start(fullBag.isChecked()); }
            catch (IllegalArgumentException | IllegalStateException refused) { toast(activity, refused.getMessage()); }
            update.run();
        });
        stop.setOnClickListener(v -> { GiftBatchSettings.stop(); update.run(); });
        DialogLifecycle.bind(activity, dialog, () -> handler.removeCallbacks(refresh));
        dialog.setOnShowListener(d -> {
            MapTheme.style(body); MapTheme.styleButtons(dialog);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(MapTheme.surface()));
            handler.post(refresh);
        });
        dialog.show();
    }

    private static void toast(Activity activity, String message) {
        Toast.makeText(activity, message == null || message.isEmpty() ? "Impossible de démarrer." : message,
            Toast.LENGTH_SHORT).show();
    }
}
