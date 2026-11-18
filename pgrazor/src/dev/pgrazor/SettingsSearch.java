package dev.pgrazor;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The settings search catalogue: pure Java, no Android types, so it runs under the host JVM test
 * suite. The panel registers a view target for each entry; this class owns only the text and the
 * match order.
 *
 * <p>Every entry keeps one pre-normalised haystack (name, description, category, group and
 * synonyms), so a keystroke is a substring scan over a small list rather than a view walk. Matching
 * is case-insensitive, accent-insensitive ({@code pokemon} finds {@code Pokémon}) and requires every
 * query word to appear, which keeps multi-word searches precise.
 */
final class SettingsSearch {
    static final class Entry {
        final int id;
        final String name, description, category, group;
        final int page;
        private final String normalizedName, normalizedDescription, haystack;

        private Entry(int id, String name, String description, String category, String group, int page) {
            this.id = id; this.name = name; this.description = description;
            this.category = category; this.group = group; this.page = page;
            this.normalizedName = normalize(name);
            this.normalizedDescription = normalize(description);
            this.haystack = this.normalizedName + ' ' + this.normalizedDescription + ' '
                + normalize(category) + ' ' + normalize(group) + ' ' + normalize(synonyms(name));
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern COUNT = Pattern.compile("\\s*\\(\\d+\\)$");

    int add(String name, String group, String category, int page) {
        int id = entries.size();
        entries.add(new Entry(id, name, describe(name, group, category), category, group, page));
        return id;
    }

    int size() { return entries.size(); }

    /** All matches in display order: exact name, name prefix, name substring, then text matches. */
    List<Entry> find(String query) {
        String wanted = normalize(query);
        if (wanted.isEmpty() || entries.isEmpty()) return Collections.emptyList();
        String[] tokens = wanted.split(" ");
        ArrayList<Entry> hits = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            boolean all = true;
            for (int t = 0; t < tokens.length; t++) {
                if (!entry.haystack.contains(tokens[t])) { all = false; break; }
            }
            if (all) hits.add(entry);
        }
        final String whole = wanted;
        Collections.sort(hits, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                int rank = rank(a, whole) - rank(b, whole);
                if (rank != 0) return rank;
                int length = a.normalizedName.length() - b.normalizedName.length();
                if (length != 0) return length;
                return a.normalizedName.compareTo(b.normalizedName);
            }
        });
        return hits;
    }

    private static int rank(Entry entry, String query) {
        if (entry.normalizedName.equals(query)) return 0;
        if (entry.normalizedName.startsWith(query)) return 1;
        if (entry.normalizedName.contains(query)) return 2;
        if (entry.normalizedDescription.startsWith(query)) return 3;
        if (entry.normalizedDescription.contains(query)) return 4;
        return 5;
    }

    /** Lowercase, strip accents and collapse spaces; {@code Pokémon} and {@code pokemon} compare equal. */
    static String normalize(String value) {
        if (value == null || value.isEmpty()) return "";
        String lower = value.toLowerCase(Locale.ROOT).trim();
        String folded = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return SPACES.matcher(MARKS.matcher(folded).replaceAll("")).replaceAll(" ");
    }

    /** The setting name without its current value: {@code "Throw rating: Great"} becomes {@code "Throw rating"}. */
    static String cleanName(String raw) {
        if (raw == null) return "";
        String name = SPACES.matcher(raw.trim()).replaceAll(" ");
        int split = name.indexOf(": ");
        if (split > 0) name = name.substring(0, split);
        name = COUNT.matcher(name).replaceAll("");
        String renamed = RENAMES.get(normalize(name));
        return renamed == null ? name : renamed;
    }

    private static String describe(String name, String group, String category) {
        String text = DESCRIPTIONS.get(normalize(name));
        if (text != null) return text;
        if ("quick menu entries".equals(normalize(group))) return "Show " + name + " in the quick menu.";
        if ("entrees du menu rapide".equals(normalize(group))) return "Afficher " + name + " dans le menu rapide.";
        if (group != null && !group.isEmpty()) return "Option " + group + ".";
        return category;
    }

    private static String synonyms(String name) {
        String value = SYNONYMS.get(normalize(name));
        return value == null ? "" : value;
    }

    private static final HashMap<String, String> DESCRIPTIONS = new HashMap<>();
    private static final HashMap<String, String> RENAMES = new HashMap<>();
    private static final HashMap<String, String> SYNONYMS = new HashMap<>();
    private static void put(HashMap<String, String> map, String key, String value) { map.put(normalize(key), value); }

    static {
        put(RENAMES, "Evolve", "Evolve stages");
        put(RENAMES, "Applies to", "Evolve applies to");
        put(RENAMES, "Selected species", "Evolve species");
        put(RENAMES, "Encounter", "Encounter IV display");
        put(RENAMES, "Pokémon stats screen", "Pokémon stats IV display");
        put(RENAMES, "Pokémon storage list", "Storage list IV display");
        put(RENAMES, "Heal", "Heal timing");
        put(RENAMES, "Heal when", "Heal threshold");
        put(RENAMES, "Items", "Healing items");
        put(RENAMES, "Priority", "Incubator priority");
        put(RENAMES, "VGP+", "VGP+ mode");

        put(DESCRIPTIONS, "Location spoofing", "Use the spoofed location instead of the device GPS.");
        put(DESCRIPTIONS, "Coordinates & speed", "Set the latitude, longitude and walking speed.");
        put(DESCRIPTIONS, "World map", "Pick a destination, walk route or nearby point on the map.");
        put(DESCRIPTIONS, "Show joystick", "Show the on-screen movement joystick.");
        put(DESCRIPTIONS, "Idle walking", "Walk in a circle when the joystick is idle. Speed 0 pauses it.");
        put(DESCRIPTIONS, "Game map tap", "Choose what a single or double tap on the game map does.");
        put(DESCRIPTIONS, "Spawn booster (80 m)", "Load nearby Pokémon sooner. Can cause lag in busy areas.");
        put(DESCRIPTIONS, "Cooldown overlay", "Show the estimated catch cooldown timer on the map.");
        put(DESCRIPTIONS, "Cooldown history", "Review recent cooldown actions and locations.");
        put(DESCRIPTIONS, "Show local time", "Show the wall-clock time at the spoofed coordinates.");
        put(DESCRIPTIONS, "Count encounters", "Count wild encounters for this account in a rolling 24 hours.");
        put(DESCRIPTIONS, "Show the count on the map", "Show the encounter count next to the PGRazor title.");
        put(DESCRIPTIONS, "Quick catch", "Speed up or skip the catch summary after a throw.");
        put(DESCRIPTIONS, "Faster catch summary", "Speed up or skip the catch summary after a throw.");
        put(DESCRIPTIONS, "Throw rating", "Set the throw quality used for automatic throws.");
        put(DESCRIPTIONS, "Throw variety", "Pick a random throw quality for each throw.");
        put(DESCRIPTIONS, "Always curveball", "Curve every automatic throw.");
        put(DESCRIPTIONS, "Always hit", "Make automatic throws always land.");
        put(DESCRIPTIONS, "AR+ throw bonus", "Apply the AR+ throw bonus to automatic throws.");
        put(DESCRIPTIONS, "Show catch chance", "Show the catch chance during an encounter.");
        put(DESCRIPTIONS, "Auto ball", "Pick the cheapest ball that reaches your target catch chance.");
        put(DESCRIPTIONS, "Auto ball target", "Catch chance target used by Auto ball.");
        put(DESCRIPTIONS, "Auto throw", "Throw automatically using your ball, berry and throw settings.");
        put(DESCRIPTIONS, "Catch on map (tap Pokémon)", "Catch a tapped wild, lure or incense Pokémon without opening it.");
        put(DESCRIPTIONS, "Catch berries & overlay", "Use berries automatically and show the berry picker overlay.");
        put(DESCRIPTIONS, "Berry priority", "Choose the berry order for automatic catching.");
        put(DESCRIPTIONS, "Transfer below 90% IV", "Transfer new catches below the IV threshold after the catch summary.");
        put(DESCRIPTIONS, "Custom keep list", "Keep specific species, including all forms, from auto-transfer.");
        put(DESCRIPTIONS, "Keep-all overlay button", "Show a floating Keep All button that holds every catch.");
        put(DESCRIPTIONS, "Keep Great League PvP IVs", "Keep catches that rank well in Great League.");
        put(DESCRIPTIONS, "Keep Ultra League PvP IVs", "Keep catches that rank well in Ultra League.");
        put(DESCRIPTIONS, "Keep Master League PvP IVs", "Keep catches that rank well in Master League.");
        put(DESCRIPTIONS, "Evolve before transferring", "Evolve new catches before applying transfer rules.");
        put(DESCRIPTIONS, "Evolve stages", "Try one or all available evolution stages.");
        put(DESCRIPTIONS, "Evolve applies to", "Apply evolution to selected species only or to every transfer.");
        put(DESCRIPTIONS, "Evolve species", "Choose the species to evolve before transferring.");
        put(DESCRIPTIONS, "Encounter IV display", "Show level, IVs and PvP rank on the catch screen.");
        put(DESCRIPTIONS, "Pokémon stats IV display", "Show IV details on the Pokémon stats screen.");
        put(DESCRIPTIONS, "Storage list IV display", "Show IV details in the Pokémon storage list.");
        put(DESCRIPTIONS, "Mass Evolve", "Evolve many Pokémon in one run.");
        put(DESCRIPTIONS, "VGP overlay", "Show the Virtual Go Plus button and status on the map.");
        put(DESCRIPTIONS, "VGP+ mode", "Extended Virtual Go Plus ball, berry and throw control.");
        put(DESCRIPTIONS, "VGP+ auto ball", "Pick a ball by catch chance for VGP+.");
        put(DESCRIPTIONS, "VGP+ auto ball target", "Catch chance target used by VGP+ auto ball.");
        put(DESCRIPTIONS, "Standard ball", "Ball used by VGP+ when auto ball is off.");
        put(DESCRIPTIONS, "Standard berry", "Berry used by VGP+ when automatic berries are on.");
        put(DESCRIPTIONS, "High CP threshold", "Use the premium ball and berry above this CP.");
        put(DESCRIPTIONS, "High level threshold", "Use the premium ball and berry above this level.");
        put(DESCRIPTIONS, "Curveball", "Curve VGP+ throws.");
        put(DESCRIPTIONS, "Catch speed", "How long VGP+ waits before each throw and scan.");
        put(DESCRIPTIONS, "Auto-spin Gym discs", "Spin nearby Gym discs while VGP is running.");
        put(DESCRIPTIONS, "Candy species (Pinap)", "Species that receive Pinap berries.");
        put(DESCRIPTIONS, "Catch only shinies", "Open every Pokémon but throw only at shinies.");
        put(DESCRIPTIONS, "Catch only", "Catch only the selected species.");
        put(DESCRIPTIONS, "Nearby Pokémon", "Let Virtual Go Plus catch nearby Pokémon.");
        put(DESCRIPTIONS, "Nearby PokéStops", "Let Virtual Go Plus spin nearby PokéStops.");
        put(DESCRIPTIONS, "Push notifications", "Notify when Virtual Go Plus acts.");
        put(DESCRIPTIONS, "Stop on cooldown (estimated)", "Pause Virtual Go Plus during the estimated cooldown.");
        put(DESCRIPTIONS, "Quick spin", "Spin PokéStops automatically.");
        put(DESCRIPTIONS, "Auto-transfer items", "Trim inventory items down to limits you choose.");
        put(DESCRIPTIONS, "Smart battling", "Fill teams with counters and swap to the best counter.");
        put(DESCRIPTIONS, "Smart healing", "Heal and revive automatically between battles.");
        put(DESCRIPTIONS, "Heal timing", "Choose when Smart healing runs.");
        put(DESCRIPTIONS, "Heal threshold", "Choose how much HP missing starts healing.");
        put(DESCRIPTIONS, "Healing items", "Use smart potion choices or heal fully.");
        put(DESCRIPTIONS, "Revive fainted Pokémon", "Revive fainted Pokémon during Smart healing.");
        put(DESCRIPTIONS, "Gym Slotter & Berry Feeder", "Place defenders and feed gyms automatically.");
        put(DESCRIPTIONS, "Battle until defeated", "Repeat a gym battle until the gym is defeated.");
        put(DESCRIPTIONS, "Remote Gym Feeding", "Feed the gyms you defend once with surplus berries.");
        put(DESCRIPTIONS, "Auto-Raid", "Find and complete nearby raids.");
        put(DESCRIPTIONS, "Auto quick attack", "Use fast attacks automatically in battles.");
        put(DESCRIPTIONS, "Auto charged attack", "Use charged attacks automatically in battles.");
        put(DESCRIPTIONS, "Auto Max attack", "Use Max attacks automatically in Dynamax battles.");
        put(DESCRIPTIONS, "Auto dodge", "Dodge automatically in battles.");
        put(DESCRIPTIONS, "Auto shield", "Use shields automatically in Team Rocket battles.");
        put(DESCRIPTIONS, "Auto incubate", "Hatch eggs with the unlimited incubator.");
        put(DESCRIPTIONS, "Incubator priority", "Hatch the shortest or longest distance egg first.");
        put(DESCRIPTIONS, "Auto open gifts", "Open friend gifts automatically.");
        put(DESCRIPTIONS, "Auto send gifts", "Send friend gifts automatically.");
        put(DESCRIPTIONS, "Add an owned sticker", "Add an owned sticker when sending gifts.");
        put(DESCRIPTIONS, "Send & Open Gifts", "Open and send friend gifts in one batch.");
        put(DESCRIPTIONS, "Run the Buddy Emotion Engine", "Build buddy mood with snapshots and petting.");
        put(DESCRIPTIONS, "Open gifts your buddy brings back", "Open gifts returned by your buddy.");
        put(DESCRIPTIONS, "Show EP overlay", "Show the buddy excitement status overlay.");
        put(DESCRIPTIONS, "Feeding priority", "Choose the berry order used by the buddy engine.");
        put(DESCRIPTIONS, "Reserve per berry", "Keep this many of each berry before feeding.");
        put(DESCRIPTIONS, "Shundo Sniper (Experimental)", "Teleport to nearby spawns looking for a shundo.");
        put(DESCRIPTIONS, "Shiny Scanner (Experimental)", "Check nearby wild Pokémon for shinies without catching.");
        put(DESCRIPTIONS, "Daily Incense Session", "Walk a 15-minute incense session at 10.5 km/h.");
        put(DESCRIPTIONS, "Research", "Auto-claim, delete and catch field research.");
        put(DESCRIPTIONS, "Halloween 2026", "Event preferences for Halloween 2026.");
        put(DESCRIPTIONS, "Go Wild November 2026", "Event preferences for GO Wild Area 2026.");
        put(DESCRIPTIONS, "Dark theme", "Switch the whole interface between the dark and light palette.");
        put(DESCRIPTIONS, "UI scale", "Resize the joystick and map overlays.");
        put(DESCRIPTIONS, "Skip cutscenes", "Skip repeatable cutscenes and hatch animations.");
        put(DESCRIPTIONS, "Lower resolution", "Draw the map and Pokémon at half resolution.");
        put(DESCRIPTIONS, "Limit to 20 FPS", "Cap the game at 20 frames per second.");
        put(DESCRIPTIONS, "Low graphics quality", "Turn off shadows, fog, reflections and other heavy effects.");
        put(DESCRIPTIONS, "Export / import settings", "Back up or restore every saved setting.");
        put(DESCRIPTIONS, "Run log / export", "Open the run log and export it as text.");
        put(DESCRIPTIONS, "Diagnostics", "Camera and catch or spin capture diagnostics.");

        put(DESCRIPTIONS, "Keep legendary / mythical / Ultra Beasts", "Never auto-transfer legendary, mythical or Ultra Beast Pokémon.");
        put(DESCRIPTIONS, "Keep shiny Pokemon", "Never auto-transfer shiny Pokémon.");
        put(DESCRIPTIONS, "Keep event Pokemon", "Never auto-transfer Pokémon caught during an event.");
        put(DESCRIPTIONS, "Keep background Pokemon", "Never auto-transfer Pokémon with a special background.");
        put(DESCRIPTIONS, "Keep XXS", "Never auto-transfer XXS Pokémon.");
        put(DESCRIPTIONS, "Keep XXL", "Never auto-transfer XXL Pokémon.");
        put(DESCRIPTIONS, "Keep 0% IV (0/0/0)", "Never auto-transfer a 0% IV Pokémon.");
        put(DESCRIPTIONS, "Keep costume Pokemon", "Never auto-transfer costume Pokémon.");

        put(SYNONYMS, "Location spoofing", "fake gps mock location teleport");
        put(SYNONYMS, "Coordinates & speed", "lat lon latitude longitude kmh walking speed");
        put(SYNONYMS, "World map", "map place search pin route");
        put(SYNONYMS, "Auto ball", "ball pokeball great ultra catch rate");
        put(SYNONYMS, "Auto throw", "throw catch automatic");
        put(SYNONYMS, "Transfer below 90% IV", "transfer iv appraise candy");
        put(SYNONYMS, "Custom keep list", "keep species list transfer");
        put(SYNONYMS, "Keep Great League PvP IVs", "pvp rank great league");
        put(SYNONYMS, "Keep Ultra League PvP IVs", "pvp rank ultra league");
        put(SYNONYMS, "Keep Master League PvP IVs", "pvp rank master league");
        put(SYNONYMS, "Encounter IV display", "iv overlay catch screen stats");
        put(SYNONYMS, "Pokémon stats IV display", "iv overlay stats screen");
        put(SYNONYMS, "Storage list IV display", "iv overlay storage list");
        put(SYNONYMS, "VGP overlay", "go plus virtual vgp button");
        put(SYNONYMS, "Quick spin", "pokestop spin stop");
        put(SYNONYMS, "Smart healing", "heal potion revive");
        put(SYNONYMS, "Auto incubate", "egg incubator hatch");
        put(SYNONYMS, "Dark theme", "light mode appearance");
        put(SYNONYMS, "UI scale", "size resize scale overlays");
        put(SYNONYMS, "Limit to 20 FPS", "fps frames battery performance");
        put(SYNONYMS, "Lower resolution", "resolution battery performance graphics");
        put(SYNONYMS, "Low graphics quality", "graphics quality battery performance shadows");
        put(SYNONYMS, "Export / import settings", "backup restore settings");
        put(SYNONYMS, "Run log / export", "log txt diagnostics export");
        put(SYNONYMS, "Shiny Scanner (Experimental)", "shiny scan scanner notification");
        put(SYNONYMS, "Shundo Sniper (Experimental)", "shundo sniper teleport shiny");

        // Catalogue français : clés = libellés du panneau traduit.
        put(RENAMES, "Évolution", "Étapes d'évolution");
        put(RENAMES, "S'applique à", "Évolution appliquée à");
        put(RENAMES, "Espèces sélectionnées", "Espèces à faire évoluer");
        put(RENAMES, "Rencontre", "IV affichés en rencontre");
        put(RENAMES, "Écran des stats du Pokémon", "IV affichés dans les stats");
        put(RENAMES, "Liste du stockage Pokémon", "IV affichés dans le stockage");
        put(RENAMES, "Soigner", "Moment du soin");
        put(RENAMES, "Soigner si", "Seuil de soin");
        put(RENAMES, "Objets", "Objets de soin");
        put(RENAMES, "Priorité", "Priorité d'incubation");
        put(RENAMES, "VGP+", "Mode VGP+");
        put(DESCRIPTIONS, "Spoof de position", "Utilise la position spoofée au lieu du GPS de l'appareil.");
        put(DESCRIPTIONS, "Coordonnées & vitesse", "Règle la latitude, la longitude et la vitesse de marche.");
        put(DESCRIPTIONS, "Carte du monde", "Choisis une destination, une route ou un point proche sur la carte.");
        put(DESCRIPTIONS, "Afficher le joystick", "Affiche le joystick de déplacement à l'écran.");
        put(DESCRIPTIONS, "Marche à l'arrêt", "Marche en cercle quand le joystick est au repos. Vitesse 0 = pause.");
        put(DESCRIPTIONS, "Appui sur la carte", "Choisis l'effet d'un appui simple ou double sur la carte du jeu.");
        put(DESCRIPTIONS, "Booster d'apparitions (80 m)", "Charge plus vite les Pokémon proches. Peut faire ramer dans les zones chargées.");
        put(DESCRIPTIONS, "Overlay du cooldown", "Affiche le minuteur de cooldown estimé sur la carte.");
        put(DESCRIPTIONS, "Historique du cooldown", "Consulte les actions et positions récentes du cooldown.");
        put(DESCRIPTIONS, "Afficher l'heure locale", "Affiche l'heure locale aux coordonnées spoofées.");
        put(DESCRIPTIONS, "Compter les rencontres", "Compte les rencontres sauvages de ce compte sur 24 h glissantes.");
        put(DESCRIPTIONS, "Afficher le compteur sur la carte", "Affiche le nombre de rencontres à côté du titre PGRazor.");
        put(DESCRIPTIONS, "Capture rapide", "Accélère ou passe le résumé de capture après un lancer.");
        put(DESCRIPTIONS, "Résumé de capture plus rapide", "Accélère ou passe le résumé de capture après un lancer.");
        put(DESCRIPTIONS, "Qualité du lancer", "Règle la qualité des lancers automatiques.");
        put(DESCRIPTIONS, "Lancers variés", "Choisit une qualité de lancer au hasard à chaque lancer.");
        put(DESCRIPTIONS, "Toujours en balle courbe", "Lance chaque Ball automatique en balle courbe.");
        put(DESCRIPTIONS, "Toujours toucher", "Les lancers automatiques touchent toujours.");
        put(DESCRIPTIONS, "Bonus de lancer RA+", "Applique le bonus de lancer RA+ aux lancers automatiques.");
        put(DESCRIPTIONS, "Afficher la chance de capture", "Affiche la chance de capture pendant une rencontre.");
        put(DESCRIPTIONS, "Ball auto", "Choisit la Ball la moins chère qui atteint ta chance de capture cible.");
        put(DESCRIPTIONS, "Objectif Ball auto", "Chance de capture visée par Ball auto.");
        put(DESCRIPTIONS, "Lancer auto", "Lance automatiquement avec tes réglages de Ball, de baie et de lancer.");
        put(DESCRIPTIONS, "Capture depuis la carte (touche le Pokémon)", "Capture un Pokémon sauvage, de Leurre ou d'Encens touché sur la carte sans l'ouvrir.");
        put(DESCRIPTIONS, "Baies de capture & overlay", "Utilise les baies automatiquement et affiche l'overlay de choix de baie.");
        put(DESCRIPTIONS, "Priorité des baies", "Choisis l'ordre des baies pour la capture automatique.");
        put(DESCRIPTIONS, "Transférer sous 90 % d'IV", "Transfère les nouvelles captures sous le seuil d'IV après le résumé de capture.");
        put(DESCRIPTIONS, "Liste perso à garder", "Protège certaines espèces, toutes formes comprises, du transfert auto.");
        put(DESCRIPTIONS, "Bouton overlay « tout garder »", "Affiche un bouton flottant Tout garder qui conserve chaque capture.");
        put(DESCRIPTIONS, "Garder les IV PvP Super Ligue", "Garde les captures bien classées en Super Ligue.");
        put(DESCRIPTIONS, "Garder les IV PvP Hyper Ligue", "Garde les captures bien classées en Hyper Ligue.");
        put(DESCRIPTIONS, "Garder les IV PvP Master Ligue", "Garde les captures bien classées en Master Ligue.");
        put(DESCRIPTIONS, "Faire évoluer avant de transférer", "Fait évoluer les nouvelles captures avant d'appliquer les règles de transfert.");
        put(DESCRIPTIONS, "Étapes d'évolution", "Tente une ou toutes les étapes d'évolution disponibles.");
        put(DESCRIPTIONS, "Évolution appliquée à", "Applique l'évolution aux espèces choisies seulement ou à chaque transfert.");
        put(DESCRIPTIONS, "Espèces à faire évoluer", "Choisis les espèces à faire évoluer avant le transfert.");
        put(DESCRIPTIONS, "IV affichés en rencontre", "Affiche le niveau, les IV et le rang PvP sur l'écran de capture.");
        put(DESCRIPTIONS, "IV affichés dans les stats", "Affiche les IV sur l'écran des stats du Pokémon.");
        put(DESCRIPTIONS, "IV affichés dans le stockage", "Affiche les IV dans la liste du stockage Pokémon.");
        put(DESCRIPTIONS, "Évolution en masse", "Fait évoluer beaucoup de Pokémon d'un coup.");
        put(DESCRIPTIONS, "Overlay VGP", "Affiche le bouton et l'état du Virtual Go Plus sur la carte.");
        put(DESCRIPTIONS, "Mode VGP+", "Contrôle avancé de la Ball, de la baie et du lancer du Virtual Go Plus.");
        put(DESCRIPTIONS, "Ball auto VGP+", "Choisit la Ball selon la chance de capture pour le VGP+.");
        put(DESCRIPTIONS, "Cible Ball auto VGP+", "Chance de capture visée par la Ball auto du VGP+.");
        put(DESCRIPTIONS, "Ball standard", "Ball utilisée par le VGP+ quand la Ball auto est désactivée.");
        put(DESCRIPTIONS, "Baie standard", "Baie utilisée par le VGP+ quand les baies auto sont activées.");
        put(DESCRIPTIONS, "Seuil de PC élevé", "Utilise la Ball et la baie premium au-dessus de ce PC.");
        put(DESCRIPTIONS, "Seuil de niveau élevé", "Utilise la Ball et la baie premium au-dessus de ce niveau.");
        put(DESCRIPTIONS, "Balle courbe", "Lancers VGP+ en balle courbe.");
        put(DESCRIPTIONS, "Vitesse de capture", "Temps d'attente du VGP+ avant chaque lancer et recherche.");
        put(DESCRIPTIONS, "Spin auto des disques d'Arène", "Fait tourner les disques d'Arène proches pendant le VGP.");
        put(DESCRIPTIONS, "Espèces à Bonbons (Nanana)", "Espèces qui reçoivent des Baies Nanana.");
        put(DESCRIPTIONS, "Capturer seulement les shiny", "Ouvre chaque Pokémon mais ne lance que sur les shiny.");
        put(DESCRIPTIONS, "Capturer seulement", "Capture seulement les espèces choisies.");
        put(DESCRIPTIONS, "Pokémon à proximité", "Laisse le Virtual Go Plus capturer les Pokémon proches.");
        put(DESCRIPTIONS, "PokéStops à proximité", "Laisse le Virtual Go Plus faire tourner les PokéStops proches.");
        put(DESCRIPTIONS, "Notifications push", "Notifie quand le Virtual Go Plus agit.");
        put(DESCRIPTIONS, "Stop pendant le cooldown (estimé)", "Met le Virtual Go Plus en pause pendant le cooldown estimé.");
        put(DESCRIPTIONS, "Spin rapide", "Fait tourner les PokéStops automatiquement.");
        put(DESCRIPTIONS, "Jeter les objets auto", "Réduit les objets du sac aux limites que tu choisis.");
        put(DESCRIPTIONS, "Combat intelligent", "Remplit les équipes avec des counters et passe au meilleur counter.");
        put(DESCRIPTIONS, "Soin intelligent", "Soigne et ranime automatiquement entre les combats.");
        put(DESCRIPTIONS, "Moment du soin", "Choisis quand le soin intelligent s'active.");
        put(DESCRIPTIONS, "Seuil de soin", "Choisis à partir de combien de PV manquants le soin démarre.");
        put(DESCRIPTIONS, "Objets de soin", "Choix intelligent des potions ou soin complet.");
        put(DESCRIPTIONS, "Ranimer les Pokémon K.O.", "Ranime les Pokémon K.O. pendant le soin intelligent.");
        put(DESCRIPTIONS, "Placement en arène & nourrissage", "Place des défenseurs et nourrit les Arènes automatiquement.");
        put(DESCRIPTIONS, "Combattre jusqu'à la défaite", "Répète un combat d'Arène jusqu'à la vaincre.");
        put(DESCRIPTIONS, "Nourrissage d'arène à distance", "Nourrit une fois tes Arènes avec les baies en surplus.");
        put(DESCRIPTIONS, "Auto-Raid", "Trouve et termine les raids proches.");
        put(DESCRIPTIONS, "Attaque rapide auto", "Utilise les attaques rapides automatiquement en combat.");
        put(DESCRIPTIONS, "Attaque chargée auto", "Utilise les attaques chargées automatiquement en combat.");
        put(DESCRIPTIONS, "Attaque Max auto", "Utilise les attaques Max automatiquement en combat Dynamax.");
        put(DESCRIPTIONS, "Esquive auto", "Esquive automatiquement en combat.");
        put(DESCRIPTIONS, "Bouclier auto", "Utilise les boucliers automatiquement contre la Team GO Rocket.");
        put(DESCRIPTIONS, "Incubation auto", "Fait éclore les Œufs avec l'incubateur infini.");
        put(DESCRIPTIONS, "Priorité d'incubation", "Fait éclore d'abord l'Œuf à la distance la plus courte ou la plus longue.");
        put(DESCRIPTIONS, "Ouvrir les cadeaux auto", "Ouvre automatiquement les cadeaux d'amis.");
        put(DESCRIPTIONS, "Envoyer les cadeaux auto", "Envoie automatiquement les cadeaux d'amis.");
        put(DESCRIPTIONS, "Ajouter un autocollant possédé", "Ajoute un autocollant possédé en envoyant les cadeaux.");
        put(DESCRIPTIONS, "Envoyer & ouvrir les cadeaux", "Ouvre et envoie les cadeaux d'amis en une fois.");
        put(DESCRIPTIONS, "Lancer le moteur d'émotions du copain", "Fait monter l'humeur du copain avec des photos et des caresses.");
        put(DESCRIPTIONS, "Ouvrir les cadeaux rapportés par le copain", "Ouvre les cadeaux rapportés par ton copain.");
        put(DESCRIPTIONS, "Afficher l'overlay EP", "Affiche l'overlay de l'humeur du copain.");
        put(DESCRIPTIONS, "Priorité de nourrissage", "Choisis l'ordre des baies du moteur copain.");
        put(DESCRIPTIONS, "Réserve par baie", "Garde ce nombre de chaque baie avant de nourrir.");
        put(DESCRIPTIONS, "Shundo Sniper (expérimental)", "Se téléporte vers les apparitions à la recherche d'un shundo.");
        put(DESCRIPTIONS, "Shiny Scanner (expérimental)", "Vérifie si les Pokémon sauvages proches sont shiny sans les capturer.");
        put(DESCRIPTIONS, "Session d'Encens du jour", "Marche une session d'Encens de 15 minutes à 10,5 km/h.");
        put(DESCRIPTIONS, "Études", "Réclame, supprime et capture les Études de terrain automatiquement.");
        put(DESCRIPTIONS, "Halloween 2026", "Réglages de l'événement Halloween 2026.");
        put(DESCRIPTIONS, "Go Wild November 2026", "Réglages de l'événement GO Wild Area 2026.");
        put(DESCRIPTIONS, "Thème sombre", "Bascule toute l'interface entre thème sombre et clair.");
        put(DESCRIPTIONS, "Taille de l'interface", "Redimensionne le joystick et les overlays de la carte.");
        put(DESCRIPTIONS, "Passer les cinématiques", "Passe les cinématiques répétitives et les animations d'éclosion.");
        put(DESCRIPTIONS, "Résolution réduite", "Affiche la carte et les Pokémon en demi-résolution.");
        put(DESCRIPTIONS, "Limiter à 20 FPS", "Limite le jeu à 20 images par seconde.");
        put(DESCRIPTIONS, "Graphismes bas", "Désactive les ombres, le brouillard, les reflets et autres effets lourds.");
        put(DESCRIPTIONS, "Exporter / importer les réglages", "Sauvegarde ou restaure tous les réglages.");
        put(DESCRIPTIONS, "Journal / export", "Ouvre le log de la session et l'exporte en texte.");
        put(DESCRIPTIONS, "Diagnostics", "Diagnostics caméra et capture/spin.");
        put(DESCRIPTIONS, "Garder légendaires / fabuleux / Ultra-Chimères", "Ne transfère jamais les Pokémon légendaires, fabuleux ou Ultra-Chimères.");
        put(DESCRIPTIONS, "Garder les Pokémon shiny", "Ne transfère jamais les Pokémon shiny.");
        put(DESCRIPTIONS, "Garder les Pokémon d'événement", "Ne transfère jamais les Pokémon capturés pendant un événement.");
        put(DESCRIPTIONS, "Garder les Pokémon avec fond spécial", "Ne transfère jamais les Pokémon avec un fond spécial.");
        put(DESCRIPTIONS, "Garder les XXS", "Ne transfère jamais les Pokémon XXS.");
        put(DESCRIPTIONS, "Garder les XXL", "Ne transfère jamais les Pokémon XXL.");
        put(DESCRIPTIONS, "Garder les 0 % IV (0/0/0)", "Ne transfère jamais un Pokémon 0 % IV.");
        put(DESCRIPTIONS, "Garder les Pokémon costumés", "Ne transfère jamais les Pokémon costumés.");
        put(SYNONYMS, "Spoof de position", "fake gps mock location teleport faux gps fausse position téléportation".trim());
        put(SYNONYMS, "Coordonnées & vitesse", "lat lon latitude longitude kmh walking speed vitesse marche".trim());
        put(SYNONYMS, "Carte du monde", "map place search pin route carte lieu recherche épingle route".trim());
        put(SYNONYMS, "Ball auto", "ball pokeball great ultra catch rate pokeball super hyper taux capture".trim());
        put(SYNONYMS, "Lancer auto", "throw catch automatic lancer capture automatique".trim());
        put(SYNONYMS, "Transférer sous 90 % d'IV", "transfer iv appraise candy transfert iv bonbons".trim());
        put(SYNONYMS, "Liste perso à garder", "keep species list transfer garder espèces liste transfert".trim());
        put(SYNONYMS, "Garder les IV PvP Super Ligue", "pvp rank great league ".trim());
        put(SYNONYMS, "Garder les IV PvP Hyper Ligue", "pvp rank ultra league ".trim());
        put(SYNONYMS, "Garder les IV PvP Master Ligue", "pvp rank master league ".trim());
        put(SYNONYMS, "IV affichés en rencontre", "iv overlay catch screen stats ".trim());
        put(SYNONYMS, "IV affichés dans les stats", "iv overlay stats screen ".trim());
        put(SYNONYMS, "IV affichés dans le stockage", "iv overlay storage list ".trim());
        put(SYNONYMS, "Overlay VGP", "go plus virtual vgp button go plus virtuel bouton".trim());
        put(SYNONYMS, "Spin rapide", "pokestop spin stop pokestop tourner".trim());
        put(SYNONYMS, "Soin intelligent", "heal potion revive soin potion rappel".trim());
        put(SYNONYMS, "Incubation auto", "egg incubator hatch oeuf incubateur éclosion".trim());
        put(SYNONYMS, "Thème sombre", "light mode appearance mode clair sombre apparence".trim());
        put(SYNONYMS, "Taille de l'interface", "size resize scale overlays taille redimensionner".trim());
        put(SYNONYMS, "Limiter à 20 FPS", "fps frames battery performance images batterie performances".trim());
        put(SYNONYMS, "Résolution réduite", "resolution battery performance graphics résolution batterie performances graphismes".trim());
        put(SYNONYMS, "Graphismes bas", "graphics quality battery performance shadows graphismes qualité batterie ombres".trim());
        put(SYNONYMS, "Exporter / importer les réglages", "backup restore settings sauvegarde restaurer réglages".trim());
        put(SYNONYMS, "Journal / export", "log txt diagnostics export journal diagnostics export".trim());
        put(SYNONYMS, "Shiny Scanner (expérimental)", "shiny scan scanner notification ".trim());
        put(SYNONYMS, "Shundo Sniper (expérimental)", "shundo sniper teleport shiny ".trim());
    }
}
