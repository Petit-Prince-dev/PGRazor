package dev.pgrazor.events;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Event selection stays independent of gameplay policy and native object classification. */
public final class EventPanel {
    private EventPanel() {}

    public static void showSelector(Activity activity, boolean dark) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        AlertDialog dialog = dev.pgrazor.ChoiceList.attach(new AlertDialog.Builder(theme(activity, dark))
            .setTitle("Événements")
            .setNegativeButton("Fermer", null).create(), new String[]{"Halloween 2026", "Go Wild November 2026"},
            index -> { if (index == 0) showHalloween(activity, dark); else showGoWild(activity, dark); });
        bind(activity, dialog, () -> {});
        dialog.show();
    }

    private static Context theme(Activity activity, boolean dark) {
        return new ContextThemeWrapper(activity, dark ? android.R.style.Theme_Material_Dialog_Alert
            : android.R.style.Theme_Material_Light_Dialog_Alert);
    }

    public static void showHalloween(Activity activity, boolean dark) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Context context = theme(activity, dark);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * context.getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad / 2, pad, pad / 2);
        TextView description = new TextView(context);
        description.setText("Ouvre chaque boîte visible sur la carte et passe l'écran de rencontre pour les Pokémon obtenus.");
        description.setTextSize(15);
        content.addView(description);
        TextView status = new TextView(context);
        status.setPadding(0, pad / 2, 0, pad / 2);
        status.setTextSize(13);
        content.addView(status);
        Switch collector = new Switch(context);
        collector.setText("Ouvrir les boîtes et passer les rencontres");
        collector.setTextSize(15);
        content.addView(collector);

        ScrollView scroller = new ScrollView(context);
        scroller.addView(content);

        AlertDialog dialog = new AlertDialog.Builder(context).setTitle("Halloween 2026")
            .setView(scroller).setNegativeButton("Fermer", null).create();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable refresh = new Runnable() {
            public void run() {
                if (!dialog.isShowing()) return;
                boolean available = EventSettings.boxCollectorAvailable();
                collector.setEnabled(available);
                collector.setChecked(EventSettings.boxCollectorEnabled(activity));
                status.setText(available
                    ? "Prêt à ouvrir les boîtes visibles. Les écrans de rencontre sont passés sans capture."
                    : "Indisponible : le collecteur de boîtes n'a pas été vérifié. La collecte est désactivée.");
                handler.postDelayed(this, 500);
            }
        };
        collector.setOnCheckedChangeListener((button, checked) -> {
            if (checked == EventSettings.boxCollectorEnabled(activity)) return;
            EventSettings.setBoxCollectorRequested(activity, checked);
            button.setChecked(EventSettings.boxCollectorEnabled(activity));
        });
        bind(activity, dialog, () -> handler.removeCallbacks(refresh));
        dialog.setOnShowListener(ignored -> refresh.run());
        dialog.show();
    }

    public static void showGoWild(Activity activity, boolean dark) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Context context = theme(activity, dark);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * context.getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad / 2, pad, pad / 2);
        TextView description = new TextView(context);
        description.setText("Privilégier les Safari Balls GO pour les Pokémon Colossaux. Si les Safari Balls sont indisponibles"
            + " ou interdites, tes réglages de Ball normaux s'appliquent.");
        description.setTextSize(15);
        content.addView(description);

        Switch vgp = new Switch(context);
        vgp.setText("Utiliser les Safari Balls dans le VGP+");
        vgp.setTextSize(15);
        vgp.setChecked(EventSettings.wildVgpSafari(activity));
        content.addView(vgp);
        TextView vgpHelp = new TextView(context);
        vgpHelp.setText("S'applique pendant que le VGP+ tourne.");
        vgpHelp.setTextSize(13);
        vgpHelp.setPadding(0, 0, 0, pad / 2);
        content.addView(vgpHelp);

        Switch autoBall = new Switch(context);
        autoBall.setText("Utiliser les Safari Balls dans Ball auto");
        autoBall.setTextSize(15);
        autoBall.setChecked(EventSettings.wildAutoBallSafari(activity));
        content.addView(autoBall);
        TextView autoHelp = new TextView(context);
        autoHelp.setText("S'applique quand Ball auto est activé.");
        autoHelp.setTextSize(13);
        content.addView(autoHelp);

        TextView status = new TextView(context);
        status.setPadding(0, pad / 2, 0, 0);
        status.setTextSize(13);
        content.addView(status);

        ScrollView scroller = new ScrollView(context);
        scroller.addView(content);

        AlertDialog dialog = new AlertDialog.Builder(context).setTitle("Go Wild November 2026")
            .setView(scroller).setNegativeButton("Fermer", null).create();
        vgp.setOnCheckedChangeListener((button, checked) -> {
            if (checked == EventSettings.wildVgpSafari(activity)) return;
            if (!EventSettings.setWildVgpSafari(activity, checked)) {
                status.setText("Impossible d'enregistrer ce changement. Réessaie.");
                button.setChecked(EventSettings.wildVgpSafari(activity));
                return;
            }
            status.setText("");
            dev.pgrazor.Control.event("event-wild-safari", "vgp_plus=" + (checked ? "ON" : "OFF"));
        });
        autoBall.setOnCheckedChangeListener((button, checked) -> {
            if (checked == EventSettings.wildAutoBallSafari(activity)) return;
            if (!EventSettings.setWildAutoBallSafari(activity, checked)) {
                status.setText("Impossible d'enregistrer ce changement. Réessaie.");
                button.setChecked(EventSettings.wildAutoBallSafari(activity));
                return;
            }
            status.setText("");
            dev.pgrazor.Control.event("event-wild-safari", "auto_ball=" + (checked ? "ON" : "OFF"));
        });
        bind(activity, dialog, () -> {});
        dialog.show();
    }

    private static void bind(Activity owner, AlertDialog dialog, Runnable cleanup) {
        Application application = owner.getApplication();
        Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
            public void onActivityPaused(Activity activity) { if (activity == owner) dialog.dismiss(); }
            public void onActivityDestroyed(Activity activity) { if (activity == owner) dialog.dismiss(); }
            public void onActivityCreated(Activity activity, Bundle state) {}
            public void onActivityStarted(Activity activity) {}
            public void onActivityResumed(Activity activity) {}
            public void onActivityStopped(Activity activity) {}
            public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        };
        application.registerActivityLifecycleCallbacks(lifecycle);
        dialog.setOnDismissListener(ignored -> {
            cleanup.run();
            application.unregisterActivityLifecycleCallbacks(lifecycle);
        });
    }
}
