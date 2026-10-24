package iped.app.ui.insights;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import iped.app.ui.insights.GeoHotspots.Hotspot;

public class GeoHotspotsTest {

    private static final double LAT = -15.79390;
    private static final double LON = -47.88280;

    /** meters per degree of latitude */
    private static final double M_PER_DEG = 6371008.8 * Math.PI / 180;

    private static double north(double lat, double meters) {
        return lat + meters / M_PER_DEG;
    }

    private static double east(double lat, double lon, double meters) {
        return lon + meters / (M_PER_DEG * Math.cos(Math.toRadians(lat)));
    }

    private static Date date(String iso) {
        return Date.from(Instant.parse(iso));
    }

    @Test
    public void testDistance() {
        assertEquals(M_PER_DEG, GeoHotspots.distance(0, 0, 1, 0), 0.01);
        assertEquals(30, GeoHotspots.distance(LAT, LON, north(LAT, 30), LON), 0.01);
        assertEquals(30, GeoHotspots.distance(LAT, LON, LAT, east(LAT, LON, 30)), 0.01);
    }

    @Test
    public void testFindsLargestAreasFirst() {
        GeoHotspots geo = new GeoHotspots(30);
        // area A: 5 records of 4 items within 10 m
        geo.add(LAT, LON, "1", "Images", date("2022-01-01T10:00:00Z"));
        geo.add(north(LAT, 5), LON, "2", "Images", date("2022-03-01T10:00:00Z"));
        geo.add(LAT, east(LAT, LON, 8), "3", "Images", null);
        geo.add(north(LAT, -6), east(LAT, LON, -6), "4", "Chats", date("2021-12-25T10:00:00Z"));
        geo.add(north(LAT, 2), LON, "4", "Chats", date("2021-12-26T10:00:00Z"));
        // area B, 1 km away: 3 records within 20 m
        double latB = north(LAT, 1000);
        geo.add(latB, LON, "5", "Images", null);
        geo.add(north(latB, 15), LON, "6", "Images", null);
        geo.add(north(latB, -15), LON, "7", "Images", null);
        // isolated record
        geo.add(north(LAT, 5000), LON, "8", "Images", null);

        assertEquals(9, geo.getTotalRecords());
        assertEquals(8, geo.getTotalItems());

        List<Hotspot> hotspots = geo.find(20, 2);
        assertEquals(2, hotspots.size());

        Hotspot a = hotspots.get(0);
        assertEquals(5, a.getRecords());
        assertEquals(4, a.getItems());
        assertEquals(date("2021-12-25T10:00:00Z"), a.getFirst());
        assertEquals(date("2022-03-01T10:00:00Z"), a.getLast());
        Map<String, Integer> types = a.getTypes();
        assertEquals(List.of("Images", "Chats"), List.copyOf(types.keySet()));
        assertEquals(Integer.valueOf(3), types.get("Images"));
        assertTrue(GeoHotspots.distance(a.getLatitude(), a.getLongitude(), LAT, LON) <= 30);

        Hotspot b = hotspots.get(1);
        assertEquals(3, b.getRecords());
        assertEquals(latB, b.getLatitude(), 1e-5);

        assertEquals(1, geo.find(1, 2).size());
    }

    @Test
    public void testAreaIsCenteredOnARecord() {
        GeoHotspots geo = new GeoHotspots(30);
        // records at 0, 25 and 50 m: only the middle one reaches the other two
        geo.add(LAT, LON, "1", "Images", null);
        geo.add(north(LAT, 25), LON, "2", "Images", null);
        geo.add(north(LAT, 50), LON, "3", "Images", null);
        List<Hotspot> hotspots = geo.find(20, 2);
        assertEquals(1, hotspots.size());
        assertEquals(3, hotspots.get(0).getRecords());
        assertEquals(north(LAT, 25), hotspots.get(0).getLatitude(), 1e-5);
    }

    @Test
    public void testRecordsFartherThanTheRadius() {
        GeoHotspots geo = new GeoHotspots(30);
        geo.add(LAT, LON, "1", "Images", null);
        geo.add(north(LAT, 35), LON, "2", "Images", null);
        assertTrue(geo.find(20, 2).isEmpty());
    }

    @Test
    public void testHighLatitude() {
        GeoHotspots geo = new GeoHotspots(30);
        double lat = 70;
        geo.add(lat, 20, "1", "Images", null);
        geo.add(lat, east(lat, 20, 28), "2", "Images", null);
        geo.add(lat, east(lat, 20, -28), "3", "Images", null);
        List<Hotspot> hotspots = geo.find(20, 2);
        assertEquals(1, hotspots.size());
        assertEquals(3, hotspots.get(0).getRecords());
    }

    @Test
    public void testParseLocation() {
        GeoHotspots geo = new GeoHotspots(30);
        assertTrue(geo.add("-15.7939;-47.8828", "1", "Images", null));
        assertTrue(geo.add(" -15.7939 ; -47.8828 ", "2", "Images", null));
        assertFalse(geo.add("invalid", "3", "Images", null));
        assertFalse(geo.add("-15.7939", "4", "Images", null));
        assertFalse(geo.add("95;10", "5", "Images", null));
        assertFalse(geo.add(null, "6", "Images", null));
        assertEquals(2, geo.getTotalRecords());
        assertEquals(1, geo.find(20, 2).size());
    }

    @Test
    public void testHtml() {
        GeoHotspots geo = new GeoHotspots(30);
        assertTrue(geo.toHtml(geo.find(20, 2), 20, (key, args) -> "[" + key + "]")
                .contains("[AnalystInsights.GeoNone]"));

        geo.add(LAT, LON, "1", "<Images>", date("2022-01-01T10:00:00Z"));
        geo.add(north(LAT, 5), LON, "2", "<Images>", date("2022-03-01T10:00:00Z"));
        String html = geo.toHtml(geo.find(20, 2), 20, (key, args) -> "[" + key + "]");
        assertTrue(html.contains("-15.79390, -47.88280"));
        assertTrue(html.contains("https://www.openstreetmap.org/?mlat=-15.79390&mlon=-47.88280"));
        assertTrue(html.contains("&lt;Images&gt;: 2"));
        assertTrue(html.contains("2022-01-01 10:00:00"));
        assertTrue(html.contains("2022-03-01 10:00:00"));
    }
}
