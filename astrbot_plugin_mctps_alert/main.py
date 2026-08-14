from datetime import datetime

from aiohttp import web

from astrbot.api import AstrBotConfig, logger
from astrbot.api.event import AstrMessageEvent, MessageChain, filter
from astrbot.api.message_components import Plain
from astrbot.api.star import Context, Star, register


@register(
    "mctps_alert",
    "Basem",
    "MC TPS 低值提醒：接收 MC 服务器 mctpsalert mod 的 webhook，推送告警到指定 QQ 群（OneBot v11）",
    "1.0.0",
)
class MCTpsAlertPlugin(Star):
    def __init__(self, context: Context, config: AstrBotConfig):
        super().__init__(context)
        self.config = config
        self.app = None
        self.runner = None
        self.site = None
        self._running = False

    async def initialize(self):
        """插件加载后启动 webhook HTTP 服务"""
        await self._start_http_server()

    async def terminate(self):
        """插件卸载时关闭 HTTP 服务"""
        if self.site:
            await self.site.stop()
        if self.runner:
            await self.runner.cleanup()
        self._running = False
        logger.info("[mctps_alert] webhook 服务已停止")

    # ---------- HTTP ----------

    async def _start_http_server(self):
        host = self.config.get("server_host", "127.0.0.1")
        port = int(self.config.get("server_port", 8080))
        self.app = web.Application()
        self.app.router.add_post("/notify", self._handle_notify)
        self.app.router.add_get("/health", self._handle_health)
        self.runner = web.AppRunner(self.app)
        await self.runner.setup()
        self.site = web.TCPSite(self.runner, host, port)
        await self.site.start()
        self._running = True
        logger.info(f"[mctps_alert] webhook 服务已启动: http://{host}:{port}/notify")

    def _auth_ok(self, request: web.Request) -> bool:
        token = self.config.get("api_token", "")
        if not token:
            return True
        return request.headers.get("Authorization", "") == f"Bearer {token}"

    async def _handle_notify(self, request: web.Request):
        if not self._auth_ok(request):
            return web.json_response({"success": False, "error": "unauthorized"}, status=401)
        try:
            data = await request.json()
        except Exception:
            return web.json_response({"success": False, "error": "invalid json"}, status=400)

        event = data.get("event")
        text = self._format_message(data)
        if not text:
            return web.json_response({"success": False, "error": "unsupported event"}, status=400)

        results = await self._broadcast(text)
        ok = sum(1 for r in results if r)
        return web.json_response({"success": ok > 0, "sent": ok, "total": len(results)})

    async def _handle_health(self, request: web.Request):
        return web.json_response(
            {
                "status": "ok",
                "running": self._running,
                "target_groups": self.config.get("target_groups", []),
                "server": f"{self.config.get('server_host', '127.0.0.1')}:{self.config.get('server_port', 8080)}",
            }
        )

    # ---------- 消息 ----------

    async def _broadcast(self, text: str) -> list:
        groups = self.config.get("target_groups", []) or []
        if not groups:
            logger.warning("[mctps_alert] 未配置 target_groups，消息未发送")
            return []
        adapter = self.config.get("platform_id", "aiocqhttp")
        # 动态查找匹配适配器类型名的平台实例，取其唯一实例 ID（platform.meta().id）。
        # 实例 ID 由用户在 WebUI 创建平台适配器时填写，可能不是适配器类型名（例如 "default"）。
        platform_ids = []
        try:
            pm = getattr(self.context, "platform_manager", None)
            if pm is not None:
                for p in pm.platform_insts:
                    meta = p.meta()
                    if meta.name == adapter:
                        platform_ids.append(meta.id)
        except Exception:
            pass
        if not platform_ids:
            logger.warning(f"[mctps_alert] 未找到平台实例（adapter={adapter}），消息未发送")
            return []
        chain = MessageChain(chain=[Plain(text)])
        results = []
        for gid in groups:
            for platform_id in platform_ids:
                # 会话字符串格式：{platform_id}:GroupMessage:{群号}
                session = f"{platform_id}:GroupMessage:{int(gid)}"
                try:
                    await self.context.send_message(session, chain)
                    results.append(True)
                    logger.info(f"[mctps_alert] 已推送到群 {gid}（platform_id={platform_id}）")
                except Exception as e:
                    logger.error(f"[mctps_alert] 推送到群 {gid} 失败: {e}")
                    results.append(False)
        return results

    def _format_message(self, data: dict) -> str:
        event = data.get("event")
        server = data.get("serverName", "未知服务器")
        tps = data.get("tps")
        threshold = data.get("threshold", 18)
        ts = data.get("timestamp")
        time_str = datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M:%S") if ts else "-"
        prefix = self.config.get("message_prefix", "")
        suffix = self.config.get("message_suffix", "")
        if event == "tps_low":
            body = (
                "[TPS 告警]\n"
                f"服务器: {server}\n"
                f"当前 TPS: {tps}（阈值 {threshold}）\n"
                f"时间: {time_str}"
            )
        elif event == "tps_test":
            body = (
                "[mctpsalert 连通测试]\n"
                f"服务器: {server}\n"
                f"当前 TPS: {tps}\n"
                f"时间: {time_str}\n"
                "链路正常: mod → AstrBot → QQ 群"
            )
        else:
            return ""
        return f"{prefix}{body}{suffix}"

    # ---------- 指令 ----------

    @filter.command("mctps_test")
    async def mctps_test(self, event: AstrMessageEvent):
        """向所有配置的目标群发送一条测试消息，用于验证链路"""
        results = await self._broadcast("【mctps_alert 测试】webhook 推送链路正常")
        ok = sum(1 for r in results if r)
        yield event.plain_result(f"测试推送完成：成功 {ok}/{len(results)} 个群")
