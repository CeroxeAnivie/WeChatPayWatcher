package fun.ceroxe.wcpw;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
                WeChatMonitorService.containsExactAmount(List.of("微信支付 收款到账 ￥10.01"), "0.01"),
                "目标 0.01 不能命中实际文本 10.01"
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
    }

    public void testSerialChangeRequiresPositiveSerial() {
        assertFalse(WeChatMonitorService.isReliableSerialChange(-1, 42), "未识别流水号不能触发变动");
        assertFalse(WeChatMonitorService.isReliableSerialChange(0, 42), "流水号 0 不能触发变动");
        assertFalse(WeChatMonitorService.isReliableSerialChange(42, 42), "相同流水号不能触发变动");
        assertTrue(WeChatMonitorService.isReliableSerialChange(43, 42), "正向新流水号必须触发变动");
    }

    public void testPaymentDecisionAllowsChangedReceiptWithoutNewSerial() {
        List<String> receiptTexts = List.of(
                "收款到账通知",
                "收款金额",
                "￥",
                "8.64",
                "今日第1笔收款，共计￥8.64",
                "收款成功，已存入零钱。点击可查看详情"
        );

        assertTrue(WeChatMonitorService.hasReceiptContext(receiptTexts), "微信收款通知上下文必须被识别");
        assertTrue(
                WeChatMonitorService.shouldAcceptPayment(1, 1, true, true, 200, 100),
                "流水号未变化但 OCR 文本相对任务启动基准已变化时，应允许确认支付"
        );
        assertFalse(
                WeChatMonitorService.shouldAcceptPayment(1, 1, true, true, 100, 100),
                "流水号和 OCR 文本都未变化时，不能把旧通知当成新支付"
        );
        assertFalse(
                WeChatMonitorService.shouldAcceptPayment(1, 1, true, false, 200, 100),
                "缺少收款通知上下文时不能仅凭金额确认"
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
