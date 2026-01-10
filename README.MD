# 🕵️‍♂️ WeChat Payment Watcher (WCPW)

**WCPW** 是一个基于 Java 和 OCR 技术的微信个人免签支付监控中间件。它运行在你的本地电脑或 VNC 服务器上，通过“视觉识别”屏幕上的微信收款弹窗，实现支付回调通知。

核心原理：**HTTP 请求唤醒 -> 锁定任务 -> 屏幕变化检测 -> OCR 识别金额 -> 回调业务系统**。

---

## 🛠️ 环境要求

由于本程序依赖屏幕截图 (`java.awt.Robot`)，**无法在无图形界面的 Linux (Headless) 上直接运行**。

### 1. 基础环境
*   **JDK**: Java 17 或更高版本
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
java -jar xxxx.jar
```
首次运行会自动生成 `config.properties` 配置文件并退出。

### 2. 配置 (`config.properties`)
修改配置文件，设置你的安全 Token 和端口：

```properties
# 服务端口
server.port=9090

# 【必须修改】鉴权 Token，你的业务系统发起请求时必须携带此 Token
auth.token=YOUR_SECURE_TOKEN_123456

# 订单超时时间 (秒)
order.timeout.seconds=300

# 回调重试配置
callback.retry.count=3
callback.retry.interval.ms=2000

# SSL 配置 (可选，留空则使用 HTTP)
ssl.cert.path=
ssl.key.path=
```

### 3. 重启服务
配置完成后再次运行，看到以下日志即启动成功：
```text
✅ OCR 引擎初始化完毕 (ROI: 380x450 | Thr: 0.05 | Force: 20000ms)
🚀 服务启动 (HTTP) Port: 9090
✅ 微信支付守卫已就绪 | 等待请求...
```

---

## 🔌 API 接口文档

### 1. 发起监控任务
当用户在你的商城点击支付时，你的后端服务器调用此接口。

*   **URL**: `http://<IP>:9090/`
*   **Method**: `POST`
*   **Content-Type**: `application/json`

#### 请求参数 (JSON)
| 字段            | 类型     | 必填 | 说明                                         |
|:--------------|:-------|:---|:-------------------------------------------|
| `token`       | String | 是  | 必须与 `config.properties` 中的 `auth.token` 一致 |
| `money`       | Number | 是  | 期望收到的金额 (例如 1.39)                          |
| `timestamp`   | String | 是  | 业务系统的订单时间戳或ID (用于透传)                       |
| `callbackUrl` | String | 是  | 支付成功/超时后，WCPW 通知的地址                        |

**请求示例:**
```json
{
    "token": "YOUR_SECURE_TOKEN_123456",
    "money": 1.39,
    "timestamp": "ORDER_20260111_001",
    "callbackUrl": "http://your-shop.com/api/payment/notify"
}
```

#### 响应参数 (JSON)
| 状态码 | 状态             | 说明                                      |
|:----|:---------------|:----------------------------------------|
| 200 | `READY`        | 监控已启动，请让用户扫码付款                          |
| 200 | `PENDING`      | 系统忙碌 (已有订单正在监控)，返回 `waitSeconds` 建议等待时间 |
| 401 | `UNAUTHORIZED` | Token 错误                                |
| 400 | `ERROR`        | 参数错误                                    |

**响应示例 (成功):**
```json
{
    "status": "READY",
    "message": "Monitoring Started",
    "data": null
}
```

---

### 2. 支付结果回调
当检测到收款成功，或超过设定的超时时间，WCPW 会向你的 `callbackUrl` 发送 POST 请求。

*   **Method**: `POST`
*   **Content-Type**: `application/json`

#### 回调载荷 (Payload)
| 字段                 | 类型     | 说明                              |
|:-------------------|:-------|:--------------------------------|
| `status`           | String | `SUCCESS` (成功) 或 `TIMEOUT` (超时) |
| `requestTimestamp` | String | 原样返回你请求时的 `timestamp`           |
| `detectTimestamp`  | Long   | 实际检测到金额的时间戳                     |
| `amount`           | Number | 实际收到的金额                         |
| `message`          | String | 附加信息                            |

**回调示例:**
```json
{
    "status": "SUCCESS",
    "requestTimestamp": "ORDER_20260111_001",
    "detectTimestamp": 1768156200000,
    "amount": 1.39,
    "message": "SUCCESS"
}
```
*注：收到回调后，请你的业务系统返回 HTTP 200，否则 WCPW 会重试。*

---

## 📂 日志说明

系统会自动在运行目录下创建 `logs` 文件夹。
*   文件名格式：`logs/log_yyyyMMdd_HHmmss.log`
*   日志包含详细的 OCR 识别过程、任务状态和错误信息，方便排查漏单原因。

**日志示例:**
```text
14:30:00 [main] INFO  Application - 🚀 服务启动 (HTTP) Port: 9090
14:32:10 [pool-1] INFO  Monitor - [TaskId] 📸 #15 [Motion] 耗时 500ms -> [收款到账通知 | ￥1.39]
14:32:10 [pool-1] INFO  Monitor - [TaskId] ✅✅✅ 金额匹配成功: ¥1.39
14:32:11 [pool-2] INFO  Callback - [TaskId] 📤 发起回调 -> http://... (Status: SUCCESS)
```

---

## ⚙️ 核心逻辑配置 (开发人员)

如果你需要修改源码，关键逻辑位于 `WeChatMonitorService.java`：

1.  **ROI 区域**: 默认 `380x450` (右下角)。如果你的分辨率不是 1024x768，可能需要修改 `ROI_WIDTH` / `ROI_HEIGHT`。
2.  **动作阈值 (`MOTION_THRESHOLD`)**: 默认为 `0.05` (5%)。
    *   VNC 画质差/噪点多 -> 调大此值。
    *   本地高清屏幕 -> 可调小此值。
3.  **心跳扫描 (`FORCE_SCAN_INTERVAL_MS`)**: 默认为 `20000` (20秒)。防止因画面静止导致的漏单。

---

## ⚠️ 免责声明

本项目仅供**个人学习、技术研究或个人小额测试**使用。
1.  **严禁**用于非法用途（如洗钱、跑分平台等）。
2.  本项目不保证 100% 的识别准确率，涉及资金交易请务必保留人工复查手段。
3.  使用此软件产生的任何法律后果由使用者自行承担。