package dev.pgrazor.root;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The only screen of the PGRazor Root installer. It shows the device's root/Zygisk/module/game
 * status honestly, installs the bundled Zygisk module through the detected root manager on an
 * explicit tap, offers a launch-time on/off switch and an always-available reboot button. Detection
 * is read-only; install, switching and reboot require an explicit action. All root work runs off
 * the main thread.
 */
public final class InstallerActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private TextView statusView;
    private TextView logView;
    private ScrollView logScroll;
    private Button checkButton;
    private Button installButton;
    private Button rebootButton;
    private Button toggleButton;
    private boolean pendingLogged;

    private volatile boolean busy;
    private RootStatus status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(32, 56, 32, 32);
        screen.setBackgroundColor(Color.rgb(9, 17, 29));

        TextView heading = new TextView(this);
        heading.setText("Installateur PGRazor Root");
        heading.setTextSize(24);
        heading.setTextColor(Color.WHITE);
        screen.addView(heading);

        TextView subtitle = new TextView(this);
        subtitle.setText("Installe PGRazor dans le Pokémon GO officiel " + InstallerConfig.GAME_VERSION_NAME
            + " en module Zygisk. Le jeu n'est pas modifié ; chaque changement demande ta confirmation.");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setPadding(0, 8, 0, 16);
        screen.addView(subtitle);

        statusView = new TextView(this);
        statusView.setTextSize(15);
        statusView.setTextColor(Color.WHITE);
        statusView.setText("Vérification de l'appareil…");
        screen.addView(statusView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, 16, 0, 8);
        checkButton = addButton(buttons, "Revérifier", v -> refresh());
        installButton = addButton(buttons, "Installer le module", v -> confirmInstall());
        installButton.setEnabled(false);
        screen.addView(buttons);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        toggleButton = addButton(actions, "PGRazor : On / Off", v -> confirmToggle());
        rebootButton = addButton(actions, "Redémarrer", v -> confirmReboot());
        toggleButton.setEnabled(false);
        rebootButton.setEnabled(false);
        screen.addView(actions);

        TextView logHeading = new TextView(this);
        logHeading.setText("Log");
        logHeading.setTextSize(16);
        logHeading.setTextColor(Color.WHITE);
        logHeading.setPadding(0, 16, 0, 4);
        screen.addView(logHeading);

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextColor(Color.rgb(200, 220, 200));
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        screen.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(screen);
        refresh();
    }

    private Button addButton(LinearLayout row, String label, View.OnClickListener onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(onClick);
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        return button;
    }

    /** Read-only status refresh, run off the main thread (the root probe blocks). */
    private void refresh() {
        if (busy) return;
        setBusy(true);
        statusView.setText("Vérification de l'appareil…");
        log("Vérification du root, de Zygisk, du module et du jeu…");
        new Thread(() -> {
            RootStatus detected = RootStatus.detect(this);
            handler.post(() -> {
                status = detected;
                statusView.setText(detected.describe());
                if (!detected.rawProbe.isEmpty()) log("Analyse :\n" + detected.rawProbe);
                toggleButton.setText(detected.toggleSupported ? (detected.injectionOff ? "PGRazor : Off" : "PGRazor : On") : "PGRazor : On / Off");
                rebootButton.setText(detected.rebootNeeded ? "Redémarrer pour activer" : "Redémarrer");
                if (detected.rebootNeeded && !pendingLogged) log("Un changement de module est en attente. Redémarre pour l'activer.");
                pendingLogged = detected.rebootNeeded;
                if (!detected.rooted) {
                    log("Pas d'accès root. Autorise cette appli dans ton gestionnaire root, puis Revérifier.");
                } else if (!detected.managerSupported) {
                    log("Aucun gestionnaire root compatible (Magisk 27+, KernelSU ou APatch requis).");
                } else {
                    if (!"Enabled".equals(detected.zygiskLabel) && !detected.zygiskLabel.startsWith("Enabled (") && !detected.zygiskLabel.startsWith("Configured (")) {
                        log("Zygisk est requis. PGRazor ne se chargera pas tant que Zygisk n'est pas actif : " + detected.zygiskLabel);
                    }
                    if (detected.gameInstalled && !detected.gameCertTrusted) {
                        log("Le Pokémon GO installé n'est pas signé par Niantic. Utilise l'appli officielle ; sinon PGRazor reste désactivé.");
                    } else if (detected.gameInstalled && !detected.gameVersionMatch) {
                        log("Le jeu installé n'est pas en " + InstallerConfig.GAME_VERSION_NAME
                            + ". Le module s'installe, mais PGRazor reste désactivé tant que les versions ne correspondent pas.");
                    } else if (!detected.gameInstalled) {
                        log("Pokémon GO n'est pas installé. Le module s'installe, mais il n'a encore rien dans quoi se charger.");
                    }
                }
                setBusy(false);
            });
        }, "pgrazor-root-detect").start();
    }

    private void confirmInstall() {
        if (busy || status == null || !status.canInstall()) return;
        new AlertDialog.Builder(this)
            .setTitle("Installer le module PGRazor Root ?")
            .setMessage("Ceci installe le module Zygisk avec " + status.managerLabel
                + ". Le module se charge uniquement dans le Pokémon GO officiel et prend effet après un redémarrage.")
            .setPositiveButton("Installer", (dialog, which) -> install())
            .setNegativeButton("Annuler", null)
            .show();
    }

    private void install() {
        if (busy || status == null || !status.canInstall()) return;
        setBusy(true);
        log("Installation du module avec " + status.managerLabel + "…");
        final RootStatus target = status;
        new Thread(() -> {
            RootShell.Result result;
            File zip;
            try {
                zip = extractModule();
            } catch (IOException copyFailed) {
                handler.post(() -> {
                    log("Impossible de préparer le module : " + copyFailed.getMessage());
                    setBusy(false);
                });
                return;
            }
            result = target.install(zip);
            final RootShell.Result outcome = result;
            handler.post(() -> {
                if (!outcome.output.isEmpty()) log(outcome.output);
                if (outcome.ok()) {
                    log("Module installé. Touche Redémarrer pour l'activer.");
                } else if (outcome.timedOut) {
                    log("Résultat de l'installation inconnu. Le gestionnaire root travaille peut-être encore ; vérifie-le avant de retenter.");
                } else if (!outcome.started) {
                    log("Impossible de lancer l'installation : " + outcome.output);
                } else {
                    log("Échec de l'installation (code " + outcome.code + "). Vois la sortie ci-dessus.");
                }
                setBusy(false);
                refresh();
            });
        }, "pgrazor-root-install").start();
    }

    private void confirmToggle() {
        if (busy || status == null || !status.canToggle()) return;
        final boolean on = status.injectionOff;
        new AlertDialog.Builder(this)
            .setTitle(on ? "Activer PGRazor ?" : "Désactiver PGRazor ?")
            .setMessage("Pok\u00e9mon GO will be closed. PGRazor will be " + (on ? "on" : "off")
                + " à la prochaine ouverture. Pas besoin de redémarrer.")
            .setPositiveButton(on ? "Activer" : "Désactiver", (dialog, which) -> setInjection(on))
            .setNegativeButton("Annuler", null).show();
    }

    private void setInjection(boolean on) {
        if (busy || status == null || !status.canToggle()) return;
        final RootStatus target = status;
        setBusy(true);
        new Thread(() -> {
            RootShell.Result result = target.setInjection(on);
            handler.post(() -> {
                if (!result.output.isEmpty()) log(result.output);
                if (result.ok()) log("PGRazor est " + (on ? "on" : "désactivé (le module reste installé)")
                    + ". Ouvre Pok\u00e9mon GO quand tu veux.");
                else log(result.timedOut ? "Résultat du changement inconnu ; nouvelle vérification de l'état du module."
                    : "Impossible de confirmer le changement. Vois la sortie ci-dessus.");
                setBusy(false);
                refresh();
            });
        }, "pgrazor-root-toggle").start();
    }

    private void confirmReboot() {
        if (busy || status == null || !status.rooted) return;
        new AlertDialog.Builder(this)
            .setTitle("Redémarrer maintenant ?")
            .setMessage(status.rebootNeeded ? "Zygisk ne lit sa liste de modules qu'au démarrage. Redémarre pour activer le changement PGRazor Root en attente." : "Redémarrer l'appareil maintenant ? Pok\u00e9mon GO et les autres applis vont se fermer.")
            .setPositiveButton("Redémarrer", (dialog, which) -> {
                if (busy || status == null || !status.rooted) return;
                setBusy(true);
                log("Redémarrage…");
                new Thread(() -> {
                    RootShell.Result result = RootStatus.reboot();
                    handler.post(() -> {
                        if (!result.ok() && !result.output.isEmpty()) log(result.output);
                        setBusy(false);
                    });
                }, "pgrazor-root-reboot").start();
            })
            .setNegativeButton("Plus tard", null)
            .show();
    }

    /** Copies the bundled module zip into app storage so the root process can read it by path. */
    private File extractModule() throws IOException {
        File out = new File(getFilesDir(), InstallerConfig.MODULE_ASSET);
        File temp = new File(out.getPath() + ".tmp");
        try (InputStream in = getAssets().open(InstallerConfig.MODULE_ASSET);
             FileOutputStream os = new FileOutputStream(temp)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) os.write(buffer, 0, n);
            os.getFD().sync();
        }
        if (out.exists() && !out.delete()) throw new IOException("Impossible de remplacer " + out);
        if (!temp.renameTo(out)) throw new IOException("Impossible de préparer " + out);
        return out;
    }

    private void setBusy(boolean value) {
        busy = value;
        checkButton.setEnabled(!value);
        installButton.setEnabled(!value && status != null && status.canInstall());
        toggleButton.setEnabled(!value && status != null && status.canToggle());
        rebootButton.setEnabled(!value && status != null && status.rooted);
    }

    private void log(String message) {
        String line = "[" + clock.format(new Date()) + "] " + message + "\n";
        logView.append(line);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }
}
