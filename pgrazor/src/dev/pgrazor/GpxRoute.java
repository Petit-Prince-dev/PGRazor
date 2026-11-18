package dev.pgrazor;

/**
 * An immutable, validated walking route: an ordered list of [latitude, longitude] points.
 *
 * <p>This type is plain Java with no Android dependencies so it can be exercised off-device.
 * The constructor validates defensively and copies its input, and {@link #points()} hands back a
 * deep copy, so a {@code GpxRoute} never aliases a caller's array and cannot be mutated after
 * construction. Point order is preserved exactly as supplied.
 */
public final class GpxRoute {
    /** Longest accepted route name, in characters, after trimming. */
    public static final int NAME_LIMIT = 80;

    /** Display name for the route; never null, trimmed, non-empty, free of control characters. */
    public final String name;

    // Each row is a fresh {lat, lon} pair owned solely by this instance.
    private final double[][] points;

    public GpxRoute(String name, double[][] points) {
        if (name == null) throw new IllegalArgumentException("Une route doit avoir un nom.");
        name = name.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Entre un nom pour cette route.");
        if (name.length() > NAME_LIMIT) throw new IllegalArgumentException("Les noms de route peuvent contenir au plus " + NAME_LIMIT + " caractères.");
        for (int i = 0; i < name.length(); i++)
            if (Character.isISOControl(name.charAt(i))) throw new IllegalArgumentException("Utilise un nom sans retour à la ligne ni caractère de contrôle.");
        if (points == null) throw new IllegalArgumentException("Une route doit avoir des points.");
        if (points.length == 0) throw new IllegalArgumentException("Une route doit avoir au moins un point.");
        if (points.length > GpxCodec.MAX_POINTS) throw new IllegalArgumentException("Les routes peuvent contenir au plus " + GpxCodec.MAX_POINTS + " points.");
        double[][] copy = new double[points.length][];
        for (int i = 0; i < points.length; i++) {
            double[] point = points[i];
            if (point == null || point.length != 2) throw new IllegalArgumentException("Chaque point doit être une paire [lat, lon].");
            double lat = point[0], lon = point[1];
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180)
                throw new IllegalArgumentException("Le point " + (i + 1) + " est hors de la plage de coordonnées valide.");
            copy[i] = new double[]{lat, lon};
        }
        this.name = name;
        this.points = copy;
    }

    /** Number of points in the route. */
    public int size() { return points.length; }

    /** A deep copy of the ordered [lat, lon] points; mutating the result never affects this route. */
    public double[][] points() {
        double[][] copy = new double[points.length][];
        for (int i = 0; i < points.length; i++) copy[i] = new double[]{points[i][0], points[i][1]};
        return copy;
    }
}
