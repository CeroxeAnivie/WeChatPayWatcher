package fun.ceroxe.wcpw;

import com.google.gson.Gson;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

public class CallbackClient {
    private static final Logger logger = LoggerFactory.getLogger(CallbackClient.class);
    private static final Gson gson = new Gson();
    private final OkHttpClient client;
    private final int maxRetries;
    private final long retryIntervalMs;
    private final String callbackSecret;

    public CallbackClient() {
        this.maxRetries = AppConfig.getInt("callback.retry.count", 3);
        this.retryIntervalMs = AppConfig.getInt("callback.retry.interval.ms", 2000);
        this.callbackSecret = AppConfig.get("callback.secret"); // 读取签名密钥
        this.client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    public void sendCallback(String taskId, String originalUrl, DTOs.CallbackPayload payload) {
        // 1. 准备参数
        String finalUrl = signAndBuildUrl(originalUrl, payload);

        // 2. 依然发送 JSON Body，但 NAS 主要靠 URL 参数验签
        String json = gson.toJson(payload);
        RequestBody body = RequestBody.create(json, MediaType.get("application/json; charset=utf-8"));

        Request request = new Request.Builder().url(finalUrl).post(body).build();

        logger.info("[{}] 📤 发起回调 -> {} (Sign Generated)", taskId, finalUrl);

        int attempt = 0;
        boolean success = false;

        while (attempt < maxRetries && !success) {
            attempt++;
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    logger.info("[{}] ✅ 回调成功 (第{}次): HTTP 200", taskId, attempt);
                    success = true;
                } else {
                    logger.warn("[{}] ⚠️ 回调失败 (第{}次): HTTP {}", taskId, attempt, response.code());
                }
            } catch (Exception e) {
                logger.warn("[{}] ⚠️ 回调网络异常 (第{}次): {}", taskId, attempt, e.getMessage());
            }

            if (!success && attempt < maxRetries) {
                try {
                    Thread.sleep(retryIntervalMs);
                } catch (InterruptedException ignored) {
                }
            }
        }
        if (!success) logger.error("[{}] ❌ 回调彻底失败", taskId);
    }

    /**
     * 核心签名逻辑
     * 1. 提取 URL 中的 oid
     * 2. 组合参数 (oid, money, status, timestamp)
     * 3. 排序 -> 拼接 Secret -> MD5
     * 4. 返回带签名的新 URL
     */
    private String signAndBuildUrl(String url, DTOs.CallbackPayload payload) {
        try {
            // 使用 TreeMap 进行自动键值排序 (NAS 的验签要求)
            Map<String, String> params = new TreeMap<>();

            // A. 解析原 URL 中的 oid
            String oid = null;
            URI uri = new URI(url);
            String query = uri.getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2 && "oid".equals(kv[0])) {
                        oid = kv[1];
                        params.put("oid", oid);
                    }
                }
            }

            // B. 加入业务参数
            // 注意：必须保证金额格式与 NAS 收到的一致 (字符串)
            String moneyStr = String.format("%.2f", payload.amount());
            String timeStr = String.valueOf(System.currentTimeMillis());

            params.put("money", moneyStr);
            params.put("status", payload.status());
            params.put("timestamp", timeStr);

            // C. 拼接签名串: k=v&k=v...&key=SECRET
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    sb.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
                }
            }
            sb.append("key=").append(callbackSecret);

            // D. 计算 MD5
            String sign = md5(sb.toString()).toUpperCase();

            // E. 重新构造 URL (追加参数)
            String separator = url.contains("?") ? "&" : "?";
            return url + separator +
                    "money=" + moneyStr +
                    "&status=" + payload.status() +
                    "&timestamp=" + timeStr +
                    "&sign=" + sign;

        } catch (Exception e) {
            logger.error("签名生成失败", e);
            return url; // 降级：发送原始 URL (必然会被 NAS 拒绝，保证安全)
        }
    }

    private String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] array = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : array) {
                sb.append(Integer.toHexString((b & 0xFF) | 0x100).substring(1, 3));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}