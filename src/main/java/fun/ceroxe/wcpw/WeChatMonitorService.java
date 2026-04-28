package fun.ceroxe.wcpw;

import com.benjaminwan.ocrlibrary.OcrResult;
import com.benjaminwan.ocrlibrary.TextBlock;
import io.github.mymonstercat.Model;
import io.github.mymonstercat.ocr.InferenceEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class WeChatMonitorService {
    private static final Logger logger = LoggerFactory.getLogger(WeChatMonitorService.class);

    private static final int ROI_WIDTH = 380;
    private static final int ROI_HEIGHT = 450;
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("(?<!\\d)(\\d+(?:[.,]\\d{1,2})?)(?!\\d)");

    private static final AtomicLong GLOBAL_BASELINE_SERIAL = new AtomicLong(-1);

    // 【新增】用于统计当前正在运行的监控任务数量
    private final AtomicInteger activeTaskCount = new AtomicInteger(0);
    // 【新增】定时调度器
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Baseline-Guard-Thread");
        t.setDaemon(true); // 设为守护线程，随主线程退出
        return t;
    });

    private final InferenceEngine engine;
    private final Robot robot;
    private final Dimension screenSize;

    public WeChatMonitorService() {
        try {
            System.setProperty("java.awt.headless", "false");
            this.robot = new Robot();
            this.screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            this.engine = InferenceEngine.getInstance(Model.ONNX_PPOCR_V3);

            performWarmUp();
            initGlobalBaseline();

            // 【新增】启动兜底定时任务：每1小时检测一次
            startBackgroundBaselineGuard();
        } catch (Exception e) {
            throw new RuntimeException("Monitor Service Init Failed", e);
        }
    }

    /**
     * 【新增】后台兜底逻辑
     * 每隔一小时检查一次，如果没有活跃请求，则更新基准
     */
    private void startBackgroundBaselineGuard() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // 核心逻辑：如果当前有正在处理的请求，直接放弃本次刷新，确保实时响应
                if (activeTaskCount.get() > 0) {
                    logger.debug("🛡️ 兜底检查跳过：当前有 {} 个活跃监控任务，优先保证实时性", activeTaskCount.get());
                    return;
                }

                logger.info("🛡️ 执行例行兜底基准检测 (周期: 1小时)...");
                long oldBaseline = GLOBAL_BASELINE_SERIAL.get();
                long currentSerial = scanCurrentSerial();

                // 只有在真的发生变化时才记录，避免刷日志
                if (oldBaseline != currentSerial) {
                    GLOBAL_BASELINE_SERIAL.set(currentSerial);
                    logger.info("🛡️ 兜底基准已更新: #{} -> #{}", oldBaseline, currentSerial);
                } else {
                    logger.debug("🛡️ 兜底检测完成：基准未发生变化 (#{})", currentSerial);
                }
            } catch (Exception e) {
                logger.error("🛡️ 兜底基准检测发生异常", e);
            }
        }, 1, 1, TimeUnit.HOURS); // 延迟1小时启动，每1小时执行一次
    }

    public boolean monitorPayment(String taskId, double targetAmount, long timeoutSeconds) {
        // 【新增】任务进入，增加活跃计数
        activeTaskCount.incrementAndGet();

        try {
            long endTime = System.currentTimeMillis() + (timeoutSeconds * 1000L);
            String amountStr = formatAmount(targetAmount);
            int startTextFingerprint = scanCurrentTextFingerprint(taskId);

            long startBaseline = GLOBAL_BASELINE_SERIAL.get();
            logger.info("[{}] 👁️ 监控启动 | 当前全局基准: #{} | 目标: ¥{} | OCR基准指纹: {}",
                    taskId,
                    startBaseline,
                    amountStr,
                    startTextFingerprint);

            int scanCount = 0;

            while (System.currentTimeMillis() < endTime) {
                try {
                    if (Thread.currentThread().isInterrupted()) return false;

                    Thread.sleep(5000);
                    scanCount++;

                    Path tempFile = null;
                    BufferedImage currentFrame = captureROI();

                    try {
                        tempFile = Files.createTempFile("ocr_", ".png");
                        ImageIO.write(currentFrame, "png", tempFile.toFile());

                        long t1 = System.currentTimeMillis();
                        OcrResult result = engine.runOcr(tempFile.toAbsolutePath().toString());
                        long cost = System.currentTimeMillis() - t1;

                        List<TextBlock> blocks = (result != null) ? result.getTextBlocks() : null;
                        printCleanLog(taskId, scanCount, cost, blocks);

                        long currentSerial = 0;
                        List<String> texts = List.of();
                        if (blocks != null) {
                            currentSerial = findMaxSerialNumber(blocks);
                            texts = blocks.stream().map(TextBlock::getText).toList();
                        }

                        long currentBaseline = GLOBAL_BASELINE_SERIAL.get();
                        boolean serialChanged = isReliableSerialChange(currentSerial, currentBaseline);
                        boolean amountMatched = containsExactAmount(texts, amountStr);
                        boolean receiptContextMatched = hasReceiptContext(texts);
                        int currentTextFingerprint = fingerprintTexts(texts);
                        boolean textChangedSinceTaskStart = currentTextFingerprint != startTextFingerprint;
                        boolean shouldAcceptPayment = shouldAcceptPayment(
                                currentSerial,
                                currentBaseline,
                                amountMatched,
                                receiptContextMatched,
                                currentTextFingerprint,
                                startTextFingerprint
                        );

                        logger.debug("[{}] 🧭 判定: serial={} baseline={} serialChanged={} amountMatched={} receiptContext={} textChanged={} currentFp={} startFp={}",
                                taskId,
                                currentSerial,
                                currentBaseline,
                                serialChanged,
                                amountMatched,
                                receiptContextMatched,
                                textChangedSinceTaskStart,
                                currentTextFingerprint,
                                startTextFingerprint);

                        if (serialChanged) {
                            logger.info("[{}] ⚡ 捕获变动: 基准 #{} -> 当前 #{}", taskId, currentBaseline, currentSerial);
                            GLOBAL_BASELINE_SERIAL.set(currentSerial);
                        }

                        if (shouldAcceptPayment) {
                            logger.info("[{}] ✅✅✅ 支付成功: 单号 #{} | 金额 ¥{} | serialChanged={} | textChanged={}",
                                    taskId,
                                    currentSerial,
                                    amountStr,
                                    serialChanged,
                                    textChangedSinceTaskStart);
                            return true;
                        }

                        if (serialChanged) {
                            logger.warn("[{}] ⚠️ 忽略: 单号变动 #{} 但未满足支付判定 (amountMatched={}, receiptContext={}) -> 基准已更新",
                                    taskId,
                                    currentSerial,
                                    amountMatched,
                                    receiptContextMatched);
                        }

                    } finally {
                        deleteTempFile(tempFile);
                    }

                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (Exception e) {
                    logger.error("[{}] 监控循环异常", taskId, e);
                }
            }

            refreshBaselineOnTimeout(taskId);
            logger.info("[{}] ⏰ 监控超时", taskId);
            return false;

        } finally {
            // 【新增】无论成功失败或异常，必须减少活跃计数
            activeTaskCount.decrementAndGet();
        }
    }

    /**
     * 程序关闭时调用，优雅关闭线程池
     */
    public void shutdown() {
        scheduler.shutdownNow();
        logger.info("Monitor service shutdown.");
    }

    // --- 以下代码与原逻辑保持一致 ---

    private void initGlobalBaseline() {
        long current = scanCurrentSerial();
        GLOBAL_BASELINE_SERIAL.set(current);
        logger.info("🏁 服务启动 | 全局基准初始化完成: #{}", current);
    }

    private void refreshBaselineOnTimeout(String taskId) {
        try {
            long currentScreenSerial = scanCurrentSerial();
            long oldBaseline = GLOBAL_BASELINE_SERIAL.getAndSet(currentScreenSerial);
            logger.info("[{}] 🔄 超时刷新基准: #{} -> #{}", taskId, oldBaseline, currentScreenSerial);
        } catch (Exception e) {
            logger.error("[{}] 超时刷新基准失败", taskId, e);
        }
    }

    private long scanCurrentSerial() {
        Path tempFile = null;
        try {
            BufferedImage frame = captureROI();
            tempFile = Files.createTempFile("scan_", ".png");
            ImageIO.write(frame, "png", tempFile.toFile());

            OcrResult result = engine.runOcr(tempFile.toAbsolutePath().toString());
            if (result != null && result.getTextBlocks() != null) {
                long val = findMaxSerialNumber(result.getTextBlocks());
                return (val == -1) ? 0 : val;
            }
        } catch (Exception e) {
            logger.error("OCR Scan Error", e);
        } finally {
            deleteTempFile(tempFile);
        }
        return 0;
    }

    private long findMaxSerialNumber(List<TextBlock> blocks) {
        Pattern pattern = Pattern.compile("第(\\d+)笔");
        long max = -1;
        for (TextBlock block : blocks) {
            String text = block.getText().replaceAll("\\s+", "");
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                try {
                    long val = Long.parseLong(matcher.group(1));
                    if (val > max) max = val;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return max;
    }

    private boolean checkAmountMatch(List<TextBlock> blocks, String target) {
        List<String> texts = blocks.stream().map(TextBlock::getText).toList();
        return containsExactAmount(texts, target);
    }

    static boolean containsExactAmount(List<String> texts, String target) {
        if (texts == null || texts.isEmpty()) return false;

        String normalizedTarget = normalizeAmount(target);
        if (normalizedTarget == null) return false;

        for (String text : candidateAmountTexts(texts)) {
            if (text == null || text.isBlank()) continue;

            String normalizedText = normalizeOcrText(text);
            Matcher matcher = AMOUNT_PATTERN.matcher(normalizedText);
            while (matcher.find()) {
                String candidate = normalizeAmount(matcher.group(1));
                if (normalizedTarget.equals(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean hasReceiptContext(List<String> texts) {
        if (texts == null || texts.isEmpty()) return false;
        String normalizedText = normalizeOcrText(String.join("", texts));
        return normalizedText.contains("收款到账通知")
                || (normalizedText.contains("收款金额")
                && (normalizedText.contains("收款成功")
                || normalizedText.contains("已存入零钱")
                || normalizedText.contains("今日第")))
                || (normalizedText.contains("微信支付") && normalizedText.contains("收款"));
    }

    static boolean isReliableSerialChange(long currentSerial, long currentBaseline) {
        return currentSerial > 0 && currentSerial != currentBaseline;
    }

    static boolean shouldAcceptPayment(
            long currentSerial,
            long currentBaseline,
            boolean amountMatched,
            boolean receiptContextMatched,
            int currentTextFingerprint,
            int startTextFingerprint
    ) {
        if (!amountMatched || !receiptContextMatched) {
            return false;
        }
        if (isReliableSerialChange(currentSerial, currentBaseline)) {
            return true;
        }
        return currentTextFingerprint != 0 && currentTextFingerprint != startTextFingerprint;
    }

    static int fingerprintTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) return 0;
        String normalized = texts.stream()
                .filter(text -> text != null && !text.isBlank())
                .map(WeChatMonitorService::normalizeOcrText)
                .collect(Collectors.joining("|"));
        return normalized.hashCode();
    }

    static String formatAmount(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static List<String> candidateAmountTexts(List<String> texts) {
        String joined = String.join("", texts);
        if (joined.isBlank()) return texts;
        return java.util.stream.Stream.concat(texts.stream(), java.util.stream.Stream.of(joined)).toList();
    }

    private static String normalizeOcrText(String text) {
        return text.replace('，', '.')
                .replace('。', '.')
                .replaceAll("\\s+", "");
    }

    private static String normalizeAmount(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String cleaned = raw.trim()
                    .replace(',', '.')
                    .replaceAll("[^0-9.]", "");
            if (cleaned.isBlank()) return null;
            return new BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP).toPlainString();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int scanCurrentTextFingerprint(String taskId) {
        Path tempFile = null;
        try {
            BufferedImage frame = captureROI();
            tempFile = Files.createTempFile("scan_start_", ".png");
            ImageIO.write(frame, "png", tempFile.toFile());

            OcrResult result = engine.runOcr(tempFile.toAbsolutePath().toString());
            List<TextBlock> blocks = (result != null) ? result.getTextBlocks() : null;
            if (blocks == null) return 0;
            List<String> texts = blocks.stream().map(TextBlock::getText).toList();
            int fingerprint = fingerprintTexts(texts);
            logger.debug("[{}] 🧭 任务启动 OCR 基准 -> fp={} [{}]",
                    taskId,
                    fingerprint,
                    texts.stream().map(String::trim).collect(Collectors.joining(" | ")));
            return fingerprint;
        } catch (Exception e) {
            logger.debug("[{}] 任务启动 OCR 基准采集失败，后续使用首轮扫描兜底", taskId, e);
            return 0;
        } finally {
            deleteTempFile(tempFile);
        }
    }

    private void performWarmUp() {
        try {
            Path temp = Files.createTempFile("warmup_", ".png");
            BufferedImage empty = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
            ImageIO.write(empty, "png", temp.toFile());
            engine.runOcr(temp.toAbsolutePath().toString());
            deleteTempFile(temp);
        } catch (Exception ignored) {
        }
    }

    private void printCleanLog(String taskId, int count, long cost, List<TextBlock> blocks) {
        if (blocks == null) return;
        String keyInfo = blocks.stream()
                .map(TextBlock::getText)
                .map(String::trim)
                .filter(t -> t.matches(".*\\d.*") || t.contains("收款"))
                .collect(Collectors.joining(" | "));

        if (!keyInfo.isEmpty()) {
            logger.debug("[{}] 📸 #{} [Scan] 耗时{}ms -> [{}]", taskId, count, cost, keyInfo);
        }
    }

    private BufferedImage captureROI() {
        int x = (int) screenSize.getWidth() - ROI_WIDTH;
        int y = (int) screenSize.getHeight() - ROI_HEIGHT;
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        return robot.createScreenCapture(new Rectangle(x, y, ROI_WIDTH, ROI_HEIGHT));
    }

    private void deleteTempFile(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
            }
        }
    }
}
