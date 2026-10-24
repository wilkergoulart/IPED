package iped.app.ui.insights;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.Test;

import iped.parsers.whatsapp.MessageSequenceGap;

import iped.app.ui.insights.WhatsAppInsights.ChatGap;
import iped.app.ui.insights.WhatsAppInsights.ChatStats;
import iped.app.ui.insights.WhatsAppInsights.ParticipantStats;

public class WhatsAppInsightsTest {

    private static Date date(String iso) {
        return Date.from(Instant.parse(iso));
    }

    private WhatsAppInsights sample() {
        WhatsAppInsights insights = new WhatsAppInsights();
        // chat split in two fragments, both carrying the same counters
        insights.addChat("0/10/1", "WhatsApp Chat - Ana", "/iPhone/ChatStorage.sqlite", 10, 4, null);
        insights.addChat("0/10/1", "WhatsApp Chat - Ana", "/iPhone/ChatStorage.sqlite", 10, 4, null);
        insights.addChat("0/10/2", "WhatsApp Chat - Bruno", "/iPhone/ChatStorage.sqlite", 3, 3,
                List.of(new MessageSequenceGap(5, 5, 0, date("2020-01-01T10:00:00Z"), date("2020-01-01T10:05:00Z")),
                        new MessageSequenceGap(20, 29, 3, date("2021-06-01T08:00:00Z"), null)));
        // Android chat, without counter
        insights.addChat("0/20/1", "WhatsApp Chat - Carla", "/Android/msgstore.db", null, null, null);

        insights.addMessage("Ana", date("2021-12-31T23:59:59Z"), false);
        insights.addMessage("Ana", date("2022-03-01T10:00:00Z"), true);
        insights.addMessage("Ana", date("2022-04-01T10:00:00Z"), false);
        insights.addMessage("Bruno", date("2019-01-01T00:00:00Z"), false);
        insights.addMessage(null, null, false);
        return insights;
    }

    @Test
    public void testSummary() {
        WhatsAppInsights insights = sample();
        assertEquals(3, insights.getTotalChats());
        assertEquals(5, insights.getTotalMessages());
        assertEquals(1, insights.getDeletedMessages());
        assertEquals(date("2019-01-01T00:00:00Z"), insights.getFirstMessageDate());
        assertEquals(date("2022-04-01T10:00:00Z"), insights.getLastMessageDate());
        assertEquals(Integer.valueOf(2022), insights.getPeakYear());
        assertEquals(Integer.valueOf(1), insights.getMessagesPerYear().get(2019));
        assertEquals(Integer.valueOf(1), insights.getMessagesPerYear().get(2021));
        assertEquals(Integer.valueOf(2), insights.getMessagesPerYear().get(2022));
    }

    @Test
    public void testChatsWithCounterSortedByDifference() {
        List<ChatStats> chats = sample().getChatsWithCounter();
        assertEquals(2, chats.size());
        assertEquals("WhatsApp Chat - Ana", chats.get(0).getName());
        assertEquals(Integer.valueOf(6), chats.get(0).getDifference());
        assertEquals("WhatsApp Chat - Bruno", chats.get(1).getName());
        assertEquals(Integer.valueOf(0), chats.get(1).getDifference());
    }

    @Test
    public void testParticipants() {
        List<ParticipantStats> participants = sample().getParticipants();
        assertEquals(3, participants.size());
        ParticipantStats ana = participants.get(0);
        assertEquals("Ana", ana.getName());
        assertEquals(3, ana.getMessages());
        assertEquals(1, ana.getDeletedMessages());
        assertEquals(date("2021-12-31T23:59:59Z"), ana.getFirst());
        assertEquals(date("2022-04-01T10:00:00Z"), ana.getLast());
        assertNull(participants.get(1).getName());
        assertEquals("Bruno", participants.get(2).getName());
    }

    @Test
    public void testPeakYearTieUsesEarliestYear() {
        WhatsAppInsights insights = new WhatsAppInsights();
        insights.addMessage("Ana", date("2020-05-01T00:00:00Z"), false);
        insights.addMessage("Ana", date("2018-05-01T00:00:00Z"), false);
        assertEquals(Integer.valueOf(2018), insights.getPeakYear());
    }

    @Test
    public void testHtml() {
        WhatsAppInsights insights = sample();
        insights.addMessage("<b>Eve</b>", date("2022-01-01T00:00:00Z"), false);
        String html = insights.toHtml((key, args) -> "[" + key + "]");
        assertTrue(html.contains("[AnalystInsights.CounterTitle]"));
        assertTrue(html.contains("<td>WhatsApp Chat - Ana</td><td class=\"num\">10</td><td class=\"num\">4</td>"
                + "<td class=\"num\">6</td>"));
        assertTrue(html.contains("2019-01-01 00:00:00"));
        assertTrue(html.contains("[AnalystInsights.UnknownSender]"));
        assertTrue(html.contains("&lt;b&gt;Eve&lt;/b&gt;"));
        assertFalse(html.contains("<b>Eve</b>"));
        assertFalse(html.contains("[AnalystInsights.CounterNotAvailable]"));
    }

    @Test
    public void testHtmlWithoutData() {
        String html = new WhatsAppInsights().toHtml((key, args) -> "[" + key + "]");
        assertTrue(html.contains("[AnalystInsights.NoWhatsApp]"));
    }

    @Test
    public void testHtmlWithoutCounter() {
        WhatsAppInsights insights = new WhatsAppInsights();
        insights.addChat("0/20/1", "WhatsApp Chat - Carla", "/Android/msgstore.db", null, null, null);
        insights.addMessage("Carla", date("2023-01-01T00:00:00Z"), false);
        String html = insights.toHtml((key, args) -> "[" + key + "]");
        assertTrue(html.contains("[AnalystInsights.CounterNotAvailable]"));
    }

    @Test
    public void testSequenceGaps() {
        List<ChatGap> gaps = sample().getSequenceGaps();
        assertEquals(2, gaps.size());
        assertEquals(10, gaps.get(0).getGap().getMissing());
        assertEquals("WhatsApp Chat - Bruno", gaps.get(0).getChat().getName());
        assertEquals(1, gaps.get(1).getGap().getMissing());

        String html = sample().toHtml((key, args) -> "[" + key + "]");
        assertTrue(html.contains("[AnalystInsights.GapsTitle]"));
        assertTrue(html.contains("<td class=\"num\">10</td><td class=\"nowrap\">20 - 29</td><td class=\"num\">3</td>"));
        assertTrue(html.contains("2020-01-01 10:05:00"));
        assertFalse(html.contains("[AnalystInsights.GapsNotFound]"));
    }

    @Test
    public void testNoSequenceGaps() {
        WhatsAppInsights insights = new WhatsAppInsights();
        insights.addChat("0/20/1", "WhatsApp Chat - Carla", "/Android/msgstore.db", null, null, null);
        assertTrue(insights.toHtml((key, args) -> "[" + key + "]").contains("[AnalystInsights.GapsNotFound]"));
    }
}
