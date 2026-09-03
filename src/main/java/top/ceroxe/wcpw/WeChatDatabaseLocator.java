package top.ceroxe.wcpw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.nio.file.InvalidPathException;
import java.util.OptionalLong;
import java.util.stream.Stream;

/** Locates the database actually opened by a running Debian WeChat process. */
final class WeChatDatabaseLocator {
    private static final Logger logger = LoggerFactory.getLogger(WeChatDatabaseLocator.class);
    private static final Set<String> PROCESS_MARKERS = Set.of("wechat", "wechatappex", "xwechat");

    private WeChatDatabaseLocator() { }

    static Path locate() {
        List<Path> candidates = candidates();
        boolean encryptedCandidate = false;
        Path encryptedPath = null;
        for (Path candidate : candidates) {
            if (PaymentDatabaseMonitor.hasMessageTables(candidate)) {
                logger.info("已从微信进程/文件系统定位微信收款消息库 | file={}", candidate);
                return candidate;
            }
            if (PaymentDatabaseMonitor.looksEncrypted(candidate) && likelyPaymentStore(candidate)) {
                encryptedCandidate = true;
                if (encryptedPath == null) encryptedPath = candidate;
                if (encryptedHasMessageTables(candidate)) {
                    logger.info("已解密校验并定位微信收款消息库 | file={}", candidate);
                    return candidate;
                }
                logger.debug("已读取加密候选库但未发现动态 Msg_* 表 | file={}", candidate);
            }
        }
        if (encryptedCandidate) {
            logger.info("已定位加密微信数据库，准备从微信进程内提取当前会话密钥 | file={}", encryptedPath);
            return encryptedPath;
        }
        throw new IllegalStateException("暂未找到微信收款消息库；等待微信登录并产生收款消息");
    }

    static boolean isMessageStore(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.startsWith("biz_message") || name.startsWith("message_");
    }

    private static boolean encryptedHasMessageTables(Path database) {
        OptionalLong pid = findProcessId(database);
        if (pid.isEmpty()) return false;
        Path snapshot = null;
        byte[] key = null;
        try {
            key = WeChatEncryptedDatabase.extractKey(database, pid.getAsLong());
            snapshot = Files.createTempFile("wcpw-locator-", ".db");
            WeChatEncryptedDatabase.decrypt(database, snapshot, key);
            return PaymentDatabaseMonitor.hasMessageTables(snapshot);
        } catch (RuntimeException | IOException ignored) {
            return false;
        } finally {
            if (key != null) java.util.Arrays.fill(key, (byte) 0);
            if (snapshot != null) try { Files.deleteIfExists(snapshot); } catch (IOException ignored) { }
        }
    }

    static OptionalLong findProcessId(Path databasePath) {
        Path expected = databasePath.toAbsolutePath().normalize();
        for (ProcessHandle process : ProcessHandle.allProcesses().toList()) {
            ProcessHandle.Info info = process.info();
            String command = (info.command().orElse("") + " " + info.commandLine().orElse("")).toLowerCase(Locale.ROOT);
            if (PROCESS_MARKERS.stream().noneMatch(command::contains)) continue;
            Path fdDir = Paths.get("/proc", Long.toString(process.pid()), "fd");
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(fdDir)) {
                for (Path fd : entries) {
                    try {
                        if (expected.equals(Files.readSymbolicLink(fd).toAbsolutePath().normalize())) return OptionalLong.of(process.pid());
                    } catch (IOException | SecurityException ignored) { }
                }
            } catch (IOException | SecurityException ignored) { }
        }
        return OptionalLong.empty();
    }

    private static List<Path> candidates() {
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        for (ProcessHandle process : ProcessHandle.allProcesses().toList()) {
            ProcessHandle.Info info = process.info();
            String command = (info.command().orElse("") + " " + info.commandLine().orElse("")).toLowerCase(Locale.ROOT);
            if (PROCESS_MARKERS.stream().noneMatch(command::contains)) continue;
            collectProcessFiles(process.pid(), result);
        }
        String home = System.getProperty("user.home", "");
        for (String relative : List.of(
                ".config/wechat", ".local/share/wechat", ".local/share/tencent", ".wechat",
                "Documents/xwechat_files", "Documents/WeChat Files")) {
            collectFilesystemFiles(Paths.get(home, relative), result);
        }
        return result.stream().filter(Files::isRegularFile)
                .sorted(Comparator.comparingInt(WeChatDatabaseLocator::paymentCandidateScore)
                        .thenComparing(Path::toString)).toList();
    }

    private static void collectProcessFiles(long pid, Set<Path> result) {
        Path fdDir = Paths.get("/proc", Long.toString(pid), "fd");
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(fdDir)) {
            for (Path fd : entries) {
                try {
                    Path target = Files.readSymbolicLink(fd);
                    addDatabase(target, result);
                } catch (IOException | SecurityException ignored) { }
            }
        } catch (IOException | SecurityException ignored) { }

        Path maps = Paths.get("/proc", Long.toString(pid), "maps");
        try (Stream<String> lines = Files.lines(maps)) {
            lines.map(line -> {
                int separator = line.lastIndexOf(' ');
                return separator < 0 ? "" : line.substring(separator + 1).trim();
            }).forEach(path -> addDatabase(Paths.get(path), result));
        } catch (IOException | SecurityException | InvalidPathException ignored) { }
    }

    private static void collectFilesystemFiles(Path root, Set<Path> result) {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> files = Files.walk(root, 5)) {
            files.filter(Files::isRegularFile).forEach(path -> addDatabase(path, result));
        } catch (IOException | SecurityException ignored) { }
    }

    private static void addDatabase(Path path, Set<Path> result) {
        String name = path.toString().toLowerCase(Locale.ROOT);
        if (name.endsWith("-wal") || name.endsWith("-shm")) {
            path = Paths.get(path.toString().substring(0, path.toString().length() - 4));
            name = path.toString().toLowerCase(Locale.ROOT);
        }
        if (name.endsWith(".db") || name.endsWith(".sqlite") || name.endsWith(".sqlite3")) result.add(path.toAbsolutePath().normalize());
    }

    private static boolean likelyPaymentStore(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.contains("message") || name.contains("session") || name.contains("general")
                || name.contains("pay") || name.contains("transfer") || name.contains("db_storage") || name.equals("db");
    }

    private static int paymentCandidateScore(Path path) {
        String value = path.toString().toLowerCase(Locale.ROOT);
        // The business payment table is in the primary message store; the
        // biz-message store is a separate channel and must not win ties.
        if (value.contains("biz_message")) return 0;
        if (value.endsWith("/message_0.db") || value.endsWith("\\message_0.db")) return 3;
        if (value.contains("message")) return 4;
        if (value.contains("session")) return 1;
        if (value.contains("general")) return 2;
        if (value.contains("pay")) return 3;
        if (value.contains("transfer")) return 4;
        return 10;
    }

}
