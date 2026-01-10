package fun.ceroxe.wcpw;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import okhttp3.*;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Scanner;

public class AutomatedIntegrationTest {

    // 目标地址 (确保端口和域名正确)
    private static final String TARGET_URL = "https://p.ceroxe.fun:58080/";

    // 【新增】鉴权 Token (必须与服务端 config.properties 保持一致)
    private static final String AUTH_TOKEN = "ceroxe-secret-8888";

    // 回调配置 (本机监听端口 + 告诉服务端的公网回调地址)
    private static final int LOCAL_CALLBACK_PORT = 9090;
    // 注意：如果你是在本地跑测试，服务端在云端，服务端必须能访问到这个 IP
    private static final String PUBLIC_CALLBACK_URL = "http://p.ceroxe.fun:47891/notify";

    // 忽略 SSL 验证的 Client
    private static final OkHttpClient client = getUnsafeOkHttpClient();
    private static final Gson gson = new Gson();

    public static void main(String[] args) throws IOException, InterruptedException {
        System.out.println("==========================================");
        System.out.println("   微信支付守卫 - 自动化集成测试 (Client)");
        System.out.println("==========================================");
        System.out.println("目标服务: " + TARGET_URL);
        System.out.println("回调地址: " + PUBLIC_CALLBACK_URL);
        System.out.println("鉴权Token: " + AUTH_TOKEN);
        System.out.println("------------------------------------------");

        // 1. 获取控制台输入
        Scanner scanner = new Scanner(System.in);
        System.out.print(">>> 请输入要测试的金额 (例如 1.06): ");
        double amount;
        try {
            amount = scanner.nextDouble();
        } catch (Exception e) {
            System.err.println("输入无效，请输入数字！");
            return;
        }

        // 2. 启动本地回调监听
        HttpServer callbackServer = HttpServer.create(new InetSocketAddress(LOCAL_CALLBACK_PORT), 0);
        callbackServer.createContext("/notify", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            System.out.println("\n\n[⭐⭐ 测试通过] 收到服务端回调!");
            System.out.println("内容: " + body);

            // 简单的响应
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("OK".getBytes());
            exchange.close();
        });
        callbackServer.start();
        System.out.println(">>> 本地回调监听已启动 (Port: " + LOCAL_CALLBACK_PORT + ")");

        // 3. 发起支付请求
        System.out.println(">>> 正在向服务端发送请求 (金额: " + amount + ")...");
        sendPaymentRequest(amount);

        // 4. 保持运行
        System.out.println(">>> ⏳ 正在等待结果... (请在服务端触发识别，或等待超时)");
        System.out.println(">>> (按 Ctrl+C 强制退出)");

        // 等待 70秒 (比服务端的 60秒超时稍长一点)
        Thread.sleep(70000);

        callbackServer.stop(0);
        System.out.println("\n>>> 测试结束 (超时未收到回调)");
        System.exit(0);
    }

    private static void sendPaymentRequest(double money) {
        new Thread(() -> {
            try {
                // 【适配】构造请求 DTO (包含 token)
                DTOs.PaymentRequest req = new DTOs.PaymentRequest(
                        AUTH_TOKEN,
                        money,
                        String.valueOf(System.currentTimeMillis()),
                        PUBLIC_CALLBACK_URL
                );

                Request request = new Request.Builder()
                        .url(TARGET_URL)
                        .post(RequestBody.create(gson.toJson(req), MediaType.get("application/json")))
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    System.out.println("   --> 发送结果: HTTP " + response.code() + " " + response.message());
                    if (response.isSuccessful() && response.body() != null) {
                        System.out.println("   --> Server返回: " + response.body().string());
                    } else if (response.body() != null) {
                        System.err.println("   --> 错误详情: " + response.body().string());
                    }
                }
            } catch (Exception e) {
                System.err.println("   --> 请求发送失败: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    // 核心：绕过 SSL 验证的黑科技方法 (用于自签名证书)
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
                    .hostnameVerifier((hostname, session) -> true) // 允许所有域名
                    .build();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}