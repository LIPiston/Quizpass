# 开发与维护

## 快速调试与开发

开发循环很短：**编译 → 换 jar → `reload`**。Kalmia 不需要重启进程，改完 Kotlin 重载一次即可看到效果。

```bash
# 1. 编译
./gradlew build

# 2. 换掉运行目录里的 jar
cp build/libs/Quizpass-1.0-SNAPSHOT.jar test/libs/

# 3. 在 Kalmia 控制台输入 reload
```

> **`reload` 不会重新定义已加载的类**。它重新读配置、重新扫描 `libs/*.jar`、重新执行入口、**替换整个路由图**，但 JVM 里已经加载的类不会变。所以**每次改完 Kotlin 都必须重新 `./gradlew build` 并换 jar**，只敲 `reload` 是看不到代码改动的。正在处理中的请求会在旧路由图上跑完，不会被中断。

可用的控制台命令：`reload`、`stop`、`exit`、`plugins`、`threads`；日志里 reload 的标志行是 `Reloading Kalmia(1.0.32) server...`。

调试时几个值得盯的位置：

| 你想确认的事 | 看哪里 |
|------|------|
| 插件被加载了 | 启动日志的 `Loading '...Quizpass-1.0-SNAPSHOT.jar' as a standard plugin` 与 `Launching declared entrypoint` |
| 配置读进来了 | `Creating config from file '...quizpass.json'`（缺文件时是 `Config not found, creating config to file`） |
| RCON 连上了 | `RCON ready: authenticated successfully` |
| 命令发出去了 | `Sending RCON command (requestId=N): /whitelist add <name>` 与 `Command response: ...` |
| 通知发出去了 | 线程名为 `Quizpass-Notifier` 的 `Sent add notification for <name> via <channel>` / `Failed to send ...` |
| 广播发出去了 | `Sending RCON command (requestId=N): /say [quizpass] <name> 已被添加到白名单`，随后服务端日志出现 `[Server thread/INFO]: [Not Secure] [Rcon] [quizpass] <name> 已被添加到白名单` |
| reload 有没有泄漏 | 连续 reload 后线程数不增长：日志里每次都应出现 `Stopping RCON connection`，且 `Quizpass-Notifier` 始终只有一条 |

改申请页面（`assets/index.html`）时**不需要**重编译插件：把 `assets.download` 设为 `false` 直接改本地文件，或者把 `assets.url` 指到自己的地址（例如本地起个静态服务器），启动时就会拉下来。

> **本仓库只包含插件源码与配置模板**，不含 Kalmia 本体（`Kalmia-*.jar`），也不含 RCON 对端、SMTP/回调的测试桩。本机开发时可以直接用 `test/` 作为工作目录（见 [快速开始](getting-started.md#快速开始)），它里面有现成的 Kalmia 与配置；但 `test/` 已被 `.gitignore` 排除，换台机器需要自备 Kalmia 本体。

## 说明与已知限制

- **未编写自动化测试**：`src/test` 目前是空目录，改动后建议按下文手动验证
- **编译依赖必须与运行时 Kalmia 版本一致**：`build.gradle.kts` 依赖 `Kalmia:1.0.32`，必须和运行目录里那个 Kalmia jar 的版本对齐。Kalmia 及其 JSON 库（Cason）在**补丁版本之间会改动方法签名**——例如 `JSONArray.getList()` 在 `1.0.28` 里返回 `ArrayList`、在 `1.0.32` 里返回 `List`，编译期无法发现，运行时才会以 `NoSuchMethodError` 崩在入口。因此**升级 Kalmia 时必须同步改这里的版本号并重新构建**；代码中读写 JSON 也尽量只用跨版本稳定的 API（`size()` / `getString(int)` / `getInt(int)` 等按下标访问的形式）
- **下载源的默认地址指向本仓库**：`assets.url` 默认取本仓库 `master` 分支的文件，所以仓库尚未推送到该地址前，启动会出现一次 404 WARN（随后保留本地文件，功能不受影响）。换地址或仓库名后请同步改这一项
- **来源地址依赖反向代理**：Kalmia 不向插件暴露客户端 socket 地址，未经过反向代理时通知里的来源地址只会是 `unknown`（见 [管理员通知](notifications.md#管理员通知)）
- **`/api/remove-whitelist` 没有任何鉴权**：页面上已经不提供移除入口，但这个接口本身仍然可达——任何能访问到 12345 端口的人都可以 POST 它把玩家移出白名单，插件不校验来源、也没有 token。**部署到公网时必须自己在反向代理层限制该路径**（例如只允许内网、加 Basic Auth，或干脆把它挡掉）；申请接口 `/api/add-whitelist` 则有意保持公开，因为玩家本来就要用
- **申请接口没有频率限制**：插件不做限流，任何人可以反复提交。要防刷需要在反向代理或 WAF 层处理
- **命令是「发出即成功」的**：`RconConnection.sendCommand()` 把帧写进连接就返回 `true`，不等服务端回执。正常情况下没有任何问题，但若服务端在读命令时**主动断开 RCON 连接**（实测：短时间内连发若干条 `whitelist add <无法被 Mojang 解析的名字>` 时，原版服务端会因阻塞的档案查询而掐断连接），那条命令可能已丢失，而接口仍返回了 `200`。这类失败只在插件日志里表现为 `RCON connection lost: socket closed by peer` 加一次自动重连。玩家逐个提交（人间隔）时未观察到该现象
- **无 License**：仓库暂未附许可证

## 手动验证

没有测试套件，按下面的场景验证行为是否符合预期：

1. **正常链路**：先起 RCON 对端，再启动 Kalmia；日志出现 `RCON ready: authenticated successfully`，接口返回 `200`
2. **申请表单**：打开 `http://127.0.0.1:12345/`，应看到游戏内名称 / 游戏方式 / QQ 号三项，提交合法内容后切到「恭喜，白名单已添加」，且 RCON 对端收到 `/whitelist add <name>`
3. **400 校验**：名称填 `ab`、QQ 填 `12ab`、或把 `account` 传成 `cracked`，接口应返回 `400` 且 `error_message` 指明出错字段；此时 RCON 对端**不应**收到任何命令
4. **RCON 先不在**：直接启动 Kalmia，确认 HTTP 服务照常起来（不阻塞），接口返回 `503`
5. **断线重连**：运行中停掉 RCON 对端，接口转为 `503`，日志出现退避重连；重新起对端后应自动恢复为 `200`
6. **reload 不泄漏**：连续 `reload` 数次，每次都应看到 `Stopping RCON connection`，且线程数不持续增长
7. **畸形帧**：向连接注入截断或类型未知的帧，连接应继续可用，而不是崩掉
8. **配置生效**：删掉 `configs/quizpass.json` 后启动，应自动生成默认配置且行为不变；把 `rcon.port` 改成别的端口并起对端，日志里的连接地址应随之改变
9. **页面下载**：把 `assets.url` 指向一个可访问的地址，启动后本地 `assets/index.html` 应被替换为新内容；指向一个 404 地址，应只出现 WARN 且保留原有文件
10. **关闭下载**：设 `assets.download: false`，启动日志中不应出现任何 `Downloading`，本地文件保持不动
11. **通知默认关闭**：默认配置下提交一次申请，接口返回 `200`，且日志中**没有**任何 `notification for`、也没有对外连接；再设 `notify.enabled: true` 但三个渠道都保持 `false`，行为应完全相同
12. **三个渠道各自可达**：分别只开一个渠道提交申请，确认邮件收到、回调收到 `POST`（带 `Authorization: Bearer`）、QQ 机器人收到消息；中文标题与正文不乱码，且正文里有 `游戏方式` 与 `QQ` 两行、回调 JSON 里有 `account` 与 `qq` 两个键
13. **渠道互不影响**：同时开启三个渠道，但故意让其中一个不可达（例如把邮件端口改成一个没人监听的端口），接口仍应返回 `200`，日志中该渠道是 `Failed to send ...` 的 WARN，另外两个渠道照常 `Sent ...`
14. **按事件开关**：设 `notify.onRemove: false`，移除白名单时不应发出通知，而新增仍应发出
15. **通知不拖慢接口**：把回调指向一个响应很慢（或只连不答）的地址，接口仍应**立刻**返回 `200`，而不是等超时
16. **reload 后通知仍可用**：`reload` 后再提交一次，三个渠道都应继续送达；连续 reload 数次后 `Quizpass-Notifier` 线程始终只有一条
17. **广播默认关闭**：默认配置下提交申请，服务端控制台**不应**出现 `[Rcon] [quizpass] ...` 这一行
18. **广播生效**：设 `broadcast.enabled: true`，提交申请，服务端控制台出现 `[Server thread/INFO]: [Not Secure] [Rcon] [quizpass] <name> 已被添加到白名单`，且在线玩家能在聊天栏看到同一条纯文本消息（用真实客户端确认，例如连接一个 1.21.11 客户端）
19. **广播按事件开关**：设 `broadcast.onRemove: false`，通过 `/api/remove-whitelist` 移除玩家时**不应**出现广播行，而新增仍应出现
20. **广播不拖累接口**：广播是 `/whitelist` 命令之后的尽力而为动作，RCON 正常时接口仍返回 `200`，玩家侧响应不因 `/say` 而变慢；RCON 恰好断开时申请仍返回成功，插件日志只多一条广播相关的 WARN
