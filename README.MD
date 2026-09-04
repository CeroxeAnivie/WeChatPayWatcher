# WeChat Payment Watcher

WCPW 是 Debian 上运行的微信支付数据库监控服务。它读取 WeChat Linux 当前进程实际打开的 WCDB/SQLite 收款消息库，从动态 `Msg_*` 表的 `message_content`/`compress_content` 识别收款通知。

## 工作方式

启动时自动扫描运行中的 `wechat`/`WeChatAppEx` 进程的 `/proc/<pid>/fd` 与 `/proc/<pid>/maps`，筛选 `.db/.sqlite` 文件并逐个校验目标表；加密库会先生成只读快照再校验，避免按文件名误选。`wechat.db.path` 留空即可使用自动定位；账号目录变化、数据库轮换和 WAL 文件不需要修改配置。

服务启动并首次发现数据库时只建立一次内部高水位，用于忽略已有历史消息。之后只处理新出现或内容变化的 `Msg_*` 行；收款通知从压缩 XML 中解析金额和 `trans_id`，同一行不会重复触发。启动时数据库尚未生成也不影响服务启动，后台会持续自动定位并在数据库出现后初始化。

## Debian 配置

需要 JDK 21。先保持 WeChat Linux 登录并运行，再启动服务：

```bash
java -jar WeChatPayWatcher-3.0.0.jar
```

首次启动会生成 `config.properties`。至少设置：

```properties
server.port=9090
wcpw.request_token=YOUR_API_ACCESS_TOKEN
wcpw.callback_secret=YOUR_SHARED_SECRET_KEY
wechat.db.path=
wechat.db.query.limit=128
wechat.db.poll.interval.ms=1000
```

数据库启用 WCDB/SQLCipher 加密时，服务会读取当前微信进程的数据库盐值，并在 `/proc/<pid>/mem` 中寻找匹配盐值的 32 字节会话密钥，按 WeChat 4.x 参数生成临时解密快照并合并已提交 WAL 页面后查询。密钥只保存在进程内存，不写日志、不落盘。服务与微信需要由同一 Debian 用户运行，并允许读取目标进程内存。

## API

向 `/` 发送认证后的 POST 请求：

```json
{"token":"YOUR_API_ACCESS_TOKEN","money":1.39,"timestamp":"1768156200000","callbackUrl":"https://example/callback?oid=ORDER_001"}
```

监控到匹配收款后，服务按现有回调协议发送 `SUCCESS`；超时发送 `TIMEOUT`。回调队列、重试和签名规则保持不变。

## 验证

```bash
./mvnw test
./mvnw package
```

测试使用临时 SQLite 数据库，覆盖历史消息忽略、新收款识别、金额精确匹配、变化去重和非收款过滤。

隔离检测（不启动 HTTP、请求或回调）可直接运行：

```bash
java -cp WeChatPayWatcher-3.0.0.jar top.ceroxe.wcpw.DatabaseMonitorIsolation --timeout-seconds 900
```
