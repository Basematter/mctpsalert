package com.basem.mctpsalert;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge 服务端配置（生成于 config/mctpsalert-server.toml）。
 */
public class Config {
    public static final ModConfigSpec SPEC;

    /** 检查间隔（分钟），每 N 分钟采样一次 TPS */
    public static final ModConfigSpec.IntValue CHECK_INTERVAL_MINUTES;
    /** 告警阈值：TPS 低于该值触发（默认使用 spark tps 的 5s 档） */
    public static final ModConfigSpec.DoubleValue ALERT_THRESHOLD;
    /** 恢复阈值：TPS 回到该值及以上后，才允许再次触发（滞回，防刷屏） */
    public static final ModConfigSpec.DoubleValue RECOVERY_THRESHOLD;
    /** 告警最小冷却（分钟）：距上次告警不足该时长不重复推送 */
    public static final ModConfigSpec.IntValue MIN_ALERT_COOLDOWN_MINUTES;
    /** AstrBot 插件 webhook 地址；留空则只写日志不推送 */
    public static final ModConfigSpec.ConfigValue<String> WEBHOOK_URL;
    /** webhook 请求超时（秒） */
    public static final ModConfigSpec.IntValue WEBHOOK_TIMEOUT_SECONDS;
    /** 服务器名，随 webhook 推送，用于区分多服务器 */
    public static final ModConfigSpec.ConfigValue<String> SERVER_NAME;
    /** 未安装 spark 时是否回退使用原生 tick 时间计算 TPS */
    public static final ModConfigSpec.BooleanValue NATIVE_FALLBACK;
    /** 玩家加入服务器时，是否向其发送一条 mod 加载成功提示 */
    public static final ModConfigSpec.BooleanValue ANNOUNCE_ON_JOIN;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("mctpsalert - MC TPS 低值提醒").push("general");

        CHECK_INTERVAL_MINUTES = b.comment("检查间隔（分钟），每 N 分钟采样一次 TPS 并判断是否告警")
                .defineInRange("checkIntervalMinutes", 3, 1, 1440);
        ALERT_THRESHOLD = b.comment("告警阈值：TPS 低于该值触发。判定使用 spark tps 输出的 5s 档（若用原生兜底则取最近 100 tick 平均值）")
                .defineInRange("alertThreshold", 18.0, 1.0, 20.0);
        RECOVERY_THRESHOLD = b.comment("恢复阈值：TPS 回到该值及以上后，才允许再次触发告警（滞回）")
                .defineInRange("recoveryThreshold", 20.0, 1.0, 20.0);
        MIN_ALERT_COOLDOWN_MINUTES = b.comment("告警最小冷却（分钟），距上次告警不足该时长不重复推送")
                .defineInRange("minAlertCooldownMinutes", 10, 0, 1440);
        WEBHOOK_URL = b.comment("AstrBot 插件 webhook 地址（默认与插件默认监听 127.0.0.1:8080 一致）；留空则只记录日志不推送")
                .define("webhookUrl", "http://127.0.0.1:8080/notify");
        WEBHOOK_TIMEOUT_SECONDS = b.comment("webhook 请求超时（秒）")
                .defineInRange("webhookTimeoutSeconds", 5, 1, 60);
        SERVER_NAME = b.comment("服务器名，随 webhook 推送，用于区分多服务器")
                .define("serverName", "MC-Server");
        NATIVE_FALLBACK = b.comment("未安装 spark 模组时，是否回退使用原生 tick 时间计算 TPS")
                .define("nativeFallback", true);
        ANNOUNCE_ON_JOIN = b.comment("玩家加入服务器时，是否向其发送一条 mctpsalert 加载成功提示")
                .define("announceOnJoin", true);

        b.pop();
        SPEC = b.build();
    }
}
