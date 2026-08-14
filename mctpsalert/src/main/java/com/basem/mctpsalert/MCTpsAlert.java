package com.basem.mctpsalert;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.NeoForge;

@Mod(MCTpsAlert.MODID)
public class MCTpsAlert {
    public static final String MODID = "mctpsalert";

    public MCTpsAlert(IEventBus modEventBus, ModContainer modContainer) {
        // 服务器配置（config/mctpsalert-server.toml）
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        // 仅专用服务器（DEDICATED_SERVER）注册监控与调试指令；
        // 客户端即使安装本 mod 也不注册任何事件，不产生副作用（单机也不会触发）。
        if (FMLLoader.getDist() == Dist.DEDICATED_SERVER) {
            NeoForge.EVENT_BUS.register(TpsMonitor.class);
            NeoForge.EVENT_BUS.register(ModCommands.class);
        }
    }
}
