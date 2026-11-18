package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.Properties;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Field research automation: auto-claim completed field research and auto-remove selected tasks.
 * Preferences persist with Control's other settings; the native coordinator reads {@link #config()}
 * on its transport loop and reports the observed task catalog and request results to
 * {@link #publish(String)}.
 */
public final class ResearchSettings {
    private ResearchSettings() {}
    private static final ResearchPreferences preferences = new ResearchPreferences();
    private static final Object LOCK = new Object();
    private static String message = "";
    private static int claimed, removed, caught;
    // Categories the player opened in the Research dialog (UI thread only); closed until opened, and
    // remembered until the game restarts.
    private static final java.util.Set<String> expanded = new java.util.HashSet<>();

    public static void read(Properties p) { preferences.read(p); }
    public static void write(Properties p) { preferences.write(p); }
    public static String config() { return preferences.config(); }

    /** Native "research" events: the observed catalog, or one request result. */
    public static void publish(String detail) {
        try {
            JSONObject o = new JSONObject(detail);
            String phase = o.optString("phase", "");
            if ("catalog".equals(phase)) {
                JSONArray tasks = o.optJSONArray("tasks");
                boolean changed = false;
                for (int i = 0; tasks != null && i < tasks.length(); i++) {
                    JSONObject t = tasks.optJSONObject(i);
                    if (t != null) changed |= preferences.observe(t.optString("t"), t.optString("d"), t.optString("r"), t.optString("c"));
                }
                if (changed) Control.saveSettings();
                return;
            }
            synchronized (LOCK) {
                if ("claimed".equals(phase)) claimed++;
                if ("removed".equals(phase)) removed++;
                if ("caught".equals(phase)) caught++;
                message = NativeText.fr(o.optString("message", ""));
            }
        } catch (Exception ignored) {}
    }

    static String status() {
        synchronized (LOCK) {
            String counts = "Cette session : réclamées " + claimed + ", supprimées " + removed + ", capturés " + caught;
            return message.isEmpty() ? counts : counts + "\nDernier : " + message;
        }
    }

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Context ui = MapTheme.wrap(activity);
        LinearLayout body = new LinearLayout(ui); body.setOrientation(LinearLayout.VERTICAL);
        int pad = MapTheme.dp(ui, 16); body.setPadding(pad, pad, pad, pad);

        CheckBox claim = new CheckBox(ui); claim.setText("Réclamer auto les Études de terrain terminées");
        claim.setChecked(preferences.claim()); body.addView(claim);
        TextView claimHelp = new TextView(ui);
        claimHelp.setText("Réclame les Études de terrain une fois l'objectif atteint. Les rencontres Pokémon en récompense t'attendent dans l'écran Études du jeu. Les Études spéciales, temporaires et de l'histoire restent manuelles.");
        body.addView(claimHelp);

        CheckBox remove = new CheckBox(ui); remove.setText("Supprimer auto les tâches choisies");
        remove.setChecked(preferences.remove()); body.addView(remove);
        TextView removeHelp = new TextView(ui);
        removeHelp.setText("Supprime les Études de terrain non terminées dont la tâche est cochée ci-dessous. Les tâches bonus d'arène ne sont jamais supprimées.");
        body.addView(removeHelp);

        CheckBox catchEncounters = new CheckBox(ui); catchEncounters.setText("Capturer auto les rencontres d'Études");
        catchEncounters.setChecked(preferences.catchEncounters()); body.addView(catchEncounters);
        TextView catchHelp = new TextView(ui);
        catchHelp.setText("Capture les rencontres d'Études de terrain en attente sans l'écran de rencontre, avec tes réglages de Ball et de lancer VGP+. Chaque lancer utilise une Ball. Les Pokémon capturés suivent tes règles de transfert, Tout garder inclus. Fonctionne avec le VGP.");
        body.addView(catchHelp);

        EditText search = new EditText(ui); search.setHint("Chercher des tâches ou récompenses"); search.setSingleLine(true);
        body.addView(search);
        TextView empty = new TextView(ui);
        empty.setText("Aucune Étude de terrain vue. Les tâches apparaissent ici quand le jeu te les donne pendant que PGRazor tourne.");
        body.addView(empty);
        LinearLayout list = new LinearLayout(ui); list.setOrientation(LinearLayout.VERTICAL); body.addView(list);
        TextView status = new TextView(ui); status.setText(status()); body.addView(status);

        Runnable[] fill = new Runnable[1];
        Runnable refill = () -> list.post(fill[0]);
        fill[0] = () -> {
            list.removeAllViews();
            String query = search.getText().toString().trim();
            List<String[]> tasks = preferences.tasks(query);
            empty.setVisibility(tasks.isEmpty() && query.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
            // Different tasks can share their text and reward (an event and a season "Win a raid");
            // the game's task ID tells those rows apart.
            java.util.Map<String, Integer> same = new java.util.HashMap<>();
            for (String[] task : tasks) same.merge(task[1] + "\n" + task[2], 1, Integer::sum);
            for (String category : ResearchPreferences.CATEGORIES) {
                List<String> templates = new java.util.ArrayList<>();
                int picked = 0;
                for (String[] task : tasks) if (task[3].equals(category)) {
                    templates.add(task[0]);
                    if (preferences.selected(task[0])) picked++;
                }
                if (templates.isEmpty()) continue;
                // A search opens every category with a match; otherwise the player's choice holds.
                boolean open = !query.isEmpty() || expanded.contains(category);
                list.addView(categoryHeader(ui, activity, category, templates, picked, open, query.isEmpty(), refill));
                if (open) for (String[] task : tasks) if (task[3].equals(category)) list.addView(taskRow(ui, activity, task, same, refill));
            }
            MapTheme.style(list);
        };
        claim.setOnCheckedChangeListener((v, on) -> { preferences.setClaim(on); Control.saveSettings(); });
        remove.setOnCheckedChangeListener((v, on) -> { preferences.setRemove(on); Control.saveSettings(); });
        catchEncounters.setOnCheckedChangeListener((v, on) -> { preferences.setCatchEncounters(on); Control.saveSettings(); });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { fill[0].run(); }
        });
        fill[0].run();

        ScrollView scroll = new ScrollView(ui); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(ui).setTitle("Étude").setView(scroll)
            .setPositiveButton("OK", null).create();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable refresh = new Runnable() {
            public void run() { if (dialog.isShowing()) { status.setText(status()); handler.postDelayed(this, 1000); } }
        };
        DialogLifecycle.bind(activity, dialog, () -> handler.removeCallbacks(refresh));
        dialog.setOnShowListener(d -> {
            MapTheme.style(body); MapTheme.styleButtons(dialog);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(MapTheme.surface()));
            handler.post(refresh);
        });
        dialog.show();
    }

    private static String categoryName(String category) {
        if (ResearchPreferences.ENCOUNTER.equals(category)) return "Rencontre";
        if (ResearchPreferences.EVENT.equals(category)) return "Événement";
        return "Item";
    }

    /**
     * A category's header: tapping its name opens or closes it, and its All box ticks or clears every
     * task it lists (only the search matches while a search is typed).
     */
    private static LinearLayout categoryHeader(Context ui, Activity activity, String category, List<String> templates,
                                               int picked, boolean open, boolean collapsible, Runnable refill) {
        LinearLayout row = new LinearLayout(ui);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, MapTheme.dp(ui, 12), 0, MapTheme.dp(ui, 4));
        TextView title = new TextView(ui);
        String text = (collapsible ? (open ? "▾ " : "▸ ") : "") + categoryName(category) + " (" + templates.size() + ")";
        if (picked > 0) text += " · " + picked + " cochées";
        title.setText(text);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(0, MapTheme.dp(ui, 8), 0, MapTheme.dp(ui, 8));
        if (collapsible) title.setOnClickListener(v -> {
            if (!expanded.remove(category)) expanded.add(category);
            refill.run();
        });
        row.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        CheckBox all = new CheckBox(ui);
        all.setText("Tous");
        all.setChecked(picked == templates.size());
        all.setOnCheckedChangeListener((v, on) -> {
            try { preferences.selectAll(templates, on); Control.saveSettings(); }
            catch (IllegalArgumentException refused) { toast(activity, refused.getMessage()); }
            refill.run();
        });
        row.addView(all);
        return row;
    }

    private static CheckBox taskRow(Context ui, Activity activity, String[] task, java.util.Map<String, Integer> same,
                                    Runnable refill) {
        CheckBox box = new CheckBox(ui);
        String label = task[2].isEmpty() ? task[1] : task[1] + "\n" + task[2];
        if (same.get(task[1] + "\n" + task[2]) > 1) label += "\n" + task[0];
        box.setText(label);
        box.setChecked(preferences.selected(task[0]));
        box.setOnCheckedChangeListener((v, on) -> {
            try { preferences.select(task[0], on); Control.saveSettings(); }
            catch (IllegalArgumentException refused) { toast(activity, refused.getMessage()); }
            refill.run();
        });
        return box;
    }

    private static void toast(Activity activity, String text) {
        Toast.makeText(activity, text == null || text.isEmpty() ? "Non enregistré." : text, Toast.LENGTH_SHORT).show();
    }
}
