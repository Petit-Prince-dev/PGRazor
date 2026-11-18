package dev.pgrazor;

import android.app.Activity;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Transfer and evolve-before-transfer use independent settings through the shared species picker. */
public final class TransferKeepDialog {
    private TransferKeepDialog() {}

    public static void show(Activity activity, Runnable onSaved) {
        PokemonSpeciesDialog.show(activity, "Liste perso à garder", "species", 2000,
            Control::transferKeepSpecies, Control::setTransferKeepSpecies, onSaved,
            "Aucune espèce protégée en plus", null);
    }

    public static void show(Activity activity, String title, String countNoun,
                            Supplier<String> seed, Consumer<String> commit, Runnable onSaved) {
        PokemonSpeciesDialog.show(activity, title, "species", 2000, seed, commit, onSaved,
            "Aucune espèce sélectionnée", null);
    }
}
