package dev.pgrazor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Ready-made GPS walks listed in Destinations, under the hotspots. To add one, append a {@link Preset}
 * to {@link #ALL}: where it is, whether it loops, its name and its ordered [latitude, longitude] stops.
 * The stops are validated like any GPX route (see {@link GpxRoute}).
 */
public final class PresetRoutes {
    private PresetRoutes() {}

    public static final class Preset {
        /** "Ville, pays", shown under the name. */
        public final String place;
        /** Starts again from the first stop after the last one. */
        public final boolean loop;
        public final GpxRoute route;
        Preset(String place, boolean loop, String name, double[][] stops) {
            this.place = place; this.loop = loop; this.route = new GpxRoute(name, stops);
        }
    }

    public static final List<Preset> ALL = Collections.unmodifiableList(Arrays.<Preset>asList(
        // new Preset("Ville, pays", true, "Nom du trajet", new double[][]{{48.858370, 2.294481}, {48.861111, 2.335833}}),
    ));
}
