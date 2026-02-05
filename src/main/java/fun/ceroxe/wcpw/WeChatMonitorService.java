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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class WeChatMonitorService {
    private static final Logger logger = LoggerFactory.getLogger(WeChatMonitorService.class);

    // 扫描区域配置
    private static final int ROI_WIDTH = 380;
    private static final int ROI_HEIGHT = 450;

    // 【核心变更】全局基准：程序启动时确定，之后随任务结果更新
    private static final AtomicLong GLOBAL_BASELINE_SERIAL = new AtomicLong(-1);

    private final InferenceEngine engine;
    private final Robot robot;
    private final Dimension screenSize;

    public WeChatMonitorService() {
        try {
            System.setProperty("java.awt.headless", "false");
            this.robot = new Robot();
            this.screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            this.engine = InferenceEngine.getInstance(Model.ONNX_PPOCR_V3);

            // 预热
            performWarmUp();

            // 【步骤1】程序启动时，立即建立一次全局基准
            initGlobalBaseline();
        } catch (Exception e) {
            throw new RuntimeException("Monitor Service Init Failed", e);
        }
    }

    /**
     * 初始化全局基准
     * 识别屏幕当前的单号，作为所有后续任务的起点
     */
    private void initGlobalBaseline() {
        long current = scanCurrentSerial();
        GLOBAL_BASELINE_SERIAL.set(current);
        logger.info("🏁 服务启动 | 全局基准初始化完成: #{}", current);
    }

    /**
     * 核心监控逻辑：全局基准 + 变动触发 + 结果刷新
     */
    public boolean monitorPayment(String taskId, double targetAmount, long timeoutSeconds) {
        long endTime = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        String amountStr = String.format("%.2f", targetAmount);
        String amountNoDot = amountStr.replace(".", "");

        // 获取任务开始时的基准（沿用上一次任务结束时的状态）
        long startBaseline = GLOBAL_BASELINE_SERIAL.get();
        logger.info("[{}] 👁️ 监控启动 | 当前全局基准: #{} | 目标: ¥{}", taskId, startBaseline, amountStr);

        int scanCount = 0;

        while (System.currentTimeMillis() < endTime) {
            try {
                if (Thread.currentThread().isInterrupted()) return false;

                // 轮询间隔 5 秒
                Thread.sleep(5000);
                scanCount++;

                Path tempFile = null;
                BufferedImage currentFrame = captureROI();

                try {
                    // OCR 识别过程
                    tempFile = Files.createTempFile("ocr_", ".png");
                    ImageIO.write(currentFrame, "png", tempFile.toFile());

                    long t1 = System.currentTimeMillis();
                    OcrResult result = engine.runOcr(tempFile.toAbsolutePath().toString());
                    long cost = System.currentTimeMillis() - t1;

                    List<TextBlock> blocks = (result != null) ? result.getTextBlocks() : null;
                    printCleanLog(taskId, scanCount, cost, blocks);

                    // 获取当前屏幕单号
                    long currentSerial = 0;
                    if (blocks != null) {
                        currentSerial = findMaxSerialNumber(blocks);
                        if (currentSerial == -1) currentSerial = 0;
                    }

                    // 获取实时基准（注意：并发情况下可能被其他线程改变，但在单任务流中是稳定的）
                    long currentBaseline = GLOBAL_BASELINE_SERIAL.get();

                    // 【步骤2】只要基准变化（变大变小都算），就算新订单
                    if (currentSerial != currentBaseline) {
                        logger.info("[{}] ⚡ 捕获变动: 基准 #{} -> 当前 #{}", taskId, currentBaseline, currentSerial);

                        // 立即更新全局基准！
                        // 这样下一次循环（或下一个任务）就不会重复处理这个变动了
                        GLOBAL_BASELINE_SERIAL.set(currentSerial);

                        // 检查金额
                        boolean amountMatched = false;
                        if (blocks != null) {
                            amountMatched = checkAmountMatch(blocks, amountStr, amountNoDot);
                        }

                        // 【步骤3】如果匹配，返回成功（基准已在上面更新为最新屏幕状态）
                        if (amountMatched) {
                            logger.info("[{}] ✅✅✅ 支付成功: 单号 #{} | 金额 ¥{} | 基准已刷新", taskId, currentSerial, amountStr);
                            return true;
                        } else {
                            logger.warn("[{}] ⚠️ 忽略: 单号变动 #{} 但金额不符 (期望 ¥{}) -> 基准已更新，继续等待下一次变动", taskId, currentSerial, amountStr);
                            // 注意：这里我们已经更新了 GLOBAL_BASELINE_SERIAL，
                            // 所以循环继续运行时，会基于这个新单号等待下一次变化。
                        }
                    }
                    // else: 屏幕没变，继续 sleep

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

        // 【步骤4】超时处理
        // 按照要求：如果超时，也刷新基准，将现在的屏幕作为新的基准
        refreshBaselineOnTimeout(taskId);

        logger.info("[{}] ⏰ 监控超时", taskId);
        return false;
    }

    /**
     * 超时退出前，强制读取一次屏幕并更新全局基准
     * 防止下一次任务进来时，误判旧屏幕为新变动
     */
    private void refreshBaselineOnTimeout(String taskId) {
        try {
            long currentScreenSerial = scanCurrentSerial();
            long oldBaseline = GLOBAL_BASELINE_SERIAL.getAndSet(currentScreenSerial);
            logger.info("[{}] 🔄 超时刷新基准: #{} -> #{} (确保下个任务从当前状态开始)", taskId, oldBaseline, currentScreenSerial);
        } catch (Exception e) {
            logger.error("[{}] 超时刷新基准失败", taskId, e);
        }
    }

    // --- 辅助方法 ---

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
            // 忽略异常，返回0
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

    private boolean checkAmountMatch(List<TextBlock> blocks, String target, String targetNoDot) {
        for (TextBlock block : blocks) {
            String clean = block.getText().replaceAll("[^0-9.]", "");
            if (clean.equals(target) || clean.contains(target) || clean.equals(targetNoDot)) return true;
        }
        return false;
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