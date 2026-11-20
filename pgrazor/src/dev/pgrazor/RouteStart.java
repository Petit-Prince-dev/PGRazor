package dev.pgrazor;

/** Pure route helpers: great-circle distances and their short text. */
final class RouteStart {
    private RouteStart() {}
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    /** Great-circle (haversine) distance in meters. */
    static double meters(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /** "85 m" or "1.2 km". */
    static String distance(double meters) {
        if (meters < 1000) return Math.round(meters) + " m";
        return String.format(java.util.Locale.US, "%.1f km", meters / 1000);
    }
}
