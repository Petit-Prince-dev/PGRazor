package dev.pgrazor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Curated locations supplied by the user. UTC labels are supplied offsets, not live clocks. */
public final class Hotspots {
    private Hotspots() {}
    public static final class Spot {
        public final String utc, country, city, name;
        public final double latitude, longitude;
        Spot(String utc,String country,String city,String name,double latitude,double longitude) {
            this.utc=utc;this.country=country;this.city=city;this.name=name;
            this.latitude=latitude;this.longitude=longitude;
        }
    }
    public static final List<Spot> ALL=Collections.unmodifiableList(Arrays.asList(
        new Spot("UTC -10","Hawaï","Honolulu","Moana Mall",21.291100,-157.843600),
        new Spot("UTC -7","USA","San Francisco","Pier 39",37.808600,-122.409800),
        new Spot("UTC -6","Mexique","Mexico City","Alameda Central",19.43585,-99.14434),
        new Spot("UTC -5","USA","Chicago","Lincoln Zoo Park",41.918776,-87.635437),
        new Spot("UTC -4","USA","New York","Bryant Park",40.753751,-73.983537),
        new Spot("UTC -3","Brésil","São Paulo","Ibirapuera Park",-23.5864600,-46.6581793),
        new Spot("UTC -2","Groenland","Nuuk","Centre-ville",64.175642,-51.737517),
        new Spot("UTC +0","UK","Londres","Trafalgar Square",51.5080,-0.1281),
        new Spot("UTC +1","Espagne","Zaragoza","Plaza de Europa",41.659268,-0.892103),
        new Spot("UTC +3","Türkiye","Bornova","Buyuk Park",38.462677,27.217599),
        new Spot("UTC +4","UAE","Dubai","Jumeirah Beach",25.074792,55.130958),
        new Spot("UTC +8","Taiwan","Taipei","Gare centrale",25.0480,121.5168),
        new Spot("UTC +9","Japon","Tokyo","Ikebukuro Station",35.729503,139.710900),
        new Spot("UTC +13","Nouvelle-Zélande","Wellington","Botanic Garden",-41.282348,174.767127)
    ));
}
