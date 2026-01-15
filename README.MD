# 🕵️‍♂️ WeChat Payment Watcher (WCPW)

**WCPW** 是一个基于 Java 和 OCR 技术的微信个人免签支付监控中间件。它运行在你的本地电脑或 VNC 服务器上，通过“视觉识别”屏幕上的微信收款弹窗，实现支付回调通知。

核心原理：**HTTP 请求唤醒 -> 锁定任务 -> 屏幕变化检测 -> OCR 识别金额 -> <font color="red">生成安全签名</font> -> 回调业务系统**。

> 🛡️ **安全升级 (v2.0)**: 本版本引入了**强制 MD5 签名校验**机制，彻底防止伪造回调攻击。配合 HTTPS 使用可实现无懈可击的支付闭环。

---

## 🛠️ 环境要求

由于本程序依赖屏幕截图 (`java.awt.Robot`)，**无法在无图形界面的 Linux (Headless) 上直接运行**。

### 1. 基础环境
*   **JDK**: Java 21 或更高版本
*   **OS**: Windows / Linux (带桌面环境 或 VNC) / macOS
*   **依赖库**: Linux 下可能需要安装 `libgomp1` 和 `libgl1-mesa-glx` (OCR 引擎依赖)

### 2. VNC / 屏幕设置 (关键) 🖥️
为了确保 OCR 识别率和性能，请严格遵守以下设置：

*   **分辨率**: 建议 **1024 x 768** (程序针对此分辨率优化了扫描区域)。
*   **微信位置**: 微信 PC 端必须保持开启，且窗口不能最小化（可以被其他窗口覆盖，但弹窗必须能显示）。
    <br>![img.png](img.png)
*   **弹窗设置**: 必须开启微信设置中的 **“收款到账通知”**。
*   **弹窗位置**: 微信通知弹窗必须位于屏幕 **右下角**。
*   **干扰排除**: 尽量保持右下角背景干净，避免有动态壁纸或秒针跳动的时钟，以降低 CPU 占用。

---

## 🚀 快速开始

### 1. 启动
下载 Release 中的打包好的 Jar 包并运行：
```bash
java -jar wcpw-server.jar
```
首次运行会自动生成 `config.properties` 配置文件并退出。

### 2. 配置 (`config.properties`)
修改配置文件，设置你的安全 Token 和**签名密钥**：

```properties
# 服务端口
server.port=9090

# 【必须修改】鉴权 Token，你的业务系统发起请求时必须携带此 Token (用于调用 WCPW)
auth.token=YOUR_API_ACCESS_TOKEN

# 【新增必填】回调签名密钥 (用于 WCPW 回调你的系统)
# 必须与你的业务系统 (如 NeoAuthServer) 中的验签密钥一致
callback.secret=YOUR_SHARED_SECRET_KEY

# 订单超时时间 (秒)
order.timeout.seconds=300

# 回调重试配置
callback.retry.count=3
callback.retry.interval.ms=2000

# SSL 配置 (推荐开启 HTTPS 以防止中间人攻击)
ssl.cert.path=
ssl.key.path=
```

### 3. 重启服务
配置完成后再次运行，看到以下日志即启动成功：
```text
✅ OCR 引擎初始化完毕 (ROI: 380x450 | Thr: 0.05 | Force: 20000ms)
🚀 服务启动 (HTTPS) Port: 9090
✅ 微信支付守卫已就绪 | 等待请求...
```

---

## 🔌 API 接口文档

### 1. 发起监控任务
当用户在你的商城点击支付时，你的后端服务器调用此接口。

*   **URL**: `https://<IP>:9090/` (推荐 HTTPS)
*   **Method**: `POST`
*   **Content-Type**: `application/json`

#### 请求参数 (JSON)
| 字段            | 类型     | 必填 | 说明                                         |
|:--------------|:-------|:---|:-------------------------------------------|
| `token`       | String | 是  | 必须与 `config.properties` 中的 `auth.token` 一致 |
| `money`       | Number | 是  | 期望收到的金额 (例如 1.39)                          |
| `timestamp`   | String | 是  | 业务时间戳                                   |
| `callbackUrl` | String | 是  | **关键**: 必须包含你的订单号参数 (如 `?oid=xxxxx`)      |

**请求示例:**
```json
{
    "token": "YOUR_API_ACCESS_TOKEN",
    "money": 1.39,
    "timestamp": "1768156200000",
    "callbackUrl": "https://your-shop.com/api/callback?oid=ORDER_20260111_001"
}
```

---

### 2. 支付结果回调 (核心安全机制)
当检测到收款成功或超时，WCPW 会向你的 `callbackUrl` 发起 POST 请求。

**⚠️ 注意：WCPW 会自动在你的 `callbackUrl` 后追加签名参数。**

*   **Method**: `POST`
*   **Content-Type**: `application/json`
*   **URL 示例**: `https://your-shop.com/api/callback?oid=xxx&money=1.39&status=SUCCESS&timestamp=...&sign=MD5_SIGNATURE`

#### 验签算法 (Security)
为了防止黑客伪造回调骗取发货，你的业务系统**必须**按以下步骤验证 `sign` 参数：

1.  **提取参数**: 获取 `oid` (订单号), `money` (金额), `status` (状态), `timestamp` (时间戳)。
2.  **参数排序**: 将上述参数按 Key 的 ASCII 码从小到大排序 (字典序)。
3.  **拼接密钥**: 格式为 `key1=val1&key2=val2...&key=YOUR_CALLBACK_SECRET`。
4.  **生成签名**: 对拼接后的字符串进行 **MD5** 运算，并转为**大写**。
5.  **比对**: 将计算结果与 URL 中的 `sign` 参数比对。

**签名原串示例:**
```text
money=1.39&oid=ORDER_001&status=SUCCESS&timestamp=1768156200000&key=YOUR_SHARED_SECRET_KEY
```

#### 回调 Body (JSON)
除了 URL 参数外，Body 中也会包含详细信息（供日志记录，不建议仅凭 Body 判断发货）：
```json
{
    "status": "SUCCESS",
    "requestTimestamp": "...",
    "detectTimestamp": 1768156200000,
    "amount": 1.39,
    "message": "SUCCESS"
}
```

---

## 📂 日志说明

系统会自动在运行目录下创建 `logs` 文件夹。
*   文件名格式：`logs/log_yyyyMMdd_HHmmss.log`
*   日志包含详细的 OCR 识别过程、任务状态和错误信息，方便排查漏单原因。

**日志示例:**
```text
14:32:10 [pool-1] INFO  Monitor - [ORDER_001] 📸 #15 [Motion] 耗时 500ms -> [收款到账通知 | ￥1.39]
14:32:10 [pool-1] INFO  Monitor - [ORDER_001] ✅✅✅ 金额匹配成功: ¥1.39
14:32:11 [pool-2] INFO  Callback - [ORDER_001] 📤 发起回调 -> https://...?sign=ABC... (Sign Generated)
```

---

## ⚠️ 免责声明

本项目仅供**个人学习、技术研究或个人小额测试**使用。
1.  **严禁**用于非法用途（如洗钱、跑分平台等）。
2.  本项目不保证 100% 的识别准确率，涉及资金交易请务必保留人工复查手段。
3.  使用此软件产生的任何法律后果由使用者自行承担。