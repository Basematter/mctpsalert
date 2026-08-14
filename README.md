# mctpsalert — MC TPS 低值提醒（NeoForge 1.21.1 + AstrBot + OneBot v11）

> 开源协议：[GPL-3.0](LICENSE)

| 指令 | 作用 |
|---|---|
| `/mctps status` | 显示当前配置与运行状态：检查间隔、告警/恢复阈值、webhook 地址、spark 是否安装、玩家加入提示开关 |
| `/mctps test` | 立即采样一次 TPS 并显示，同时推送一条 `tps_test` 事件到 AstrBot → 目标 QQ 群，验证 mod → AstrBot → QQ 全链路 |

