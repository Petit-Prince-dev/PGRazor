package dev.pgrazor;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;

/**
 * Multi-account support is removed from this build: there is one storage namespace, the default
 * one (app files dir and plain preference names), so existing single-account data is used as is.
 * The class keeps its name so the many storage call sites stay unchanged.
 */
final class AccountProfiles {
    private AccountProfiles() {}

    static boolean allowed() { return true; }
    static String currentId() { return "default"; }
    /** Any account the game reports is the one account this build serves. */
    static boolean matchesAutomationIdentity(String id) { return id != null && !id.isEmpty(); }
    static File file(Context context, String name) { return new File(context.getFilesDir(), name); }
    static SharedPreferences preferences(Context context, String scope) {
        return context.getSharedPreferences(scope, Context.MODE_PRIVATE);
    }
}
