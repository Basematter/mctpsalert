package com.basem.mctpsalert;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(MCTpsAlert.MODID)
public class MCTpsAlert {
    public static final String MODID = "mctpsalert";

    /**
     * Forge 1.20.1 的 FMLModContainer 只接受两种构造器：无参，或注入 FMLJavaModLoadingContext
     * （注意：NeoForge 1.21 那种 (IEventBus, ModContainer) 形式在 Forge 1.20.1 上不支持）。
     * 这里用注入式，避免调用已被标记 @Deprecated(forRemoval) 的 ModLoadingContext.get()。
     */
    public MCTpsAlert(FMLJavaModLoadingContext context) {
        // 服务器配置（config/mctpsalert-server.toml）
        context.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        // 仅专用服务器（DEDICATED_SERVER）注册监控与调试指令；
        // 客户端即使安装本 mod 也不注册任何事件，不产生副作用（单机也不会触发）。
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            MinecraftForge.EVENT_BUS.register(TpsMonitor.class);
            MinecraftForge.EVENT_BUS.register(ModCommands.class);
        }
    }
}
