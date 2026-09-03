package top.ceroxe.wcpw;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** WeChat 4.x encrypted-WCDB page decoder. Keys are kept in memory only. */
final class WeChatEncryptedDatabase {
    static final int PAGE_SIZE = 4096;
    private static final int RESERVE_SIZE = 80;
    private static final int KEY_SIZE = 32;
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{96}");
    private static final HexFormat HEX_FORMAT = HexFormat.of();

    private WeChatEncryptedDatabase() { }

    static byte[] extractKey(Path database, long pid) {
        try {
            if (Files.size(database) < PAGE_SIZE) throw new IllegalStateException("微信加密数据库文件长度不足一页");
            byte[] page = readPage(database);
            String salt = HEX_FORMAT.formatHex(java.util.Arrays.copyOf(page, 16));
            Path memory = Path.of("/proc", Long.toString(pid), "mem");
            try (FileChannel channel = FileChannel.open(memory, StandardOpenOption.READ)) {
                for (String range : readableRanges(pid)) {
                    String[] parts = range.split(" ", 3);
                    long start = Long.parseUnsignedLong(parts[0].split("-", 2)[0], 16);
                    long end = Long.parseUnsignedLong(parts[0].split("-", 2)[1], 16);
                    long length = Math.min(end - start, 256L * 1024 * 1024);
                    ByteBuffer buffer = ByteBuffer.allocateDirect((int) Math.min(length, 1024 * 1024));
                    for (long offset = 0; offset < length; offset += buffer.capacity()) {
                        buffer.clear();
                        int size = (int) Math.min(buffer.capacity(), length - offset);
                        buffer.limit(size);
                        try {
                            channel.position(start + offset);
                            int read = 0;
                            while (read < size) {
                                int n = channel.read(buffer);
                                if (n < 0) break;
                                if (n == 0) continue;
                                read += n;
                            }
                            if (read != size) continue;
                        }
                        catch (IOException ignored) { continue; }
                        byte[] bytes = new byte[size];
                        buffer.flip(); buffer.get(bytes);
                        for (int i = 0; i + 99 < bytes.length; i++) {
                            if (bytes[i] != 'x' || bytes[i + 1] != '\'') continue;
                            String candidate = new String(bytes, i + 2, 96, java.nio.charset.StandardCharsets.US_ASCII);
                            if (!HEX.matcher(candidate).matches() || bytes[i + 98] != '\'') continue;
                            String candidateSalt = candidate.substring(64).toLowerCase(Locale.ROOT);
                            if (!salt.equals(candidateSalt)) continue;
                            byte[] key = HEX_FORMAT.parseHex(candidate.substring(0, 64));
                            if (verifyPageHmac(page, key)) return key;
                            byte[] derived = pbkdf2(key, saltBytes(page), 256000, KEY_SIZE);
                            if (verifyPageHmac(page, derived)) return derived;
                        }
                    }
                }
            } catch (IOException ignored) {
                // Ubuntu's Yama ptrace policy may deny /proc/pid/mem; key_info.db is tried below.
            }
            for (Path keyInfo : keyInfoFiles(pid)) {
                for (byte[] passphrase : keyInfoCandidates(keyInfo)) {
                    byte[] derived = pbkdf2(passphrase, saltBytes(page), 256000, KEY_SIZE);
                    if (verifyPageHmac(page, derived)) return derived;
                }
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("读取微信进程密钥失败，请确认服务与微信使用同一用户运行", e);
        }
        throw new IllegalStateException("微信进程内未找到匹配当前数据库盐值的密钥");
    }

    static void decrypt(Path encrypted, Path output, byte[] key) {
        try (FileChannel input = FileChannel.open(encrypted, StandardOpenOption.READ);
             FileChannel out = FileChannel.open(output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            long pages = input.size() / PAGE_SIZE;
            if (pages == 0) throw new IllegalStateException("微信加密数据库为空");
            ByteBuffer page = ByteBuffer.allocate(PAGE_SIZE);
            for (int number = 1; number <= pages; number++) {
                page.clear();
                while (page.hasRemaining() && input.read(page) > 0) { }
                if (page.hasRemaining()) throw new IOException("微信加密数据库页读取不完整");
                page.flip();
                byte[] raw = new byte[PAGE_SIZE]; page.get(raw);
                byte[] clear = decryptPage(raw, key, number);
                ByteBuffer outputPage = ByteBuffer.wrap(clear);
                while (outputPage.hasRemaining()) out.write(outputPage);
            }
            applyWal(encrypted.resolveSibling(encrypted.getFileName() + "-wal"), out, key);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("解密微信数据库快照失败", e);
        }
    }

    private static void applyWal(Path wal, FileChannel output, byte[] key) throws IOException, GeneralSecurityException {
        if (!Files.isRegularFile(wal) || Files.size(wal) < 32 + 24 + PAGE_SIZE) return;
        try (FileChannel input = FileChannel.open(wal, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(32);
            readFully(input, header);
            header.flip();
            int pageSize = header.getInt(8);
            if (pageSize != PAGE_SIZE) return;
            long frameSize = 24L + PAGE_SIZE;
            ByteBuffer frameHeader = ByteBuffer.allocate(24);
            ByteBuffer encryptedPage = ByteBuffer.allocate(PAGE_SIZE);
            while (input.position() + frameSize <= input.size()) {
                frameHeader.clear();
                if (!readFully(input, frameHeader)) break;
                frameHeader.flip();
                long pageNumber = Integer.toUnsignedLong(frameHeader.getInt());
                encryptedPage.clear();
                if (!readFully(input, encryptedPage) || pageNumber == 0 || pageNumber > 1_000_000) break;
                encryptedPage.flip();
                byte[] raw = new byte[PAGE_SIZE];
                encryptedPage.get(raw);
                byte[] clear = decryptPage(raw, key, (int) pageNumber);
                ByteBuffer page = ByteBuffer.wrap(clear);
                output.position((pageNumber - 1) * (long) PAGE_SIZE);
                while (page.hasRemaining()) output.write(page);
            }
        }
    }

    private static boolean readFully(FileChannel channel, ByteBuffer target) throws IOException {
        while (target.hasRemaining()) {
            int read = channel.read(target);
            if (read < 0) return false;
            if (read == 0) continue;
        }
        return true;
    }

    private static byte[] decryptPage(byte[] page, byte[] key, int number) throws GeneralSecurityException {
        byte[] iv = java.util.Arrays.copyOfRange(page, PAGE_SIZE - RESERVE_SIZE, PAGE_SIZE - RESERVE_SIZE + 16);
        int offset = number == 1 ? 16 : 0;
        byte[] encrypted = java.util.Arrays.copyOfRange(page, offset, PAGE_SIZE - RESERVE_SIZE);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] clear = cipher.doFinal(encrypted);
        byte[] result = new byte[PAGE_SIZE];
        if (number == 1) System.arraycopy("SQLite format 3\u0000".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, result, 0, 16);
        System.arraycopy(clear, 0, result, number == 1 ? 16 : 0, clear.length);
        return result;
    }

    private static boolean verifyPageHmac(byte[] page, byte[] key) throws GeneralSecurityException {
        byte[] salt = saltBytes(page);
        byte[] macSalt = new byte[16];
        for (int i = 0; i < 16; i++) macSalt[i] = (byte) (salt[i] ^ 0x3A);
        byte[] macKey = pbkdf2(key, macSalt, 2, KEY_SIZE);
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(macKey, "HmacSHA512"));
        mac.update(page, 16, PAGE_SIZE - RESERVE_SIZE);
        mac.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array());
        return MessageDigest.isEqual(mac.doFinal(), java.util.Arrays.copyOfRange(page, PAGE_SIZE - 64, PAGE_SIZE));
    }

    private static byte[] saltBytes(byte[] page) { return java.util.Arrays.copyOf(page, 16); }

    private static java.util.List<Path> keyInfoFiles(long pid) {
        java.util.LinkedHashSet<Path> result = new java.util.LinkedHashSet<>();
        Path fd = Path.of("/proc", Long.toString(pid), "fd");
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(fd)) {
            for (Path item : entries) try {
                Path target = Files.readSymbolicLink(item);
                if (target.getFileName().toString().equalsIgnoreCase("key_info.db")) result.add(target);
            } catch (IOException | SecurityException ignored) { }
        } catch (IOException | SecurityException ignored) { }
        return result.stream().filter(Files::isRegularFile).toList();
    }

    private static java.util.List<byte[]> keyInfoCandidates(Path keyInfo) {
        java.util.List<byte[]> result = new java.util.ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + keyInfo.toString().replace("\\", "/") + "?mode=ro");
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT key_info_data FROM LoginKeyInfoTable")) {
            while (rows.next()) {
                byte[] blob = rows.getBytes(1);
                if (blob == null || blob.length < KEY_SIZE) continue;
                for (int offset = 0; offset + KEY_SIZE <= blob.length; offset += 4) {
                    byte[] candidate = java.util.Arrays.copyOfRange(blob, offset, offset + KEY_SIZE);
                    if (result.stream().noneMatch(existing -> java.util.Arrays.equals(existing, candidate))) result.add(candidate);
                }
            }
        } catch (Exception ignored) { }
        return result;
    }

    private static byte[] pbkdf2(byte[] password, byte[] salt, int iterations, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA512"); mac.init(new SecretKeySpec(password, "HmacSHA512"));
        byte[] block = new byte[salt.length + 4]; System.arraycopy(salt, 0, block, 0, salt.length); block[salt.length + 3] = 1;
        byte[] result = mac.doFinal(block), output = result.clone();
        for (int i = 1; i < iterations; i++) { result = mac.doFinal(result); for (int j = 0; j < output.length; j++) output[j] ^= result[j]; }
        return java.util.Arrays.copyOf(output, length);
    }

    private static byte[] readPage(Path database) throws IOException {
        byte[] page = new byte[PAGE_SIZE];
        try (FileChannel channel = FileChannel.open(database, StandardOpenOption.READ)) { channel.read(ByteBuffer.wrap(page)); }
        return page;
    }

    private static java.util.List<String> readableRanges(long pid) throws IOException {
        try (java.util.stream.Stream<String> lines = Files.lines(Path.of("/proc", Long.toString(pid), "maps"))) {
            return lines.map(String::trim)
                    .map(line -> line.split("\\s+", 3))
                    .filter(parts -> parts.length >= 2 && parts[1].startsWith("r"))
                    .map(parts -> parts[0] + " " + parts[1])
                    .toList();
        }
    }
}
