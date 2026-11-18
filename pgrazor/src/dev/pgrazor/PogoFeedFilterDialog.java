package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Scrollable, themed modal that edits a PoGoCoordinates display-filter set. It never talks to the network
 * or constructs request bodies; it only mutates a caller-owned {@link JSONObject} copy of the current
 * filters and hands it back on Apply so the owner can validate it through
 * {@code PogoFeedFilters.fromJson(...)}, persist it and refetch.
 *
 * <p>Two modes are supported. In the full "Nearby" mode every one of the 21 viewport display-filter
 * keys is editable, plus local named presets. In "My Shinies" mode only the 8 keys the shinies
 * endpoint honours (species mode/list, IV min/max, level min/max, size XXS/XXL) are shown; the other
 * 13 keys are left untouched in the working copy so the user's Nearby settings are preserved.
 */
public final class PogoFeedFilterDialog {
    private PogoFeedFilterDialog() {}

    /** Selectable National Dex range for the species allowlist. */
    private static final int MIN_SPECIES_ID = 1, MAX_SPECIES_ID = 1025;

    /** Owner validates and applies the mutated JSON; returns true when accepted (dialog dismisses). */
    public interface ApplyHandler { boolean apply(JSONObject filters); }

    /** Lets the owner track child dialogs so they are torn down when the feed closes. */
    public interface DialogSink { void track(AlertDialog dialog); }

    /**
     * @param working a fresh {@code toViewportJson()} copy of the current filters (all 21 keys). It is
     *                mutated in place; the owner must only read it back through {@code onApply}.
     */
    public static void show(Activity activity, Context ui, boolean myShiniesMode, JSONObject working,
                            JSONObject speciesNames, SharedPreferences prefs,
                            DialogSink sink, ApplyHandler onApply) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || working == null) return;
        new Editor(activity, ui, myShiniesMode, working, speciesNames, prefs, sink, onApply).open();
    }

    /** Holds the working JSON, the live control references and the preset/species sub-dialogs. */
    private static final class Editor {
        private final Activity activity;
        private final Context ui;
        private final boolean myShinies;
        private final JSONObject working;
        private final JSONObject speciesNames;
        private final SharedPreferences prefs;
        private final DialogSink sink;
        private final ApplyHandler onApply;

        private AlertDialog dialog;
        private LinearLayout content;   // repopulated on Reset / preset apply
        private TextView status;
        private final List<NumField> numbers = new ArrayList<>();
        private Button speciesButton;   // "Choisir les espèces (N)"; label refreshed on change
        private Button[] speciesModeButtons;
        private String editingPreset;   // PGRazorX: the saved preset being edited (Edit in Saved presets), or null

        Editor(Activity activity, Context ui, boolean myShinies, JSONObject working,
               JSONObject speciesNames, SharedPreferences prefs, DialogSink sink, ApplyHandler onApply) {
            this.activity = activity; this.ui = ui; this.myShinies = myShinies; this.working = working;
            this.speciesNames = speciesNames; this.prefs = prefs; this.sink = sink; this.onApply = onApply;
        }

        void open() {
            ScrollView scroll = new ScrollView(ui);
            scroll.setFillViewport(true);
            content = new LinearLayout(ui);
            content.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(18);
            content.setPadding(pad, dp(10), pad, dp(10));
            scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
            populate();

            dialog = new AlertDialog.Builder(ui)
                .setTitle(myShinies ? "Filtres Mes shiny" : "Filtres")
                .setView(scroll)
                .setPositiveButton("Appliquer", null)   // overridden so invalid input does not dismiss
                .setNeutralButton("Réinitialiser", null)     // overridden so Reset does not dismiss
                .setNegativeButton("Annuler", null)   // default dismiss: no change applied
                .create();
            if (sink != null) sink.track(dialog);
            FeedTheme.style(scroll); FeedTheme.styleGameButtons(scroll);
            android.view.Window w = dialog.getWindow();
            if (w != null) w.setBackgroundDrawable(FeedTheme.dialogBackground(ui));
            dialog.setOnShowListener(d -> {
                FeedTheme.styleButtons(dialog); FeedTheme.gameFooter(dialog, null);
                refreshSpeciesMode();
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> onApplyClicked());
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> onResetClicked());
            });
            dialog.show();
        }

        // ------------------------------------------------------------------ content

        private void populate() {
            content.removeAllViews();
            numbers.clear();
            speciesButton = null; speciesModeButtons = null;

            status = label("", 12);
            status.setPadding(0, 0, 0, dp(6));
            content.addView(status);

            if (myShinies) {
                content.addView(caption("S'applique seulement à Mes shiny. Tes autres réglages À proximité sont conservés."));
                speciesSection();
                ivLevelSection();
                sizeSection();
            } else {
                presetsSection();
                generalSection();
                ivLevelSection();
                alwaysShowSection();
                sizeSection();
                speciesSection();
                pvpSection();
                shiniesSection();
            }
            FeedTheme.style(content); FeedTheme.styleGameButtons(content);
            refreshSpeciesMode();
        }

        private void generalSection() {
            header("Général");
            content.addView(checkbox("Tout afficher (ignore les filtres ci-dessous)", "show_all"));
        }

        private void ivLevelSection() {
            header("IV & niveau");
            content.addView(numberRow("IV min", "min_iv", 0, 100));
            content.addView(numberRow("IV max", "max_iv", 0, 100));
            content.addView(numberRow("Niveau min", "min_level", 0, 50));
            content.addView(numberRow("Niveau max", "max_level", 0, 50));
        }

        private void alwaysShowSection() {
            header("Toujours afficher (exceptions aux filtres)");
            content.addView(checkbox("Parfait (100 % IV)", "always_show_perfect"));
            content.addView(checkbox("Zéro (0 % IV)", "always_show_zero"));
            content.addView(checkbox("Espèces rares", "always_show_rare"));
            content.addView(checkbox("Taille notable", "always_show_size"));
        }

        private void sizeSection() {
            header("Taille");
            content.addView(checkbox("Filtre XXS", "filter_xxs"));
            content.addView(checkbox("Filtre XXL", "filter_xxl"));
        }

        private void pvpSection() {
            header("PvP");
            content.addView(checkbox("Super Ligue", "show_pvp_great"));
            content.addView(checkbox("Hyper Ligue", "show_pvp_ultra"));
            content.addView(numberRow("Rang PvP max", "pvp_max_rank", 1, PogoFeedFilters.MAX_PVP_RANK));
            content.addView(caption("Avec une ligue cochée, chaque actualisation cherche une par une, à 10 s d'intervalle : d'abord par IV, puis Super Ligue, puis Hyper Ligue. Tous les résultats sont ajoutés au flux."));
        }

        private void shiniesSection() {
            header("Shiny");
            content.addView(checkbox("Shiny uniquement", "shiny_only"));
            content.addView(checkbox("Fond spécial uniquement", "background_only"));
        }

        private void speciesSection() {
            header("Espèces");
            LinearLayout modeRow = new LinearLayout(ui);
            modeRow.setOrientation(LinearLayout.HORIZONTAL);
            speciesModeButtons = new Button[2];
            final String[] modes = {"off", "allowlist"};
            final String[] labels = {"Toutes les espèces", "Liste autorisée"};
            for (int i = 0; i < 2; i++) {
                final String mode = modes[i];
                Button b = segButton(labels[i]);
                b.setOnClickListener(v -> { put("species_filter_mode", mode); refreshSpeciesMode(); });
                modeRow.addView(b, new LinearLayout.LayoutParams(0, dp(40), 1));
                speciesModeButtons[i] = b;
            }
            content.addView(modeRow);
            speciesButton = button("Choisir les espèces");
            speciesButton.setOnClickListener(v -> openSpeciesPicker());
            content.addView(speciesButton, new LinearLayout.LayoutParams(-1, dp(44)));
            refreshSpeciesButton();
        }

        private void presetsSection() {
            header("Préréglages");
            Button presets = button("Préréglages enregistrés…");
            presets.setOnClickListener(v -> openPresets());
            content.addView(presets, new LinearLayout.LayoutParams(-1, dp(44)));
            // PGRazorX: while a saved preset is being edited, its changes are written back to it here.
            if (editingPreset != null) {
                final String n = editingPreset;
                content.addView(caption("Modification de «" + n + " » : change les filtres ci-dessous, puis enregistre."));
                LinearLayout row = new LinearLayout(ui);
                row.setOrientation(LinearLayout.HORIZONTAL);
                Button saveTo = button("Enregistrer dans «" + n + "\""), stop = button("Arrêter la modification");
                row.addView(saveTo, new LinearLayout.LayoutParams(0, dp(44), 1));
                row.addView(stop, new LinearLayout.LayoutParams(0, dp(44), 1));
                content.addView(row, new LinearLayout.LayoutParams(-1, -2));
                saveTo.setOnClickListener(v -> {
                    JSONObject saved = readPresets();
                    if (!saved.has(n)) { toast("Ce préréglage n'existe plus."); editingPreset = null; populate(); return; }
                    if (savePreset(saved, n)) { status.setText("Préréglage «" + n + " » mis à jour."); toast("Préréglage mis à jour."); }
                });
                stop.setOnClickListener(v -> { editingPreset = null; populate(); status.setText("Modification du préréglage arrêtée ; les filtres restent tels quels."); });
            }
        }

        /** Validates the working filters and stores them under {@code n}; false (with a toast) when it cannot. */
        private boolean savePreset(JSONObject presets, String n) {
            collectNumbers();
            try {
                JSONObject validated = PogoFeedFilters.fromJson(working).toViewportJson();
                JSONObject next = new JSONObject(presets.toString()); next.put(n, validated);
                if (next.toString().length() > 150000) { toast("Les préréglages enregistrés sont trop gros."); return false; }
                presets.put(n, validated);
                writePresets(presets);
                return true;
            } catch (JSONException e) { toast("Impossible d'enregistrer ce préréglage."); return false; }
        }

        // ------------------------------------------------------------------ species mode state

        private void refreshSpeciesMode() {
            if (speciesModeButtons == null) return;
            boolean allowlist = "allowlist".equals(working.optString("species_filter_mode", "off"));
            highlight(speciesModeButtons[0], !allowlist);
            highlight(speciesModeButtons[1], allowlist);
            if (speciesButton != null) {
                speciesButton.setEnabled(allowlist);
                speciesButton.setAlpha(allowlist ? 1f : 0.5f);
            }
            refreshSpeciesButton();
        }

        private void refreshSpeciesButton() {
            if (speciesButton == null) return;
            JSONArray list = working.optJSONArray("species_filter_list");
            int n = list == null ? 0 : list.length();
            speciesButton.setText(n == 0 ? "Choisir les espèces" : "Choisir les espèces (" + n + ")");
        }

        // ------------------------------------------------------------------ apply / reset

        private void onApplyClicked() {
            collectNumbers();
            if (onApply != null && onApply.apply(working)) { dialog.dismiss(); return; }
            status.setText("Certaines valeurs semblent incorrectes (ex. min au-dessus du max). Vérifie et réessaie.");
        }

        private void onResetClicked() {
            JSONObject defaults;
            try {
                defaults = new PogoFeedFilters().toViewportJson();
            } catch (JSONException e) { toast("Impossible de réinitialiser les filtres."); return; }
            if (myShinies) {
                // Only reset the keys the shinies endpoint honours; leave Nearby-only keys in place.
                copyKeys(defaults, working, SHINIES_KEYS);
            } else {
                copyAll(defaults, working); // same 21-key set; overwrite in place
            }
            populate();
            status.setText("Filtres réinitialisés. Applique pour les utiliser.");
        }

        /** Reads every numeric field into the working copy, clamped to its allowed range. */
        private void collectNumbers() {
            for (NumField f : numbers) {
                int current = working.optInt(f.key, f.min);
                int value = current;
                String text = f.view.getText().toString().trim();
                if (!text.isEmpty()) {
                    try { value = Integer.parseInt(text); } catch (NumberFormatException ignored) { value = current; }
                }
                value = Math.max(f.min, Math.min(f.max, value));
                put(f.key, value);
                f.view.setText(Integer.toString(value));
            }
        }

        // ------------------------------------------------------------------ species picker

        /** Opens the shared species allowlist picker, wiring it to the working filter JSON. */
        private void openSpeciesPicker() {
            PokemonSpeciesDialog.show(activity, "Espèces autorisées", "species", MAX_SPECIES_ID,
                this::loadSpeciesCsv, this::saveSpeciesCsv, this::refreshSpeciesMode,
                "Aucune espèce sélectionnée", sink == null ? null : sink::track);
        }

        /** Canonical sorted comma-separated IDs from the working allowlist, restricted to the selectable range. */
        private String loadSpeciesCsv() {
            TreeSet<Integer> selected = new TreeSet<>();
            JSONArray list = working.optJSONArray("species_filter_list");
            if (list != null) for (int i = 0; i < list.length(); i++) {
                int id = list.optInt(i, -1);
                if (id >= MIN_SPECIES_ID && id <= MAX_SPECIES_ID) selected.add(id);
            }
            StringBuilder sb = new StringBuilder();
            for (int id : selected) { if (sb.length() > 0) sb.append(','); sb.append(id); }
            return sb.toString();
        }

        /** Writes the picked IDs back as a JSONArray of ints; a nonempty save enables the allowlist,
         *  an empty save leaves the current mode untouched (an empty allowlist matches no species). */
        private void saveSpeciesCsv(String csv) {
            JSONArray out = new JSONArray();
            boolean any = false;
            if (csv != null) for (String part : csv.split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                try {
                    int id = Integer.parseInt(part);
                    if (id >= MIN_SPECIES_ID && id <= MAX_SPECIES_ID) { out.put(id); any = true; }
                } catch (NumberFormatException ignored) { }
            }
            put("species_filter_list", out);
            if (any) put("species_filter_mode", "allowlist");
        }

        // ------------------------------------------------------------------ presets (Nearby only, local)

        private void openPresets() {
            final JSONObject presets = readPresets();
            final List<String> names = new ArrayList<>();
            for (java.util.Iterator<String> it = presets.keys(); it.hasNext(); ) names.add(it.next());
            java.util.Collections.sort(names);

            LinearLayout body = new LinearLayout(ui);
            body.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(16);
            body.setPadding(pad, dp(8), pad, 0);

            final EditText name = new EditText(ui);
            name.setHint("Nomme ce préréglage");
            name.setSingleLine(true);
            body.addView(name, new LinearLayout.LayoutParams(-1, dp(48)));
            Button save = button("Enregistrer les filtres actuels");
            body.addView(save, new LinearLayout.LayoutParams(-1, dp(44)));

            final LinearLayout listCol = new LinearLayout(ui);
            listCol.setOrientation(LinearLayout.VERTICAL);
            listCol.setPadding(0, dp(10), 0, 0);
            ScrollView sv = new ScrollView(ui);
            sv.addView(listCol);
            body.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

            AlertDialog manager = new AlertDialog.Builder(ui)
                .setTitle("Préréglages de filtres")
                .setView(body)
                .setNegativeButton("Fermer", null)
                .create();
            if (sink != null) sink.track(manager);

            final Runnable[] rebuild = new Runnable[1];
            rebuild[0] = () -> {
                listCol.removeAllViews();
                if (names.isEmpty()) { listCol.addView(caption("Aucun préréglage enregistré.")); }
                for (String n : new ArrayList<>(names)) {
                    // PGRazorX: name on its own line, then Apply / Edit / Rename / Delete.
                    LinearLayout row = new LinearLayout(ui);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setPadding(0, dp(4), 0, dp(6));
                    TextView nameView = label(n, 14);
                    row.addView(nameView, new LinearLayout.LayoutParams(-1, -2));
                    LinearLayout actions = new LinearLayout(ui);
                    actions.setOrientation(LinearLayout.HORIZONTAL);
                    Button apply = button("Appliquer"), edit = button("Modifier"), rename = button("Renommer"), del = button("Supprimer");
                    for (Button b : new Button[]{apply, edit, rename, del}) actions.addView(b, new LinearLayout.LayoutParams(0, dp(40), 1));
                    row.addView(actions, new LinearLayout.LayoutParams(-1, -2));
                    listCol.addView(row, new LinearLayout.LayoutParams(-1, -2));
                    apply.setOnClickListener(v -> {
                        JSONObject p = presets.optJSONObject(n);
                        if (p != null) {
                            editingPreset = null;
                            copyAll(p, working); populate();
                            status.setText("Préréglage «" + n + " » chargé. Applique pour l'utiliser.");
                        }
                        manager.dismiss();
                    });
                    edit.setOnClickListener(v -> {
                        JSONObject p = presets.optJSONObject(n);
                        if (p != null) {
                            editingPreset = n;
                            copyAll(p, working); populate();
                            status.setText("Modification du préréglage «" + n + " ». Change les filtres, puis touche Enregistrer dans «" + n + "\".");
                        }
                        manager.dismiss();
                    });
                    rename.setOnClickListener(v -> {
                        final EditText field = new EditText(ui);
                        field.setSingleLine(true); field.setText(n); field.setSelection(n.length());
                        AlertDialog ask = new AlertDialog.Builder(ui)
                            .setTitle("Renommer le préréglage")
                            .setView(field)
                            .setPositiveButton("Renommer", null)
                            .setNegativeButton("Annuler", null)
                            .create();
                        if (sink != null) sink.track(ask);
                        ask.setOnShowListener(d2 -> {
                            FeedTheme.styleButtons(ask); FeedTheme.gameFooter(ask, null);
                            ask.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v2 -> {
                                String next = field.getText().toString().trim();
                                if (next.isEmpty() || next.length() > 40) { toast("Utilise un nom de préréglage de 1 à 40 caractères."); return; }
                                if (next.equals(n)) { ask.dismiss(); return; }
                                if (presets.has(next)) { toast("Un préréglage avec ce nom existe déjà."); return; }
                                try {
                                    JSONObject value = presets.optJSONObject(n);
                                    if (value == null) { ask.dismiss(); return; }
                                    presets.remove(n); presets.put(next, value); writePresets(presets);
                                    names.remove(n); names.add(next); java.util.Collections.sort(names);
                                    if (n.equals(editingPreset)) { editingPreset = next; populate(); }
                                    rebuild[0].run(); ask.dismiss(); toast("Préréglage renommé.");
                                } catch (JSONException e) { toast("Impossible de renommer ce préréglage."); }
                            });
                        });
                        ask.show();
                    });
                    del.setOnClickListener(v -> {
                        presets.remove(n); names.remove(n); writePresets(presets);
                        if (n.equals(editingPreset)) { editingPreset = null; populate(); }
                        rebuild[0].run();
                    });
                    FeedTheme.style(row); FeedTheme.styleGameButtons(row);
                }
            };
            save.setOnClickListener(v -> {
                String n = name.getText().toString().trim();
                if (n.isEmpty() || n.length() > 40) { toast("Utilise un nom de préréglage de 1 à 40 caractères."); return; }
                if (!presets.has(n) && presets.length() >= 25) { toast("Tu peux enregistrer jusqu'à 25 préréglages."); return; }
                if (!savePreset(presets, n)) return;
                if (!names.contains(n)) names.add(n);
                java.util.Collections.sort(names);
                name.setText("");
                rebuild[0].run();
                toast("Préréglage enregistré.");
            });

            manager.setOnShowListener(d -> { FeedTheme.style(body); FeedTheme.styleGameButtons(body); FeedTheme.styleButtons(manager); FeedTheme.gameFooter(manager, null); rebuild[0].run(); });
            manager.show();
        }

        private JSONObject readPresets() {
            try {
                String s = prefs.getString("filter_presets_v1", null);
                return s != null ? new JSONObject(s) : new JSONObject();
            } catch (JSONException e) { return new JSONObject(); }
        }

        private void writePresets(JSONObject presets) {
            prefs.edit().putString("filter_presets_v1", presets.toString()).apply();
        }

        // ------------------------------------------------------------------ view helpers

        private View numberRow(String labelText, String key, int min, int max) {
            LinearLayout row = new LinearLayout(ui);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(label(labelText, 14), new LinearLayout.LayoutParams(0, -2, 1));
            EditText field = new EditText(ui);
            field.setInputType(InputType.TYPE_CLASS_NUMBER);
            field.setSingleLine(true);
            field.setGravity(Gravity.CENTER);
            field.setText(Integer.toString(clamp(working.optInt(key, min), min, max)));
            row.addView(field, new LinearLayout.LayoutParams(dp(84), dp(48)));
            numbers.add(new NumField(key, field, min, max));
            return row;
        }

        private CheckBox checkbox(String labelText, String key) {
            CheckBox cb = new CheckBox(ui);
            cb.setText(labelText);
            cb.setTextSize(14);
            cb.setPadding(dp(4), dp(6), dp(4), dp(6));
            cb.setChecked(working.optBoolean(key, false));
            cb.setOnCheckedChangeListener((v, checked) -> put(key, checked));
            return cb;
        }

        private void header(String text) {
            TextView t = label(text, 13);
            t.setAllCaps(true);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            t.setTextColor(FeedTheme.accent());
            t.setPadding(0, dp(14), 0, dp(4));
            content.addView(t);
        }

        private TextView caption(String text) {
            TextView t = label(text, 12);
            t.setTextColor(FeedTheme.muted());
            t.setPadding(0, 0, 0, dp(4));
            return t;
        }

        private void highlight(Button b, boolean on) {
            FeedTheme.gameSelected(b, on);
        }

        private int dp(float v) { return Math.round(v * activity.getResources().getDisplayMetrics().density); }
        private TextView label(String text, int size) { TextView v = new TextView(ui); v.setText(text); v.setTextSize(size); return v; }
        private Button button(String text) { Button b = new Button(ui); b.setText(text); b.setAllCaps(false); b.setTextSize(13.5f); return b; }
        private Button segButton(String text) {
            Button b = new Button(ui); b.setText(text); b.setAllCaps(false); b.setTextSize(13);
            b.setPadding(dp(10), 0, dp(10), 0); return b;
        }
        private void toast(String message) { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show(); }

        private void put(String key, Object value) {
            try { working.put(key, value); } catch (JSONException ignored) { }
        }
    }

    private static final class NumField {
        final String key; final EditText view; final int min, max;
        NumField(String key, EditText view, int min, int max) { this.key = key; this.view = view; this.min = min; this.max = max; }
    }

    /** The keys the My Shinies endpoint honours; Reset in shinies mode only touches these. */
    private static final String[] SHINIES_KEYS = {
        "species_filter_mode", "species_filter_list", "min_iv", "max_iv", "min_level", "max_level",
        "filter_xxs", "filter_xxl"
    };

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private static void copyAll(JSONObject from, JSONObject to) {
        for (java.util.Iterator<String> it = from.keys(); it.hasNext(); ) {
            String k = it.next();
            try { to.put(k, from.get(k)); } catch (JSONException ignored) { }
        }
    }

    private static void copyKeys(JSONObject from, JSONObject to, String[] keys) {
        for (String k : keys) if (from.has(k)) {
            try { to.put(k, from.get(k)); } catch (JSONException ignored) { }
        }
    }
}
