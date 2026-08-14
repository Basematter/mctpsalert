# mctpsalert v1.1.0 发布说明

> 仓库：[github.com/Basematter/mctpsalert](https://github.com/Basematter/mctpsalert)

MC 服务器 TPS 低值告警系统：NeoForge 1.21.1 服务端 mod 定时采样 TPS，低于阈值时经 HTTP webhook 推送给 AstrBot 插件，由 AstrBot 通过 OneBot v11 向指定 QQ 群发送告警；插件同时注册 LLM 函数工具，可在群里直接生成并发送 TPS 折线图。

## v1.1.0 更新内容

- **新增 LLM 函数工具 `send_tps_chart`**：在接入 LLM 的 QQ 群里说"发一下 TPS 折线图"，机器人自动调用工具，用 matplotlib 生成**面积图** PNG 并发送到当前会话
  - TPS 以面积图呈现（左 Y 轴固定 0–20、阈值虚线、中文字体自适应），**同时叠加显示在线人数**（右侧第二 Y 轴固定 0–8 人，橙色折线）；X 轴时间格式 `HH:MM:SS`
  - 仅保留下（bottom）与左（left）两条边框线，去掉上/右线避免重叠
  - 可指定采样点数 `count`，10~500
- **新增 TPS 历史存储**：mod 每次采样推送 `tps_sample`（含 `playerCount` 在线人数），插件写入 SQLite（`data/plugins/astrbot_plugin_mctps_alert/tps_history.db`），作为图表数据来源
- **spark 数据获取改用官方 API**：优先 `SparkProvider.get().tps().poll(5s)`（类型化调用公共接口，非反射，规避 JPMS 模块隔离），命令解析仅作兜底，修复原 spark 命令输出捕获为空的问题
- 依赖新增 `matplotlib`（自动安装）

## 本版本包含

| 文件 | 说明 |
| --- | --- |
| `mctpsalert.jar` | NeoForge 1.21.1 服务端 mod（客户端可选安装，不安装也不影响） |
| `astrbot_plugin_mctps_alert.zip` | AstrBot 插件（v1.1.0），接收 webhook、推送 QQ 群消息、提供 TPS 面积图函数工具（含在线人数） |

## 系统架构

```
MC 服务器进程                        AstrBot 进程                   QQ
┌───────────────────────┐   webhook    ┌────────────────────────┐   ┌──────┐
│ spark mod (spark tps) │──TPS 数据──▶│ mctps_alert 插件        │   │      │
│ mctpsalert mod        │─HTTP POST──▶│  (aiohttp :8080/notify) │──▶│ 群聊  │
└───────────────────────┘             └────────────────────────┘   └──────┘
```

## 一、mod 安装（服务端）

1. 将 `mctpsalert.jar` 放入服务器 `mods/` 目录（需 NeoForge 1.21.1；spark mod 建议安装，未安装时自动使用原生 tick 计算兜底）。
2. 启动服务器，自动生成配置文件 `config/mctpsalert-server.toml`，按需修改：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `checkIntervalMinutes` | 3 | 采样间隔（分钟） |
| `alertThreshold` | 18 | TPS 低于该值触发告警（取 spark 5s 档 / 原生最近 100 tick 均值） |
| `recoveryThreshold` | 20 | TPS 回到该值后允许再次告警（滞回） |
| `minAlertCooldownMinutes` | 10 | 告警最小冷却（分钟） |
| `webhookUrl` | `http://127.0.0.1:8080/notify` | AstrBot 插件地址；留空仅记日志 |
| `webhookTimeoutSeconds` | 5 | webhook 超时（秒） |
| `serverName` | 空 | 随推送的服务器名，用于区分多服务器 |
| `nativeFallback` | true | 未安装 spark 时用原生 tick 计算 TPS |
| `announceOnJoin` | true | 玩家加入时发送 mod 加载成功提示 |

## 二、AstrBot 插件安装

1. 在 AstrBot WebUI「插件市场 → 安装本地插件」上传 `astrbot_plugin_mctps_alert.zip`。
2. 启用插件，打开插件配置确认：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `server_host` / `server_port` | `127.0.0.1` / `8080` | webhook 监听地址，需与 mod 的 `webhookUrl` 一致 |
| `api_token` | 空 | 可选，设置后 mod 需在配置里带 `Authorization: Bearer <token>` 才能调用 |
| `platform_id` | `aiocqhttp` | 平台适配器类型名（自动匹配实例 ID） |
| `target_groups` | 空 | 目标 QQ 群号列表，可配多个 |
| `message_prefix` / `message_suffix` | 空 | 推送消息前后缀 |

3. 重启 AstrBot 使插件生效。

## 三、指令

| 指令 | 说明 |
| --- | --- |
| `/mctps status` | 显示当前配置与运行状态（间隔、阈值、webhook、spark 是否安装等） |
| `/mctps test` | 立即采样并推送 `tps_test` 到目标 QQ 群，验证 mod → AstrBot → QQ 全链路 |

## 四、webhook 数据格式（二次开发用）

`event` 字段区分三种事件：

| event | 含义 | 插件行为 |
| --- | --- | --- |
| `tps_low` | TPS 低于阈值 | 向目标群推送告警 |
| `tps_test` | `/mctps test` 连通测试 | 向目标群推送测试消息 |
| `tps_sample` | 每次定时采样（默认 3 分钟） | 写入 SQLite 历史（含在线人数），供图表函数工具使用，不发群消息 |

```json
{
  "event": "tps_low",
  "serverName": "MC-Server",
  "source": "spark",
  "tps": 17.2,
  "threshold": 18,
  "playerCount": 12,
  "timestamp": 1760000000,
  "windows": { "tps5s": 17.2, "tps10s": 17.5, "tps1m": 18.2, "tps5m": 19.0, "tps15m": 19.5 },
  "dimensions": { "*": [17.2, 17.5, 18.2, 19.0, 19.5], "overworld": [...], "the_nether": [...] }
}
```

## 五、快速验证

1. 启动服务器，约 30 秒后首次采样，日志出现 `[mctpsalert] 采样完成 source=spark tps5s=20.00 ...`。
2. 服务端执行 `/mctps test`，确认目标群收到 `[mctpsalert 连通测试]` 消息。
3. 临时调低 `alertThreshold` 高于当前 TPS，等待下个采样周期，确认告警推送正常。

## 注意事项

- mod 仅在**专用服务器（DEDICATED_SERVER）**上运行；客户端即使安装也不注册任何事件。
- spark 优先：若已安装 spark 但 API 与命令解析均失败（日志提示），请查看 `[mctpsalert] 捕获到的原始输出` 日志反馈排查。
- TPS 面积图通过 LLM 函数工具（`send_tps_chart`）在群里发送，需在 AstrBot 中配置好 LLM Provider，插件首次安装时会自动安装 `matplotlib` 依赖。

## 开源协议

GNU General Public License v3.0（GPL-3.0），详见仓库 `LICENSE`。
