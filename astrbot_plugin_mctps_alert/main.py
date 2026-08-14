import os
import sqlite3
import threading
from dataclasses import field
from datetime import datetime
from typing import Any

from aiohttp import web
from pydantic.dataclasses import dataclass

from astrbot.api import AstrBotConfig, logger
from astrbot.api.event import AstrMessageEvent, MessageChain, filter
from astrbot.api.message_components import Image, Plain
from astrbot.api.star import Context, Star, register
from astrbot.core.agent.run_context import ContextWrapper
from astrbot.core.agent.tool import FunctionTool, ToolExecResult
from astrbot.core.astr_agent_context import AstrAgentContext
from astrbot.core.utils.astrbot_path import get_astrbot_plugin_data_path

PLUGIN_DATA_DIR = os.path.join(get_astrbot_plugin_data_path(), "astrbot_plugin_mctps_alert")
DB_PATH = os.path.join(PLUGIN_DATA_DIR, "tps_history.db")
_SCHEMA = """
CREATE TABLE IF NOT EXISTS tps_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts INTEGER NOT NULL,
    server_name TEXT NOT NULL DEFAULT '',
    source TEXT NOT NULL DEFAULT '',
    tps REAL NOT NULL,
    threshold REAL,
    player_count INTEGER NOT NULL DEFAULT 0,
    event TEXT NOT NULL DEFAULT 'tps_sample'
);
CREATE INDEX IF NOT EXISTS idx_tps_history_ts ON tps_history(ts);
"""
# SQLite 连接仅由 webhook 处理线程 + 工具调用线程使用，加锁保证安全
_db_lock = threading.Lock()


def _init_db() -> None:
    os.makedirs(PLUGIN_DATA_DIR, exist_ok=True)
    with _db_lock, sqlite3.connect(DB_PATH) as conn:
        conn.executescript(_SCHEMA)
        # 兼容旧库：补充 player_count 列
        cols = {row[1] for row in conn.execute("PRAGMA table_info(tps_history)")}
        if "player_count" not in cols:
            conn.execute("ALTER TABLE tps_history ADD COLUMN player_count INTEGER NOT NULL DEFAULT 0")


def _insert_sample(server_name: str, source: str, tps: float, threshold, event: str, player_count: int = 0) -> None:
    with _db_lock, sqlite3.connect(DB_PATH) as conn:
        conn.execute(
            "INSERT INTO tps_history (ts, server_name, source, tps, threshold, player_count, event) VALUES (?,?,?,?,?,?,?)",
            (int(datetime.now().timestamp()), server_name, source, float(tps), threshold, int(player_count), event),
        )


def _query_history(limit: int | None = None) -> list[dict]:
    """返回按时间正序的 TPS 历史记录（不含告警/测试事件）；limit 为 None 时返回全部历史"""
    with _db_lock, sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        base = (
            "SELECT ts, server_name, source, tps, threshold, player_count, event FROM tps_history "
            "WHERE event = 'tps_sample'"
        )
        if limit is None:
            rows = conn.execute(base + " ORDER BY ts ASC").fetchall()
        else:
            rows = conn.execute(base + " ORDER BY ts DESC LIMIT ?", (limit,)).fetchall()
            rows.reverse()
    return [dict(r) for r in rows]


def render_tps_chart(rows: list[dict]) -> str:
    """用 matplotlib 绘制 TPS 面积图（含在线人数）并保存为 PNG，返回文件路径。"""
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib import dates as mdates, font_manager
    from matplotlib.lines import Line2D
    from matplotlib.patches import Patch

    # 优先使用中文字体（Windows 微软雅黑/黑体），避免中文乱码
    for font_name in ["Microsoft YaHei", "SimHei", "PingFang SC", "Noto Sans CJK SC"]:
        try:
            font_manager.findfont(font_name, fallback_to_default=False)
            plt.rcParams["font.family"] = [font_name]
            break
        except Exception:
            continue
    plt.rcParams["axes.unicode_minus"] = False

    times = [datetime.fromtimestamp(r["ts"]) for r in rows]
    tps = [r["tps"] for r in rows]
    players = [int(r.get("player_count") or 0) for r in rows]
    threshold = rows[-1].get("threshold") or 18.0
    n = len(rows)
    # 数据量大时不再画每个采样点的小圆点，避免拥挤
    marker = "o" if n <= 200 else ""

    fig, ax = plt.subplots(figsize=(10, 5), dpi=110)

    # TPS 面积图
    ax.fill_between(times, tps, 0, color="#2563eb", alpha=0.35, label="TPS")
    ax.plot(times, tps, color="#2563eb", linewidth=1.6, marker=marker, markersize=2.5)
    ax.axhline(threshold, color="#dc2626", linestyle="--", linewidth=1.2)

    # 只保留下(left)与左(bottom)边框线，去掉上(top)/右(right)线防止与其他元素重叠
    for spine in ("top", "right"):
        ax.spines[spine].set_visible(False)

    # 左 Y 轴（TPS）：固定 0–20
    ax.set_ylim(0.0, 20.0)
    ax.set_ylabel("TPS")
    ax.grid(True, alpha=0.3, axis="y")

    # 在线人数：双 Y 轴，右侧只显示刻度数字、不画轴线，避免与 TPS 轴重叠
    ax2 = ax.twinx()
    ax2.plot(times, players, color="#f59e0b", linewidth=1.6, marker=marker, markersize=2.5,
             label="在线人数")
    for spine in ("top", "left", "right"):
        ax2.spines[spine].set_visible(False)
    ax2.tick_params(axis="y", length=0)
    ax2.set_ylim(0, 8)  # 右 Y 轴（在线人数）：固定 0–8 人
    ax2.set_ylabel("在线人数")

    # 合并图例（TPS 面积、阈值虚线、在线人数）
    handles = [
        Patch(facecolor="#2563eb", alpha=0.35, label="TPS"),
        Line2D([0], [0], color="#dc2626", linestyle="--", label=f"阈值 {threshold:.1f}"),
        Line2D([0], [0], color="#f59e0b", marker="s", linewidth=1.6, label="在线人数"),
    ]
    ax.legend(handles=handles, loc="upper left")

    # X 轴：刻度从首次采样时间到现在；按时间跨度选择格式，限制刻度数量避免拥挤
    ax.xaxis.set_major_locator(plt.MaxNLocator(6))
    span_seconds = (times[-1] - times[0]).total_seconds() if n > 1 else 0
    if span_seconds <= 24 * 3600:
        x_fmt = "%H:%M:%S"
    elif span_seconds <= 60 * 24 * 3600:
        x_fmt = "%m-%d %H:%M"
    else:
        x_fmt = "%Y-%m-%d"
    ax.xaxis.set_major_formatter(mdates.DateFormatter(x_fmt))
    ax.tick_params(axis="x", rotation=15)
    for label in ax.get_xticklabels():
        label.set_ha("right")

    server = rows[-1].get("server_name") or ""
    title = f"TPS 折线图（{server} · 最近 {len(rows)} 次采样）" if server else f"TPS 折线图（最近 {len(rows)} 次采样）"
    ax.set_title(title, fontsize=12)

    fig.tight_layout()
    png_path = os.path.join(PLUGIN_DATA_DIR, "tps_chart.png")
    fig.savefig(png_path, bbox_inches="tight")
    plt.close(fig)
    return png_path


@register(
    "mctps_alert",
    "Basem",
    "MC TPS 低值提醒：接收 MC 服务器 mctpsalert mod 的 webhook，推送告警到指定 QQ 群（OneBot v11），并提供 TPS 折线图函数工具",
    "1.1.0",
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
        """插件加载后初始化数据库、启动 webhook 服务并注册函数工具"""
        _init_db()
        await self._start_http_server()
        self._register_llm_tools()

    async def terminate(self):
        """插件卸载时关闭 HTTP 服务"""
        if self.site:
            await self.site.stop()
        if self.runner:
            await self.runner.cleanup()
        self._running = False
        logger.info("[mctps_alert] webhook 服务已停止")

    def _register_llm_tools(self):
        """注册 LLM 函数工具：send_tps_chart（发送 TPS 折线图）"""
        try:
            self.context.add_llm_tools(SendTpsChartTool())
            logger.info("[mctps_alert] 已注册 LLM 函数工具 send_tps_chart")
        except Exception as e:
            logger.error(f"[mctps_alert] 注册 LLM 函数工具失败: {e}")

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
        # tps_sample：仅记录历史（供折线图函数工具使用），不发群消息
        if event == "tps_sample":
            try:
                _insert_sample(
                    server_name=str(data.get("serverName", "")),
                    source=str(data.get("source", "")),
                    tps=float(data.get("tps", 0)),
                    threshold=data.get("threshold"),
                    event="tps_sample",
                    player_count=data.get("playerCount", 0),
                )
            except Exception as e:
                logger.error(f"[mctps_alert] 记录 TPS 样本失败: {e}")
                return web.json_response({"success": False, "error": "db error"}, status=500)
            return web.json_response({"success": True, "event": "tps_sample"})

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


@dataclass
class SendTpsChartTool(FunctionTool[AstrAgentContext]):
    """LLM 函数工具：生成并发送 TPS 折线图到当前会话。"""

    __pydantic_config__ = {"arbitrary_types_allowed": True}

    name: str = "send_tps_chart"
    description: str = (
        "生成并发送 MC 服务器 TPS 折线图图片到当前会话，默认显示从首次采样到现在的全部历史（含在线人数）。"
        "当用户想看服务器 TPS 变化趋势、TPS 折线图、近期性能图表、或者问 TPS 是否稳定时调用。"
    )
    parameters: dict[str, Any] = field(
        default_factory=lambda: {
            "type": "object",
            "properties": {
                "count": {
                    "type": "integer",
                    "description": "只显示最近多少次采样（每次采样间隔约 3 分钟），范围 10~500；不传或传 -1 时显示全部历史（从首次采样至今）。",
                    "default": -1,
                },
            },
            "required": [],
        }
    )

    async def call(self, context: ContextWrapper[AstrAgentContext], count: int = -1) -> ToolExecResult:
        """执行：查询历史 → 画折线图 → 发送到当前会话。"""
        # count < 0 / 解析失败 → 全部历史；否则取最近 count 次
        limit = None
        try:
            if int(count) >= 0:
                limit = max(10, min(500, int(count)))
        except (TypeError, ValueError):
            limit = None

        rows = _query_history(limit)
        if not rows:
            return "暂无 TPS 历史数据。请确认服务器已安装 mod 且配置了正确的 webhookUrl，并已产生至少一次采样。"

        event = context.context.event
        try:
            png_path = render_tps_chart(rows)
            if limit is None:
                caption = (
                    f"TPS 历史（{datetime.fromtimestamp(rows[0]['ts']).strftime('%m-%d %H:%M')} "
                    f"至 {datetime.fromtimestamp(rows[-1]['ts']).strftime('%m-%d %H:%M')}，共 {len(rows)} 次）"
                )
            else:
                caption = (
                    f"最近 {len(rows)} 次 TPS 采样"
                    f"（{datetime.fromtimestamp(rows[0]['ts']).strftime('%m-%d %H:%M')} 起）"
                )
            await event.send(
                MessageChain(
                    chain=[
                        Image.fromFileSystem(png_path),
                        Plain(caption),
                    ]
                )
            )
        except Exception as e:
            logger.error(f"[mctps_alert] 发送 TPS 折线图失败: {e}", exc_info=True)
            return f"生成或发送 TPS 折线图失败: {e}"

        return f"已向当前会话发送 TPS 折线图，共 {len(rows)} 个采样点（{caption}）。"
