package com.basem.mctpsalert;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 核心监控器：
 * 1. 按 tick 计数定时（默认 15 分钟）采样 TPS；
 * 2. 优先执行 spark 的 `spark tps` 命令并解析输出（默认取 5s 档），spark 未安装时回退原生 tick 时间；
 * 3. 滞回状态机（低于 alertThreshold 触发，回到 recoveryThreshold 复位）+ 冷却，防刷屏；
 * 4. 触发后异步 POST webhook 给 AstrBot 插件，不阻塞服务端线程。
 */
public class TpsMonitor {
    private static final Logger LOGGER = LoggerFactory.getLogger(TpsMonitor.class);

    private static final ExecutorService HTTP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mctpsalert-webhook");
        t.setDaemon(true);
        return t;
    });

    private static long tickCounter = 0;
    private static volatile boolean alerted = false;
    private static final AtomicLong lastAlertAtMillis = new AtomicLong(0);

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();
        tickCounter++;
        int intervalTicks = Config.CHECK_INTERVAL_MINUTES.get() * 1200;
        // 服务器启动约 30 秒后（600 tick）做一次初检，便于部署后快速验证
        if (tickCounter == 600 || tickCounter % intervalTicks == 0) {
            checkTps(server);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        HTTP_EXECUTOR.shutdownNow();
    }

    /** 玩家加入服务器时，向其发送一条 mod 加载成功提示，便于确认功能已生效 */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!Config.ANNOUNCE_ON_JOIN.get() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        String version = ModList.get().getModContainerById(MCTpsAlert.MODID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("?");
        String webhook = Config.WEBHOOK_URL.get().isEmpty() ? "未配置" : "已配置";
        Component msg = Component.literal(String.format(
                "[mctpsalert] Mod 加载成功 (v%s) | TPS 监控已启用 | 检查间隔 %d 分钟 | 阈值 %.1f | Webhook: %s",
                version, Config.CHECK_INTERVAL_MINUTES.get(), Config.ALERT_THRESHOLD.get(), webhook))
                .withStyle(ChatFormatting.GREEN);
        player.sendSystemMessage(msg);
    }

    private static void checkTps(MinecraftServer server) {
        TpsSnapshot snap = sampleTps(server);
        if (snap == null) {
            return;
        }
        double threshold = Config.ALERT_THRESHOLD.get();
        double recovery = Config.RECOVERY_THRESHOLD.get();
        LOGGER.info("[mctpsalert] 采样完成 source={} tps5s={} threshold={}",
                snap.source, String.format("%.2f", snap.tps), threshold);

        if (snap.tps < threshold) {
            if (!alerted && cooldownOk()) {
                alerted = true;
                lastAlertAtMillis.set(System.currentTimeMillis());
                fireAlert(snap);
            }
        } else if (snap.tps >= recovery) {
            alerted = false;
        }
    }

    private static boolean cooldownOk() {
        int min = Config.MIN_ALERT_COOLDOWN_MINUTES.get();
        if (min <= 0) {
            return true;
        }
        long last = lastAlertAtMillis.get();
        return last == 0 || System.currentTimeMillis() - last >= min * 60_000L;
    }

    // ---------- 调试指令 ----------

    /** /mctps status：展示当前配置与运行状态 */
    public static int runStatus(CommandSourceStack source) {
        String webhook = Config.WEBHOOK_URL.get();
        source.sendSuccess(() -> Component.literal(
                "[mctpsalert] 状态 | 检查间隔 " + Config.CHECK_INTERVAL_MINUTES.get() + " 分钟" +
                " | 告警阈值 " + Config.ALERT_THRESHOLD.get() +
                " | 恢复阈值 " + Config.RECOVERY_THRESHOLD.get() +
                " | Webhook: " + (webhook.isEmpty() ? "未配置" : webhook) +
                " | spark: " + (ModList.get().isLoaded("spark") ? "已安装" : "未安装(原生兜底)") +
                " | 玩家加入提示: " + (Config.ANNOUNCE_ON_JOIN.get() ? "开" : "关")), false);
        return 1;
    }

    /** /mctps test：立即采样 TPS 并推送一条测试 webhook 到 AstrBot，验证链路 */
    public static int runTest(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        TpsSnapshot snap = sampleTps(server);
        if (snap == null) {
            source.sendFailure(Component.literal("[mctpsalert] 采样失败（spark 未安装且 nativeFallback 关闭）"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(
                "[mctpsalert] 当前 TPS=%.2f（来源: %s）", snap.tps, snap.source)), false);

        String url = Config.WEBHOOK_URL.get();
        if (url.isEmpty()) {
            source.sendFailure(Component.literal("[mctpsalert] webhookUrl 未配置，跳过推送。请先在 config/mctpsalert-server.toml 配置"));
            return 0;
        }
        String payload = buildPayload(snap, Config.SERVER_NAME.get(), "tps_test");
        int timeoutSec = Config.WEBHOOK_TIMEOUT_SECONDS.get();
        boolean ok = postWebhookSync(url, payload, timeoutSec);
        if (ok) {
            source.sendSuccess(() -> Component.literal("[mctpsalert] 测试 webhook 推送成功，请检查 QQ 群是否收到消息"), false);
            return 1;
        }
        source.sendFailure(Component.literal("[mctpsalert] webhook 推送失败，请检查 AstrBot 插件是否运行及地址是否正确"));
        return 0;
    }

    // ---------- TPS 采样 ----------

    private static TpsSnapshot sampleTps(MinecraftServer server) {
        boolean sparkLoaded = ModList.get().isLoaded("spark");
        if (sparkLoaded) {
            List<String> lines = runCommandCapture(server, "spark tps");
            SparkTps spark = SparkTps.parse(lines);
            if (spark != null) {
                return new TpsSnapshot(spark.overall5s, spark.rows, "spark");
            }
            LOGGER.warn("[mctpsalert] spark 已安装但解析输出失败，捕获到的原始输出: {}", lines);
        }
        if (Config.NATIVE_FALLBACK.get()) {
            return nativeTps(server);
        }
        LOGGER.warn("[mctpsalert] 未安装 spark 且 nativeFallback 关闭，跳过本次采样");
        return null;
    }

    /** 在服务端主线程执行命令并捕获全部输出行 */
    private static List<String> runCommandCapture(MinecraftServer server, String command) {
        List<String> out = new ArrayList<>();
        CommandSource output = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                out.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        ServerLevel level = server.overworld();
        CommandSourceStack source = new CommandSourceStack(
                output,
                level.getSharedSpawnPos().getCenter(),
                Vec2.ZERO,
                level,
                4,
                "Server",
                Component.literal("Server"),
                server,
                null);
        try {
            server.getCommands().performPrefixedCommand(source, command);
        } catch (Exception e) {
            LOGGER.warn("[mctpsalert] 执行命令 '{}' 失败: {}", command, e.toString());
        }
        return out;
    }

    /** 原生兜底：取主世界最近记录的 tick 耗时（纳秒）换算 TPS */
    @Nullable
    private static TpsSnapshot nativeTps(MinecraftServer server) {
        long[] times = server.getTickTime(Level.OVERWORLD);
        if (times.length == 0) {
            return null;
        }
        double sum = 0;
        for (long t : times) {
            sum += t;
        }
        double avgNs = sum / times.length;
        double tps = Math.min(1_000_000_000.0 / avgNs, 20.0);
        return new TpsSnapshot(tps, null, "native");
    }

    // ---------- 告警推送 ----------

    private static void fireAlert(TpsSnapshot snap) {
        String serverName = Config.SERVER_NAME.get();
        String tpsStr = String.format("%.1f", snap.tps);
        String url = Config.WEBHOOK_URL.get();
        if (url.isEmpty()) {
            LOGGER.warn("[mctpsalert] TPS 低于阈值！server={} tps={} 阈值={}（未配置 webhookUrl，仅记录日志）",
                    serverName, tpsStr, Config.ALERT_THRESHOLD.get());
            return;
        }
        String payload = buildPayload(snap, serverName, "tps_low");
        int timeoutSec = Config.WEBHOOK_TIMEOUT_SECONDS.get();
        HTTP_EXECUTOR.submit(() -> postWebhook(url, payload, timeoutSec));
    }

    private static String buildPayload(TpsSnapshot snap, String serverName, String event) {
        JsonObject root = new JsonObject();
        root.addProperty("event", event);
        root.addProperty("serverName", serverName);
        root.addProperty("source", snap.source);
        root.addProperty("tps", snap.tps);
        root.addProperty("threshold", Config.ALERT_THRESHOLD.get());
        root.addProperty("timestamp", System.currentTimeMillis() / 1000);

        if (snap.rows != null && snap.rows.containsKey("*")) {
            JsonObject windows = new JsonObject();
            String[] names = {"5s", "10s", "1m", "5m", "15m"};
            double[] vals = snap.rows.get("*");
            for (int i = 0; i < vals.length && i < names.length; i++) {
                windows.addProperty("tps" + names[i], vals[i]);
            }
            root.add("windows", windows);

            JsonObject dims = new JsonObject();
            for (Map.Entry<String, double[]> e : snap.rows.entrySet()) {
                JsonArray arr = new JsonArray();
                for (double v : e.getValue()) {
                    arr.add(v);
                }
                dims.add(e.getKey(), arr);
            }
            root.add("dimensions", dims);
        }
        return new Gson().toJson(root);
    }

    private static void postWebhook(String url, String payload, int timeoutSec) {
        try {
            sendHttp(url, payload, timeoutSec);
        } catch (Exception e) {
            LOGGER.error("[mctpsalert] webhook 推送失败: {}", e.toString());
        }
    }

    /** 同步发送 webhook 并返回是否成功（供 /mctps test 使用） */
    private static boolean postWebhookSync(String url, String payload, int timeoutSec) {
        try {
            sendHttp(url, payload, timeoutSec);
            return true;
        } catch (Exception e) {
            LOGGER.error("[mctpsalert] webhook 推送失败: {}", e.toString());
            return false;
        }
    }

    private static void sendHttp(String url, String payload, int timeoutSec) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSec))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        LOGGER.info("[mctpsalert] webhook 推送完成，HTTP {}", resp.statusCode());
    }

    // ---------- 数据模型 ----------

    /** spark tps 输出：key 为维度名（* 表示整体），值为 [5s, 10s, 1m, 5m, 15m] 五档 */
    private record SparkTps(double overall5s, Map<String, double[]> rows) {
        @Nullable
        static SparkTps parse(List<String> lines) {
            Map<String, double[]> rows = new LinkedHashMap<>();
            double overall = -1;
            for (String raw : lines) {
                String line = raw.trim();
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String key = line.substring(0, colon).trim();
                String[] parts = line.substring(colon + 1).split(",");
                if (parts.length == 0) {
                    continue;
                }
                double[] vals = new double[parts.length];
                boolean ok = true;
                for (int i = 0; i < parts.length; i++) {
                    try {
                        vals[i] = Double.parseDouble(parts[i].trim());
                    } catch (NumberFormatException e) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
                if (key.equals("*")) {
                    overall = vals[0];
                }
                rows.put(key, vals);
            }
            if (overall < 0) {
                return null;
            }
            return new SparkTps(overall, rows);
        }
    }

    private record TpsSnapshot(double tps, Map<String, double[]> rows, String source) {
    }
}
