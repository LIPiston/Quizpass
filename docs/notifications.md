# 管理员通知

玩家提交申请后可以通知管理员，**默认完全关闭**。总开关与三个渠道：

| 键 | 默认值 | 说明 |
|------|--------|------|
| `notify.enabled` | `false` | 通知总开关，`false` 时下列所有渠道都不生效 |
| `notify.onAdd` | `true` | 是否在**新增**白名单时通知 |
| `notify.onRemove` | `true` | 是否在**移除**白名单时通知 |
| `notify.timeoutMillis` | `10000` | 单个渠道的连接与读写超时（毫秒） |
| `notify.email.enabled` | `false` | 是否启用邮件通知 |
| `notify.email.host` / `port` | `smtp.example.com` / `465` | SMTP 服务器与端口 |
| `notify.email.ssl` | `true` | 是否使用**隐式 TLS**（一般 465 端口用这个） |
| `notify.email.startTls` | `false` | 是否使用 **STARTTLS**（一般 587 端口用这个）。与 `ssl` 互斥，两者同时为 `true` 时按 `ssl` 处理 |
| `notify.email.username` / `password` | 空 | SMTP 认证凭据；留空则不认证 |
| `notify.email.from` | 空 | 发件人地址 |
| `notify.email.to` | `[]` | 收件人列表；为空时即使 `enabled` 为 `true` 也不会发信 |
| `notify.email.subject` | `Quizpass 白名单变更: {player} ({event})` | 主题模板，`{player}` 与 `{event}` 会被替换 |
| `notify.email.trustAll` | `false` | 跳过 TLS 证书校验。**仅用于自签名证书的测试环境** |
| `notify.callback.enabled` | `false` | 是否启用 HTTP 回调 |
| `notify.callback.url` | `http://127.0.0.1:8080/quizpass/notify` | 回调地址，以 `POST` + JSON 发送 |
| `notify.callback.token` | 空 | 非空时带上 `Authorization: Bearer <token>` |
| `notify.qqbot.enabled` | `false` | 是否启用 QQ 机器人通知 |
| `notify.qqbot.url` | `http://127.0.0.1:3000` | OneBot v11 实现的地址 |
| `notify.qqbot.groupId` | 空 | 群号，非空时走 `/send_group_msg` |
| `notify.qqbot.userId` | 空 | 私聊 QQ 号，`groupId` 为空时才走 `/send_private_msg` |
| `notify.qqbot.accessToken` | 空 | 非空时带上 `Authorization: Bearer <token>` |

行为约定：

- **通知是异步、失败不影响业务的**。提交后立刻把通知丢到后台线程（`Quizpass-Notifier`）发送，玩家的 HTTP 响应不等通知，也不受通知成败影响；接口的成功/失败语义（`200` / `400` / `500` / `503`）永远只由字段校验与 RCON 是否发出命令决定。
- **各渠道互不影响**。某个渠道失败只记一条 WARN（`Failed to send ... via <channel>`），其余渠道照常发送。
- **邮件是内置实现的**，直接用 socket 讲 SMTP，没有引入新的第三方依赖。中文主题按 RFC 2047 编码，正文以 UTF-8 + base64 传输，因此中文不会乱码。
- **来源地址只能尽力而为**。Kalmia 没有把客户端 socket 地址暴露给插件，所以通知里的来源地址取自 `X-Forwarded-For`（取第一个）或 `X-Real-IP` 请求头；两个都没有时如实写 `unknown`，而不是编一个不确定的值。页面直接暴露、未经反向代理时就是这种情况。
- 通知里的站点名取自请求的 `Host` 头，因此是玩家实际访问的域名，而不是服务端的监听地址。

回调渠道的请求体（`Content-Type: application/json`）：

```json
{
    "event": "add",
    "player": "Steve",
    "account": "premium",
    "qq": "123456789",
    "command": "/whitelist add Steve",
    "host": "quizpass.example.com",
    "address": "203.0.113.77",
    "time": "2026-10-11 23:31:37",
    "text": "Quizpass 白名单申请\n\n玩家: Steve\n游戏方式: 正版\nQQ: 123456789\n操作: add\n命令: /whitelist add Steve\n时间: 2026-10-11 23:31:37\n来源地址: 203.0.113.77\n站点: quizpass.example.com"
}
```

`account` 是线上值（`premium` / `skin`），而 `text` 里写的是中文标签（`正版` / `皮肤站`）—— 前者便于程序判断，后者便于人直接阅读。移出事件（`event: "remove"`）不经过申请表单，因此 `account` 与 `qq` 是**空字符串**，`text` 里也不会出现这两行。另外 `text` 里的标题与动作名随事件变化（申请是「Quizpass 白名单申请」，移出是「Quizpass 白名单移除」）。

QQ 机器人渠道发往 OneBot v11 的 `/send_group_msg`（或 `/send_private_msg`），体为 `{"group_id":987654321,"message":"<上面那段 text>"}`。

HTTP 监听地址、端口和静态资源目录由 Kalmia 侧配置（`configs/kalmia_http.json`）决定，`assets.path` 需要落在它配置的 `asset_path` 之内。下面是与本插件相关的字段（完整文件还包含 netty 缓冲、`cache`、`error_page` 等项）：

```json
{
    "server_host": "0.0.0.0",
    "server_port": 12345,
    "asset_manager": {
        "asset_path": "assets/",
        "enable": true
    }
}
```
