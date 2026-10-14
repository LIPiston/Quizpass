# 架构与结构

## 工作原理

插件启动后在 `QuizpassBootstrap` 里做四件事：读取 `configs/quizpass.json`、按配置拉取申请页面、创建 `RconConnection` 并异步开始连接、注册 reload/stop 监听器并启动 Kalmia HTTP 服务。

页面下载是有意做成**失败不影响启动**的：网络不通、地址 404、响应为空、写入失败都只记一条 WARN，保留磁盘上已有的那份 `index.html` 继续服务。下载先写同目录的临时文件再原子替换，因此也不会出现「请求正好读到写了一半的文件」。

RCON 连接由 `RconConnection` 自己维护一条独立的 Netty 管线，状态机如下：

```
DISCONNECTED ──► CONNECTING ──► AUTHENTICATING ──► READY
      ▲                │                │            │
      └────────────────┴────────────────┴────────────┘
                    连接断开 / 认证失败 / 超时
```

- **退避策略**：`min(30s, 1s << n) + 抖动`，即 1s、2s、4s、8s、16s，之后封顶 30s；抖动最多 500ms，避免同时重连
- **超时保护**：WebSocket 握手 5 秒、AUTH 应答 5 秒，对端不回话也会放弃并重试，不会永久卡住
- **代际（generation）隔离**：一次连接尝试里的多条失败路径只会触发一次重连；被放弃的旧连接迟到的回调会被忽略

报文编解码集中在 `RconCodec`，纯函数、不抛异常：`decode()` 遇到长度不足、声明长度越界或类型未知时返回 `null`。RCON 帧结构（全部小端）：

```
size(4) | requestId(4) | requestType(4) | body | 0x00 0x00
```

`body` 长度 = `size - 10`，起始偏移 12。

玩家提交一次申请的完整链路：

```
玩家(网页表单) ──POST /api/add-whitelist──► Kalmia HTTP ──► 校验三项字段
(name / account / qq)                         │     非法则 400，不碰 RCON
                                              │
                                              ├─► RCON: /whitelist add <name> ──► Minecraft 服务端
                                              │        （未就绪则 503）
                                              │
                                              ├─► RCON: /say [quizpass] <name> ...（可选，写入服务端日志并广播）
                                              │
                                              └─► 后台线程（可选）──► 邮件 / 回调 / QQ 机器人
                                                       （携带游戏方式与 QQ 号）
```

校验失败返回 `400` 时**不会**发出任何 RCON 命令，玩家看到的仍然是那张表单，只是出错字段被标红。命令发出后页面才切到「恭喜，白名单已添加」。

游戏内广播这一段同样是**尽力而为**的：它在 `/whitelist` 命令之后发出，失败只记一条 WARN，不会把已经成功的申请变成错误。用 `/say` 而非 `/tellraw`，是因为只有 `/say` 会写进服务端控制台日志；代价是 `/say` 不支持颜色（见 [配置](configuration.md#配置)）。

通知这一段是**旁路**的：命令发出后就把通知交给后台线程，HTTP 响应立刻返回，不等通知结果。因此通知渠道慢、挂掉或配错都不会影响玩家（详见 [管理员通知](notifications.md#管理员通知)）。

## 项目结构

| 路径 | 说明 |
|------|------|
| `src/main/kotlin/.../entrypoint/QuizpassBootstrap.kt` | 插件入口：读配置、拉取申请页面、装配 RCON 连接、注册监听器与 HTTP 路由 |
| `src/main/kotlin/.../config/QuizpassConfig.kt` | 读取 `configs/quizpass.json`，缺文件时按默认值生成 |
| `src/main/kotlin/.../assets/AssetsDownloader.kt` | 申请页面在线下载（限时、原子写入、失败不影响启动） |
| `src/main/kotlin/.../notify/Notification.kt` | 通知内容（事件、玩家、游戏方式、QQ、命令、来源、时间）与文本/JSON 渲染 |
| `src/main/kotlin/.../notify/Notifier.kt` | 通知分发：单后台线程、按渠道独立容错 |
| `src/main/kotlin/.../notify/SmtpMailer.kt` | 内置 SMTP 客户端（明文 / STARTTLS / 隐式 TLS） |
| `src/main/kotlin/.../rcon/RconConnection.kt` | 自维护的 Netty 管线与重连状态机 |
| `src/main/kotlin/.../rcon/RconCodec.kt` | RCON 报文编解码（纯函数） |
| `src/main/kotlin/.../data/` | 报文数据类与请求类型枚举 |
| `src/main/resources/META-INF/plugin.json` | Kalmia 插件声明 |
| `assets/index.html` | 玩家申请页（同时是下载源的副本，也是离线兜底） |
| `configs/` | Kalmia 配置模板 |
