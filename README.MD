# 🕵️‍♂️ WeChat Payment Watcher (WCPW)

**WCPW** 是一个基于 Java 和 OCR 技术的微信个人免签支付监控中间件。它运行在你的本地电脑或 VNC 服务器上，通过“视觉识别”屏幕上的微信收款弹窗，实现支付回调通知。

核心原理：**HTTP 请求唤醒 -> 锁定任务 -> 屏幕变化检测 -> OCR 识别金额 -> <font color="red">生成安全签名</font> -> 回调业务系统**。

> 🛡️ **支付判定修复 (v2.1.3)**: 使用轻量 `ONNX_PPOCR_V4`，仅以微信界面的 `今日第 X 笔` 变化认定新账，再按最大字号 OCR 候选和完整金额 token 精确比较；`1.00` 不会命中 `0.01`、`11.00` 或 `100.00`。

---

## 🛠️ 环境要求

由于本程序依赖屏幕截图 (`java.awt.Robot`)，**无法在无图形界面的 Linux (Headless) 上直接运行**。

### 1. 基础环境
*   **JDK**: **Java 21 或更高版本**（必须，代码使用了虚拟线程与 Record 特性）
*   **OS**: Windows / Linux (带桌面环境 或 VNC) / macOS
*   **依赖库**: Linux 下可能需要安装 `libgomp1` 和 `libgl1-mesa-glx` (OCR 引擎依赖)

### 2. VNC / 屏幕设置 (关键) 🖥️
为了确保 OCR 识别率和性能，请严格遵守以下设置：

*   **分辨率**: 建议 **1024 x 768** (程序针对此分辨率优化了扫描区域)。
*   **微信位置**: 微信 PC 端必须保持开启，且窗口不能最小化。
*   **弹窗设置**: 必须开启微信设置中的 **“收款到账通知”**。
*   **弹窗位置**: 微信通知弹窗必须位于屏幕 **右下角**。
*   **干扰排除**: 尽量保持右下角背景干净，避免有动态壁纸。

![img.png](img.png)

---

## 🚀 快速开始

### 1. 启动
下载 Release 中的打包好的 Jar 包并运行：
```bash
java -jar WeChatPayWatcher-X.X.X.jar
```
首次运行会自动生成 `config.properties` 配置文件并退出。

### 2. 配置 (`config.properties`)
修改配置文件，设置你的安全 Token 和**签名密钥**：

```properties
# 服务端口
server.port=9090
# 监听地址（生产环境通常由反向代理暴露；本地/VNC 可保持 0.0.0.0）
server.bind.host=0.0.0.0

# 【必须修改】鉴权 Token，用于调用 WCPW
wcpw.request_token=YOUR_API_ACCESS_TOKEN

# 【新增必填】回调签名密钥，必须与你的业务系统 (如 NeoAuthServer) 一致
wcpw.callback_secret=YOUR_SHARED_SECRET_KEY

# 订单超时时间 (秒)
order.timeout.seconds=120
order.timeout.max.seconds=900

# 单次 API 请求体和订单金额上限
request.max.body.bytes=65536
order.max.amount=1000000

# 回调发送总次数上限（允许 1-3；默认和硬上限均为 3）
callback.retry.count=3
# 两次回调请求之间的等待时间（毫秒，默认 2000）
callback.retry.interval.ms=2000
callback.queue.max.record.bytes=1048576

# callbackUrl 仍由已认证调用方指定；仅限制协议和长度
callback.allowed.schemes=http,https
callback.url.max.length=2048

# 默认保持旧系统兼容；新接收方建议使用 HMAC-SHA256
callback.signature.algorithm=MD5
```

---

## 🔌 API 接口文档

### 1. 发起监控任务
*   **URL**: `http://<IP>:9090/`
*   **Method**: `POST`
*   **安全加固**: 本接口内置 **200ms 强制延迟**。自动化扫描工具会因首包响应过慢而判定端口无效，从而大幅提升隐身安全性。

#### 请求参数 (JSON)
| 字段            | 类型     | 必填 | 说明                                         |
|:--------------|:-------|:---|:-------------------------------------------|
| `token`       | String | 是  | 必须与 `config.properties` 中的 `wcpw.request_token` 一致 |
| `money`       | Number | 是  | 期望收到的金额 (例如 1.39)                          |
| `timestamp`   | String | 是  | 业务时间戳                                      |
| `callbackUrl` | String | 是  | **关键**: 必须包含你的订单号参数 (如 `?oid=xxxxx`)       |

---

### 2. 支付结果回调 (核心安全机制)
当检测到收款成功或超时，WCPW 会向你的 `callbackUrl` 发起 POST 请求。

每条回调全生命周期最多发送 `callback.retry.count` 次。失败后按照 `callback.retry.interval.ms` 等待再进行下一次发送，默认间隔为 2000ms；尝试次数和下一次允许发送的时间都会写入 `callback_queue`，进程重启后只继续剩余次数且不会绕过等待时间。次数耗尽后记录移入 `callback_queue/failed` 供人工核查，不存在每分钟重新获得三次机会的“恢复周期”。

每一次实际 HTTP 回调请求都会分别记录开始和结果日志；前两次失败会额外记录按配置计算的重试计划，最后一次失败仍保留自己的失败日志，然后再记录一次最终耗尽摘要。普通失败不打印 Java 堆栈。

**⚠️ 注意：WCPW 会根据 TreeMap 自动生成的字典序追加签名参数，并保留 callbackUrl 中的其他 query 参数。**

*   **Method**: `POST`
*   **URL 示例**: `.../callback?oid=xxx&money=1.39&status=SUCCESS&timestamp=...&sign=MD5_SIGNATURE`

#### 验签算法 (Security)
为了防止伪造回调，你的业务系统**必须**按以下字典序验证 `sign` 参数：

1.  **参数排序**: 将 `money`, `oid`, `status`, `timestamp` 按 Key 的 ASCII 码从小到大排序。
2.  **拼接格式**: `money=val&oid=val&status=val&timestamp=val&key=YOUR_CALLBACK_SECRET`。
3.  **生成签名**: 默认对拼接后的字符串进行 **MD5** 运算并转为大写；配置 `callback.signature.algorithm=HMAC-SHA256` 时改用 HMAC-SHA256。

**签名原串示例 (严格字典序):**
```text
money=1.39&oid=ORDER_001&status=SUCCESS&timestamp=1768156200000&key=YOUR_SHARED_SECRET_KEY
```

### 3. 2c2g Debian 部署建议

*   使用 RapidOCR 内置的 ONNX PPOCRv4 模型，不需要额外模型服务、Python 进程或 GPU。
*   使用 JDK 21，建议通过 systemd/cgroup 限制 CPU 和内存；JVM 堆可从 `-Xmx384m` 起步后按实测调整。
*   `java.awt.Robot` 需要 X11/VNC/Xvfb，真正的 headless 环境无法识别屏幕。
*   生产环境建议让 Nginx/Caddy 负责 HTTPS，WCPW 绑定 `127.0.0.1`；HTTP fallback 仍保留用于本地/VNC 场景。

---

## 📂 日志说明

系统会自动在 `logs` 文件夹记录详细的监控轨迹：
*   请求通过鉴权并取得唯一任务锁后，服务立即 OCR 屏幕右下角 `380×450` 区域，以微信当前的 `今日第 X 笔` 建立该订单专属基线；只有成功识别到合法的 `X` 才返回 `READY`。
*   基线建立完成后，任务每 5 秒重新截取同一区域并执行 OCR；只有微信界面中的 `今日第 X 笔` 相对本次订单基线发生变化时才检查金额。
*   微信计数未变化时继续等待；计数变化后，无论递增还是递减，都用最大字号的完整金额 token 判定本次金额并更新基线。
*   普通运行错误只记录异常类型和简短原因，不打印 Java 调用堆栈。
*   OCR 图片只作为单次推理临时文件使用；每次扫描完成后删除，程序启动时还会清理本程序已知前缀的残留 PNG。`ocr_native_libs` 中的原生库文件不是截图，会随程序复用。

**日志示例:**
```text
14:32:10 INFO - [ORDER_001] 📸 #15 耗时 500ms -> [收款到账通知 | ￥1.39]
14:32:15 INFO - [ORDER_001] 📸 #16 耗时 380ms -> [微信支付 | 收款成功]
```

---

## ⚠️ 免责声明
1.  **严禁**用于非法用途。
2.  本项目不保证 100% 的识别准确率，涉及资金交易请务必保留人工复查手段。
3.  使用此软件产生的任何法律后果由使用者自行承担。
