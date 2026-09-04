package top.ceroxe.wcpw;

import com.github.luben.zstd.ZstdInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只读监听微信收款消息库中的动态 Msg_* 表。 */
public final class PaymentDatabaseMonitor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PaymentDatabaseMonitor.class);
    private static final Pattern RECEIPT_MARKER = Pattern.compile("(?:个人收款码到账|收款金额|收款到账|收款)");
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(?:收款金额|到账金额|实收金额)\\s*[¥￥]?\\s*([0-9]+(?:[.,][0-9]{1,2})?)"
                    + "|[¥￥]\\s*([0-9]+(?:[.,][0-9]{1,2})?)");
    private static final Pattern TRANSACTION_PATTERN = Pattern.compile(
            "(?i)(?:trans_id|transaction_id)=([^&<\\s]+)");
    private static final Pattern PUB_TIME_PATTERN = Pattern.compile("<pub_time>\\s*(\\d+)\\s*</pub_time>");

    private volatile Path databasePath;
    private final boolean automaticPath;
    private final int queryLimit;
    private final Map<String, String> liveFingerprints = new ConcurrentHashMap<>();
    private volatile boolean encrypted;
    private volatile byte[] encryptionKey;
    private volatile Path decryptedSnapshot;
    private volatile boolean liveInitialized;
    private volatile boolean captureFirstDatabaseForTask;

    public static PaymentDatabaseMonitor fromConfig() {
        String configuredPath = AppConfig.get("wechat.db.path");
        Path path = configuredPath == null || configuredPath.isBlank() ? null : Paths.get(configuredPath);
        return new PaymentDatabaseMonitor(path, AppConfig.getInt("wechat.db.query.limit", 128));
    }

    public PaymentDatabaseMonitor(Path databasePath, int queryLimit) {
        this.automaticPath = databasePath == null;
        this.databasePath = databasePath == null ? null : databasePath.toAbsolutePath().normalize();
        this.queryLimit = Math.max(1, Math.min(queryLimit, 2000));
    }

    static boolean looksEncrypted(Path path) {
        if (!Files.isRegularFile(path)) return false;
        byte[] header = new byte[16];
        try (InputStream input = Files.newInputStream(path)) {
            if (input.read(header) < header.length) return false;
            return !"SQLite format 3\u0000".equals(new String(header, StandardCharsets.US_ASCII));
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    static boolean hasMessageTables(Path path) {
        if (!Files.isRegularFile(path)) return false;
        String uri = path.toAbsolutePath().normalize().toString().replace("\\", "/");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + uri + "?mode=ro");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT 1 FROM sqlite_master WHERE type='table' AND name LIKE 'Msg_%' "
                             + "AND (sql LIKE '%message_content%' OR sql LIKE '%compress_content%') LIMIT 1")) {
            return result.next();
        } catch (SQLException | RuntimeException ignored) {
            return false;
        }
    }

    public synchronized void validate() {
        ensureDatabasePath();
        if (!Files.isRegularFile(databasePath)) throw new IllegalStateException("微信收款消息数据库文件不存在: " + databasePath);
        encrypted = looksEncrypted(databasePath);
        try (Connection connection = openConnection()) {
            List<String> tables = messageTables(connection);
            if (tables.isEmpty()) throw new SQLException("微信收款消息库不存在动态 Msg_* 表");
            logger.info("微信收款消息库已就绪 | file={} | 动态表数量={} | tables={}", databasePath, tables.size(), tables);
            initializeLiveWatermark(connection);
        } catch (SQLException e) {
            resetAutomaticPath();
            throw databaseException(e);
        } catch (RuntimeException e) {
            resetAutomaticPath();
            throw e;
        }
    }

    public String sourceDescription() { return (databasePath == null ? "自动定位" : databasePath) + "#Msg_*"; }

    /** 订单先于数据库出现时，首个数据库快照属于本次活动源，不能当作历史。 */
    public synchronized void prepareForTask() {
        if (liveInitialized) return;
        if (databasePath == null || !Files.isRegularFile(databasePath)) {
            captureFirstDatabaseForTask = true;
            logger.info("订单启动时尚未发现微信收款消息库；首个出现的数据库记录将作为实时事件处理");
        }
    }

    public synchronized void cancelTaskPreparation() {
        if (!liveInitialized) captureFirstDatabaseForTask = false;
    }

    private void initializeLiveWatermark(Connection connection) throws SQLException {
        liveFingerprints.clear();
        for (Row row : readRows(connection)) liveFingerprints.put(row.key(), row.fingerprint());
        liveInitialized = true;
        logger.info("微信收款新消息监听已就绪 | 启动时忽略历史记录数={}", liveFingerprints.size());
    }

    /** 返回自上次观察以来新出现或内容变化的第一条收款消息。 */
    public synchronized Optional<Change> findNewChange() {
        try (Connection connection = openConnection()) {
            if (!liveInitialized) {
                if (captureFirstDatabaseForTask) {
                    liveFingerprints.clear();
                    liveInitialized = true;
                    captureFirstDatabaseForTask = false;
                    logger.info("首个微信收款消息库已出现 | 当前记录作为实时事件源");
                } else {
                    initializeLiveWatermark(connection);
                }
            }
            for (Row row : readRows(connection)) {
                String previous = liveFingerprints.put(row.key(), row.fingerprint());
                if (Objects.equals(previous, row.fingerprint())) continue;
                logger.info("检测到微信消息行变化 | key={} | 新增={} ", row.key(), previous == null);
                PaymentEvent event = parsePayment(row);
                if (event != null) return Optional.of(new Change(event));
            }
            return Optional.empty();
        } catch (SQLException e) {
            resetAutomaticPath();
            throw databaseException(e);
        }
    }

    /** 无活动订单时推进高水位，避免后台产生的消息污染下一笔订单。 */
    public synchronized void observeNewRows() {
        try (Connection connection = openConnection()) {
            if (!liveInitialized) initializeLiveWatermark(connection);
            for (Row row : readRows(connection)) liveFingerprints.put(row.key(), row.fingerprint());
        } catch (SQLException e) {
            resetAutomaticPath();
            throw databaseException(e);
        }
    }

    private Connection openConnection() throws SQLException {
        ensureDatabasePath();
        Path readablePath = encrypted ? refreshDecryptedSnapshot() : databasePath;
        String uri = readablePath.toString().replace("\\", "/");
        Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + uri + "?mode=ro");
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(2);
            statement.execute("PRAGMA busy_timeout=2000");
            statement.execute("PRAGMA query_only=ON");
        }
        return connection;
    }

    private List<Row> readRows(Connection connection) throws SQLException {
        List<Row> rows = new ArrayList<>();
        for (String table : messageTables(connection)) {
            String sql = "SELECT rowid AS __wcpw_rowid, * FROM " + quoteIdentifier(table)
                    + " ORDER BY rowid DESC LIMIT " + queryLimit;
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
                ResultSetMetaData metadata = result.getMetaData();
                while (result.next()) {
                    Map<String, Object> values = new HashMap<>();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        values.put(metadata.getColumnName(index).toLowerCase(Locale.ROOT), result.getObject(index));
                    }
                    long rowid = result.getLong("__wcpw_rowid");
                    String localId = textValue(values.get("local_id"));
                    String key = table + "|" + (localId == null || localId.isBlank()
                            ? "rowid:" + rowid : "local_id:" + localId);
                    rows.add(new Row(key, fingerprint(values), values));
                }
            }
        }
        return rows;
    }

    private PaymentEvent parsePayment(Row row) {
        String content = messageContent(row.values());
        if (content == null || !RECEIPT_MARKER.matcher(content).find()) {
            logger.debug("忽略非收款消息 | key={}", row.key());
            return null;
        }
        Matcher amountMatcher = AMOUNT_PATTERN.matcher(content.replace(',', '.'));
        if (!amountMatcher.find()) {
            logger.warn("发现收款通知但未解析到金额 | key={}", row.key());
            return null;
        }
        String amountText = amountMatcher.group(1) != null ? amountMatcher.group(1) : amountMatcher.group(2);
        BigDecimal amount;
        try {
            amount = new BigDecimal(amountText).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            logger.warn("收款通知金额格式异常 | key={} | value={}", row.key(), amountText);
            return null;
        }
        Matcher transaction = TRANSACTION_PATTERN.matcher(content);
        String transactionId = transaction.find() ? transaction.group(1) : row.key();
        Matcher pubTime = PUB_TIME_PATTERN.matcher(content);
        String paidAt = pubTime.find() ? pubTime.group(1) : textValue(row.values().get("create_time"));
        logger.info("解析到微信收款通知 | transactionId={} | amount=¥{} | paidAt={}", transactionId, amount, paidAt);
        return new PaymentEvent(transactionId, amount, paidAt);
    }

    private static List<String> messageTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'Msg_%' "
                             + "AND (sql LIKE '%message_content%' OR sql LIKE '%compress_content%') ORDER BY name")) {
            while (result.next()) tables.add(result.getString(1));
        }
        if (tables.isEmpty()) throw new SQLException("微信收款消息库尚未生成动态 Msg_* 表");
        return tables;
    }

    private static String fingerprint(Map<String, Object> values) {
        StringBuilder result = new StringBuilder();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                result.append('|').append(entry.getKey()).append('=').append(canonicalValue(entry.getValue())));
        return result.toString();
    }

    private static String canonicalValue(Object value) {
        if (value instanceof byte[] bytes) return java.util.HexFormat.of().formatHex(bytes);
        return String.valueOf(value);
    }

    private static String textValue(Object value) {
        if (value == null) return null;
        if (value instanceof byte[] bytes) {
            try (ZstdInputStream input = new ZstdInputStream(new ByteArrayInputStream(bytes));
                 ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(bytes.length * 4, 1_048_576))) {
                input.transferTo(output);
                return output.toString(StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException ignored) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
        return value.toString();
    }

    private static String messageContent(Map<String, Object> values) {
        String content = textValue(values.get("message_content"));
        if (content != null && !content.isBlank()) return content;
        content = textValue(values.get("compress_content"));
        if (content != null && !content.isBlank()) return content;
        return textValue(values.get("packed_info_data"));
    }

    private Path refreshDecryptedSnapshot() {
        if (encryptionKey == null) {
            java.util.OptionalLong pid = WeChatDatabaseLocator.findProcessId(databasePath);
            if (pid.isEmpty()) throw new IllegalStateException("加密微信数据库未关联运行中的微信进程");
            encryptionKey = WeChatEncryptedDatabase.extractKey(databasePath, pid.getAsLong());
            logger.info("已从微信进程提取数据库会话密钥 | pid={} | file={}", pid.getAsLong(), databasePath);
        }
        try {
            Path next = Files.createTempFile("wcpw-wechat-decrypted-", ".db");
            WeChatEncryptedDatabase.decrypt(databasePath, next, encryptionKey);
            Path old = decryptedSnapshot;
            decryptedSnapshot = next;
            if (old != null) Files.deleteIfExists(old);
            return next;
        } catch (IOException e) {
            throw new IllegalStateException("创建微信数据库解密快照失败", e);
        }
    }

    private void ensureDatabasePath() {
        if (databasePath != null) return;
        databasePath = WeChatDatabaseLocator.locate();
        encrypted = looksEncrypted(databasePath);
        logger.info("已自动定位微信收款消息数据库 | file={}", databasePath);
    }

    private void resetAutomaticPath() {
        if (automaticPath) {
            databasePath = null;
            encrypted = false;
            if (encryptionKey != null) java.util.Arrays.fill(encryptionKey, (byte) 0);
            encryptionKey = null;
            liveInitialized = false;
            liveFingerprints.clear();
        }
    }

    @Override public synchronized void close() {
        if (decryptedSnapshot != null) {
            try { Files.deleteIfExists(decryptedSnapshot); } catch (IOException ignored) { }
            decryptedSnapshot = null;
        }
        if (encryptionKey != null) java.util.Arrays.fill(encryptionKey, (byte) 0);
        encryptionKey = null;
        liveFingerprints.clear();
        liveInitialized = false;
        captureFirstDatabaseForTask = false;
    }

    private static String quoteIdentifier(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }

    private static RuntimeException databaseException(SQLException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("not a database") || normalized.contains("file is encrypted")) {
            return new IllegalStateException("微信数据库已加密；未能提取当前会话密钥", e);
        }
        if (normalized.contains("busy") || normalized.contains("locked")) {
            return new DatabaseBusyException("微信数据库正忙或被锁定: " + message, e);
        }
        return new IllegalStateException("读取微信收款消息库失败: " + message, e);
    }

    private record Row(String key, String fingerprint, Map<String, Object> values) { }
    public record Change(PaymentEvent event) { }
    public record PaymentEvent(String transactionId, BigDecimal amount, String paidAt) { }
    public static final class DatabaseBusyException extends IllegalStateException {
        public DatabaseBusyException(String message, Throwable cause) { super(message, cause); }
    }
}
