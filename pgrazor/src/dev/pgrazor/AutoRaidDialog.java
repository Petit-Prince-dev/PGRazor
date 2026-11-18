package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * "Auto-Raid" settings dialog in the Automation section. Edits a detached {@link AutoRaidPreferences}
 * draft seeded from the coordinator-owned {@code AutoRaid} controller: every change is validated and
 * persisted through {@link AutoRaid#configure}, and Start hands the draft to {@link AutoRaid#start},
 * which freezes it for the session. Chrome follows {@link MapTheme}; polling and the dialog stay scoped
 * through {@link DialogLifecycle}, refreshing the visible status once a second. While a session runs the
 * configuration controls are disabled so the running settings cannot change underneath it.
 */
public final class AutoRaidDialog {
    private AutoRaidDialog() {}

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Context ui = MapTheme.wrap(activity);
        LinearLayout body = new LinearLayout(ui); body.setOrientation(LinearLayout.VERTICAL);
        int pad = MapTheme.dp(ui, 16); body.setPadding(pad, pad, pad, pad);

        // The draft is this dialog's own working copy; the controller keeps the authoritative one.
        final AutoRaidPreferences draft = AutoRaid.preferences();
        final org.json.JSONObject[] names = {null};

        TextView description = new TextView(ui);
        description.setText("Trouve le raid correspondant le plus proche chargé par le jeu, s'y rend, attend assez de joueurs, "
            + "puis combat avec tes réglages d'attaque auto en raid et capture le boss. S'arrête à la limite de raids, après un shiny "
            + "capturé si choisi, ou après deux défaites de suite. « Stop » termine la session après le raid en cours.");
        body.addView(description);

        TextView status = new TextView(ui); body.addView(status);

        final TextView tiers = button(body, "");
        final TextView bosses = button(body, "");
        final TextView maxRaids = button(body, "");
        final TextView minPlayers = button(body, "");
        final TextView waitSeconds = button(body, "");
        final TextView travel = button(body, "");
        final TextView distance = button(body, "");

        final CheckBox stopShiny = new CheckBox(ui);
        stopShiny.setText("Arrêter après un shiny capturé (il compte quand même dans le maximum)"); body.addView(stopShiny);
        final CheckBox freePass = new CheckBox(ui);
        freePass.setText("Utiliser le Passe de Raid gratuit du jour"); body.addView(freePass);
        final CheckBox premiumPass = new CheckBox(ui);
        premiumPass.setText("Utiliser des Passes de Combat Premium"); body.addView(premiumPass);
        TextView passNote = new TextView(ui);
        passNote.setText("Utilise un passe gratuit autorisé avant un Passe de Combat Premium. Les Passes de Raid à distance ne sont jamais utilisés.");
        body.addView(passNote);

        final CheckBox razzBerry = new CheckBox(ui);
        razzBerry.setText("Utiliser une Baie Framby sur les captures bonus"); body.addView(razzBerry);
        final CheckBox goldenRazzBerry = new CheckBox(ui);
        goldenRazzBerry.setText("Utiliser une Baie Framby dorée sur les captures bonus"); body.addView(goldenRazzBerry);
        TextView berryNote = new TextView(ui);
        berryNote.setText("La Framby dorée est prioritaire, sinon la Framby normale. Décoche les deux pour n'utiliser aucune baie. "
            + "L'Auto-Raid fait des balles courbes Excellent avec « Toujours toucher ». Le Pokémon peut quand même s'échapper.");
        body.addView(berryNote);

        final Button start = new Button(ui); start.setAllCaps(false); start.setText("Démarrer"); body.addView(start);
        final Button stop = new Button(ui); stop.setAllCaps(false); stop.setText("Arrêter"); body.addView(stop);

        final boolean[] refreshing = {false};
        final View[] config = {tiers, bosses, maxRaids, minPlayers, waitSeconds, travel, distance, stopShiny, freePass, premiumPass, razzBerry, goldenRazzBerry};
        final Runnable update = () -> {
            refreshing[0] = true;
            boolean running = AutoRaid.running();
            status.setText(AutoRaid.status());
            tiers.setText("Niveaux de raid : " + AutoRaidPreferences.tierSummary(draft.tierMask));
            bosses.setText("Boss de raid : " + bossSummary(draft.bosses, names[0]));
            maxRaids.setText("Raids max : " + draft.maxRaids);
            minPlayers.setText("Joueurs min dans le lobby : " + draft.minPlayers);
            waitSeconds.setText("Attente au lobby : " + draft.waitSeconds + " s");
            travel.setText("Trajet vers les raids : " + (draft.walk ? "Marcher" : "Téléporter"));
            distance.setText("Rester dans un rayon de : " + AutoRaidPreferences.distanceSummary(draft.maxDistanceKm));
            stopShiny.setChecked(draft.stopOnShiny);
            freePass.setChecked(draft.freePass);
            premiumPass.setChecked(draft.premiumPass);
            razzBerry.setChecked(draft.razzBerry);
            goldenRazzBerry.setChecked(draft.goldenRazzBerry);
            for (View v : config) SettingsUi.enabled(v, !running);
            start.setEnabled(!running);
            stop.setEnabled(running);
            refreshing[0] = false;
        };

        tiers.setOnClickListener(v -> tierPicker(activity, draft, update));
        bosses.setOnClickListener(v -> bossInput(activity, draft, update));
        maxRaids.setOnClickListener(v -> intInput(activity, draft, update, "Raids terminés max ("
            + AutoRaidPreferences.MAX_RAIDS_MIN + "-" + AutoRaidPreferences.MAX_RAIDS_MAX + ")",
            AutoRaidPreferences.MAX_RAIDS_MIN, AutoRaidPreferences.MAX_RAIDS_MAX, draft.maxRaids, n -> draft.maxRaids = n));
        minPlayers.setOnClickListener(v -> intInput(activity, draft, update, "Joueurs min dans le lobby ("
            + AutoRaidPreferences.MIN_PLAYERS_MIN + "-" + AutoRaidPreferences.MIN_PLAYERS_MAX + ")",
            AutoRaidPreferences.MIN_PLAYERS_MIN, AutoRaidPreferences.MIN_PLAYERS_MAX, draft.minPlayers, n -> draft.minPlayers = n));
        waitSeconds.setOnClickListener(v -> intInput(activity, draft, update, "Attente au lobby en secondes ("
            + AutoRaidPreferences.WAIT_MIN + "-" + AutoRaidPreferences.WAIT_MAX + ")",
            AutoRaidPreferences.WAIT_MIN, AutoRaidPreferences.WAIT_MAX, draft.waitSeconds, n -> draft.waitSeconds = n));
        travel.setOnClickListener(v -> travelPicker(activity, draft, update));
        distance.setOnClickListener(v -> distancePicker(activity, draft, update));
        stopShiny.setOnCheckedChangeListener((v, on) -> { if (!refreshing[0]) { draft.stopOnShiny = on; apply(activity, draft, update); } });
        freePass.setOnCheckedChangeListener((v, on) -> { if (!refreshing[0]) { draft.freePass = on; apply(activity, draft, update); } });
        premiumPass.setOnCheckedChangeListener((v, on) -> { if (!refreshing[0]) { draft.premiumPass = on; apply(activity, draft, update); } });
        razzBerry.setOnCheckedChangeListener((v, on) -> { if (!refreshing[0]) { draft.razzBerry = on; apply(activity, draft, update); } });
        goldenRazzBerry.setOnCheckedChangeListener((v, on) -> { if (!refreshing[0]) { draft.goldenRazzBerry = on; apply(activity, draft, update); } });

        start.setOnClickListener(v -> {
            try { AutoRaid.start(draft); }
            catch (IllegalArgumentException | IllegalStateException refused) { toast(activity, refused.getMessage()); }
            update.run();
        });
        stop.setOnClickListener(v -> { AutoRaid.stop(); update.run(); });

        update.run();
        ScrollView scroll = new ScrollView(ui); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(ui).setTitle("Auto-Raid").setView(scroll)
            .setPositiveButton("OK", null).create();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable refresh = new Runnable() {
            public void run() { if (dialog.isShowing()) { update.run(); handler.postDelayed(this, 1000); } }
        };
        DialogLifecycle.bind(activity, dialog, () -> handler.removeCallbacks(refresh));
        dialog.setOnShowListener(d -> {
            MapTheme.style(body); MapTheme.styleButtons(dialog);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(MapTheme.surface()));
            handler.post(refresh);
        });
        dialog.show();
        new Thread(() -> {
            org.json.JSONObject loaded = PokemonSpeciesDialog.readNames(activity);
            activity.runOnUiThread(() -> {
                if (!dialog.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
                names[0] = loaded;
                update.run();
            });
        }, "pgrazor-raid-names").start();
    }

    /** Persists the current draft through the controller, reporting any rejection without changing it. */
    private static void apply(Activity activity, AutoRaidPreferences draft, Runnable update) {
        try { AutoRaid.configure(draft); }
        catch (IllegalArgumentException | IllegalStateException refused) {
            java.util.Properties saved = new java.util.Properties();
            AutoRaid.preferences().write(saved); draft.read(saved);
            toast(activity, refused.getMessage());
        }
        update.run();
    }

    private interface IntSetter { void set(int value); }

    private static void intInput(Activity a, AutoRaidPreferences draft, Runnable update,
                                 String title, int min, int max, int current, IntSetter save) {
        Context ui = MapTheme.wrap(a);
        EditText edit = new EditText(ui); edit.setSingleLine(true); edit.setText(String.valueOf(current));
        edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        AlertDialog d = new AlertDialog.Builder(ui).setTitle(title).setView(edit)
            .setPositiveButton("Enregistrer", null).setNegativeButton("Annuler", null).create();
        DialogLifecycle.bind(a, d, null);
        d.setOnShowListener(x -> { MapTheme.style(edit); MapTheme.styleButtons(d);
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w -> {
                try {
                    int n = Integer.parseInt(edit.getText().toString().trim());
                    if (n < min || n > max) throw new NumberFormatException();
                    save.set(n); apply(a, draft, update); d.dismiss();
                } catch (NumberFormatException e) { edit.setError("Entre un nombre entier de " + min + " à " + max + "."); }
            });
        });
        d.show();
    }

    private static void bossInput(Activity a, AutoRaidPreferences draft, Runnable update) {
        // Auto-Raid's picker: category browsing + bulk select, saving only the canonical boss Dex IDs.
        PokemonSpeciesDialog.showRaid(a, "Boss de raid", "bosses", AutoRaidPreferences.MAX_BOSSES,
            () -> draft.bosses, value -> {
                draft.bosses = AutoRaidPreferences.normalizeBosses(value);
                apply(a, draft, update);
            }, null, "N'importe quel boss (aucune sélection)");
    }

    private static String bossSummary(String bosses, org.json.JSONObject names) {
        if (bosses == null || bosses.isEmpty()) return "Tous";
        String[] ids = bosses.split(",");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(3, ids.length); i++) {
            if (i > 0) out.append(", ");
            String name = names == null ? "" : names.optString(ids[i], "");
            out.append(name.isEmpty() ? "#" + ids[i] : name);
        }
        if (ids.length > 3) out.append(" +").append(ids.length - 3).append(" de plus");
        return out.toString();
    }

    private static void tierPicker(Activity a, AutoRaidPreferences draft, Runnable update) {
        Context ui = MapTheme.wrap(a);
        LinearLayout body = new LinearLayout(ui); body.setOrientation(LinearLayout.VERTICAL);
        TextView help = new TextView(ui);
        help.setText("Choisis les niveaux de raid à rejoindre. N'en choisis aucun pour tous les niveaux."); body.addView(help);
        final int[] mask = {draft.tierMask};
        for (int i = 0; i < AutoRaidPreferences.TIER_LABELS.length; i++) {
            final int bit = 1 << i;
            CheckBox row = new CheckBox(ui); row.setText(AutoRaidPreferences.TIER_LABELS[i]);
            row.setChecked((mask[0] & bit) != 0);
            row.setOnCheckedChangeListener((v, on) -> { if (on) mask[0] |= bit; else mask[0] &= ~bit; });
            body.addView(row);
        }
        ScrollView scroll = new ScrollView(ui); scroll.addView(body);
        AlertDialog d = new AlertDialog.Builder(ui).setTitle("Niveaux de raid").setView(scroll)
            .setPositiveButton("Enregistrer", null).setNegativeButton("Annuler", null).create();
        DialogLifecycle.bind(a, d, null);
        d.setOnShowListener(x -> { MapTheme.style(body); MapTheme.styleButtons(d);
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                draft.tierMask = mask[0]; apply(a, draft, update); d.dismiss();
            });
        });
        d.show();
    }

    private static void travelPicker(Activity a, AutoRaidPreferences draft, Runnable update) {
        Context ui = MapTheme.wrap(a);
        AlertDialog d = new AlertDialog.Builder(ui).setTitle("Trajet vers les raids")
            .setNegativeButton("Annuler", null).create();
        ChoiceList.attach(d, new String[]{
            "Téléporter\nAller directement au raid",
            "Marcher\nY aller à vitesse de marche"
        }, index -> { draft.walk = index == 1; apply(a, draft, update); });
        DialogLifecycle.bind(a, d, null);
        d.setOnShowListener(x -> { MapTheme.styleButtons(d);
            d.getWindow().setBackgroundDrawable(new ColorDrawable(MapTheme.surface()));
        });
        d.show();
    }

    private static void distancePicker(Activity a, AutoRaidPreferences draft, Runnable update) {
        Context ui = MapTheme.wrap(a);
        AlertDialog d = new AlertDialog.Builder(ui).setTitle("Rester dans un rayon de")
            .setNegativeButton("Annuler", null).create();
        final int[] choices = AutoRaidPreferences.DISTANCE_CHOICES_KM;
        String[] labels = new String[choices.length];
        for (int i = 0; i < choices.length; i++)
            labels[i] = choices[i] == 0 ? "Sans limite\nRejoindre le raid le plus proche où qu'il soit"
                : choices[i] + " km\nSeulement les raids à moins de " + choices[i] + " km de l'endroit où tu appuies sur Démarrer";
        ChoiceList.attach(d, labels, index -> { draft.maxDistanceKm = choices[index]; apply(a, draft, update); });
        DialogLifecycle.bind(a, d, null);
        d.setOnShowListener(x -> { MapTheme.styleButtons(d);
            d.getWindow().setBackgroundDrawable(new ColorDrawable(MapTheme.surface()));
        });
        d.show();
    }

    private static TextView button(LinearLayout body, String text) {
        Button b = new Button(body.getContext()); b.setText(text); b.setAllCaps(false); body.addView(b); return b;
    }

    private static void toast(Activity activity, String message) {
        Toast.makeText(activity, message == null || message.isEmpty() ? "Impossible de mettre à jour l'Auto-Raid." : message,
            Toast.LENGTH_SHORT).show();
    }
}
