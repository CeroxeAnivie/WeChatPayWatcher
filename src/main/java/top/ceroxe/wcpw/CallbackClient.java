package top.ceroxe.wcpw;

import com.google.gson.Gson;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class CallbackClient {
    private static final Logger logger = LoggerFactory.getLogger(CallbackClient.class);
    private static final Gson gson = new Gson();
    private final OkHttpClient client;
    private final String callbackSecret;
    private final SecurityPolicy securityPolicy;

    public CallbackClient() {
        this.callbackSecret = AppConfig.get("wcpw.callback_secret");
        this.securityPolicy = new SecurityPolicy();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    int maxAttempts() {
        return Math.max(1, Math.min(3, AppConfig.getInt("callback.retry.count", 3)));
    }

    long retryDelayMillis() {
        return Math.max(100L, Math.min(60_000L,
                AppConfig.getInt("callback.retry.interval.ms", 2000)));
    }

    boolean sendAttempt(
            String taskId,
            String originalUrl,
            DTOs.CallbackPayload payload,
            int attempt,
            int maxAttempts
    ) {
        try {
            // 1. Parse and validate the caller-provided callback endpoint.
            HttpUrl parsedUrl = HttpUrl.parse(originalUrl);
            if (parsedUrl == null || !securityPolicy.isCallbackSchemeAllowed(parsedUrl.scheme())) {
                throw new IllegalArgumentException("Invalid callbackUrl scheme");
            }

            // 2. 准备签名参数 (必须与 NAS 端的验签算法完全一致)
            Map<String, String> params = new TreeMap<>();
            params.put("oid", payload.oid());
            params.put("money", String.format(Locale.ROOT, "%.2f", payload.amount()));
            params.put("status", payload.status());
            params.put("timestamp", String.valueOf(payload.detectTimestamp()));

            // 3. 计算签名
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
            }
            sb.append("key=").append(callbackSecret);
            String sign = sign(sb.toString());

            HttpUrl.Builder urlBuilder = parsedUrl.newBuilder();
            // Preserve caller-supplied routing parameters while replacing the
            // signed fields, preventing duplicate or stale security values.
            urlBuilder.removeAllQueryParameters("oid")
                    .removeAllQueryParameters("money")
                    .removeAllQueryParameters("status")
                    .removeAllQueryParameters("timestamp")
                    .removeAllQueryParameters("sign");
            String finalUrl = urlBuilder
                    .addQueryParameter("oid", payload.oid())
                    .addQueryParameter("money", params.get("money"))
                    .addQueryParameter("status", params.get("status"))
                    .addQueryParameter("timestamp", params.get("timestamp"))
                    .addQueryParameter("sign", sign)
                    .build()
                    .toString();

            logger.info("[{}] 📤 发起回调({}/{}) -> {}://{}{}",
                    taskId, attempt, maxAttempts, parsedUrl.scheme(), parsedUrl.host(), parsedUrl.encodedPath());

            Request request = new Request.Builder()
                    .url(finalUrl)
                    .post(RequestBody.create(gson.toJson(payload), MediaType.get("application/json")))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    logger.info("[{}] ✅ 回调成功: HTTP {}", taskId, response.code());
                    return true;
                }
                logger.warn("[{}] 回调尝试失败({}/{}) | reason=HTTP {}",
                        taskId, attempt, maxAttempts, response.code());
            }
        } catch (Exception e) {
            logger.warn("[{}] 回调尝试失败({}/{}) | reason={}",
                    taskId, attempt, maxAttempts, LogSupport.describe(e));
        }
        return false;
    }

    boolean waitUntil(String taskId, long retryAt) {
        long waitMillis = Math.max(0L, retryAt - System.currentTimeMillis());
        try {
            Thread.sleep(waitMillis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("[{}] 回调重试等待被中断", taskId);
            return false;
        }
    }

    private String sign(String canonical) throws GeneralSecurityException {
        if ("HMAC-SHA256".equals(securityPolicy.signatureAlgorithm())) {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(callbackSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))).toUpperCase(Locale.ROOT);
        }
        MessageDigest md = MessageDigest.getInstance("MD5");
        return hex(md.digest(canonical.getBytes(StandardCharsets.UTF_8))).toUpperCase(Locale.ROOT);
    }

    private String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }
}
