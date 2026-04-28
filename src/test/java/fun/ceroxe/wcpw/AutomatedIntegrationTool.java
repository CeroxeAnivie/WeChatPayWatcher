package fun.ceroxe.wcpw;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import okhttp3.*;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeMap;

/**
 * 微信支付全链条安全集成测试工具 (NAS 模拟器)
 * 覆盖：预支付请求 -> 异步回调 -> 字段提取 -> 签名算法验证 -> JSON Payload 校验
 */
public class AutomatedIntegrationTool {

    // ================= 配置区域 (请根据实际环境修改) =================

    // 1. WCPW 守卫服务的 API 地址
    private static final String WCPW_API_URL = "http://127.0.0.1:9090/";

    // 2. 鉴权 Token (对应 WCPW 的 wcpw.request_token)
    private static final String AUTH_TOKEN = "wcpw-request-token";

    // 3. 共享密钥 (对应 WCPW 的 wcpw.callback_secret 和 NAS 的 wcpw.callback_secret)
    private static final String SHARED_SECRET = "wcpw-callback-secret";

    // 4. 本机模拟监听端口
    private static final int NAS_SIMULATOR_PORT = 47891;

    // 5. 模拟公网回调地址 (WCPW 成功后会访问这里)
    private static final String CALLBACK_BASE_URL = "http://127.0.0.1:" + NAS_SIMULATOR_PORT + "/api/callback";

    // =============================================================

    private static final OkHttpClient client = getUnsafeOkHttpClient();
    private static final Gson gson = new Gson();

    public static void main(String[] args) throws IOException {
        System.out.println("🚀 [NAS 模拟器] 全链条安全测试启动...");
        System.out.println("------------------------------------------");

        Scanner scanner = new Scanner(System.in);
        System.out.print(">>> 输入模拟测试金额 (例 0.01): ");
        double amount = scanner.nextDouble();

        String testOid = "NAS_TEST_" + System.currentTimeMillis();
        // 按照 OrderService 逻辑构造初始回调地址
        String finalCallbackUrl = CALLBACK_BASE_URL + "?oid=" + testOid;

        // 1. 启动本地回调服务器 (模拟 WebServer.java)
        startNasSimulator(testOid);

        // 2. 向 WCPW 发起监控请求 (模拟 OrderService.createOrder)
        sendPaymentRequestToWcpw(testOid, amount, finalCallbackUrl);

        System.out.println("\n>>> [等待中] 请在 120 秒内完成微信扫码支付 (金额: " + amount + ")");
        System.out.println(">>> 测试程序运行中...");
    }

    private static void startNasSimulator(String expectedOid) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(NAS_SIMULATOR_PORT), 0);

        server.createContext("/api/callback", exchange -> {
            try {
                // 只接受 POST
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                // A. 提取并打印 URL 参数 (测试 WebServer 解析能力)
                String query = exchange.getRequestURI().getQuery();
                Map<String, String> params = parseQueryParams(query);

                System.out.println("\n[📨 收到回调通知]");
                System.out.println("   URL Query: " + query);

                // B. 提取 Body (测试 DTOs 兼容性)
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                DTOs.CallbackPayload payload = gson.fromJson(body, DTOs.CallbackPayload.class);
                System.out.println("   Body JSON: " + body);

                // C. 执行企业级验签 (同步 OrderService 逻辑)
                System.out.println("------------------------------------------");
                System.out.println("🔐 执行安全验签校验...");

                boolean isSignValid = verifySignature(params);
                boolean isOidValid = expectedOid.equals(params.get("oid")) && expectedOid.equals(payload.oid());

                if (isSignValid && isOidValid) {
                    System.out.println("✅ [测试通过] 签名合法，OID 链路匹配！");
                    System.out.println("   订单状态: " + payload.status());
                    System.out.println("   实收金额: " + payload.amount());
                } else {
                    System.err.println("❌ [测试失败] 校验不通过！");
                    if (!isSignValid) System.err.println("   原因: 签名不匹配 (计算结果与收到结果不符)");
                    if (!isOidValid) System.err.println("   原因: OID 链路丢失 (Expected: " + expectedOid + ")");
                }
                System.out.println("==========================================\n");

                // 回复 WCPW
                String response = "{\"code\":200,\"msg\":\"success\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length());
                exchange.getResponseBody().write(response.getBytes());

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                exchange.close();
            }
        });

        server.setExecutor(null);
        server.start();
        System.out.println(">>> NAS 模拟服务器已在端口 " + NAS_SIMULATOR_PORT + " 就绪");
    }

    private static void sendPaymentRequestToWcpw(String oid, double amount, String callback) {
        DTOs.PaymentRequest req = new DTOs.PaymentRequest(
                AUTH_TOKEN,
                amount,
                String.valueOf(System.currentTimeMillis()),
                callback
        );

        RequestBody body = RequestBody.create(gson.toJson(req), MediaType.get("application/json"));
        Request request = new Request.Builder()
                .url(WCPW_API_URL)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                System.err.println("❌ 发送监控请求失败: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                System.out.println(">>> 监控请求已发送，状态码: " + response.code());
                System.out.println(">>> 响应结果: " + response.body().string());
            }
        });
    }

    // ================== 核心验签逻辑 (必须与 OrderService 完全同步) ==================

    private static boolean verifySignature(Map<String, String> params) {
        if (!params.containsKey("sign")) return false;

        String incomingSign = params.get("sign");

        // 1. 使用 TreeMap 排序
        Map<String, String> sortedParams = new TreeMap<>(params);
        sortedParams.remove("sign");

        // 2. 拼接 k=v&...
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sortedParams.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
            }
        }

        // 3. 加上 Key (共享密钥)
        sb.append("key=").append(SHARED_SECRET);

        // 4. MD5 并转大写
        String calculatedSign = md5(sb.toString()).toUpperCase();

        System.out.println("   [验签调试] 待签名串: " + sb);
        System.out.println("   [验签调试] 计算结果: " + calculatedSign);
        System.out.println("   [验签调试] 收到签名: " + incomingSign);

        return calculatedSign.equals(incomingSign);
    }

    private static String md5(String s) {
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

    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isBlank()) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private static OkHttpClient getUnsafeOkHttpClient() {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[]{};
                        }
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
