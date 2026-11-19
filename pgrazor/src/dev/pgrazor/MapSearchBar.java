package dev.pgrazor;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.pgrazor.PlaceSearchClient.Place;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The map's search bar. Places are suggested under it as you type (Photon, ranked around the map's centre,
 * after a short pause); coordinates typed in are offered at once, without a lookup. Choosing a suggestion
 * hands its point to {@link Selection}: nothing moves on its own. Lookups run on one background thread owned
 * by the bar and stopped by {@link #close}.
 */
final class MapSearchBar {
    interface Selection { void select(double latitude, double longitude); }
    /** The map's current centre {latitude, longitude}, to rank places near what is on screen. */
    interface Center { double[] get(); }

    private static final int QUERY_LIMIT = 200, PAUSE_MS = 450, RETRY_MS = 1000, SHOWN = 5;

    /** The field, for the caller to place in its top bar. */
    final EditText input;
    /** The floating card of suggestions under the field, for the caller to place; hidden while empty. */
    final LinearLayout suggestions;
    private final Activity activity;
    private final Context ui;
    private final Center center;
    private final Selection selection;
    private final TextView note;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
    private final PlaceSearchClient client = new PlaceSearchClient();
    private final Runnable lookup = this::lookup;
    /** Bumped on every edit and on close, so a late answer for older text is dropped. */
    private int generation;
    private String query = "";
    /** Set while the field is filled in by a choice, so that text is not searched again. */
    private boolean quiet, closed;

    MapSearchBar(Activity activity, Context ui, Center center, Selection selection) {
        this.activity = activity; this.ui = ui; this.center = center; this.selection = selection;
        input = new EditText(ui);
        input.setHint("Chercher un lieu ou des coordonnées"); input.setTextSize(14); input.setSingleLine(true);
        input.setTag(DialogStyle.KEEP); input.setGravity(Gravity.CENTER_VERTICAL);
        input.setPadding(dp(18), 0, dp(18), 0);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(QUERY_LIMIT)});
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            public void onTextChanged(CharSequence text, int start, int before, int count) {}
            public void afterTextChanged(Editable text) { if (!quiet) edited(); }
        });
        input.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            main.removeCallbacks(lookup); lookup.run(); return true;
        });

        suggestions = new LinearLayout(ui); suggestions.setOrientation(LinearLayout.VERTICAL); suggestions.setTag(DialogStyle.KEEP);
        suggestions.setPadding(0, dp(4), 0, dp(4)); suggestions.setClickable(true); suggestions.setVisibility(View.GONE);
        suggestions.setBackground(SettingsUi.cardBackground(ui)); suggestions.setElevation(dp(4));
        note = SettingsUi.text(ui, "", 13, SettingsUi.muted()); note.setPadding(dp(16), dp(10), dp(16), dp(10));
    }

    void paint() { input.setHintTextColor(SettingsUi.muted()); input.setTextColor(SettingsUi.ink()); }

    /** Closes the suggestions and the keyboard; true when there was something open to close. */
    boolean dismiss() {
        boolean open = suggestions.getVisibility() == View.VISIBLE || input.hasFocus();
        suggestions.setVisibility(View.GONE);
        keyboard(false); input.clearFocus();
        return open;
    }

    private void edited() {
        if (closed) return;
        main.removeCallbacks(lookup);
        generation++;
        query = input.getText().toString().trim();
        if (query.isEmpty()) { suggestions.setVisibility(View.GONE); return; }
        double[] point = CoordinateInput.find(query);
        if (point != null) {
            String text = String.format(Locale.US, "%.6f, %.6f", point[0], point[1]);
            show(null);
            add(new Place("Aller à " + text, "Coordonnées", point[0], point[1]));
            return;
        }
        if (query.length() < 3) { suggestions.setVisibility(View.GONE); return; }
        main.postDelayed(lookup, PAUSE_MS);
    }

    private void lookup() {
        if (closed || query.length() < 2 || CoordinateInput.find(query) != null) return;
        final int gen = generation;
        final String q = query;
        double[] at = center.get();
        final double lat = Double.isFinite(at[0]) && Math.abs(at[0]) <= 90 ? at[0] : 0, lon = Double.isFinite(at[1]) && Math.abs(at[1]) <= 180 ? at[1] : 0;
        if (suggestions.getVisibility() != View.VISIBLE || suggestions.getChildCount() == 0) show("Recherche…");
        try {
            executor.execute(() -> {
                List<Place> found = null; String error = null; boolean busy = false;
                try { found = client.search(q, lat, lon); }
                catch (IOException e) { error = "Recherche indisponible. Vérifie ta connexion."; busy = String.valueOf(e.getMessage()).startsWith("Patiente"); }
                catch (RuntimeException e) { error = "La recherche n'a pas abouti."; }
                final List<Place> result = found; final String failed = error; final boolean retry = busy;
                main.post(() -> {
                    if (closed || gen != generation) return;
                    // The service takes one request a second: a quick typist just waits for the next slot.
                    if (retry) { main.postDelayed(lookup, RETRY_MS); return; }
                    if (failed != null) { show(failed); return; }
                    if (result == null || result.isEmpty()) { show("Aucun lieu trouvé. Ajoute une ville ?"); return; }
                    show(null);
                    for (int i = 0; i < result.size() && i < SHOWN; i++) if (result.get(i) != null) add(result.get(i));
                });
            });
        } catch (RejectedExecutionException closing) { /* the bar is closing */ }
    }

    /** Empties the card and shows it, with a note line when {@code message} is set. */
    private void show(String message) {
        suggestions.removeAllViews();
        if (message != null) { note.setText(message); suggestions.addView(note); }
        suggestions.setVisibility(View.VISIBLE);
    }

    private void add(Place place) {
        if (suggestions.getChildCount() > 0) {
            View rule = new View(ui); rule.setBackgroundColor(SettingsUi.border());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.7f))); lp.setMargins(dp(16), 0, dp(16), 0);
            suggestions.addView(rule, lp);
        }
        LinearLayout row = new LinearLayout(ui); row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(9), dp(16), dp(9)); row.setClickable(true); row.setFocusable(true);
        row.setBackground(SettingsUi.ripple(SettingsUi.rounded(ui, 0x00000000, SettingsUi.RADIUS_CARD)));
        String name = place.name == null ? "" : place.name;
        TextView title = SettingsUi.text(ui, name, 14, SettingsUi.ink()); title.setTypeface(SettingsUi.medium());
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(title);
        if (place.detail != null && !place.detail.isEmpty()) {
            TextView detail = SettingsUi.text(ui, place.detail, 12, SettingsUi.muted());
            detail.setSingleLine(true); detail.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(detail);
        }
        row.setContentDescription("Afficher " + name + " sur la carte");
        final double lat = place.latitude, lon = place.longitude;
        row.setOnClickListener(v -> choose(name.startsWith("Aller à ") ? name.substring(8) : name, lat, lon));
        suggestions.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void choose(String name, double lat, double lon) {
        if (closed) return;
        generation++; main.removeCallbacks(lookup);
        quiet = true; input.setText(name); input.setSelection(input.getText().length()); quiet = false;
        dismiss();
        selection.select(lat, lon);
    }

    private void keyboard(boolean open) {
        InputMethodManager keys = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keys == null) return;
        if (open) keys.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        else keys.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    void close() {
        if (closed) return;
        closed = true; generation++;
        main.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        // close() may touch the network, so keep it off the main thread.
        new Thread(() -> { try { client.close(); } catch (Throwable ignored) {} }, "pgrazor-place-search-close").start();
    }

    private int dp(float value) { return SettingsUi.dp(ui, value); }
}
