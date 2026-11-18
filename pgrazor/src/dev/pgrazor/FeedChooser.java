package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;

/** The quick menu's Feeds entry: pick which live feed service to open (each keeps its own sign-in). */
final class FeedChooser {
    private FeedChooser() {}

    static void show(Activity activity) {
        String[] labels = {"LivePokeMap\nPokémon, PokéStops, Arènes, raids et Sites de Puissance à proximité",
                           "PoGoCoordinates\nRecherche de Pokémon par IV, rang PvP et plus ; connexion avec Discord"};
        AlertDialog choose = ChoiceList.attach(new AlertDialog.Builder(MapTheme.wrap(activity)).setTitle("Flux")
            .setNegativeButton("Annuler", null).create(), labels, which -> {
                if (which == 0) LiveFeedDialog.show(activity); else PogoFeedDialog.show(activity);
            });
        DialogLifecycle.bind(activity, choose, null);
        choose.setOnShowListener(d -> MapTheme.styleButtons(choose));
        choose.show();
    }
}
