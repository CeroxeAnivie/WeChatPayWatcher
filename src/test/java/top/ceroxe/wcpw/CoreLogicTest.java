package top.ceroxe.wcpw;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import com.benjaminwan.ocrlibrary.Point;
import com.benjaminwan.ocrlibrary.TextBlock;

public class CoreLogicTest {

    public void testAmountMatchingRejectsSubstringFalsePositive() {
        assertFalse(
                WeChatMonitorService.containsExactAmount(List.of(), "1.00"),
                "空 OCR 文本不能命中任何金额"
        );
        assertFalse(
                WeChatMonitorService.containsExactAmount(List.of("微信支付 收款到账 ￥11.00"), "1.00"),
                "目标 1.00 不能命中实际文本 11.00"
        );
        assertFalse(
                WeChatMonitorService.containsExactAmount(List.of("收款到账通知", "收款金额", "￥0.01"), "1.00"),
                "目标 1.00 不能局部命中实际金额 0.01 中的数字 1"
        );
        assertFalse(
                WeChatMonitorService.containsExactAmount(List.of("微信支付 收款到账 ￥10.01"), "0.01"),
                "目标 0.01 不能命中实际文本 10.01"
        );
        assertFalse(
                WeChatMonitorService.containsExactAmount(List.of("收款到账通知", "收款金额", "￥100.00"), "1.00"),
                "目标 1.00 不能命中完整金额 100.00"
        );
        assertFalse(
                WeChatMonitorService.containsExactAmount(List.of("收款到账 100元"), "1.00"),
                "目标 1.00 不能命中完整金额 100元"
        );
    }

    public void testAmountMatchingAcceptsOnlyCompleteAmountToken() {
        assertTrue(
                WeChatMonitorService.containsExactAmount(List.of("微信支付 收款到账 ￥1.00 第123笔"), "1.00"),
                "完整金额 token 必须可以命中"
        );
        assertTrue(
                WeChatMonitorService.containsExactAmount(List.of("收款到账 1,50元"), "1.50"),
                "OCR 常见中文逗号小数点必须被规范化"
        );
        assertTrue(
                WeChatMonitorService.containsExactAmount(List.of("￥", "8.64"), "8.64"),
                "金额符号和数字被 OCR 拆成多个块时仍必须命中完整金额"
        );
        assertFalse(
                WeChatMonitorService.containsExactAmount(List.of("第100笔"), "1.00"),
                "流水号纯数字不能被当成金额"
        );
        assertFalse(
                WeChatMonitorService.containsExactAmount(
                        List.of("收款到账通知", "收款金额", "￥0.1", "今日第1笔收款，收款成功"),
                        "1.00"
                ),
                "目标 1.00 不能被收款流水号第1笔伪命中"
        );
        List<String> screenshotLikeReceipt = List.of(
                "收款到账通知",
                "收款金额",
                "￥0.10",
                "汇总",
                "今日第2笔收款，共计￥1.10",
                "备注",
                "收款成功，已存入零钱"
        );
        assertTrue(
                WeChatMonitorService.containsExactAmount(screenshotLikeReceipt, "0.10"),
                "本次收款金额必须从收款金额区域识别"
        );
        assertFalse(
                WeChatMonitorService.containsExactAmount(screenshotLikeReceipt, "1.10"),
                "累计汇总金额不能被当成本次收款金额"
        );
    }

    public void testSerialChangeRequiresPositiveSerial() {
        assertFalse(WeChatMonitorService.isReliableSerialChange(-1, 42), "未识别流水号不能触发变动");
        assertFalse(WeChatMonitorService.isReliableSerialChange(0, 42), "流水号 0 不能触发变动");
        assertFalse(WeChatMonitorService.isReliableSerialChange(42, 42), "相同流水号不能触发变动");
        assertTrue(WeChatMonitorService.isReliableSerialChange(43, 42), "正向新流水号必须触发变动");
    }

    public void testWechatReceiptCounterIsTheOnlyNewPaymentBaseline() {
        assertFalse(WeChatMonitorService.isReliableSerialChange(2, 2),
                "微信今日第 X 笔未变化时，即使金额相同也必须继续等待");
        assertTrue(WeChatMonitorService.isReliableSerialChange(3, 2),
                "微信计数递增必须认定为新账");
        assertTrue(WeChatMonitorService.isReliableSerialChange(1, 9),
                "微信跨天自行回到第 1 笔时必须认定为新账");
    }

    public void testSecurityPolicyRejectsNonFiniteMoneyAndCallbackUserInfo() {
        SecurityPolicy policy = new SecurityPolicy();
        DTOs.PaymentRequest nonFinite = new DTOs.PaymentRequest(
                "token", Double.NaN, "1768156200000", "https://example.com/callback?oid=ORDER"
        );
        assertTrue(policy.validatePaymentRequest(nonFinite) != null,
                "非有限金额不能进入监控任务");

        DTOs.PaymentRequest userInfoUrl = new DTOs.PaymentRequest(
                "token", 1.00, "1768156200000", "https://user:secret@example.com/callback?oid=ORDER"
        );
        assertTrue(policy.validatePaymentRequest(userInfoUrl) != null,
                "callbackUrl 不应携带用户信息");

        DTOs.PaymentRequest subCentAmount = new DTOs.PaymentRequest(
                "token", 1.001, "1768156200000", "https://example.com/callback?oid=ORDER"
        );
        assertTrue(policy.validatePaymentRequest(subCentAmount) != null,
                "超过两位小数的金额不能先四舍五入后进入 OCR 比较");
    }

    public void testSecurityPolicyAcceptsConfiguredCallbackShape() {
        SecurityPolicy policy = new SecurityPolicy();
        DTOs.PaymentRequest request = new DTOs.PaymentRequest(
                "token", 1.39, "1768156200000",
                "http://127.0.0.1:47891/api/callback?oid=ORDER&tenant=demo"
        );
        assertTrue(policy.validatePaymentRequest(request) == null,
                "受信任调用方指定的 HTTP callbackUrl 应保持可用");
    }

    public void testCallbackPolicyUsesThreeAttemptsWithConfiguredDelay() {
        CallbackClient client = new CallbackClient();
        assertEquals(3, client.maxAttempts(), "回调总次数硬上限必须是 3");
        assertEquals(2_000L, client.retryDelayMillis(), "两次回调之间默认必须等待 2000ms");
    }

    public void testLargestReceiptAmountWinsOverCumulativeSummary() {
        List<TextBlock> blocks = List.of(
                textBlock("收款金额", 100, 120),
                textBlock("￥0.10", 130, 190),
                textBlock("今日第2笔收款，共计￥1.10", 220, 240)
        );
        assertTrue(
                WeChatMonitorService.containsExactAmountInBlocks(blocks, "0.10"),
                "大字号的本次收款金额必须被选中"
        );
        assertFalse(
                WeChatMonitorService.containsExactAmountInBlocks(blocks, "1.10"),
                "小字号的累计汇总金额不能被选中"
        );

        List<TextBlock> oneCentBlocks = List.of(
                textBlock("收款金额", 100, 120),
                textBlock("￥0.01", 130, 190),
                textBlock("今日第1笔收款，共计￥0.01", 220, 240)
        );
        assertFalse(
                WeChatMonitorService.containsExactAmountInBlocks(oneCentBlocks, "1.00"),
                "生产 OCR 文本框路径必须按完整金额比较，不能用 0.01 中的数字 1 命中 1.00"
        );

        assertFalse(
                WeChatMonitorService.containsExactAmountInBlocks(
                        List.of(textBlock("￥0.10", 130, 190), textBlock("共计￥1.10", 220, 240)),
                        "0.10"
                ),
                "缺少收款金额标签时必须拒绝当前帧，不能扫描整个 ROI 猜测主金额"
        );
    }

    public void testDurableStoreKeepsDuplicateTaskIdsAsSeparateRecords() throws Exception {
        Path queueDir = Files.createTempDirectory("wcpw-callback-queue-test-");
        try {
            DurableCallbackStore store = new DurableCallbackStore(queueDir);
            store.save(task("record-a", "ORDER-001"));
            store.save(task("record-b", "ORDER-001"));

            List<DTOs.DurableCallbackTask> tasks = store.loadAll();
            assertEquals(2, tasks.size(), "相同 taskId 的两条回调不能互相覆盖");

            Set<String> recordIds = tasks.stream()
                    .map(DTOs.DurableCallbackTask::recordId)
                    .collect(Collectors.toSet());
            assertTrue(recordIds.contains("record-a"), "第一条回调记录必须保留");
            assertTrue(recordIds.contains("record-b"), "第二条回调记录必须保留");

            store.delete(tasks.stream()
                    .filter(task -> "record-a".equals(task.recordId()))
                    .findFirst()
                    .orElseThrow());

            List<DTOs.DurableCallbackTask> remaining = store.loadAll();
            assertEquals(1, remaining.size(), "按 recordId 删除不能误删同 taskId 的其他回调");
            assertEquals("record-b", remaining.get(0).recordId(), "剩余记录必须是未删除的 recordId");
        } finally {
            deleteRecursively(queueDir);
        }
    }

    public void testDurableStoreBackfillsRecordIdForLegacyQueueFiles() throws Exception {
        Path queueDir = Files.createTempDirectory("wcpw-callback-legacy-test-");
        try {
            String legacyJson = """
                    {
                      "taskId": "ORDER-LEGACY",
                      "callbackUrl": "http://127.0.0.1/callback?oid=ORDER-LEGACY",
                      "payload": {
                        "oid": "ORDER-LEGACY",
                        "status": "SUCCESS",
                        "requestTimestamp": "1768156200000",
                        "detectTimestamp": 1768156201000,
                        "amount": 1.0,
                        "message": "SUCCESS"
                      }
                    }
                    """;
            Files.writeString(queueDir.resolve("ORDER-LEGACY.json"), legacyJson, StandardCharsets.UTF_8);

            DurableCallbackStore store = new DurableCallbackStore(queueDir);
            List<DTOs.DurableCallbackTask> tasks = store.loadAll();

            assertEquals(1, tasks.size(), "旧版持久化记录必须能被加载");
            assertEquals("ORDER-LEGACY", tasks.get(0).recordId(), "旧版记录必须从文件名回填 recordId");

            store.delete(tasks.get(0));
            assertEquals(0, store.loadAll().size(), "回填 recordId 后必须能删除旧版队列文件");
        } finally {
            deleteRecursively(queueDir);
        }
    }

    public void testDurableStorePersistsAttemptsAndMovesExhaustedTaskOutOfActiveQueue() throws Exception {
        Path queueDir = Files.createTempDirectory("wcpw-callback-attempt-test-");
        try {
            DurableCallbackStore store = new DurableCallbackStore(queueDir);
            DTOs.DurableCallbackTask attempted = task("record-attempt", "ORDER-ATTEMPT").withAttemptsMade(2);
            store.save(attempted);

            DTOs.DurableCallbackTask loaded = store.loadAll().get(0);
            assertEquals(2, loaded.attemptsMade(), "回调尝试次数必须持久化，重启后不能重新获得完整次数");

            long retryAt = System.currentTimeMillis() + 2_000L;
            store.save(loaded.withAttemptState(2, retryAt));
            DTOs.DurableCallbackTask scheduled = store.loadAll().get(0);
            assertEquals(retryAt, scheduled.nextAttemptAt(), "失败后的下一次允许重试时间必须持久化");

            store.markFailed(scheduled.withAttemptState(3, 0L));
            assertEquals(0, store.loadAll().size(), "耗尽次数的记录不能继续留在活动队列");
            assertTrue(
                    Files.exists(queueDir.resolve("failed").resolve("record-attempt.json")),
                    "耗尽次数的记录必须保留在失败队列供人工核查"
            );
        } finally {
            deleteRecursively(queueDir);
        }
    }

    private DTOs.DurableCallbackTask task(String recordId, String taskId) {
        DTOs.CallbackPayload payload = new DTOs.CallbackPayload(
                taskId,
                "SUCCESS",
                "1768156200000",
                1768156201000L,
                1.00,
                "SUCCESS"
        );
        return new DTOs.DurableCallbackTask(recordId, taskId, "http://127.0.0.1/callback?oid=" + taskId, payload);
    }

    private TextBlock textBlock(String text, int topY, int bottomY) {
        ArrayList<Point> points = new ArrayList<>(List.of(
                new Point(10, topY),
                new Point(100, topY),
                new Point(100, bottomY),
                new Point(10, bottomY)
        ));
        return new TextBlock(points, 1.0f, 0, 1.0f, 0.0, text, new float[0], 0.0, 0.0);
    }

    private void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    private void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " | expected=" + expected + ", actual=" + actual);
        }
    }

    private void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
