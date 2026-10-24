package iped.parsers.whatsapp;

import java.time.Instant;
import java.util.Date;

/**
 * A gap in the sequence numbers (ZSORT on iOS) of the messages still stored for
 * a chat, which suggests that messages were deleted between two existing ones.
 *
 * It is stored in the chat metadata as
 * {@code firstMissing-lastMissing;recovered;dateBefore;dateAfter}, with dates in
 * ISO-8601 (UTC) or empty when unknown.
 */
public class MessageSequenceGap {

    private final long firstMissing;
    private final long lastMissing;
    private final int recovered;
    private final Date dateBefore;
    private final Date dateAfter;

    /**
     * @param firstMissing
     *            first missing sequence number
     * @param lastMissing
     *            last missing sequence number
     * @param recovered
     *            how many of the missing messages were recovered from deleted
     *            records
     * @param dateBefore
     *            date of the existing message just before the gap
     * @param dateAfter
     *            date of the existing message just after the gap
     */
    public MessageSequenceGap(long firstMissing, long lastMissing, int recovered, Date dateBefore, Date dateAfter) {
        this.firstMissing = firstMissing;
        this.lastMissing = lastMissing;
        this.recovered = recovered;
        this.dateBefore = dateBefore;
        this.dateAfter = dateAfter;
    }

    public long getFirstMissing() {
        return firstMissing;
    }

    public long getLastMissing() {
        return lastMissing;
    }

    public long getMissing() {
        return lastMissing - firstMissing + 1;
    }

    public int getRecovered() {
        return recovered;
    }

    public Date getDateBefore() {
        return dateBefore;
    }

    public Date getDateAfter() {
        return dateAfter;
    }

    @Override
    public String toString() {
        return firstMissing + "-" + lastMissing + ";" + recovered + ";" + format(dateBefore) + ";"
                + format(dateAfter);
    }

    /**
     * Parses a value created by {@link #toString()}.
     *
     * @return the gap, or null if the value is not valid
     */
    public static MessageSequenceGap parse(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split(";", -1);
        if (parts.length != 4) {
            return null;
        }
        try {
            int dash = parts[0].indexOf('-', 1);
            long first = Long.parseLong(parts[0].substring(0, dash));
            long last = Long.parseLong(parts[0].substring(dash + 1));
            int recovered = Integer.parseInt(parts[1]);
            return new MessageSequenceGap(first, last, recovered, parseDate(parts[2]), parseDate(parts[3]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String format(Date date) {
        return date == null ? "" : date.toInstant().toString();
    }

    private static Date parseDate(String value) {
        return value.isEmpty() ? null : Date.from(Instant.parse(value));
    }
}
