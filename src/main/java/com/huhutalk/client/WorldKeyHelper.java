package com.huhutalk.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.Level;

/**
 * 计算当前世界的唯一标识。
 * 单机存档用世界名字 + 维度，服务器用 IP + 维度。
 * 注意：1.21.1 客户端 LevelData 没有 getLevelName()，
 * 世界名只能从服务端的 WorldData（IntegratedServer#getWorldData）获取。
 */
public class WorldKeyHelper {

    public static String getCurrentWorldKey(AbstractClientPlayer player) {
        Level level = player.level();
        String dimension = level.dimension().location().toString();

        Minecraft mc = Minecraft.getInstance();

        // 多人服务器：按 IP 区分
        if (mc.getCurrentServer() != null) {
            String ip = mc.getCurrentServer().ip;
            return "server/" + ip + "/" + dimension;
        }

        // 单机：从集成服务器取世界名（不同存档名不同）
        IntegratedServer integrated = mc.getSingleplayerServer();
        if (integrated != null) {
            String levelName = integrated.getWorldData().getLevelName();
            return "single/" + levelName + "/" + dimension;
        }

        // 兜底（理论上不会走到）
        return "single/unknown/" + dimension;
    }
}