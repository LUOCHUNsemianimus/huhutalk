package com.huhutalk.core.event;

import com.huhutalk.HuhuTalk;
import com.huhutalk.init.HuhuTalkItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

/**
 * 开局礼包：<b>每个存档、每个玩家一次</b>，发两部手机（Pro 版 + 电话手表）。
 *
 * <h2>为什么用 SavedData 而不是玩家身上的标记</h2>
 * 需求是"每个新档开局发一次"。{@link Granted} 存进主世界维度的 {@code DataStorage}，
 * 于是它天然与存档同生命周期：新档 = 没发过 = 发；同一个档再进 = 发过 = 不发。
 * 多人存档里按 UUID 逐个记，第二个玩家进来照样能领到自己的那份。
 * （与联系人簿 / WiFi 注册表 / 离线队列同一套写法，见这几个 SavedData。）
 *
 * <h2>发不下怎么办</h2>
 * 背包满了就<b>掉在脚下</b>，绝不"什么都不给"；同时标记已经写过，所以也不会重复发放。
 *
 * <h2>为什么在登录时发</h2>
 * {@code PlayerLoggedInEvent} 是"玩家真正进到世界里"的第一个稳定时机，
 * 此时 {@code ServerPlayer} 一定存在、UUID 一定可读。客户端侧的同名事件会被
 * {@code instanceof ServerPlayer} 挡掉。
 */
public final class PlayerStarterKit {

    private PlayerStarterKit() {
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        // 先占标记再发东西：即便下面抛异常，也不会出现"同一个档反复发"的最坏情况
        if (!Granted.get(server).markGranted(player.getUUID().toString())) {
            return;
        }

        give(player, new ItemStack(HuhuTalkItems.PHONE_SMART.get()));
        give(player, new ItemStack(HuhuTalkItems.PHONE_WATCH.get()));

        player.displayClientMessage(Component.literal(
                "[HuhuTalk] 开局礼包：「Pro 版手机」与「电话手表」各一部，已放进你的背包。\n"
                        + "把它们放进女仆的饰品栏，就能给女仆戴上手机，开始远程聊天。"), false);
        HuhuTalk.LOGGER.info("[HuhuTalk] 已为 {} 发放开局手机（存档 {}）",
                player.getGameProfile().getName(), player.level().dimension().location());
    }

    /** 塞进背包；塞不下就掉在脚下。 */
    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    /** 「已经发过开局礼包」的玩家集合（SavedData，存主世界维度，随存档走）。 */
    public static final class Granted extends SavedData {

        private static final String DATA_NAME = "huhutalk_starter_kit";
        private static final String KEY_PLAYERS = "players";

        private final Set<String> players = new HashSet<>();

        @NotNull
        public static Granted get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(
                    new SavedData.Factory<>(Granted::new, Granted::load),
                    DATA_NAME
            );
        }

        /** @return true = 本次是新标记（还没发过）；false = 这个档里已经发过了 */
        public boolean markGranted(String uuid) {
            if (!players.add(uuid)) {
                return false;
            }
            setDirty();
            return true;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag list = new ListTag();
            for (String id : players) {
                list.add(StringTag.valueOf(id));
            }
            tag.put(KEY_PLAYERS, list);
            return tag;
        }

        public static Granted load(CompoundTag tag, HolderLookup.Provider registries) {
            Granted data = new Granted();
            ListTag list = tag.getList(KEY_PLAYERS, Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                data.players.add(list.getString(i));
            }
            return data;
        }
    }
}
