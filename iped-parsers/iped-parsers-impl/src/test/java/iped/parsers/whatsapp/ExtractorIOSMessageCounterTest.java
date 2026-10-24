package iped.parsers.whatsapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

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
        ExtractorIOS extractor = new ExtractorIOS(db.getAbsolutePath(), db, new WAContactsDirectory(),
                new WAAccount("5511999990000"), false) {
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
}
