package com.huhutalk;

import com.huhutalk.core.status.MaidDeathHandler;
import com.huhutalk.core.status.MaidScanTicker;
import com.huhutalk.core.status.MaidStatusTicker;
import com.huhutalk.command.ModCommands;
import com.huhutalk.core.event.FilmTransformHandler;
import com.huhutalk.core.chat.ChatBannerMuteController;
import com.huhutalk.core.chat.ImmersiveBubbleController;
import com.huhutalk.core.chat.SignalDelayManager;
import com.huhutalk.core.load.TicketManager;
import com.huhutalk.core.move.MaidMoveData;
import com.huhutalk.init.HuhuTalkBlocks;
import com.huhutalk.init.HuhuTalkCreativeTabs;
import com.huhutalk.init.HuhuTalkItems;
import com.huhutalk.init.HuhuTalkSounds;
import com.huhutalk.network.C2SMaidChatPacket;
import com.huhutalk.network.C2SMaidImageChatPacket;
import com.huhutalk.network.C2SDeleteContactPacket;
import com.huhutalk.network.C2SRequestMaidListPacket;
import com.huhutalk.network.C2SRequestMaidStatusPacket;
import com.huhutalk.network.C2SRequestSosTaskListPacket;
import com.huhutalk.network.C2SSetSosModePacket;
import com.huhutalk.network.C2SSOSPacket;
import com.huhutalk.network.C2SSyncWaypointsPacket;
import com.huhutalk.network.C2SToggleSosBindPacket;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(HuhuTalk.MOD_ID)
public class HuhuTalk {
    public static final String MOD_ID = "huhutalk";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public HuhuTalk(IEventBus modEventBus) {
        // ========== 注册物品 / 方块 ==========
        HuhuTalkItems.ITEMS.register(modEventBus);
        HuhuTalkBlocks.BLOCKS.register(modEventBus);
        HuhuTalkCreativeTabs.TABS.register(modEventBus);
        HuhuTalkSounds.SOUND_EVENTS.register(modEventBus);

        // ========== 新增在这里 ==========
        // 注册游戏总线事件处理器（服务端、客户端通用）
        NeoForge.EVENT_BUS.register(new FilmTransformHandler());
        NeoForge.EVENT_BUS.register(new MaidScanTicker());
        NeoForge.EVENT_BUS.register(new MaidDeathHandler());
        NeoForge.EVENT_BUS.register(new MaidStatusTicker());
        NeoForge.EVENT_BUS.register(new TicketManager());
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, ModCommands::register);
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, e -> MaidMoveData.restoreOnServerStop());
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, e -> ImmersiveBubbleController.restoreAll());
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, e -> ChatBannerMuteController.restoreAll());
        // 玩家进入世界时清空其路径点缓存，客户端随后会重新同步当前世界的路径点，
        // 避免上一存档的路径点残留、被 move_to 工具误用（跨存档污染）
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class,
                e -> com.huhutalk.core.waypoint.WaypointCache.clearPlayer(e.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> ImmersiveBubbleController.restoreAll());
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> ChatBannerMuteController.restoreAll());
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> MaidMoveData.tickMoveQueue());
        // 信号延迟队列：每 tick 对账，到点投递延迟的 AI 聊天回复
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> SignalDelayManager.tick(e.getServer()));
        // 女仆悄悄话气泡抑制：每 tick 对账，到点恢复被抑制女仆的气泡
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> ImmersiveBubbleController.tick(e.getServer()));
        // 沉浸式聊天公屏抑制：每 tick 对账，到点清除过期静音标记
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> ChatBannerMuteController.tick(e.getServer()));

        modEventBus.addListener(RegisterPayloadHandlersEvent.class, this::setupPackets);
    }

    private void setupPackets(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(MOD_ID);
        // 客户端 → 服务端：聊天消息
        registrar.playToServer(C2SMaidChatPacket.TYPE, C2SMaidChatPacket.STREAM_CODEC, C2SMaidChatPacket::handle);
        // 客户端 → 服务端：带一张图片的聊天（图片走 vision_tmp 共享目录，包内只传 token）
        registrar.playToServer(C2SMaidImageChatPacket.TYPE, C2SMaidImageChatPacket.STREAM_CODEC, C2SMaidImageChatPacket::handle);
        // 客户端 → 服务端：删除联系人
        registrar.playToServer(C2SDeleteContactPacket.TYPE, C2SDeleteContactPacket.STREAM_CODEC, C2SDeleteContactPacket::handle);
        // 客户端 → 服务端：请求女仆列表
        registrar.playToServer(C2SRequestMaidListPacket.TYPE, C2SRequestMaidListPacket.STREAM_CODEC, C2SRequestMaidListPacket::handle);
        // 客户端 → 服务端：按需请求女仆状态
        registrar.playToServer(C2SRequestMaidStatusPacket.TYPE, C2SRequestMaidStatusPacket.STREAM_CODEC, C2SRequestMaidStatusPacket::handle);
        // 客户端 → 服务端：SOS 紧急呼叫
        registrar.playToServer(C2SSOSPacket.TYPE, C2SSOSPacket.STREAM_CODEC, C2SSOSPacket::handle);
        // 客户端 → 服务端：切换 SOS 绑定
        registrar.playToServer(C2SToggleSosBindPacket.TYPE, C2SToggleSosBindPacket.STREAM_CODEC, C2SToggleSosBindPacket::handle);
        // 客户端 → 服务端：设置 SOS 绑定女仆的工作模式
        registrar.playToServer(C2SSetSosModePacket.TYPE, C2SSetSosModePacket.STREAM_CODEC, C2SSetSosModePacket::handle);
        // 客户端 → 服务端：请求可用工作模式列表（SOS 绑定页渲染用）
        registrar.playToServer(C2SRequestSosTaskListPacket.TYPE, C2SRequestSosTaskListPacket.STREAM_CODEC, C2SRequestSosTaskListPacket::handle);
        // 客户端 → 服务端：同步 JourneyMap 路径点
        registrar.playToServer(C2SSyncWaypointsPacket.TYPE, C2SSyncWaypointsPacket.STREAM_CODEC, C2SSyncWaypointsPacket::handle);
        // 注意：S2C 包在 ClientPayloadSetup 中注册
    }
}