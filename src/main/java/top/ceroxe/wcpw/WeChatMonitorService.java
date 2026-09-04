package top.ceroxe.wcpw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Payment monitor backed by WeChat's SQLite/WCDB message store. */
public final class WeChatMonitorService {
    private static final Logger logger = LoggerFactory.getLogger(WeChatMonitorService.class);

    private final PaymentDatabaseMonitor databaseMonitor;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger activeTaskCount = new AtomicInteger();
    private final long pollIntervalMillis;

    public WeChatMonitorService() { this(PaymentDatabaseMonitor.fromConfig()); }

    WeChatMonitorService(PaymentDatabaseMonitor databaseMonitor) {
        this.databaseMonitor = databaseMonitor;
        this.pollIntervalMillis = Math.max(100L, AppConfig.getLong("wechat.db.poll.interval.ms", 1000L));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Payment-Database-Guard");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::guardDatabase, pollIntervalMillis, pollIntervalMillis, TimeUnit.MILLISECONDS);
    }

    public void beginMonitoringTask(String taskId) {
        activeTaskCount.incrementAndGet();
        databaseMonitor.prepareForTask();
        logger.info("[{}] 新支付事件监听任务已登记 | source={}", taskId, databaseMonitor.sourceDescription());
    }

    public void cancelPreparedTask(String taskId) {
        activeTaskCount.updateAndGet(current -> Math.max(0, current - 1));
        databaseMonitor.cancelTaskPreparation();
        logger.warn("[{}] 已取消尚未提交的数据库监控任务", taskId);
    }

    public boolean monitorPayment(String taskId, double targetAmount, long timeoutSeconds) {
        String target = formatAmount(targetAmount);
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        try {
            logger.info("[{}] 新支付事件匹配启动 | 目标金额=¥{}", taskId, target);
            while (System.nanoTime() < deadline) {
                if (Thread.currentThread().isInterrupted()) return false;
                try {
                    Optional<PaymentDatabaseMonitor.Change> event = databaseMonitor.findNewChange();
                    if (event.isPresent()) {
                        PaymentDatabaseMonitor.Change change = event.get();
                        PaymentDatabaseMonitor.PaymentEvent payment = change.event();
                        boolean amountMatched = payment.amount().compareTo(new BigDecimal(target)) == 0;
                        logger.info("[{}] 检测到新收款消息 | transactionId={} | amount=¥{} | amountMatched={}",
                                taskId, payment.transactionId(), payment.amount(), amountMatched);
                        if (amountMatched) {
                            logger.info("[{}] 支付确认成功 | transactionId={} | amount=¥{}",
                                    taskId, payment.transactionId(), payment.amount());
                            return true;
                        }
                    }
                    Thread.sleep(pollIntervalMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (PaymentDatabaseMonitor.DatabaseBusyException e) {
                    logger.debug("[{}] 数据库正忙或被锁定，将在轮询间隔后重试", taskId);
                    sleepAfterDatabaseError();
                } catch (RuntimeException e) {
                    logger.error("[{}] 数据库轮询失败 | reason={}", taskId, LogSupport.describe(e));
                    sleepAfterDatabaseError();
                }
            }
            logger.info("[{}] 数据库监控超时", taskId);
            return false;
        } finally {
            activeTaskCount.updateAndGet(current -> Math.max(0, current - 1));
        }
    }

    private void guardDatabase() {
        try {
            if (activeTaskCount.get() == 0) {
                databaseMonitor.observeNewRows();
            }
        } catch (RuntimeException e) {
            logger.warn("数据库例行检查失败 | reason={}", LogSupport.describe(e));
        }
    }

    private void sleepAfterDatabaseError() {
        try { Thread.sleep(pollIntervalMillis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public void shutdown() {
        scheduler.shutdownNow();
        databaseMonitor.close();
        logger.info("微信数据库监控服务已关闭");
    }

    static String formatAmount(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
