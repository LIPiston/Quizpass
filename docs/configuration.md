# 配置

插件配置放在工作目录的 `configs/quizpass.json`，**首次启动时自动生成**（内容即下面的默认值）。它的读取方式与 Kalmia 自身的 `launch.json` 一致，缺失的键会回落到默认值，因此也可以只写想改的那几项：

```json
{
    "rcon": {
        "host": "127.0.0.1",
        "port": 25575,
        "password": "awa"
    },
    "assets": {
        "download": true,
        "url": "https://raw.githubusercontent.com/LIPiston/Quizpass/master/assets/index.html",
        "path": "assets/index.html"
    },
    "broadcast": {
        "enabled": false,
        "onAdd": true,
        "onRemove": true,
        "addMessage": "[quizpass] {player} 已被添加到白名单",
        "removeMessage": "[quizpass] {player} 已被移出白名单"
    },
    "notify": {
        "enabled": false,
        "onAdd": true,
        "onRemove": true,
        "timeoutMillis": 10000,
        "email": {
            "enabled": false,
            "host": "smtp.example.com",
            "port": 465,
            "ssl": true,
            "startTls": false,
            "username": "",
            "password": "",
            "from": "",
            "to": [],
            "subject": "Quizpass 白名单变更: {player} ({event})",
            "trustAll": false
        },
        "callback": {
            "enabled": false,
            "url": "http://127.0.0.1:8080/quizpass/notify",
            "token": ""
        },
        "qqbot": {
            "enabled": false,
            "url": "http://127.0.0.1:3000",
            "groupId": "",
            "userId": "",
            "accessToken": ""
        }
    }
}
```

| 键 | 默认值 | 说明 |
|------|--------|------|
| `rcon.host` | `127.0.0.1` | RCON 地址 |
| `rcon.port` | `25575` | RCON 端口 |
| `rcon.password` | `awa` | RCON 密码，**请改成你自己的** |
| `assets.download` | `true` | 启动时是否从 `url` 下载申请页面 |
| `assets.url` | 本仓库 `master` 分支的 `assets/index.html` | 申请页面下载地址 |
| `assets.path` | `assets/index.html` | 申请页面写入位置，相对工作目录 |
| `broadcast.enabled` | `false` | 游戏内 `/say` 广播总开关 |
| `broadcast.onAdd` | `true` | 是否在**新增**白名单时广播 |
| `broadcast.onRemove` | `true` | 是否在**移除**白名单时广播 |
| `broadcast.addMessage` | `[quizpass] {player} 已被添加到白名单` | 新增时的广播文案，支持 `{player}` / `{event}` |
| `broadcast.removeMessage` | `[quizpass] {player} 已被移出白名单` | 移除时的广播文案，支持 `{player}` / `{event}` |

关于 `broadcast`：

- **面向服务器本身，不是面向管理员**。开关打开后，白名单变更成功时会通过 RCON 执行一条 `/say <文案>`：这行会出现在**服务端控制台日志**里，同时广播给所有在线玩家。管理员通知（`notify`）走的是另一套渠道，两者互不影响，可以只开一个。
- **只走 `/say`，不带颜色**。之所以不用 `/tellraw`，是因为 `/tellraw` 不写控制台日志（控制台里看不到），而 `/say` 两者都有——代价是 `/say` 把颜色代码当普通文本，无法上色。所以广播是纯文本的。
- **默认关闭**（`enabled: false`），不配置就完全不会执行任何 `/say`，服务端日志与玩家侧都不会多出任何一行。
- **广播字段里应只放游戏内名称**。默认文案只包含 `{player}`；`{event}` 会被替换成 `add` / `remove`。**不要把 QQ 号、游戏方式之类写进广播文案**——广播是公开的，QQ 号只应该出现在面向管理员的通知里。
- **广播是尽力而为的**：`/whitelist` 命令已经发给服务端之后才发 `/say`，因此广播失败（例如连接恰好断开）不会把玩家已经成功的申请变成错误，只在插件日志里记一条 WARN。
- 依赖 RCON：`enabled: true` 但 RCON 未就绪时不会广播（接口此时本身也会返回 `503`）。

关于 `assets`：

- **`download: false`** 时完全不发起网络请求，直接用磁盘上已有的 `assets/index.html`。适合离线部署，或想自己维护前端的情况。
- **`download: true`** 时每次启动都会拉一次并以远端内容覆盖本地。远端不可用（无法连接 / 非 200 / 空响应）只记 WARN，**保留本地文件**，不影响启动。
- `path` 会被限制在工作目录之内：越出工作目录的路径（例如 `../x.html`）会被拒绝并记 WARN，避免配置写错时覆盖到无关文件。
- 仓库里的 `assets/index.html` 就是下载源的副本，同时用作离线兜底；两者都是同一个文件，改动后需要同步。
- **下载是串行在启动路径上的**，所以远端不可达时会拖慢启动：连接超时上限 5 秒、整体请求上限 15 秒。实测指向一个黑洞地址（`10.255.255.1`）时，HTTP 服务在启动后约 5 秒才可用。页面不可用只影响首屏，`download: false` 或换用可达地址即可避免。
