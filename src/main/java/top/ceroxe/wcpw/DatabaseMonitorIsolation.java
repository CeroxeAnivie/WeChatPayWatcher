package top.ceroxe.wcpw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Optional;

/**
 * 独立数据库检测入口：不启动 HTTP、订单、鉴权或回调，只验证微信收款消息检测链路。
 * 可通过 `java -cp WeChatPayWatcher-3.0.0.jar top.ceroxe.wcpw.DatabaseMonitorIsolation` 运行。
 */
public final class DatabaseMonitorIsolation {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseMonitorIsolation.class);

    private DatabaseMonitorIsolation() { }

    public static void main(String[] args) {
        Options options = Options.parse(args);
        try (PaymentDatabaseMonitor monitor = new PaymentDatabaseMonitor(options.path(), options.queryLimit())) {
            boolean initialized = initializeExistingDatabase(monitor);
            if (!initialized) monitor.prepareForTask();

            long deadline = System.nanoTime() + Duration.ofSeconds(options.timeoutSeconds()).toNanos();
            long nextWaitLog = 0L;
            logger.info("隔离检测已启动 | 仅数据库检测 | intervalMs={} | timeoutSeconds={} | source={}",
                    options.intervalMillis(), options.timeoutSeconds(), monitor.sourceDescription());
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                try {
                    Optional<PaymentDatabaseMonitor.Change> change = monitor.findNewChange();
                    if (change.isPresent()) {
                        PaymentDatabaseMonitor.PaymentEvent event = change.get().event();
                        logger.info("隔离检测发现收款 | transactionId={} | amount=¥{} | paidAt={}",
                                event.transactionId(), event.amount(), event.paidAt());
                        System.out.println("DETECTED payment transactionId=" + event.transactionId()
                                + " amount=" + event.amount() + " paidAt=" + event.paidAt());
                    }
                } catch (RuntimeException e) {
                    long now = System.currentTimeMillis();
                    if (now >= nextWaitLog) {
                        logger.info("隔离检测等待微信收款数据库或下一条消息 | reason={}", LogSupport.describe(e));
                        nextWaitLog = now + 10_000L;
                    }
                }
                sleep(options.intervalMillis());
            }
            logger.info("隔离检测结束 | 原因=超时或收到停止信号");
        }
    }

    private static boolean initializeExistingDatabase(PaymentDatabaseMonitor monitor) {
        try {
            monitor.validate();
            logger.info("隔离检测已建立历史高水位；启动前消息不会触发");
            return true;
        } catch (RuntimeException e) {
            logger.info("启动时尚未发现可读收款数据库，将等待微信后续生成 | reason={}", LogSupport.describe(e));
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Options(Path path, int queryLimit, long intervalMillis, long timeoutSeconds) {
        static Options parse(String[] args) {
            Path path = null;
            int queryLimit = 128;
            long interval = 1000L;
            long timeout = 3600L;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--path" -> path = Paths.get(requireValue(args, ++i, "--path"));
                    case "--query-limit" -> queryLimit = Integer.parseInt(requireValue(args, ++i, "--query-limit"));
                    case "--interval-ms" -> interval = Long.parseLong(requireValue(args, ++i, "--interval-ms"));
                    case "--timeout-seconds" -> timeout = Long.parseLong(requireValue(args, ++i, "--timeout-seconds"));
                    default -> throw new IllegalArgumentException("未知参数: " + args[i]);
                }
            }
            return new Options(path, Math.max(1, Math.min(queryLimit, 2000)),
                    Math.max(100L, interval), Math.max(1L, timeout));
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) throw new IllegalArgumentException(option + " 缺少参数值");
            return args[index];
        }
    }
}
