package top.ceroxe.wcpw;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class Application {
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(Application.class);
    private static final Gson gson = new Gson();

    private static final AtomicBoolean isPending = new AtomicBoolean(false);
    private static final AtomicLong currentTaskEndTime = new AtomicLong(0);

    private static final ExecutorService monitorExecutor = Executors.newSingleThreadExecutor();
    private static final ThreadPoolExecutor callbackExecutor = new ThreadPoolExecutor(
            1,
            2,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100),
            Executors.defaultThreadFactory(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );
    private static final Set<String> callbacksInFlight = ConcurrentHashMap.newKeySet();
    private static final Map<String, DTOs.DurableCallbackTask> volatileCallbackQueue = new ConcurrentHashMap<>();

    private static WeChatMonitorService monitorService;
    private static CallbackClient callbackClient;
    private static DurableCallbackStore callbackStore;
    private static SecurityPolicy securityPolicy;

    public static void main(String[] args) {
        initLogging();

        Security.addProvider(new BouncyCastleProvider());
        AppConfig.init();
        securityPolicy = new SecurityPolicy();

        try {
            logger.info("⚙️ 正在启动微信数据库监控...");
            monitorService = new WeChatMonitorService();
        } catch (Throwable e) {
            logger.error("❌ 微信数据库监控启动失败 | reason={}", LogSupport.describe(e));
            System.exit(1);
        }

        callbackClient = new CallbackClient();
        callbackStore = new DurableCallbackStore();
        recoverPersistedCallbacksOnStartup();
        startUndertowServer();
    }

    private static void initLogging() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        try {
            loggerContext.getLogger("io.undertow").setLevel(Level.INFO);
            loggerContext.getLogger("org.xnio").setLevel(Level.INFO);
        } catch (Exception ignored) {
        }

        try {
            File logDir = new File("logs");
            if (!logDir.exists()) logDir.mkdirs();
            String timeStr = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
            String logFilePath = "logs" + File.separator + "log_" + timeStr + ".log";

            PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(loggerContext);
            encoder.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
            encoder.start();

            FileAppender<ILoggingEvent> fileAppender = new FileAppender<>();
            fileAppender.setContext(loggerContext);
            fileAppender.setName("FILE_APPENDER");
            fileAppender.setFile(logFilePath);
            fileAppender.setEncoder(encoder);
            fileAppender.start();

            Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
            rootLogger.addAppender(fileAppender);
            System.out.println("📄 日志文件已创建: " + logFilePath);
        } catch (Exception e) {
            System.err.println("❌ 初始化日志文件失败: " + e.getMessage());
        }
    }

    private static void startUndertowServer() {
        int port = AppConfig.getInt("server.port");
        String certPathStr = AppConfig.get("ssl.cert.path");
        String keyPathStr = AppConfig.get("ssl.key.path");

        SSLContext sslContext = null;
        try {
            if (certPathStr != null && !certPathStr.isBlank() && keyPathStr != null && !keyPathStr.isBlank()) {
                Path certPath = Paths.get(certPathStr);
                Path keyPath = Paths.get(keyPathStr);
                if (Files.exists(certPath) && Files.exists(keyPath)) {
                    sslContext = createSSLContext(keyPath, certPath);
                }
            }
        } catch (Exception e) {
            logger.error("❌ SSL 加载失败 | reason={}", LogSupport.describe(e));
            System.exit(1);
        }

        Undertow.Builder builder = Undertow.builder();
        String bindHost = AppConfig.get("server.bind.host", "0.0.0.0");
        if (sslContext != null) {
            builder.addHttpsListener(port, bindHost, sslContext);
            logger.info("🚀 服务启动 (HTTPS) {}:{}", bindHost, port);
        } else {
            builder.addHttpListener(port, bindHost);
            logger.info("🚀 服务启动 (HTTP) {}:{}", bindHost, port);
        }

        // 核心逻辑逻辑：定义业务处理器
        HttpHandler businessHandler = new HttpHandler() {
            @Override
            public void handleRequest(HttpServerExchange exchange) throws Exception {
                // Undertow 习惯用法：如果是 IO 线程则分发到 Worker 线程，以防阻塞 IO
                if (exchange.isInIoThread()) {
                    exchange.dispatch(this);
                    return;
                }
                if (exchange.getRequestMethod().equalToString("POST")) {
                    handlePaymentRequest(exchange);
                } else {
                    exchange.setStatusCode(405);
                }
            }
        };

        // 应用安全延迟包装器 (纯升级，无副作用)
        builder.setHandler(new SecurityDelayHandler(businessHandler));

        Undertow server = builder.build();
        server.start();
        logger.info("✅ 微信支付守卫已就绪 | 等待请求...");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            if (monitorService != null) monitorService.shutdown();
            monitorExecutor.shutdownNow();
            callbackExecutor.shutdownNow();
        }));
    }

    private static void handlePaymentRequest(HttpServerExchange exchange) {
        try {
            exchange.startBlocking();
            String body = readRequestBody(exchange, securityPolicy.maxRequestBodyBytes());
            DTOs.PaymentRequest req;
            try {
                req = gson.fromJson(body, DTOs.PaymentRequest.class);
            } catch (JsonSyntaxException e) {
                sendJson(exchange, 400, new DTOs.BaseResponse("ERROR", "Invalid JSON", null));
                return;
            }

            String validationError = securityPolicy.validatePaymentRequest(req);
            if (validationError != null) {
                logger.warn("⚠️ [API] 参数无效: {}", validationError);
                sendJson(exchange, 400, new DTOs.BaseResponse("ERROR", "Invalid Parameters", null));
                return;
            }

            String serverToken = AppConfig.get("wcpw.request_token");
            if (!securityPolicy.matchesToken(serverToken, req.token())) {
                logger.warn("⛔ [API] 鉴权失败 | IP: {}", exchange.getSourceAddress());
                sendJson(exchange, 401, new DTOs.BaseResponse("UNAUTHORIZED", "Invalid Token", null));
                return;
            }

            if (isPending.compareAndSet(false, true)) {
                int timeoutSec = securityPolicy.normalizeOrderTimeoutSeconds(
                        AppConfig.getInt("order.timeout.seconds", 120));

                String taskId = extractOid(req.callbackUrl());
                try {
                    monitorService.beginMonitoringTask(taskId);
                } catch (RuntimeException e) {
                    isPending.set(false);
                    currentTaskEndTime.set(0L);
                    logger.error("❌ [API] 微信数据库监控任务未启动 | taskId={} | reason={}", taskId, LogSupport.describe(e));
                    sendJson(exchange, 503,
                            new DTOs.BaseResponse("ERROR", "Payment Monitor Unavailable", null));
                    return;
                }

                currentTaskEndTime.set(System.currentTimeMillis() + (timeoutSec * 1000L));
                logger.info("📥 [API] 接收任务 [{}] | 目标: ¥{} | 回调主机: {}",
                        taskId, req.money(), callbackHost(req.callbackUrl()));

                try {
                    monitorExecutor.submit(() -> runMonitorTask(taskId, req, timeoutSec));
                } catch (RuntimeException e) {
                    monitorService.cancelPreparedTask(taskId);
                    isPending.set(false);
                    currentTaskEndTime.set(0L);
                    throw e;
                }

                sendJson(exchange, 200, new DTOs.BaseResponse("READY", "Monitoring Started", null));
            } else {
                long timeLeft = currentTaskEndTime.get() - System.currentTimeMillis();
                int waitSec = (timeLeft > 0) ? (int) (timeLeft / 1000) + 1 : 0;
                logger.info("⏳ [API] 系统忙碌，拒绝新请求 (剩余 {}s)", waitSec);
                sendJson(exchange, 200, new DTOs.BaseResponse("PENDING", "System Busy", new DTOs.PendingData(waitSec)));
            }
        } catch (RequestTooLargeException e) {
            // The helper has already sent the 413 response.
        } catch (Exception e) {
            logger.error("❌ [API] 内部错误 | reason={}", LogSupport.describe(e));
            isPending.set(false);
            sendJson(exchange, 500, new DTOs.BaseResponse("ERROR", e.getMessage(), null));
        }
    }

    private static String extractOid(String url) {
        try {
            okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(url);
            if (parsed != null) {
                String oid = parsed.queryParameter("oid");
                if (oid != null && !oid.isBlank()) return oid;
            }
        } catch (Exception ignored) {
            // SecurityPolicy validates the URL before this method is called.
        }
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static void runMonitorTask(
            String taskId,
            DTOs.PaymentRequest req,
            int timeoutSec
    ) {
        try {
            boolean success = monitorService.monitorPayment(taskId, req.money(), timeoutSec);
            String status = success ? "SUCCESS" : "TIMEOUT";

            DTOs.CallbackPayload payload = new DTOs.CallbackPayload(
                    taskId,
                    status,
                    req.timestamp(),
                    System.currentTimeMillis(),
                    req.money(),
                    status
            );

            DTOs.DurableCallbackTask task = new DTOs.DurableCallbackTask(
                    UUID.randomUUID().toString(),
                    taskId,
                    req.callbackUrl(),
                    payload
            );
            persistAndDispatchCallback(task, true);

        } catch (Exception e) {
            logger.error("💥 [API] 任务执行崩溃 | taskId={} | reason={}", taskId, LogSupport.describe(e));
            isPending.set(false);
        }
    }

    private static void persistAndDispatchCallback(DTOs.DurableCallbackTask task, boolean releasePaymentLock) {
        boolean durable = false;
        try {
            callbackStore.save(task);
            durable = true;
        } catch (Exception e) {
            // 落盘失败后仍保留进程内重试队列。它不能替代磁盘持久化，但能避免一次 IO 抖动
            // 直接吞掉已经确认的支付结果。
            volatileCallbackQueue.put(task.recordId(), task);
            logger.error("🚨 [Callback] 持久化失败，已转入进程内重试队列 | taskId={} | recordId={} | reason={}",
                    task.taskId(), task.recordId(), LogSupport.describe(e));
        }

        boolean durableRecord = durable;
        callbackExecutor.submit(() -> dispatchCallback(task, releasePaymentLock, durableRecord));
    }

    private static void recoverPersistedCallbacksOnStartup() {
        if (callbackStore == null || callbackClient == null) return;
        List<DTOs.DurableCallbackTask> pendingTasks = callbackStore.loadAll();
        if (!pendingTasks.isEmpty()) {
            logger.info("🔁 启动时发现 {} 条未完成回调，将从已记录次数继续发送", pendingTasks.size());
        }
        for (DTOs.DurableCallbackTask task : pendingTasks) {
            callbackExecutor.submit(() -> dispatchCallback(task, false, true));
        }
    }

    private static void dispatchCallback(DTOs.DurableCallbackTask task, boolean releasePaymentLock, boolean durableRecord) {
        String taskId = task.taskId();
        String recordId = task.recordId();
        if (!callbacksInFlight.add(recordId)) {
            if (releasePaymentLock) {
                isPending.set(false);
            }
            return;
        }
        try {
            int maxAttempts = callbackClient.maxAttempts();
            DTOs.DurableCallbackTask currentTask = task;

            if (currentTask.nextAttemptAt() > System.currentTimeMillis()) {
                long waitSeconds = Math.max(1L,
                        TimeUnit.MILLISECONDS.toSeconds(currentTask.nextAttemptAt() - System.currentTimeMillis()) + 1L);
                logger.info("[{}] 恢复待发送回调，将在约 {} 秒后继续 | recordId={} | attempts={}/{}",
                        taskId, waitSeconds, recordId, currentTask.attemptsMade(), maxAttempts);
                if (!callbackClient.waitUntil(taskId, currentTask.nextAttemptAt())) {
                    return;
                }
            }

            for (int attempt = Math.max(0, task.attemptsMade()) + 1; attempt <= maxAttempts; attempt++) {
                currentTask = currentTask.withAttemptState(attempt, 0L);
                if (!persistCallbackState(currentTask, durableRecord, "记录尝试次数")) {
                    return;
                }

                if (callbackClient.sendAttempt(
                        taskId,
                        currentTask.callbackUrl(),
                        currentTask.payload(),
                        attempt,
                        maxAttempts)) {
                    completeCallback(currentTask, durableRecord);
                    logger.info("✅ [Callback] 任务 [{}] 已确认送达 | recordId={} | attempts={}",
                            taskId, recordId, attempt);
                    return;
                }

                if (attempt < maxAttempts) {
                    long retryDelayMillis = callbackClient.retryDelayMillis();
                    long nextAttemptAt = System.currentTimeMillis() + retryDelayMillis;
                    currentTask = currentTask.withAttemptState(attempt, nextAttemptAt);
                    if (!persistCallbackState(currentTask, durableRecord, "记录下次重试时间")) {
                        return;
                    }
                    logger.info("[{}] 回调尝试 {}/{} 失败，将在 {}ms 后重试 | recordId={}",
                            taskId, attempt, maxAttempts, retryDelayMillis, recordId);
                    if (!callbackClient.waitUntil(taskId, nextAttemptAt)) {
                        logger.warn("[{}] 回调重试等待被中断，剩余次数将在下次启动时继续 | recordId={} | attempts={}/{}",
                                taskId, recordId, attempt, maxAttempts);
                        return;
                    }
                }
            }

            exhaustCallback(currentTask, durableRecord, maxAttempts);
        } finally {
            callbacksInFlight.remove(recordId);
            if (releasePaymentLock) {
                isPending.set(false);
                logger.info("🔓 [API] 任务 [{}] 结束，锁已释放", taskId);
            }
        }
    }

    private static boolean persistCallbackState(
            DTOs.DurableCallbackTask task,
            boolean durableRecord,
            String action
    ) {
        if (!durableRecord) {
            volatileCallbackQueue.put(task.recordId(), task);
            return true;
        }
        try {
            // 先持久化“本次机会已占用”再发送，确保进程崩溃后也不会超过总次数上限。
            callbackStore.save(task);
            return true;
        } catch (Exception e) {
            logger.error("🚨 [Callback] 无法{}，已停止发送以避免重启后重复放大 | taskId={} | recordId={} | reason={}",
                    action, task.taskId(), task.recordId(), LogSupport.describe(e));
            return false;
        }
    }

    private static void completeCallback(DTOs.DurableCallbackTask task, boolean durableRecord) {
        if (durableRecord) {
            callbackStore.delete(task);
        } else {
            volatileCallbackQueue.remove(task.recordId());
        }
    }

    private static void exhaustCallback(
            DTOs.DurableCallbackTask task,
            boolean durableRecord,
            int maxAttempts
    ) {
        if (durableRecord) {
            try {
                callbackStore.markFailed(task);
            } catch (Exception e) {
                logger.error("❌ [Callback] 已耗尽 {} 次机会，但移入失败队列失败；记录将留在原处且不会再次发送 | taskId={} | recordId={} | reason={}",
                        maxAttempts, task.taskId(), task.recordId(), LogSupport.describe(e));
                return;
            }
        } else {
            volatileCallbackQueue.remove(task.recordId());
        }
        logger.error("❌ [Callback] 已耗尽 {} 次机会，停止自动发送 | taskId={} | recordId={} | failedRecord={}",
                maxAttempts,
                task.taskId(),
                task.recordId(),
                durableRecord ? "callback_queue/failed/" + task.recordId() + ".json" : "unavailable");
    }

    private static void sendJson(HttpServerExchange exchange, int statusCode, Object responseObj) {
        exchange.setStatusCode(statusCode);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(gson.toJson(responseObj));
    }

    private static String readRequestBody(HttpServerExchange exchange, int maxBytes) throws Exception {
        try (InputStream input = exchange.getInputStream()) {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(Math.min(maxBytes, 8192));
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    sendJson(exchange, 413, new DTOs.BaseResponse("ERROR", "Request Too Large", null));
                    throw new RequestTooLargeException();
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (RequestTooLargeException e) {
            throw e;
        }
    }

    private static String callbackHost(String url) {
        okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(url);
        return parsed == null ? "<invalid>" : parsed.host();
    }

    private static final class RequestTooLargeException extends Exception {
    }

    private static SSLContext createSSLContext(Path keyPath, Path certPath) throws Exception {
        PrivateKey privateKey = null;
        try (InputStream is = Files.newInputStream(keyPath);
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
             PEMParser pemParser = new PEMParser(reader)) {
            Object object = pemParser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
            if (object instanceof PEMKeyPair) {
                privateKey = converter.getPrivateKey(((PEMKeyPair) object).getPrivateKeyInfo());
            } else if (object instanceof PrivateKeyInfo) {
                privateKey = converter.getPrivateKey((PrivateKeyInfo) object);
            }
        }
        List<Certificate> certChain = new ArrayList<>();
        try (InputStream is = Files.newInputStream(certPath);
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
             PEMParser pemParser = new PEMParser(reader)) {
            Object object;
            JcaX509CertificateConverter converter = new JcaX509CertificateConverter().setProvider("BC");
            while ((object = pemParser.readObject()) != null) {
                if (object instanceof X509CertificateHolder) {
                    certChain.add(converter.getCertificate((X509CertificateHolder) object));
                }
            }
        }
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setKeyEntry("alias", privateKey, null, certChain.toArray(new Certificate[0]));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, null);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);
        return context;
    }

    /**
     * 内部类：安全延迟处理器
     * 在处理实际业务前，强制休眠 200ms 以过滤快速扫描探测
     */
    private record SecurityDelayHandler(HttpHandler next) implements HttpHandler {

        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            // 在分发到业务逻辑之前进行延迟
            // 如果是 IO 线程，必须先 dispatch 才能 sleep，否则会阻塞 IO 循环
            if (exchange.isInIoThread()) {
                exchange.dispatch(this);
                return;
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            next.handleRequest(exchange);
        }
    }
}
