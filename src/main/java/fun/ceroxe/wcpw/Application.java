package fun.ceroxe.wcpw;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import com.google.gson.Gson;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class Application {
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(Application.class);
    private static final Gson gson = new Gson();

    private static final AtomicBoolean isPending = new AtomicBoolean(false);
    private static final AtomicLong currentTaskEndTime = new AtomicLong(0);

    private static final ExecutorService monitorExecutor = Executors.newSingleThreadExecutor();
    private static final ExecutorService callbackExecutor = Executors.newCachedThreadPool();

    private static WeChatMonitorService monitorService;
    private static CallbackClient callbackClient;

    public static void main(String[] args) {
        initLogging();

        String currentDir = System.getProperty("user.dir");
        File tempDir = new File(currentDir, "ocr_native_libs");
        if (!tempDir.exists()) tempDir.mkdirs();
        System.setProperty("java.io.tmpdir", tempDir.getAbsolutePath());

        Security.addProvider(new BouncyCastleProvider());
        AppConfig.init();

        try {
            logger.info("⚙️ 正在启动 OCR 引擎...");
            monitorService = new WeChatMonitorService();
        } catch (Throwable e) {
            logger.error("❌ OCR 引擎启动失败 (请检查 libgomp1 / libgl1-mesa-glx)", e);
            System.exit(1);
        }

        callbackClient = new CallbackClient();
        startUndertowServer();
    }

    private static void initLogging() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        try {
            loggerContext.getLogger("io.github.mymonstercat").setLevel(Level.WARN);
            loggerContext.getLogger("com.benjaminwan.ocrlibrary").setLevel(Level.WARN);
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
            logger.error("❌ SSL 加载失败", e);
            System.exit(1);
        }

        Undertow.Builder builder = Undertow.builder();
        if (sslContext != null) {
            builder.addHttpsListener(port, "0.0.0.0", sslContext);
            logger.info("🚀 服务启动 (HTTPS) Port: {}", port);
        } else {
            builder.addHttpListener(port, "0.0.0.0");
            logger.info("🚀 服务启动 (HTTP) Port: {}", port);
        }

        builder.setHandler(new HttpHandler() {
            @Override
            public void handleRequest(HttpServerExchange exchange) throws Exception {
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
        });

        Undertow server = builder.build();
        server.start();
        logger.info("✅ 微信支付守卫已就绪 | 等待请求...");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            monitorExecutor.shutdownNow();
            callbackExecutor.shutdownNow();
        }));
    }

    private static void handlePaymentRequest(HttpServerExchange exchange) {
        try {
            exchange.startBlocking();
            String body = new String(exchange.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            DTOs.PaymentRequest req = gson.fromJson(body, DTOs.PaymentRequest.class);

            if (req == null || req.money() <= 0 || req.callbackUrl() == null) {
                logger.warn("⚠️ [API] 参数无效: {}", body);
                sendJson(exchange, 400, new DTOs.BaseResponse("ERROR", "Invalid Parameters", null));
                return;
            }

            String serverToken = AppConfig.get("auth.token");
            if (!serverToken.equals(req.token())) {
                logger.warn("⛔ [API] 鉴权失败 | IP: {} | Token: {}", exchange.getSourceAddress(), req.token());
                sendJson(exchange, 401, new DTOs.BaseResponse("UNAUTHORIZED", "Invalid Token", null));
                return;
            }

            if (isPending.compareAndSet(false, true)) {
                int timeoutSec = AppConfig.getInt("order.timeout.seconds");
                currentTaskEndTime.set(System.currentTimeMillis() + (timeoutSec * 1000L));

                // 【优化】尝试从 callbackUrl 中提取 OID 作为 taskId，方便日志对账
                String taskId = extractOid(req.callbackUrl());
                logger.info("📥 [API] 接收任务 [{}] | 目标: ¥{} | 回调: {}", taskId, req.money(), req.callbackUrl());

                monitorExecutor.submit(() -> runMonitorTask(taskId, req, timeoutSec));

                sendJson(exchange, 200, new DTOs.BaseResponse("READY", "Monitoring Started", null));
            } else {
                long timeLeft = currentTaskEndTime.get() - System.currentTimeMillis();
                int waitSec = (timeLeft > 0) ? (int) (timeLeft / 1000) + 1 : 0;
                logger.info("⏳ [API] 系统忙碌，拒绝新请求 (剩余 {}s)", waitSec);
                sendJson(exchange, 200, new DTOs.BaseResponse("PENDING", "System Busy", new DTOs.PendingData(waitSec)));
            }
        } catch (Exception e) {
            logger.error("❌ [API] 内部错误", e);
            isPending.set(false);
            sendJson(exchange, 500, new DTOs.BaseResponse("ERROR", e.getMessage(), null));
        }
    }

    // 辅助方法：提取OID
    private static String extractOid(String url) {
        try {
            if (url.contains("oid=")) {
                String[] parts = url.split("oid=");
                if (parts.length > 1) {
                    String oid = parts[1].split("&")[0];
                    if (!oid.isBlank()) return oid;
                }
            }
        } catch (Exception ignored) {
        }
        return UUID.randomUUID().toString().substring(0, 8); // 提取失败则回退到 UUID
    }

    private static void runMonitorTask(String taskId, DTOs.PaymentRequest req, int timeoutSec) {
        try {
            boolean success = monitorService.monitorPayment(taskId, req.money(), timeoutSec);

            String status = success ? "SUCCESS" : "TIMEOUT";
            // Payload 依然保留，但 Sign 逻辑在 Client 里做
            DTOs.CallbackPayload payload = new DTOs.CallbackPayload(
                    status, req.timestamp(), System.currentTimeMillis(), req.money(), status
            );

            callbackExecutor.submit(() -> {
                callbackClient.sendCallback(taskId, req.callbackUrl(), payload);
                isPending.set(false);
                logger.info("🔓 [API] 任务 [{}] 结束，锁已释放", taskId);
            });

        } catch (Exception e) {
            logger.error("💥 [API] 任务执行崩溃", e);
            isPending.set(false);
        }
    }

    private static void sendJson(HttpServerExchange exchange, int statusCode, Object responseObj) {
        exchange.setStatusCode(statusCode);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(gson.toJson(responseObj));
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
}