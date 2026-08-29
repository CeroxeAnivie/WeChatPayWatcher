package top.ceroxe.wcpw;

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
import java.util.OptionalLong;
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
    private static final Pattern RECEIPT_SERIAL_PATTERN = Pattern.compile("第\\d+笔");

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
            this.engine = InferenceEngine.getInstance(Model.ONNX_PPOCR_V4);

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
                OptionalLong currentSerialSnapshot = scanCurrentSerial();
                if (currentSerialSnapshot.isEmpty()) {
                    logger.warn("🛡️ 兜底基准检测未识别到今日第 X 笔，本次不更新基准");
                    return;
                }
                long currentSerial = currentSerialSnapshot.getAsLong();

                // 只有在真的发生变化时才记录，避免刷日志
                if (oldBaseline != currentSerial) {
                    GLOBAL_BASELINE_SERIAL.set(currentSerial);
                    logger.info("🛡️ 兜底基准已更新: #{} -> #{}", oldBaseline, currentSerial);
                } else {
                    logger.debug("🛡️ 兜底检测完成：基准未发生变化 (#{})", currentSerial);
                }
            } catch (Exception e) {
                logger.error("🛡️ 兜底基准检测发生异常 | reason={}", LogSupport.describe(e));
            }
        }, 1, 1, TimeUnit.HOURS); // 延迟1小时启动，每1小时执行一次
    }

    public OptionalLong beginMonitoringTask(String taskId) {
        activeTaskCount.incrementAndGet();
        OptionalLong baseline = scanCurrentSerial();
        if (baseline.isEmpty()) {
            activeTaskCount.decrementAndGet();
            return OptionalLong.empty();
        }
        GLOBAL_BASELINE_SERIAL.set(baseline.getAsLong());
        logger.info("[{}] 🔒 订单专属基线已建立: 微信今日第 {} 笔", taskId, baseline.getAsLong());
        return baseline;
    }

    public void cancelPreparedTask(String taskId) {
        activeTaskCount.updateAndGet(current -> Math.max(0, current - 1));
        logger.warn("[{}] 已取消尚未启动的监控任务", taskId);
    }

    public boolean monitorPayment(
            String taskId,
            double targetAmount,
            long timeoutSeconds,
            long requestBaseline
    ) {
        try {
            long endTime = System.currentTimeMillis() + (timeoutSeconds * 1000L);
            String amountStr = formatAmount(targetAmount);

            logger.info("[{}] 👁️ 监控启动 | 当前微信计数基准: #{} | 目标: ¥{}",
                    taskId,
                    requestBaseline,
                    amountStr);

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
                        if (blocks != null) {
                            currentSerial = findMaxSerialNumber(blocks);
                        }

                        long currentBaseline = GLOBAL_BASELINE_SERIAL.get();
                        boolean serialChanged = isReliableSerialChange(currentSerial, currentBaseline);
                        if (!serialChanged) continue;

                        boolean amountMatched = containsExactAmountInBlocks(blocks, amountStr);
                        GLOBAL_BASELINE_SERIAL.set(currentSerial);
                        logger.info("[{}] ⚡ 微信计数发生变化: 基准 #{} -> 当前 #{} | amountMatched={}",
                                taskId, currentBaseline, currentSerial, amountMatched);

                        if (amountMatched) {
                            logger.info("[{}] ✅✅✅ 支付成功: 微信今日第 {} 笔 | 金额 ¥{} | 基准已更新",
                                    taskId, currentSerial, amountStr);
                            return true;
                        }

                        logger.warn("[{}] ⚠️ 新账金额不符: 微信今日第 {} 笔 | 期望 ¥{} | 基准已更新，继续等待",
                                taskId, currentSerial, amountStr);

                    } finally {
                        deleteTempFile(tempFile);
                    }

                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (Exception e) {
                    logger.error("[{}] 监控循环异常 | reason={}", taskId, LogSupport.describe(e));
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
        long current = scanCurrentSerial().orElse(0L);
        GLOBAL_BASELINE_SERIAL.set(current);
        logger.info("🏁 服务启动 | 全局基准初始化完成: #{}", current);
    }

    private void refreshBaselineOnTimeout(String taskId) {
        try {
            OptionalLong currentSnapshot = scanCurrentSerial();
            if (currentSnapshot.isEmpty()) {
                logger.warn("[{}] 超时刷新基准跳过：未识别到今日第 X 笔", taskId);
                return;
            }
            long currentScreenSerial = currentSnapshot.getAsLong();
            long oldBaseline = GLOBAL_BASELINE_SERIAL.getAndSet(currentScreenSerial);
            logger.info("[{}] 🔄 超时刷新基准: #{} -> #{}", taskId, oldBaseline, currentScreenSerial);
        } catch (Exception e) {
            logger.error("[{}] 超时刷新基准失败 | reason={}", taskId, LogSupport.describe(e));
        }
    }

    private OptionalLong scanCurrentSerial() {
        Path tempFile = null;
        try {
            BufferedImage frame = captureROI();
            tempFile = Files.createTempFile("scan_", ".png");
            ImageIO.write(frame, "png", tempFile.toFile());

            OcrResult result = engine.runOcr(tempFile.toAbsolutePath().toString());
            if (result != null && result.getTextBlocks() != null) {
                long val = findMaxSerialNumber(result.getTextBlocks());
                if (val > 0) return OptionalLong.of(val);
            }
        } catch (Exception e) {
            logger.error("OCR Scan Error | reason={}", LogSupport.describe(e));
        } finally {
            deleteTempFile(tempFile);
        }
        return OptionalLong.empty();
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

    static boolean containsExactAmount(List<String> texts, String target) {
        if (texts == null || texts.isEmpty()) return false;

        String normalizedTarget = normalizeAmount(target);
        if (normalizedTarget == null) return false;

        String normalizedJoined = normalizeOcrText(String.join("", texts));
        if (normalizedJoined.contains("收款金额")) {
            // The notification contains both the current payment and an
            // accumulated total. Once the primary label is present, scanning
            // the entire notification would make the accumulated value look
            // like the current payment.
            String primaryRegion = extractPrimaryAmountRegion(normalizedJoined);
            return containsAmountToken(primaryRegion, normalizedTarget);
        }

        String primaryBeforeSummary = extractBeforeSummary(normalizedJoined);
        if (!primaryBeforeSummary.equals(normalizedJoined)) {
            return containsAmountToken(primaryBeforeSummary, normalizedTarget);
        }

        for (String text : candidateAmountTexts(texts)) {
            if (text == null || text.isBlank()) continue;

            String normalizedText = removeReceiptSerials(normalizeOcrText(text));
            if (containsAmountToken(normalizedText, normalizedTarget)) {
                return true;
            }
        }
        return false;
    }

    static boolean containsExactAmountInBlocks(List<TextBlock> blocks, String target) {
        if (blocks == null || blocks.isEmpty()) return false;

        String normalizedTarget = normalizeAmount(target);
        if (normalizedTarget == null) return false;

        TextBlock amountLabel = blocks.stream()
                .filter(block -> block != null && normalizeOcrText(block.getText()).contains("收款金额"))
                .min(java.util.Comparator.comparingInt(WeChatMonitorService::topY))
                .orElse(null);
        if (amountLabel == null) {
            // “最大字号金额”依赖微信金额标签建立可靠上下文。标签缺失时宁可等待下一帧，
            // 不能退回扫描整个 ROI，以免把累计金额或其他窗口数字当成本次金额。
            return false;
        }

        int labelBottom = bottomY(amountLabel);
        List<AmountObservation> observations = new java.util.ArrayList<>();
        for (TextBlock block : blocks) {
            if (block == null) continue;
            String text = removeReceiptSerials(normalizeOcrText(block.getText()));
            Matcher matcher = AMOUNT_PATTERN.matcher(text);
            while (matcher.find()) {
                if (!isAmountCandidate(text, matcher.start(), matcher.end(), matcher.group(1))) continue;
                String value = normalizeAmount(matcher.group(1));
                if (value == null) continue;
                observations.add(new AmountObservation(
                        value,
                        Math.max(0, bottomY(block) - topY(block)),
                        Math.abs(topY(block) - labelBottom),
                        hasCurrencyContext(text, matcher.start(), matcher.end())
                ));
            }
        }

        if (!observations.isEmpty()) {
            // On this fixed bottom-right receipt layout, the current amount is
            // rendered in the largest type. Font height is therefore the
            // decisive signal; label proximity and currency context only
            // break ties between equally sized OCR boxes.
            AmountObservation primary = observations.stream()
                    .max(java.util.Comparator.comparingInt(AmountObservation::height)
                            .thenComparing(AmountObservation::currencyContext)
                            .thenComparing(AmountObservation::distanceFromLabel,
                                    java.util.Comparator.reverseOrder()))
                    .orElseThrow();
            return normalizedTarget.equals(primary.value());
        }

        // OCR engines may omit or merge boxes. The textual fallback still
        // stops at the summary section and therefore cannot use its total.
        return containsExactAmount(blocks.stream().map(TextBlock::getText).toList(), target);
    }

    private static boolean containsAmountToken(String text, String normalizedTarget) {
        if (text == null || text.isBlank()) return false;
        String amountText = removeReceiptSerials(text);
        Matcher matcher = AMOUNT_PATTERN.matcher(amountText);
        while (matcher.find()) {
            if (!isAmountCandidate(amountText, matcher.start(), matcher.end(), matcher.group(1))) {
                continue;
            }
            String candidate = normalizeAmount(matcher.group(1));
            if (normalizedTarget.equals(candidate)) return true;
        }
        return false;
    }

    private static String extractPrimaryAmountRegion(String normalizedText) {
        int start = normalizedText.indexOf("收款金额") + "收款金额".length();
        int end = summaryStart(normalizedText, start);
        return normalizedText.substring(start, end);
    }

    private static String extractBeforeSummary(String normalizedText) {
        return normalizedText.substring(0, summaryStart(normalizedText, 0));
    }

    private static int summaryStart(String normalizedText, int fromIndex) {
        int end = normalizedText.length();
        String[] summaryMarkers = {"汇总", "备注", "今日第", "共计", "收款小账本"};
        for (String marker : summaryMarkers) {
            int markerIndex = normalizedText.indexOf(marker, fromIndex);
            if (markerIndex >= 0 && markerIndex < end) end = markerIndex;
        }
        return end;
    }

    private static int topY(TextBlock block) {
        return block.getBoxPoint().stream().mapToInt(point -> point.getY()).min().orElse(Integer.MAX_VALUE);
    }

    private static int bottomY(TextBlock block) {
        return block.getBoxPoint().stream().mapToInt(point -> point.getY()).max().orElse(Integer.MIN_VALUE);
    }

    static boolean isReliableSerialChange(long currentSerial, long currentBaseline) {
        return currentSerial > 0 && currentSerial != currentBaseline;
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
        if (text == null) return "";
        return text.replace('，', '.')
                .replace('。', '.')
                .replaceAll("\\s+", "");
    }

    private static String removeReceiptSerials(String text) {
        return RECEIPT_SERIAL_PATTERN.matcher(text).replaceAll("");
    }

    /**
     * Bare integers are ambiguous in a receipt (serial numbers, dates, etc.).
     * Accept them only when the OCR text gives currency context; decimal tokens
     * remain valid without a currency symbol because OCR may split the symbol
     * into a separate block.
     */
    private static boolean isAmountCandidate(String text, int start, int end, String raw) {
        boolean hasDecimalSeparator = raw.indexOf('.') >= 0 || raw.indexOf(',') >= 0;
        if (hasDecimalSeparator) return true;

        int contextStart = Math.max(0, start - 4);
        int contextEnd = Math.min(text.length(), end + 4);
        String context = text.substring(contextStart, contextEnd);
        return context.contains("￥") || context.contains("¥") || context.contains("元");
    }

    private static boolean hasCurrencyContext(String text, int start, int end) {
        int contextStart = Math.max(0, start - 4);
        int contextEnd = Math.min(text.length(), end + 4);
        String context = text.substring(contextStart, contextEnd);
        return context.contains("￥") || context.contains("¥") || context.contains("元");
    }

    private record AmountObservation(String value, int height, int distanceFromLabel, boolean currencyContext) {
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
