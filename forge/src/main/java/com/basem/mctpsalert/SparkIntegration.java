package com.basem.mctpsalert;

import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.statistic.StatisticWindow;
import me.lucko.spark.api.statistic.types.DoubleStatistic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * spark API 的隔离层 —— 本类存在的唯一理由，是规避一个隐蔽的类加载陷阱。
 *
 * <p>TpsMonitor 会被注册到 Forge 事件总线，而 {@code EventBus.registerClass()} 内部会调用
 * {@code Class.getDeclaredMethods()}。这一步会强制 JVM 链接并**字节码校验** TpsMonitor，
 * 校验器于是要解析其方法体中引用到的所有类型。
 *
 * <p>如果 TpsMonitor 自己直接引用 {@code me.lucko.spark.*}，那么在**未安装 spark** 的服务器上，
 * 启动阶段就会抛 {@code NoClassDefFoundError: StatisticWindow$TicksPerSecond}，导致整个服务器起不来
 * ——即便火花只是 optional 依赖、且配置里 nativeFallback 默认为 true。
 *
 * <p>把 spark 引用全部收拢到本类后，TpsMonitor 的字节码里不再出现任何 spark 类型，校验不会再去找它。
 * 本类仅在确认 spark 已加载（{@code ModList.isLoaded("spark")}）后才会被触碰，JVM 按需加载，
 * 因此未安装 spark 时本类永远不会被加载。
 */
final class SparkIntegration {
    private static final Logger LOGGER = LoggerFactory.getLogger("mctpsalert-spark");

    private SparkIntegration() {
    }

    /**
     * 通过 spark 官方 API 获取整体 TPS（5s 档）。
     *
     * @return 5s 档 TPS；spark API 不可用、调用异常或数值无效时返回 null
     */
    static Double tps5s() {
        try {
            Spark spark = SparkProvider.get();
            if (spark == null) {
                return null;
            }
            DoubleStatistic<StatisticWindow.TicksPerSecond> tps = spark.tps();
            if (tps == null) {
                return null;
            }
            double value = tps.poll(StatisticWindow.TicksPerSecond.SECONDS_5);
            return value > 0 ? value : null;
        } catch (Throwable e) {
            LOGGER.warn("[mctpsalert] spark API 获取 TPS 失败: {}", e.toString());
            return null;
        }
    }
}
