package iped.parsers.whatsapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import fqlite.base.SqliteRow;
import fqlite.base.SqliteRowFactory;

public class ExtractorIOSMessageCounterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File createChatStorage(boolean withCounterColumn) throws IOException, SQLException {
        File db = tmp.newFile("ChatStorage.sqlite");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
                Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE ZWACHATSESSION (Z_PK INTEGER PRIMARY KEY, ZCONTACTJID VARCHAR, "
                    + "ZPARTNERNAME VARCHAR, ZLASTMESSAGEDATE TIMESTAMP, ZREMOVED INTEGER"
                    + (withCounterColumn ? ", ZMESSAGECOUNTER INTEGER" : "") + ")");
            stmt.execute("CREATE TABLE ZWAMESSAGE (Z_PK INTEGER PRIMARY KEY, ZCHATSESSION INTEGER, ZFROMJID VARCHAR, "
                    + "ZMESSAGESTATUS INTEGER, ZTEXT VARCHAR, ZISFROMME INTEGER, ZMESSAGEDATE TIMESTAMP, "
                    + "ZFLAGS INTEGER, ZSTANZAID VARCHAR, ZGROUPEVENTTYPE INTEGER, ZMESSAGETYPE INTEGER, "
                    + "ZSORT INTEGER, ZGROUPMEMBER INTEGER)");
            stmt.execute("CREATE TABLE ZWAMEDIAITEM (Z_PK INTEGER PRIMARY KEY, ZMESSAGE INTEGER, ZVCARDSTRING VARCHAR, "
                    + "ZFILESIZE INTEGER, ZMEDIALOCALPATH VARCHAR, ZVCARDNAME VARCHAR, ZTITLE VARCHAR, "
                    + "ZLATITUDE FLOAT, ZLONGITUDE FLOAT, ZMEDIAURL VARCHAR, ZXMPPTHUMBPATH VARCHAR, "
                    + "ZMETADATA BLOB, ZMOVIEDURATION INTEGER)");
            stmt.execute("CREATE TABLE ZWAGROUPMEMBER (Z_PK INTEGER PRIMARY KEY, ZCHATSESSION INTEGER, ZMEMBERJID VARCHAR)");

            String counterA = withCounterColumn ? ", 5" : "";
            String counterB = withCounterColumn ? ", 2" : "";
            stmt.execute("INSERT INTO ZWACHATSESSION VALUES (1, '5511999990001@s.whatsapp.net', 'Ana', 700000000, 0"
                    + counterA + ")");
            stmt.execute("INSERT INTO ZWACHATSESSION VALUES (2, '5511999990002@s.whatsapp.net', 'Bruno', 700000100, 0"
                    + counterB + ")");
            for (int i = 1; i <= 3; i++) {
                stmt.execute("INSERT INTO ZWAMESSAGE VALUES (" + i + ", 1, '5511999990001@s.whatsapp.net', 0, 'msg " + i
                        + "', " + (i % 2) + ", " + (700000000 + i) + ", 0, 'id" + i + "', 0, 0, " + i + ", NULL)");
            }
        }
        return db;
    }

    private List<Chat> extract(File db) throws WAExtractorException {
        return extract(db, false);
    }

    private List<Chat> extract(File db, boolean recoverDeleted) throws WAExtractorException {
        ExtractorIOS extractor = new ExtractorIOS(db.getAbsolutePath(), db, new WAContactsDirectory(),
                new WAAccount("5511999990000"), recoverDeleted) {
            @Override
            protected Connection getConnection() throws SQLException {
                return DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
            }
        };
        return extractor.getChatList();
    }

    private Map<Long, Chat> byId(List<Chat> chats) {
        Map<Long, Chat> map = new HashMap<>();
        for (Chat c : chats) {
            map.put(c.getId(), c);
        }
        return map;
    }

    @Test
    public void testReadsMessageCounterAndStoredMessages() throws Exception {
        Map<Long, Chat> chats = byId(extract(createChatStorage(true)));

        Chat ana = chats.get(1L);
        assertNotNull(ana);
        assertEquals(3, ana.getMessages().size());
        assertEquals(Integer.valueOf(5), ana.getMessageCounter());
        assertEquals(Integer.valueOf(3), ana.getStoredMessagesCount());

        Chat bruno = chats.get(2L);
        assertNotNull(bruno);
        assertEquals(Integer.valueOf(2), bruno.getMessageCounter());
        assertEquals(Integer.valueOf(0), bruno.getStoredMessagesCount());
    }

    @Test
    public void testMissingCounterColumn() throws Exception {
        Map<Long, Chat> chats = byId(extract(createChatStorage(false)));

        Chat ana = chats.get(1L);
        assertNotNull(ana);
        assertEquals(3, ana.getMessages().size());
        assertNull(ana.getMessageCounter());
        assertNull(ana.getStoredMessagesCount());
    }

    private void execute(File db, String... sqls) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
                Statement stmt = conn.createStatement()) {
            for (String sql : sqls) {
                stmt.execute(sql);
            }
        }
    }

    private static Date appleDate(long seconds) {
        return new Date((seconds + 978307200L) * 1000);
    }

    @Test
    public void testSequenceGaps() throws Exception {
        File db = createChatStorage(true);
        // chat 1 has ZSORT 1, 2, 3 and 7: 4 to 6 are missing
        execute(db, "INSERT INTO ZWAMESSAGE VALUES (7, 1, '5511999990001@s.whatsapp.net', 0, 'msg 7', 0, "
                + "700000100, 0, 'id7', 0, 0, 7, NULL)");
        Map<Long, Chat> chats = byId(extract(db));

        List<MessageSequenceGap> gaps = chats.get(1L).getSequenceGaps();
        assertEquals(1, gaps.size());
        MessageSequenceGap gap = gaps.get(0);
        assertEquals(4, gap.getFirstMissing());
        assertEquals(6, gap.getLastMissing());
        assertEquals(3, gap.getMissing());
        assertEquals(0, gap.getRecovered());
        assertEquals(appleDate(700000003), gap.getDateBefore());
        assertEquals(appleDate(700000100), gap.getDateAfter());

        assertTrue(chats.get(2L).getSequenceGaps().isEmpty());
    }

    @Test
    public void testRecoveredSorts() {
        List<SqliteRow> rows = List.of(SqliteRowFactory.create(true, "ZSORT", 5L),
                SqliteRowFactory.create(true, "ZSORT", 6L), SqliteRowFactory.create(false, "ZSORT", 7L),
                SqliteRowFactory.create(true, "ZSORT", 30L));
        Set<Long> sorts = ExtractorIOS.getRecoveredSorts(rows);
        assertEquals(Set.of(5L, 6L, 30L), sorts);
        assertEquals(2, ExtractorIOS.countInRange(sorts, 4, 23));
        assertEquals(0, ExtractorIOS.countInRange(sorts, 7, 29));
        assertTrue(ExtractorIOS.getRecoveredSorts(null).isEmpty());
    }

    @Test
    public void testSequenceGapFormat() {
        MessageSequenceGap gap = new MessageSequenceGap(10, 25, 2, appleDate(700000000), null);
        MessageSequenceGap parsed = MessageSequenceGap.parse(gap.toString());
        assertEquals(10, parsed.getFirstMissing());
        assertEquals(25, parsed.getLastMissing());
        assertEquals(16, parsed.getMissing());
        assertEquals(2, parsed.getRecovered());
        assertEquals(appleDate(700000000), parsed.getDateBefore());
        assertNull(parsed.getDateAfter());
        assertNull(MessageSequenceGap.parse("invalid"));
    }
}
