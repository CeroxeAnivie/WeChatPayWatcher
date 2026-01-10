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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class WeChatMonitorService {
    private static final Logger logger = LoggerFactory.getLogger(WeChatMonitorService.class);

    // 【修改1】缩小区域，只覆盖微信弹窗，避开多余干扰
    private static final int ROI_WIDTH = 380;
    private static final int ROI_HEIGHT = 450;

    // 【修改2】调高阈值，忽略 VNC 噪点，0.05 代表 5% 的像素变化才触发
    private static final double MOTION_THRESHOLD = 0.05;

    // 【优化参数 2】强制扫描间隔 (安全阀)
    // 即使画面完全静止，每隔 5000ms (5秒) 也会强制执行一次 OCR，防止任何潜在的漏判
    private static final long FORCE_SCAN_INTERVAL_MS = 20000;

    private final InferenceEngine engine;
    private final Robot robot;
    private final Dimension screenSize;

    private long baselineSerialNum = -1;
    private BufferedImage lastFrame = null;
    private long lastScanTime = 0;

    public WeChatMonitorService() {
        try {
            System.setProperty("java.awt.headless", "false");
            this.robot = new Robot();
            this.screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            // 这里的日志现在会显得很干净
            this.engine = InferenceEngine.getInstance(Model.ONNX_PPOCR_V3);
            logger.info("✅ OCR 引擎初始化完毕 (高性能+心跳兜底模式)");
        } catch (Exception e) {
            throw new RuntimeException("OCR Init Failed", e);
        }
    }

    public boolean monitorPayment(String taskId, double targetAmount, long timeoutSeconds) {
        long endTime = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        String amountStr = String.format("%.2f", targetAmount);
        String amountNoDot = amountStr.replace(".", "");

        logger.info("[{}] 👁️ 监控启动 | 目标金额: ¥{}", taskId, amountStr);

        // 重置状态
        baselineSerialNum = -1;
        lastFrame = null;
        lastScanTime = 0;

        int scanCount = 0;
        int skipCount = 0;

        while (System.currentTimeMillis() < endTime) {
            Path tempFile = null;
            try {
                if (Thread.currentThread().isInterrupted()) return false;
                scanCount++;

                // 1. 内存截图
                BufferedImage currentFrame = captureROI();
                long now = System.currentTimeMillis();

                // 2. 【核心优化逻辑】
                // 只有当 (画面变了) 或者 (距离上次扫描超过了强制间隔) 时，才执行 OCR
                boolean isMotionDetected = (lastFrame == null) || isFrameChanged(lastFrame, currentFrame);
                boolean isForceScan = (now - lastScanTime) > FORCE_SCAN_INTERVAL_MS;

                if (!isMotionDetected && !isForceScan) {
                    // 画面静止，且没到强制扫描时间 -> 跳过！
                    skipCount++;
                    Thread.sleep(500); // 省 CPU
                    continue;
                }

                // 更新状态
                lastFrame = currentFrame;
                lastScanTime = now;

                // 3. 写文件 (仅在需要扫描时发生)
                tempFile = Files.createTempFile("ocr_", ".png");
                ImageIO.write(currentFrame, "png", tempFile.toFile());

                // 4. 执行 OCR
                long t1 = System.currentTimeMillis();
                OcrResult result = engine.runOcr(tempFile.toAbsolutePath().toString());
                long cost = System.currentTimeMillis() - t1;

                if (result != null && result.getTextBlocks() != null) {
                    List<TextBlock> blocks = result.getTextBlocks();

                    // 打印日志 (带上触发原因：Motion 或 Force)
                    String triggerReason = isForceScan ? "Heartbeat" : "Motion";
                    printCleanLog(taskId, scanCount, skipCount, cost, triggerReason, blocks);

                    skipCount = 0; // 重置跳过计数

                    long currentSerial = findSerialNumber(blocks);
                    if (currentSerial != -1) {
                        if (baselineSerialNum == -1) {
                            baselineSerialNum = currentSerial;
                            logger.info("[{}] 🔒 锁定基准单号: #{}", taskId, baselineSerialNum);
                        } else if (currentSerial > baselineSerialNum) {
                            logger.info("[{}] ⚡ 发现新订单! #{} -> #{}", taskId, baselineSerialNum, currentSerial);
                            baselineSerialNum = currentSerial;

                            if (checkAmountMatch(blocks, amountStr, amountNoDot)) {
                                logger.info("[{}] ✅✅✅ 金额匹配成功: ¥{}", taskId, amountStr);
                                return true;
                            } else {
                                logger.warn("[{}] ⚠️ 金额不符 (期望: ¥{})", taskId, amountStr);
                            }
                        }
                    }
                }

                Thread.sleep(800);

            } catch (Exception e) {
                logger.error("[{}] 监控异常", taskId, e);
            } finally {
                if (tempFile != null) try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                }
            }
        }

        logger.info("[{}] ⏰ 监控超时", taskId);
        return false;
    }

    /**
     * 网格采样比对，性能极高
     */
    private boolean isFrameChanged(BufferedImage imgA, BufferedImage imgB) {
        if (imgA.getWidth() != imgB.getWidth() || imgA.getHeight() != imgB.getHeight()) return true;

        int width = imgA.getWidth();
        int height = imgA.getHeight();
        long diffPixels = 0;
        long totalSampled = 0;
        int step = 4; // 采样步长

        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                totalSampled++;
                if (imgA.getRGB(x, y) != imgB.getRGB(x, y)) {
                    diffPixels++;
                }
            }
        }
        return ((double) diffPixels / totalSampled) > MOTION_THRESHOLD;
    }

    private void printCleanLog(String taskId, int count, int skipCount, long cost, String reason, List<TextBlock> blocks) {
        String keyInfo = blocks.stream()
                .map(TextBlock::getText)
                .map(String::trim)
                .filter(t -> t.matches(".*\\d.*") || t.contains("收款") || t.contains("￥") || t.contains("¥"))
                .collect(Collectors.joining(" | "));

        if (!keyInfo.isEmpty()) {
            String skipMsg = skipCount > 0 ? " (跳过" + skipCount + "帧)" : "";
            // 日志里会显示是 [Motion] 触发还是 [Heartbeat] 触发
            logger.info("[{}] 📸 #{}{} [{}] 耗时{}ms -> [{}]", taskId, count, skipMsg, reason, cost, keyInfo);
        }
    }

    private BufferedImage captureROI() {
        int x = (int) screenSize.getWidth() - ROI_WIDTH;
        int y = (int) screenSize.getHeight() - ROI_HEIGHT;
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        return robot.createScreenCapture(new Rectangle(x, y, ROI_WIDTH, ROI_HEIGHT));
    }

    private long findSerialNumber(List<TextBlock> blocks) {
        Pattern pattern = Pattern.compile("第(\\d+)笔");
        for (TextBlock block : blocks) {
            String text = block.getText().replaceAll("\\s+", "");
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                try {
                    return Long.parseLong(matcher.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    private boolean checkAmountMatch(List<TextBlock> blocks, String target, String targetNoDot) {
        for (TextBlock block : blocks) {
            String clean = block.getText().replaceAll("[^0-9.]", "");
            if (clean.equals(target) || clean.contains(target) || clean.equals(targetNoDot)) return true;
        }
        return false;
    }
}