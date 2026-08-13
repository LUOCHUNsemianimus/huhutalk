package com.huhutalk.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

/**
 * 计算当前世界的唯一标识。
 * 单人生存/服务器地址 + 维度，确保不同存档不串数据。
 */
public class WorldKeyHelper {

    public static String getCurrentWorldKey(AbstractClientPlayer player) {
        String serverIp = Minecraft.getInstance().getCurrentServer() != null
                ? Minecraft.getInstance().getCurrentServer().ip
                : "singleplayer";
        String dimension = player.level().dimension().location().toString();
        return serverIp + "/" + dimension;
    }
}