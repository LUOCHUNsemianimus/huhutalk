package com.huhutalk;

import com.huhutalk.core.status.MaidDeathHandler;
import com.huhutalk.core.status.MaidScanTicker;
import com.huhutalk.client.keybind.ModKeyBinds;
import com.huhutalk.core.event.FilmTransformHandler;
import com.huhutalk.core.load.TicketManager;
import com.huhutalk.init.HuhuTalkBlocks;
import com.huhutalk.init.HuhuTalkCreativeTabs;
import com.huhutalk.init.HuhuTalkItems;
import com.huhutalk.network.C2SMaidChatPacket;
import com.huhutalk.network.C2SRequestMaidListPacket;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
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

        // ========== 新增在这里 ==========
        // 注册游戏总线事件处理器（服务端、客户端通用）
        NeoForge.EVENT_BUS.register(new FilmTransformHandler());
        NeoForge.EVENT_BUS.register(new MaidScanTicker());
        NeoForge.EVENT_BUS.register(new MaidDeathHandler());
        NeoForge.EVENT_BUS.register(new TicketManager());

        modEventBus.addListener(RegisterKeyMappingsEvent.class, ModKeyBinds::registerKeybinds);
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(RegisterPayloadHandlersEvent.class, this::setupPackets);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            IEventBus gameBus = NeoForge.EVENT_BUS;
            gameBus.addListener(ClientTickEvent.Post.class, e -> ModKeyBinds.handleKeyInput());
        });
    }

    private void setupPackets(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(MOD_ID);
        // 客户端 → 服务端：聊天消息
        registrar.playToServer(C2SMaidChatPacket.TYPE, C2SMaidChatPacket.STREAM_CODEC, C2SMaidChatPacket::handle);
        // 客户端 → 服务端：请求女仆列表
        registrar.playToServer(C2SRequestMaidListPacket.TYPE, C2SRequestMaidListPacket.STREAM_CODEC, C2SRequestMaidListPacket::handle);
        // 注意：S2C 包在 ClientPayloadSetup 中注册
    }
}