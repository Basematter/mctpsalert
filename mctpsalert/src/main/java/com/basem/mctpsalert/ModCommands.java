package com.basem.mctpsalert;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 游戏内调试指令：
 * /mctps status —— 显示当前配置与 TPS 监控运行状态
 * /mctps test   —— 立即采样 TPS 并推送一条测试 webhook 到 AstrBot，验证 mod → AstrBot → QQ 群链路
 */
public class ModCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("mctps")
                        .then(Commands.literal("status")
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> TpsMonitor.runStatus(ctx.getSource())))
                        .then(Commands.literal("test")
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> TpsMonitor.runTest(ctx.getSource())))
        );
    }
}
