# mctpsalert — MC TPS 低值提醒（NeoForge 1.21.1 + AstrBot + OneBot v11）

> 开源协议：[GPL-3.0](LICENSE)

A.

| 指令 | 作用 |
|---|---|
| `/mctps status` | 显示当前配置与运行状态：检查间隔、告警/恢复阈值、webhook 地址、spark 是否安装、玩家加入提示开关 |
| `/mctps test` | 立即采样一次 TPS 并显示，同时推送一条 `tps_test` 事件到 AstrBot → 目标 QQ 群，验证 mod → AstrBot → QQ 全链路 |

`/mctps test` 的验证方式：
- 游戏内回显 `[mctpsalert] 当前 TPS=xx.xx（来源: spark/native）` → mod 运行正常
- 回显 `测试 webhook 推送成功` 且目标群收到 `[mctpsalert 连通测试]` 消息 → mod → AstrBot → QQ 链路正常
- 若回显 `webhook 推送失败` → 检查 AstrBot 插件是否运行、`webhookUrl` 地址是否正确

B.
可以让 AstrBot 插件直接用 RCON 每 15 分钟检测tps并解析。

`spark tps`

需要服务器开启 RCON
