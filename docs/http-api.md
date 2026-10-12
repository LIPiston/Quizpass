# HTTP API

| 方法 | 路径 | 请求体 | 说明 |
|------|------|--------|------|
| `POST` | `/api/add-whitelist` | `{"username":"Steve","account":"premium","qq":"123456789"}` | 校验三项字段后执行 `/whitelist add <username>` |
| `POST` | `/api/remove-whitelist` | `{"username":"Steve"}` | 执行 `/whitelist remove <username>`；面向管理员，页面上没有入口 |

`account` 取值为 `premium`（正版）或 `skin`（皮肤站），页面上的中文标签只是显示用，线上传的就是这两个值。

响应状态码：

| 状态码 | 场景 |
|--------|------|
| `200` | 命令已发出 |
| `400` | 字段缺失或格式非法（名称非 3-16 位字母数字下划线、`account` 非 `premium`/`skin`、QQ 非 5-11 位数字）；`error_message` 指明是哪一项 |
| `500` | 请求体不是合法 JSON，`error_message` 为 `API only accept JSON post body` |
| `503` | RCON 未连接或未认证完成，`error_message` 会说明原因 |

错误响应体形如（实测字段名就是 `error_message`，同一段文字还会重复出现在 `error_details_message` 里）：

```json
{
    "error_message": "游戏内名称格式无效：请使用 3-16 个字母、数字或下划线。",
    "error_details_message": "游戏内名称格式无效：请使用 3-16 个字母、数字或下划线。",
    "internal_error_name": "Bad Request",
    "request_path": "api/add-whitelist",
    "http_meta": { "http_version": "HTTP/1.1", "http_status": 400 }
}
```

```bash
curl -X POST http://127.0.0.1:12345/api/add-whitelist \
  -H 'Content-Type: application/json' \
  -d '{"username":"Steve","account":"premium","qq":"123456789"}'
```
