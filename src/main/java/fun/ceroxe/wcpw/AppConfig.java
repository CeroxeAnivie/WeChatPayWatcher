package fun.ceroxe.wcpw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class AppConfig {
    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);
    private static final String CONFIG_FILENAME = "config.properties";
    private static final Properties props = new Properties();

    public static void init() {
        Path localConfigPath = Paths.get(CONFIG_FILENAME);

        if (!Files.exists(localConfigPath)) {
            logger.warn("⚠️ 配置文件缺失，正在生成默认模板: {}", localConfigPath.toAbsolutePath());
            try (InputStream resourceStream = AppConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILENAME)) {
                if (resourceStream == null) {
                    throw new RuntimeException("Jar包内部缺失 config.properties 模板！");
                }
                Files.copy(resourceStream, localConfigPath);
                logger.info("✅ 配置文件已生成，请修改后重启服务。");
                System.exit(0);
            } catch (IOException e) {
                throw new RuntimeException("无法生成配置文件", e);
            }
        }

        try (InputStream in = Files.newInputStream(localConfigPath)) {
            props.load(in);
            validateConfig();
        } catch (IOException e) {
            throw new RuntimeException("读取配置文件失败", e);
        }
    }

    private static void validateConfig() {
        // 【关键修改】加入 callback.secret 为必填项，用于签名
        String[] requiredKeys = {"server.port", "auth.token", "callback.secret"};
        for (String key : requiredKeys) {
            if (props.getProperty(key) == null || props.getProperty(key).isBlank()) {
                throw new RuntimeException("❌ 配置文件错误: 缺少关键项 [" + key + "]");
            }
        }
    }

    public static int getInt(String key) {
        return Integer.parseInt(get(key));
    }

    public static int getInt(String key, int defaultValue) {
        String val = props.getProperty(key);
        if (val == null || val.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static String get(String key) {
        String val = props.getProperty(key);
        return val == null ? null : val.trim();
    }
}