package dev.pgrazor;

public final class RouteStartTest {
    private static void require(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    public static void main(String[] args) {
        // Roughly 111 m between two points on a meridian.
        double m = RouteStart.meters(37.0, -122.0, 37.001, -122.0);
        require(Math.abs(m - 111.2) < 0.5, "haversine meters: " + m);
        require(RouteStart.meters(37.0, -122.0, 37.0, -122.0) == 0, "same point");

        require(RouteStart.distance(85.4).equals("85 m") && RouteStart.distance(1234).equals("1.2 km"), "distance text");
        System.out.println("RouteStartTest passed");
    }
}
