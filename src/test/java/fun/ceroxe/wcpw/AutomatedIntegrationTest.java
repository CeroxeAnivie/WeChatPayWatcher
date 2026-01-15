package fun.ceroxe.wcpw;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import okhttp3.*;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;

public class AutomatedIntegrationTest {

    // ================= 配置区域 =================

    // 目标地址 (WCPW 服务端地址)
    private static final String TARGET_URL = "http://127.0.0.1:9090/";

    // 鉴权 Token (必须与服务端 config.properties [auth.token] 一致)
    private static final String AUTH_TOKEN = "YOUR_API_ACCESS_TOKEN";

    // 签名密钥 (必须与服务端 config.properties [callback.secret] 一致)
    private static final String CALLBACK_SECRET = "YOUR_SHARED_SECRET_KEY";

    // 本机监听端口 (用于接收回调)
    private static final int LOCAL_LISTEN_PORT = 47891;

    // 告诉服务端的公网回调地址
    // 注意：如果 WCPW 在云端，这里必须填你的公网 IP；如果在本地，填 http://127.0.0.1:端口
    private static final String PUBLIC_CALLBACK_BASE = "http://p.ceroxe.fun:" + LOCAL_LISTEN_PORT + "/notify";

    // ===========================================

    private static final OkHttpClient client = getUnsafeOkHttpClient();
    private static final Gson gson = new Gson();

    public static void main(String[] args) throws IOException, InterruptedException {
        System.out.println("==========================================");
        System.out.println("   微信支付守卫 - 全链路安全集成测试 (v2.0)");
        System.out.println("==========================================");
        System.out.println("目标服务: " + TARGET_URL);
        System.out.println("本地监听: " + LOCAL_LISTEN_PORT);
        System.out.println("------------------------------------------");

        Scanner scanner = new Scanner(System.in);
        System.out.print(">>> 请输入测试金额 (例如 0.01): ");
        double amount;
        try {
            amount = scanner.nextDouble();
        } catch (Exception e) {
            System.err.println("输入无效！");
            return;
        }

        // 生成一个测试用的订单号
        String testOid = "TEST_" + System.currentTimeMillis();
        // 构造带 oid 的回调地址 (WCPW 签名逻辑强依赖 oid)
        String finalCallbackUrl = PUBLIC_CALLBACK_BASE + "?oid=" + testOid;

        // 1. 启动本地回调监听服务器
        HttpServer callbackServer = HttpServer.create(new InetSocketAddress(LOCAL_LISTEN_PORT), 0);
        callbackServer.createContext("/notify", exchange -> {
            try {
                String query = exchange.getRequestURI().getQuery();
                String body = new String(exchange.getRequestBody().readAllBytes());

                System.out.println("\n\n[📨 收到回调] ==============================");
                System.out.println("URL Params: " + query);
                System.out.println("Body JSON : " + body);

                // 解析参数
                Map<String, String> params = parseQueryParams(query);

                // === 核心：执行本地验签 ===
                System.out.println("------------------------------------------");
                System.out.println("🔐 正在进行安全签名校验...");

                if (verifySignature(params)) {
                    System.out.println("✅ [校验通过] 签名匹配！服务端身份合法。");
                    System.out.println("   服务端 Sign: " + params.get("sign"));
                } else {
                    System.err.println("❌ [校验失败] 签名不匹配！可能是伪造请求或密钥配置错误。");
                    System.err.println("   服务端 Sign: " + params.get("sign"));
                }
                System.out.println("==========================================\n");

                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("{\"code\":200}".getBytes());
            } catch (Exception e) {
                e.printStackTrace();
                exchange.sendResponseHeaders(500, 0);
            } finally {
                exchange.close();
            }
        });
        callbackServer.start();
        System.out.println(">>> 本地监听已启动，等待回调...");

        // 2. 发送请求给 WCPW
        System.out.println(">>> 正在发送监控请求 (OID: " + testOid + ")...");
        sendPaymentRequest(amount, finalCallbackUrl);

        // 3. 等待
        System.out.println(">>> ⏳ 请现在手动触发微信收款 (金额: " + amount + ")");
        System.out.println(">>> (程序将在 120秒 后超时退出)");

        Thread.sleep(120000);

        callbackServer.stop(0);
        System.out.println(">>> 测试结束 (超时)");
        System.exit(0);
    }

    private static void sendPaymentRequest(double money, String callbackUrl) {
        new Thread(() -> {
            try {
                DTOs.PaymentRequest req = new DTOs.PaymentRequest(
                        AUTH_TOKEN,
                        money,
                        String.valueOf(System.currentTimeMillis()),
                        callbackUrl
                );

                Request request = new Request.Builder()
                        .url(TARGET_URL)
                        .post(RequestBody.create(gson.toJson(req), MediaType.get("application/json")))
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    System.out.println("   --> 请求发送状态: " + response.code());
                    if (response.body() != null) {
                        System.out.println("   --> 响应: " + response.body().string());
                    }
                }
            } catch (Exception e) {
                System.err.println("   --> 发送失败: " + e.getMessage());
            }
        }).start();
    }

    // ================== 验签工具方法 ==================

    private static boolean verifySignature(Map<String, String> params) {
        if (!params.containsKey("sign")) {
            System.err.println("   [Error] 回调参数中缺少 sign 字段");
            return false;
        }

        String incomingSign = params.get("sign");

        // 1. 排除 sign 字段，其余字段按 ASCII 排序
        Map<String, String> sortedParams = new TreeMap<>(params);
        sortedParams.remove("sign");

        // 2. 拼接 k=v&k=v...&key=SECRET
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sortedParams.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
            }
        }
        sb.append("key=").append(CALLBACK_SECRET);

        // 3. 计算 MD5
        String calculatedSign = md5(sb.toString()).toUpperCase();

        System.out.println("   [Debug] 本地计算签名串: " + sb);
        System.out.println("   [Debug] 本地计算 Hash : " + calculatedSign);

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
        } catch (Exception e) { return ""; }
    }

    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                // URL Decode
                String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                String val = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                map.put(key, val);
            }
        }
        return map;
    }

    // ================== SSL 绕过工具 ==================

    private static OkHttpClient getUnsafeOkHttpClient() {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[]{}; }
                    }
            };
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .build();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}