# mctpsalert — MC TPS 低值提醒（NeoForge 1.21.1 + AstrBot + OneBot v11）

> 开源协议：[GPL-3.0](LICENSE)

每隔可配置的时间（默认 3 分钟）获取服务器 TPS：优先执行 spark mod 的 `spark tps` 指令并解析输出（默认取 **5s 档**），
TPS 低于阈值（默认 18）时通过 HTTP webhook 推送给 AstrBot 插件，由 AstrBot 用 OneBot v11 向**指定 QQ 群**发送提醒。

```
MC 服务器进程                        AstrBot 进程                   QQ
┌───────────────────────┐   webhook    ┌────────────────────────┐   ┌──────┐
│ spark mod (spark tps) │──TPS 数据──▶│ mctps_alert 插件        │   │      │
│ mctpsalert mod        │─HTTP POST──▶│  (aiohttp :8080/notify) │──▶│ 群聊  │
│ 定时15min·滞回判定     │             │   context.send_message  │   │      │
└───────────────────────┘             └────────────────────────┘   └──────┘
```

## 目录结构

```
rcon/
├── mctpsalert/                        # NeoForge mod（需要 JDK 21 构建）
│   └── src/main/java/com/basem/mctpsalert/
│       ├── MCTpsAlert.java            # mod 主类，注册服务端配置
│       ├── Config.java                # config/mctpsalert-server.toml 配置项
│       └── TpsMonitor.java            # 定时采样 / spark 解析 / 原生兜底 / 滞回状态机 / webhook
└── astrbot_plugin_mctps_alert/        # AstrBot 插件
    ├── main.py
    ├── metadata.yaml
    ├── _conf_schema.json              # WebUI 配置面板
    └── requirements.txt
```

## 一、构建 mod

本机无需安装 JDK 21 / Gradle：工程内置 foojay 工具链，Gradle wrapper 会自动下载 JDK 21。

```powershell
cd mctpsalert
.\gradlew build        # 首次会自动下载 Gradle 与 NeoForge 依赖，较慢
```

产物在 `build/libs/mctpsalert-1.0.0.jar`，放入服务端 `mods/` 目录。
服务器还需同时安装 [spark](https://modrinth.com/mod/spark)（可选，不装会自动回退原生 TPS 计算）。

> **部署方式：仅服务端，客户端可选**。
> mod 的所有逻辑（TPS 采样、webhook 推送、玩家加入提示、调试指令）都只在**专用服务器（DEDICATED_SERVER）**上注册运行；客户端即使安装本 mod 也不会注册任何事件、无任何副作用（单机/客户端不会触发监控）。
> 因此可以只把 jar 放进服务器的 `mods/` 目录；客户端玩家**不装**即可正常进服（需 NeoForge 客户端），装不装都不影响功能。

## 二、mod 配置（config/mctpsalert-server.toml）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `checkIntervalMinutes` | 15 | 采样间隔（分钟） |
| `alertThreshold` | 18.0 | 低于该值触发告警（判定用 spark tps 的 5s 档） |
| `recoveryThreshold` | 20.0 | TPS 回到该值及以上后允许再次告警（滞回防刷屏） |
| `minAlertCooldownMinutes` | 10 | 距上次告警不足该分钟数不重复推送 |
| `webhookUrl` | http://127.0.0.1:8080/notify | AstrBot 插件地址（默认与插件默认监听一致）；留空仅记日志 |
| `webhookTimeoutSeconds` | 5 | webhook 请求超时 |
| `serverName` | MC-Server | 服务器名，随推送内容展示，多服时用于区分 |
| `nativeFallback` | true | 未装 spark 时回退原生 tick 时间算 TPS |
| `announceOnJoin` | true | 玩家加入服务器时向其发送一条 mod 加载成功提示（含版本/间隔/阈值/webhook 状态） |

## 三、AstrBot 插件安装与配置

1. 将 `astrbot_plugin_mctps_alert/` 整个目录放入 AstrBot 的 `data/plugins/`（或插件管理上传 zip）。
2. 重启 AstrBot，在「插件」页启用并打开配置：
   - `target_groups`：**要接收告警的 QQ 群号，每行一个**（即“可选群聊”）；
   - `server_host`：MC 服务器与 AstrBot 同机填 `127.0.0.1`，跨机填 `0.0.0.0` 并放行 `server_port`；
   - `server_port`：默认 `8080`，需与 mod 的 `webhookUrl` 一致；
   - `api_token`：建议设置，mod 侧可配合（可选，留空则接口不鉴权）；
   - `platform_id`：OneBot v11 保持 `aiocqhttp`（即消息平台**适配器类型名**；插件会自动按此类型查找实例 ID 后发送，无需手动填实例 ID）。
3. 在任意会话发 `mctps_test` 可向全部目标群推送一条测试消息验证链路。

## 四、游戏内调试指令（mod）

服务器内（需 OP/管理员权限）执行：

| 指令 | 作用 |
|---|---|
| `/mctps status` | 显示当前配置与运行状态：检查间隔、告警/恢复阈值、webhook 地址、spark 是否安装、玩家加入提示开关 |
| `/mctps test` | 立即采样一次 TPS 并显示，同时推送一条 `tps_test` 事件到 AstrBot → 目标 QQ 群，验证 mod → AstrBot → QQ 全链路 |

`/mctps test` 的验证方式：
- 游戏内回显 `[mctpsalert] 当前 TPS=xx.xx（来源: spark/native）` → mod 运行正常
- 回显 `测试 webhook 推送成功` 且目标群收到 `[mctpsalert 连通测试]` 消息 → mod → AstrBot → QQ 链路正常
- 若回显 `webhook 推送失败` → 检查 AstrBot 插件是否运行、`webhookUrl` 地址是否正确

## 五、验证流程

1. 启动服务器，约 30 秒后 mod 做首次采样，日志出现 `[mctpsalert] 采样完成 source=spark tps5s=20.00 ...`。
2. 用 `mctps_test` 确认群消息可达。
3. 临时把 `alertThreshold` 调到 20 以上，等待下个采样周期（或重启验证），确认低 TPS 推送成功。
4. 恢复阈值为 18，TPS 回到 `recoveryThreshold` 前不会重复轰炸。

## 六、webhook 数据格式（供二次开发）

`event` 字段区分三种事件：

| event | 含义 | 插件行为 |
| --- | --- | --- |
| `tps_low` | TPS 低于阈值（告警） | 向目标 QQ 群推送告警 |
| `tps_test` | `/mctps test` 连通测试 | 向目标 QQ 群推送测试消息 |
| `tps_sample` | 每次定时采样（默认每 3 分钟） | 写入 SQLite 历史（含在线人数，`data/plugins/astrbot_plugin_mctps_alert/tps_history.db`），供图表函数工具使用，不发群消息 |

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

## 七、LLM 函数工具：发送 TPS 面积图

插件注册了函数工具 **`send_tps_chart`**（FunctionTool，AstrBot Agent 体系）。在接入 LLM 的 QQ 群里，
机器人会根据用户意图（如"发一下 TPS 折线图"、"最近服务器性能怎么样"）自动调用该工具：

- 从 SQLite 读取 TPS 历史（参数 `count` 可只取最近 N 次，10~500；**不传则显示从首次采样到现在的全部历史**）
- 用 matplotlib 生成**面积图** PNG：
  - TPS 以蓝色面积图呈现（左 Y 轴固定 0–20、告警阈值虚线、中文字体自适应）
  - **在线人数**以橙色折线叠加在右侧第二 Y 轴（`twinx`，固定 0–8 人）
  - X 轴刻度从首次采样时间到现在，格式按跨度自适应（≤1 天 `HH:MM:SS`，≤60 天 `MM-DD HH:MM`，更长 `YYYY-MM-DD`）
  - 仅保留下（bottom）与左（left）两条边框线，去掉上/右线避免与其他元素重叠
- 通过 OneBot v11 将图片发送到**当前会话**

工具说明：`生成并发送 MC 服务器最近 TPS 折线图（含在线人数）图片到当前会话。`

---

## 附录 · 方案 B：不用 mod，AstrBot 直接走 RCON

如果你不想编译/部署 Java mod，可以让 AstrBot 插件直接用 RCON 每 15 分钟跑一次 `spark tps` 并解析。
**零 Java 代码**，代价：服务器需开启 RCON（`server.properties` 设 `enable-rcon=true`、`rcon.password=...`，端口默认 `25575`）、