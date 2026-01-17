package fun.ceroxe.wcpw;

import com.google.gson.Gson;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

public class CallbackClient {
    private static final Logger logger = LoggerFactory.getLogger(CallbackClient.class);
    private static final Gson gson = new Gson();
    private final OkHttpClient client;
    private final String callbackSecret;

    public CallbackClient() {
        this.callbackSecret = AppConfig.get("callback.secret");
        this.client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    public void sendCallback(String taskId, String originalUrl, DTOs.CallbackPayload payload) {
        try {
            // 1. 提取 OID 和基础 URL
            String baseUrl = originalUrl.split("\\?")[0];

            // 2. 准备签名参数 (必须与 NAS 端的验签算法完全一致)
            Map<String, String> params = new TreeMap<>();
            params.put("oid", payload.oid());
            params.put("money", String.format("%.2f", payload.amount()));
            params.put("status", payload.status());
            params.put("timestamp", String.valueOf(payload.detectTimestamp()));

            // 3. 计算签名
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
            }
            sb.append("key=").append(callbackSecret);
            String sign = md5(sb.toString()).toUpperCase();

            // 4. 构造最终带参数的 URL
            String finalUrl = baseUrl + "?oid=" + payload.oid() +
                    "&money=" + params.get("money") +
                    "&status=" + params.get("status") +
                    "&timestamp=" + params.get("timestamp") +
                    "&sign=" + sign;

            logger.info("[{}] 📤 发起回调 -> {}", taskId, finalUrl);

            Request request = new Request.Builder()
                    .url(finalUrl)
                    .post(RequestBody.create(gson.toJson(payload), MediaType.get("application/json")))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    logger.info("[{}] ✅ 回调成功: HTTP {}", taskId, response.code());
                } else {
                    logger.error("[{}] ❌ 回调被 NAS 拒绝: HTTP {} | Body: {}", taskId, response.code(), response.body().string());
                }
            }
        } catch (Exception e) {
            logger.error("[{}] 💥 回调过程发生异常", taskId, e);
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