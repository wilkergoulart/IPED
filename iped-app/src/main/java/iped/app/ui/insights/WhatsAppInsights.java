package iped.app.ui.insights;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Aggregates WhatsApp chats and messages of a case and renders the WhatsApp
 * section of the analyst insights report. Dates are handled in UTC.
 */
public class WhatsAppInsights {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    public static class ChatStats {
        private final String name;
        private final String database;
        private Integer messageCounter;
        private Integer storedMessages;

        ChatStats(String name, String database) {
            this.name = name;
            this.database = database;
        }

        public String getName() {
            return name;
        }

        public String getDatabase() {
            return database;
        }

        public Integer getMessageCounter() {
            return messageCounter;
        }

        public Integer getStoredMessages() {
            return storedMessages;
        }

        /**
         * @return the counter minus the stored messages, or null if any of them is
         *         unknown
         */
        public Integer getDifference() {
            if (messageCounter == null || storedMessages == null) {
                return null;
            }
            return messageCounter - storedMessages;
        }
    }

    public static class ParticipantStats {
        private final String name;
        private int messages;
        private int deletedMessages;
        private Date first;
        private Date last;

        ParticipantStats(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public int getMessages() {
            return messages;
        }

        public int getDeletedMessages() {
            return deletedMessages;
        }

        public Date getFirst() {
            return first;
        }

        public Date getLast() {
            return last;
        }
    }

    private final Map<String, ChatStats> chats = new LinkedHashMap<>();
    private final Map<String, ParticipantStats> participants = new LinkedHashMap<>();
    private final TreeMap<Integer, Integer> messagesPerYear = new TreeMap<>();
    private int totalMessages;
    private int deletedMessages;
    private Date first;
    private Date last;

    /**
     * Adds a chat (or one of its fragments, as long chats are split in many
     * items). Fragments of the same chat must share the same key.
     */
    public void addChat(String key, String name, String database, Integer messageCounter, Integer storedMessages) {
        ChatStats chat = chats.computeIfAbsent(key, k -> new ChatStats(name, database));
        if (chat.messageCounter == null) {
            chat.messageCounter = messageCounter;
        }
        if (chat.storedMessages == null) {
            chat.storedMessages = storedMessages;
        }
    }

    /**
     * Adds a message.
     *
     * @param sender
     *            message sender, null for system messages
     * @param date
     *            message date, may be null
     * @param deleted
     *            if the message was recovered from deleted records
     */
    public void addMessage(String sender, Date date, boolean deleted) {
        totalMessages++;
        ParticipantStats p = participants.computeIfAbsent(sender, ParticipantStats::new);
        p.messages++;
        if (deleted) {
            deletedMessages++;
            p.deletedMessages++;
        }
        if (date != null) {
            if (first == null || date.before(first)) {
                first = date;
            }
            if (last == null || date.after(last)) {
                last = date;
            }
            if (p.first == null || date.before(p.first)) {
                p.first = date;
            }
            if (p.last == null || date.after(p.last)) {
                p.last = date;
            }
            Calendar cal = Calendar.getInstance(UTC);
            cal.setTime(date);
            messagesPerYear.merge(cal.get(Calendar.YEAR), 1, Integer::sum);
        }
    }

    public int getTotalChats() {
        return chats.size();
    }

    public int getTotalMessages() {
        return totalMessages;
    }

    public int getDeletedMessages() {
        return deletedMessages;
    }

    public Date getFirstMessageDate() {
        return first;
    }

    public Date getLastMessageDate() {
        return last;
    }

    public Map<Integer, Integer> getMessagesPerYear() {
        return Collections.unmodifiableMap(messagesPerYear);
    }

    /**
     * @return the year with most messages (the earliest one if tied), or null if
     *         no message has a date
     */
    public Integer getPeakYear() {
        Integer peak = null;
        for (Map.Entry<Integer, Integer> e : messagesPerYear.entrySet()) {
            if (peak == null || e.getValue() > messagesPerYear.get(peak)) {
                peak = e.getKey();
            }
        }
        return peak;
    }

    /**
     * @return chats with a known message counter, larger differences first
     */
    public List<ChatStats> getChatsWithCounter() {
        List<ChatStats> list = new ArrayList<>();
        for (ChatStats c : chats.values()) {
            if (c.getDifference() != null) {
                list.add(c);
            }
        }
        list.sort(Comparator.comparing(ChatStats::getDifference).reversed()
                .thenComparing(ChatStats::getName, Comparator.nullsLast(Comparator.naturalOrder())));
        return list;
    }

    /**
     * @return participants sorted by number of sent messages, larger first
     */
    public List<ParticipantStats> getParticipants() {
        List<ParticipantStats> list = new ArrayList<>(participants.values());
        list.sort(Comparator.comparingInt(ParticipantStats::getMessages).reversed()
                .thenComparing(ParticipantStats::getName, Comparator.nullsFirst(Comparator.naturalOrder())));
        return list;
    }

    /**
     * Renders the WhatsApp section as an HTML fragment.
     *
     * @param text
     *            returns the localized text of a key
     */
    public String toHtml(Function<String, String> text) {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        df.setTimeZone(UTC);

        StringBuilder sb = new StringBuilder();
        sb.append("<h2>WhatsApp</h2>\n");
        if (chats.isEmpty() && totalMessages == 0) {
            sb.append("<p>").append(escape(text.apply("AnalystInsights.NoWhatsApp"))).append("</p>\n");
            return sb.toString();
        }

        sb.append("<h3>").append(escape(text.apply("AnalystInsights.Summary"))).append("</h3>\n");
        sb.append("<table class=\"summary\">\n");
        row(sb, text.apply("AnalystInsights.Chats"), String.valueOf(getTotalChats()));
        row(sb, text.apply("AnalystInsights.Messages"), String.valueOf(totalMessages));
        row(sb, text.apply("AnalystInsights.DeletedMessages"), String.valueOf(deletedMessages));
        row(sb, text.apply("AnalystInsights.FirstMessage"), first != null ? df.format(first) : "-");
        row(sb, text.apply("AnalystInsights.LastMessage"), last != null ? df.format(last) : "-");
        Integer peak = getPeakYear();
        row(sb, text.apply("AnalystInsights.PeakYear"),
                peak != null ? peak + " (" + messagesPerYear.get(peak) + ")" : "-");
        sb.append("</table>\n");

        if (!messagesPerYear.isEmpty()) {
            sb.append("<h3>").append(escape(text.apply("AnalystInsights.MessagesPerYear"))).append("</h3>\n");
            sb.append("<table>\n<tr><th>").append(escape(text.apply("AnalystInsights.Year"))).append("</th><th>")
                    .append(escape(text.apply("AnalystInsights.Messages"))).append("</th><th>")
                    .append(escape(text.apply("AnalystInsights.Share"))).append("</th></tr>\n");
            int max = messagesPerYear.get(peak);
            int dated = messagesPerYear.values().stream().mapToInt(Integer::intValue).sum();
            for (Map.Entry<Integer, Integer> e : messagesPerYear.entrySet()) {
                // bar up to 200px
                int width = (int) Math.round(100.0 * e.getValue() / max);
                String share = String.format("%.1f%%", 100.0 * e.getValue() / dated);
                sb.append("<tr").append(e.getKey().equals(peak) ? " class=\"peak\"" : "").append("><td>")
                        .append(e.getKey()).append("</td><td class=\"num\">").append(e.getValue())
                        .append("</td><td class=\"nowrap\"><div class=\"bar\" style=\"width:").append(2 * width)
                        .append("px\"></div> ")
                        .append(share).append("</td></tr>\n");
            }
            sb.append("</table>\n");
        }

        sb.append("<h3>").append(escape(text.apply("AnalystInsights.CounterTitle"))).append("</h3>\n");
        List<ChatStats> withCounter = getChatsWithCounter();
        if (withCounter.isEmpty()) {
            sb.append("<p>").append(escape(text.apply("AnalystInsights.CounterNotAvailable"))).append("</p>\n");
        } else {
            sb.append("<p class=\"help\">").append(escape(text.apply("AnalystInsights.CounterHelp"))).append("</p>\n");
            sb.append("<table>\n<tr><th>").append(escape(text.apply("AnalystInsights.Chat"))).append("</th><th>")
                    .append(escape(text.apply("AnalystInsights.Counter"))).append("</th><th>")
                    .append(escape(text.apply("AnalystInsights.Stored"))).append("</th><th>")
                    .append(escape(text.apply("AnalystInsights.Difference"))).append("</th><th>")
                    .append(escape(text.apply("AnalystInsights.Database"))).append("</th></tr>\n");
            for (ChatStats c : withCounter) {
                sb.append("<tr").append(c.getDifference() > 0 ? " class=\"diff\"" : "").append("><td>")
                        .append(escape(c.getName())).append("</td><td class=\"num\">").append(c.getMessageCounter())
                        .append("</td><td class=\"num\">").append(c.getStoredMessages())
                        .append("</td><td class=\"num\">").append(c.getDifference()).append("</td><td>")
                        .append(escape(c.getDatabase())).append("</td></tr>\n");
            }
            sb.append("</table>\n");
        }

        sb.append("<h3>").append(escape(text.apply("AnalystInsights.SendersTitle"))).append("</h3>\n");
        sb.append("<table>\n<tr><th>").append(escape(text.apply("AnalystInsights.Participant"))).append("</th><th>")
                .append(escape(text.apply("AnalystInsights.Sent"))).append("</th><th>")
                .append(escape(text.apply("AnalystInsights.Deleted"))).append("</th><th>")
                .append(escape(text.apply("AnalystInsights.FirstMessage"))).append("</th><th>")
                .append(escape(text.apply("AnalystInsights.LastMessage"))).append("</th></tr>\n");
        for (ParticipantStats p : getParticipants()) {
            String name = p.getName() != null ? p.getName() : text.apply("AnalystInsights.UnknownSender");
            sb.append("<tr><td>").append(escape(name)).append("</td><td class=\"num\">").append(p.getMessages())
                    .append("</td><td class=\"num\">").append(p.getDeletedMessages()).append("</td><td class=\"nowrap\">")
                    .append(p.getFirst() != null ? df.format(p.getFirst()) : "-").append("</td><td class=\"nowrap\">")
                    .append(p.getLast() != null ? df.format(p.getLast()) : "-").append("</td></tr>\n");
        }
        sb.append("</table>\n");

        return sb.toString();
    }

    private static void row(StringBuilder sb, String label, String value) {
        sb.append("<tr><th>").append(escape(label)).append("</th><td>").append(escape(value)).append("</td></tr>\n");
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '&':
                    sb.append("&amp;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&#39;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
