package fun.ceroxe.wcpw;

import com.google.gson.Gson;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public class CallbackClient {
    private static final Logger logger = LoggerFactory.getLogger(CallbackClient.class);
    private static final Gson gson = new Gson();
    private final OkHttpClient client;
    private final int maxRetries;
    private final long retryIntervalMs;

    public CallbackClient() {
        this.maxRetries = AppConfig.getInt("callback.retry.count", 3);
        this.retryIntervalMs = AppConfig.getInt("callback.retry.interval.ms", 2000);
        this.client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    // 增加了 taskId 参数
    public void sendCallback(String taskId, String url, DTOs.CallbackPayload payload) {
        String json = gson.toJson(payload);
        RequestBody body = RequestBody.create(json, MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder().url(url).post(body).build();

        logger.info("[{}] 📤 发起回调 -> {} (Status: {})", taskId, url, payload.status());

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
}