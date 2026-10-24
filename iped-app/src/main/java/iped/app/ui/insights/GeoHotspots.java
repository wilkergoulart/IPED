package iped.app.ui.insights;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TimeZone;

/**
 * Finds the areas with the highest concentration of geolocated records. Each
 * area is a circle with a fixed radius centered on one of the records.
 *
 * Records are grouped greedily: the record with most other records within the
 * radius becomes the center of the first area, its records are removed, and so
 * on.
 */
public class GeoHotspots {

    private static final double EARTH_RADIUS = 6371008.8;

    /** coordinates are rounded to 5 decimals (about 1 meter) to group records */
    private static final double ROUNDING = 1e5;

    public static class Hotspot {
        private final double latitude;
        private final double longitude;
        private int records;
        private final Set<String> items = new HashSet<>();
        private final Map<String, Integer> types = new HashMap<>();
        private Date first;
        private Date last;

        Hotspot(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public double getLatitude() {
            return latitude;
        }

        public double getLongitude() {
            return longitude;
        }

        /**
         * @return number of geolocated records (an item may have many locations)
         */
        public int getRecords() {
            return records;
        }

        public int getItems() {
            return items.size();
        }

        /**
         * @return number of records of each type, most frequent first
         */
        public Map<String, Integer> getTypes() {
            Map<String, Integer> sorted = new LinkedHashMap<>();
            types.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer> comparingByValue().reversed()
                            .thenComparing(Map.Entry.comparingByKey()))
                    .forEach(e -> sorted.put(e.getKey(), e.getValue()));
            return sorted;
        }

        public Date getFirst() {
            return first;
        }

        public Date getLast() {
            return last;
        }
    }

    private static class Record {
        final String item;
        final String type;
        final Date date;

        Record(String item, String type, Date date) {
            this.item = item;
            this.type = type;
            this.date = date;
        }
    }

    /** records with the same rounded coordinates */
    private static class Site {
        final double lat;
        final double lon;
        final List<Record> records = new ArrayList<>();
        boolean assigned;
        int neighbors;

        Site(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }
    }

    private final double radius;
    private final Map<String, Site> sites = new HashMap<>();
    private final Set<String> items = new HashSet<>();
    private int totalRecords;

    /**
     * @param radius
     *            radius of the areas, in meters
     */
    public GeoHotspots(double radius) {
        this.radius = radius;
    }

    public double getRadius() {
        return radius;
    }

    /**
     * Adds a geolocated record.
     *
     * @param latitude
     *            latitude in decimal degrees
     * @param longitude
     *            longitude in decimal degrees
     * @param item
     *            id of the item which has this location
     * @param type
     *            type of the item, to be shown in the report
     * @param date
     *            date of the record, may be null
     * @return false if the coordinates are invalid
     */
    public boolean add(double latitude, double longitude, String item, String type, Date date) {
        if (Double.isNaN(latitude) || Double.isNaN(longitude) || latitude < -90 || latitude > 90 || longitude < -180
                || longitude > 180) {
            return false;
        }
        double lat = Math.round(latitude * ROUNDING) / ROUNDING;
        double lon = Math.round(longitude * ROUNDING) / ROUNDING;
        sites.computeIfAbsent(lat + ";" + lon, k -> new Site(lat, lon)).records.add(new Record(item, type, date));
        items.add(item);
        totalRecords++;
        return true;
    }

    /**
     * Parses a location as stored by IPED ("latitude;longitude") and adds it.
     */
    public boolean add(String location, String item, String type, Date date) {
        if (location == null) {
            return false;
        }
        String[] parts = location.split(";");
        if (parts.length != 2) {
            return false;
        }
        try {
            return add(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()), item, type, date);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public int getTotalRecords() {
        return totalRecords;
    }

    public int getTotalItems() {
        return items.size();
    }

    /**
     * Distance in meters between two points (haversine).
     */
    static double distance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /**
     * Grid of cells with side of about the radius, to find nearby sites.
     */
    private class Grid {
        final double cellDegrees = Math.toDegrees(radius / EARTH_RADIUS);
        final Map<Long, List<Site>> cells = new HashMap<>();

        Grid(Iterable<Site> all) {
            for (Site s : all) {
                cells.computeIfAbsent(key(cell(s.lat), cell(s.lon)), k -> new ArrayList<>()).add(s);
            }
        }

        long cell(double degrees) {
            return (long) Math.floor(degrees / cellDegrees);
        }

        long key(long latCell, long lonCell) {
            return latCell * 4_000_003L + lonCell;
        }

        /** unassigned sites within the radius, including the site itself */
        List<Site> neighbors(Site s) {
            List<Site> result = new ArrayList<>();
            long latCell = cell(s.lat);
            long lonCell = cell(s.lon);
            // a longitude degree gets shorter far from the equator
            double cos = Math.max(0.01, Math.cos(Math.toRadians(Math.min(89.9, Math.abs(s.lat) + cellDegrees))));
            long lonRange = (long) Math.ceil(1 / cos);
            // for short distances, an equirectangular projection is accurate enough
            double metersPerDegree = Math.toRadians(EARTH_RADIUS);
            double metersPerLonDegree = metersPerDegree * Math.cos(Math.toRadians(s.lat));
            double radius2 = radius * radius;
            for (long i = latCell - 1; i <= latCell + 1; i++) {
                for (long j = lonCell - lonRange; j <= lonCell + lonRange; j++) {
                    List<Site> cell = cells.get(key(i, j));
                    if (cell == null) {
                        continue;
                    }
                    for (Site o : cell) {
                        double dy = (o.lat - s.lat) * metersPerDegree;
                        double dx = (o.lon - s.lon) * metersPerLonDegree;
                        if (!o.assigned && dx * dx + dy * dy <= radius2) {
                            result.add(o);
                        }
                    }
                }
            }
            return result;
        }
    }

    private static int countRecords(List<Site> sites) {
        int count = 0;
        for (Site s : sites) {
            count += s.records.size();
        }
        return count;
    }

    /**
     * @param max
     *            maximum number of areas
     * @param minRecords
     *            minimum number of records of an area
     * @return the areas with more records, the largest first
     */
    public List<Hotspot> find(int max, int minRecords) {
        for (Site s : sites.values()) {
            s.assigned = false;
        }
        Grid grid = new Grid(sites.values());
        PriorityQueue<Site> queue = new PriorityQueue<>(
                Comparator.comparingInt((Site s) -> s.neighbors).reversed().thenComparingDouble(s -> s.lat)
                        .thenComparingDouble(s -> s.lon));
        for (Site s : sites.values()) {
            s.neighbors = countRecords(grid.neighbors(s));
            queue.add(s);
        }

        List<Hotspot> result = new ArrayList<>();
        while (!queue.isEmpty() && result.size() < max) {
            Site center = queue.poll();
            if (center.assigned) {
                continue;
            }
            List<Site> members = grid.neighbors(center);
            int count = countRecords(members);
            if (count < center.neighbors) {
                // some neighbors were taken by a previous area, check again later
                center.neighbors = count;
                queue.add(center);
                continue;
            }
            if (count < minRecords) {
                break;
            }
            Hotspot h = new Hotspot(center.lat, center.lon);
            for (Site s : members) {
                s.assigned = true;
                for (Record r : s.records) {
                    h.records++;
                    h.items.add(r.item);
                    h.types.merge(r.type != null ? r.type : "", 1, Integer::sum);
                    if (r.date != null) {
                        if (h.first == null || r.date.before(h.first)) {
                            h.first = r.date;
                        }
                        if (h.last == null || r.date.after(h.last)) {
                            h.last = r.date;
                        }
                    }
                }
            }
            result.add(h);
        }
        return result;
    }

    /**
     * Renders the areas as an HTML fragment.
     *
     * @param text
     *            returns the localized texts
     */
    public String toHtml(List<Hotspot> hotspots, int max, Localizer text) {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>").append(WhatsAppInsights.escape(text.get("AnalystInsights.GeoTitle"))).append("</h2>\n");
        if (totalRecords == 0) {
            sb.append("<p>").append(WhatsAppInsights.escape(text.get("AnalystInsights.GeoNone"))).append("</p>\n");
            return sb.toString();
        }
        sb.append("<p class=\"help\">")
                .append(WhatsAppInsights.escape(text.get("AnalystInsights.GeoHelp", (int) radius, totalRecords,
                        items.size(), max)))
                .append("</p>\n");
        if (hotspots.isEmpty()) {
            sb.append("<p>").append(WhatsAppInsights.escape(text.get("AnalystInsights.GeoNoHotspot")))
                    .append("</p>\n");
            return sb.toString();
        }
        sb.append("<table>\n<tr><th>#</th><th>").append(WhatsAppInsights.escape(text.get("AnalystInsights.GeoCenter")))
                .append("</th><th>").append(WhatsAppInsights.escape(text.get("AnalystInsights.GeoRecords")))
                .append("</th><th>").append(WhatsAppInsights.escape(text.get("AnalystInsights.GeoItems")))
                .append("</th><th>").append(WhatsAppInsights.escape(text.get("AnalystInsights.GeoTypes")))
                .append("</th><th>").append(WhatsAppInsights.escape(text.get("AnalystInsights.GeoFirst")))
                .append("</th><th>").append(WhatsAppInsights.escape(text.get("AnalystInsights.GeoLast")))
                .append("</th></tr>\n");
        int n = 1;
        for (Hotspot h : hotspots) {
            String coords = String.format(Locale.ROOT, "%.5f, %.5f", h.getLatitude(), h.getLongitude());
            String url = String.format(Locale.ROOT, "https://www.openstreetmap.org/?mlat=%.5f&mlon=%.5f#map=19/%.5f/%.5f",
                    h.getLatitude(), h.getLongitude(), h.getLatitude(), h.getLongitude());
            StringBuilder types = new StringBuilder();
            for (Map.Entry<String, Integer> e : h.getTypes().entrySet()) {
                if (types.length() > 0) {
                    types.append("<br>");
                }
                String type = e.getKey().isEmpty() ? "-" : e.getKey();
                types.append(WhatsAppInsights.escape(type)).append(": ").append(e.getValue());
            }
            sb.append("<tr><td class=\"num\">").append(n++).append("</td><td class=\"nowrap\">").append(coords)
                    .append(" <a href=\"").append(url).append("\">")
                    .append(WhatsAppInsights.escape(text.get("AnalystInsights.GeoMap"))).append("</a></td>")
                    .append("<td class=\"num\">").append(h.getRecords()).append("</td><td class=\"num\">")
                    .append(h.getItems()).append("</td><td>").append(types).append("</td><td class=\"nowrap\">")
                    .append(h.getFirst() != null ? df.format(h.getFirst()) : "-").append("</td><td class=\"nowrap\">")
                    .append(h.getLast() != null ? df.format(h.getLast()) : "-").append("</td></tr>\n");
        }
        sb.append("</table>\n");
        return sb.toString();
    }
}
