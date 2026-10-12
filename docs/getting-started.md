# 安装与运行

## 前置条件

- **JDK 25**：构建需要（`jvmToolchain(25)`）
- **Kalmia 本体**：本仓库只是插件，需要一个 Kalmia 运行目录来加载它
- **对端需提供 WebSocket 形式的 RCON**：客户端以 `ws://<host>:<port>` 发起 **WebSocket 握手**，在 WebSocket 二进制帧里承载 RCON 报文（默认 `ws://127.0.0.1:25575`）
- **服务端需开启白名单**：否则 `/whitelist add` 不会真正生效

> ⚠️ **关于对端**：客户端是向 `ws://<host>:<port>` 发起 WebSocket 握手，而原版 Minecraft 服务端的 RCON 是**裸 TCP**、没有 HTTP 升级握手。所以 25575 端口上必须是一个**讲 WebSocket 的对端**（例如把 RCON 包装成 WebSocket 的中间服务）。本仓库不含这层，也没有相关文档，部署前请先确认你对端究竟是什么。

## 快速开始

### 1. 准备 Kalmia

本仓库是**插件**，不是完整服务端，需要自备 Kalmia 本体。从 [Kalmia 仓库](https://github.com/cao-awa/Kalmia) 获取发行版 jar（本地开发用的是 `1.0.32`），放在一个工作目录里。

> 该 jar 是运行环境而非源码，**不随仓库分发**（本仓库的 `.gitignore` 已排除整个 `test/` 目录，本地运行时文件都放在那里）。

工作目录建议按下图放置：

```text
<工作目录>/
├── Kalmia-1.0.32.jar
├── configs/
│   ├── kalmia_http.json      # HTTP 监听配置
│   ├── launch.json           # 声明加载哪个插件
│   └── quizpass.json         # 本插件配置（首次启动自动生成）
├── libs/
│   └── Quizpass-1.0-SNAPSHOT.jar   # 第 2 步的构建产物
└── assets/
    └── index.html            # 玩家申请页（启动时按配置下载）
```

`configs/` 与 `assets/` 可以直接从本仓库对应目录取；`libs/` 需要自己新建，并放入第 2 步的构建产物。

> **本机已有一套可直接用的环境**：仓库里另有一个 `test/` 目录作为本地 Kalmia 工作目录（含 `Kalmia-1.0.32.jar`、`configs/`、`libs/`、`assets/`）。把构建产物复制进 `test/libs/` 后，直接在该目录启动即可，无需另搭。它整个已被 `.gitignore` 排除，**不纳入版本库**。

### 2. 构建插件

需要 JDK 25（`jvmToolchain(25)`，Gradle 会自行解析工具链）：

```bash
./gradlew build
```

产物在 `build/libs/Quizpass-1.0-SNAPSHOT.jar`。

### 3. 部署到 Kalmia

把上一步的 jar 复制进工作目录的 `libs/`，并确保 `configs/launch.json` 声明了这个入口：

```json
{
    "print_config_details": true,
    "entrypoint": [
        "top.lipiston.quizpass.entrypoint.QuizpassBootstrap#init"
    ]
}
```

> 本仓库的 `configs/launch.json` 写的就是上面的**全限定入口**。也可以简写成 `"quizpass"`——那是 jar 内 `META-INF/plugin.json` 的 `name` 字段，Kalmia 会据此找到入口。

### 4. 启动

```bash
java -jar Kalmia-1.0.32.jar
```

申请页面在 `http://127.0.0.1:12345/`。打开就是玩家申请表单（游戏内名称 / 游戏方式 / QQ 号），提交成功后切换到「恭喜，白名单已添加」。
