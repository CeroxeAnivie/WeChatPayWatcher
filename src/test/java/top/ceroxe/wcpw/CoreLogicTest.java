package top.ceroxe.wcpw;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** 动态 Msg_* 收款监听的回归测试。 */
public class CoreLogicTest {
    public void testHistoricalMessagesAreIgnoredAfterStartup() throws Exception {
        Path db = fixture("历史消息", 1);
        try (PaymentDatabaseMonitor monitor = monitor(db)) {
            monitor.validate();
            assertTrue(monitor.findNewChange().isEmpty(), "启动前的历史消息不得触发");
        } finally { Files.deleteIfExists(db); }
    }

    public void testDatabaseCreatedAfterOrderIsTreatedAsLiveSource() throws Exception {
        Path db = Files.createTempFile("wcpw-late-message-store-", ".db");
        Files.deleteIfExists(db);
        try (PaymentDatabaseMonitor monitor = monitor(db)) {
            monitor.prepareForTask();
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE Msg_fixture (local_id INTEGER PRIMARY KEY, create_time INTEGER, message_content TEXT)");
                insert(statement, 1, receipt("3.00", "TX_LATE_DB", 500));
            }
            assertTrue(monitor.findNewChange().isPresent(), "订单先启动、数据库后出现时应捕获首笔收款");
        } finally { Files.deleteIfExists(db); }
    }

    public void testNewReceiptIsDetectedWithoutRequestBaseline() throws Exception {
        Path db = fixture("历史消息", 1);
        try (PaymentDatabaseMonitor monitor = monitor(db)) {
            monitor.validate();
            insert(db, 2, receipt("1.39", "TX_DYNAMIC_001", 200));
            PaymentDatabaseMonitor.Change change = monitor.findNewChange().orElseThrow();
            assertEquals("TX_DYNAMIC_001", change.event().transactionId(), "应提取收款交易号");
            assertEquals("1.39", change.event().amount().toPlainString(), "应提取收款金额");
        } finally { Files.deleteIfExists(db); }
    }

    public void testMismatchedReceiptIsConsumedOnce() throws Exception {
        Path db = fixture("历史消息", 1);
        try (PaymentDatabaseMonitor monitor = monitor(db)) {
            monitor.validate();
            insert(db, 2, receipt("2.00", "TX_MISMATCH", 200));
            PaymentDatabaseMonitor.Change change = monitor.findNewChange().orElseThrow();
            assertEquals("2.00", change.event().amount().toPlainString(), "应读取实际金额");
            assertTrue(monitor.findNewChange().isEmpty(), "同一条消息不得重复产生事件");
        } finally { Files.deleteIfExists(db); }
    }

    public void testMatchingAmountIsExactToTwoDecimals() throws Exception {
        Path db = fixture("历史消息", 1);
        try (PaymentDatabaseMonitor monitor = monitor(db)) {
            monitor.validate();
            insert(db, 2, receipt("10.00", "TX_EXACT", 200));
            PaymentDatabaseMonitor.PaymentEvent event = monitor.findNewChange().orElseThrow().event();
            assertTrue(event.amount().compareTo(java.math.BigDecimal.valueOf(10).setScale(2)) == 0,
                    "金额比较必须保留两位小数");
        } finally { Files.deleteIfExists(db); }
    }

    public void testChangedMessageIsDetectedOnce() throws Exception {
        Path db = fixture("普通消息", 1);
        try (PaymentDatabaseMonitor monitor = monitor(db)) {
            monitor.validate();
            update(db, "UPDATE Msg_fixture SET message_content='" + sql(receipt("0.10", "TX_CHANGED", 300)) + "' WHERE local_id=1");
            assertTrue(monitor.findNewChange().isPresent(), "消息内容变化应被观察到");
            assertTrue(monitor.findNewChange().isEmpty(), "已消费的变化不得再次触发");
        } finally { Files.deleteIfExists(db); }
    }

    public void testNonReceiptMessageDoesNotTrigger() throws Exception {
        Path db = fixture("普通聊天消息", 1);
        try (PaymentDatabaseMonitor monitor = monitor(db)) {
            monitor.validate();
            insert(db, 2, "<msg><appmsg><title>转账提醒</title></appmsg></msg>");
            assertTrue(monitor.findNewChange().isEmpty(), "非收款消息不得触发");
        } finally { Files.deleteIfExists(db); }
    }

    private PaymentDatabaseMonitor monitor(Path db) { return new PaymentDatabaseMonitor(db, 64); }

    private Path fixture(String title, int id) throws Exception {
        Path db = Files.createTempFile("wcpw-message-store-", ".db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE Msg_fixture (local_id INTEGER PRIMARY KEY, create_time INTEGER, message_content TEXT)");
            insert(statement, id, "<msg><appmsg><title>" + title + "</title></appmsg></msg>");
        }
        return db;
    }

    private void insert(Path db, int id, String content) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            insert(statement, id, content);
        }
    }

    private void insert(Statement statement, int id, String content) throws Exception {
        statement.execute("INSERT INTO Msg_fixture VALUES (" + id + ",200,'" + sql(content) + "')");
    }

    private void update(Path db, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    private String receipt(String amount, String transactionId, long time) {
        return "<msg><appmsg><title>个人收款码到账¥" + amount + "</title><des>收款金额¥" + amount
                + "</des><url>https://wx.tenpay.com/?trans_id=" + transactionId
                + "</url><mmreader><item><pub_time>" + time + "</pub_time></item></mmreader></appmsg></msg>";
    }

    private String sql(String value) { return value.replace("'", "''"); }
    private void assertTrue(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
    }
}
