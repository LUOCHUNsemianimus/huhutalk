package com.huhutalk;

import com.huhutalk.core.status.MaidDeathHandler;
import com.huhutalk.core.status.MaidScanTicker;
import com.huhutalk.core.status.MaidStatusTicker;
import com.huhutalk.command.ModCommands;
import com.huhutalk.core.event.FilmTransformHandler;
import com.huhutalk.core.event.PlayerStarterKit;
import com.huhutalk.core.chat.ChatBannerMuteController;
import com.huhutalk.core.chat.ImmersiveBubbleController;
import com.huhutalk.core.chat.MaidNarrativeBus;
import com.huhutalk.core.chat.PhoneChatSourceController;
import com.huhutalk.core.chat.SignalDelayManager;
import com.huhutalk.core.load.TicketManager;
import com.huhutalk.core.move.MaidMoveData;
import com.huhutalk.core.rescue.PlayerRescueHook;
import com.huhutalk.core.supply.RemoteSupplyHook;
import com.huhutalk.core.vision.VisionSettings;
import com.huhutalk.core.vision.VisionSites;
import com.huhutalk.init.HuhuTalkBlocks;
import com.huhutalk.init.HuhuTalkCreativeTabs;
import com.huhutalk.init.HuhuTalkItems;
import com.huhutalk.init.HuhuTalkSounds;
import com.huhutalk.network.C2SClaimTransferPacket;
import com.huhutalk.network.C2SDeleteContactPacket;
import com.huhutalk.network.C2SDropTransferInboxPacket;
import com.huhutalk.network.C2SMaidChatPacket;
import com.huhutalk.network.C2SMaidChatWithItemsPacket;
import com.huhutalk.network.C2SMaidImageChatPacket;
import com.huhutalk.network.C2SPhoneTypingDonePacket;
import com.huhutalk.network.C2SPotionRescueSettingPacket;
import com.huhutalk.network.C2SRemoteSupplyReplySettingPacket;
import com.huhutalk.network.C2SRequestArchiveAssetPacket;
import com.huhutalk.network.C2SRequestArchiveHistoryPacket;
import com.huhutalk.network.C2SRequestMaidListPacket;
import com.huhutalk.network.C2SRequestMaidStatusPacket;
import com.huhutalk.network.C2SRequestSosTaskListPacket;
import com.huhutalk.network.C2SRequestVisionSitesPacket;
import com.huhutalk.network.C2SSOSPacket;
import com.huhutalk.network.C2SSetSosModePacket;
import com.huhutalk.network.C2SSyncWaypointsPacket;
import com.huhutalk.network.C2SToggleSosBindPacket;
import com.huhutalk.network.C2SVisionSelectionPacket;
import com.huhutalk.network.C2SVisionSiteEditPacket;
import com.huhutalk.network.S2CArchiveAssetPacket;
import com.huhutalk.network.S2CArchiveHistoryPacket;
import com.huhutalk.network.S2CMaidChatErrorPacket;
import com.huhutalk.network.S2CMaidChatInfoPacket;
import com.huhutalk.network.S2CMaidChatResponsePacket;
import com.huhutalk.network.S2CMaidDeliveredPacket;
import com.huhutalk.network.S2CMaidItemClaimResultPacket;
import com.huhutalk.network.S2CMaidItemTransferPacket;
import com.huhutalk.network.S2CMaidItemsSendResultPacket;
import com.huhutalk.network.S2CMaidListPacket;
import com.huhutalk.network.S2CMaidStatusPacket;
import com.huhutalk.network.S2CPhoneChatSourcePacket;
import com.huhutalk.network.S2CSosBindPacket;
import com.huhutalk.network.S2CSosTaskListPacket;
import com.huhutalk.network.S2CVisionSitesPacket;
import com.huhutalk.network.transport.PacketTransport;
import com.huhutalk.network.transport.PayloadRegistrar;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(HuhuTalk.MOD_ID)
public class HuhuTalk {
    public static final String MOD_ID = "huhutalk";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public HuhuTalk(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        // ========== 注册物品 / 方块 ==========
        HuhuTalkItems.ITEMS.register(modEventBus);
        HuhuTalkBlocks.BLOCKS.register(modEventBus);
        HuhuTalkCreativeTabs.TABS.register(modEventBus);
        HuhuTalkSounds.SOUND_EVENTS.register(modEventBus);

        // ========== 新增在这里 ==========
        // 注册游戏总线事件处理器（服务端、客户端通用）
        MinecraftForge.EVENT_BUS.register(new FilmTransformHandler());
        MinecraftForge.EVENT_BUS.register(new MaidScanTicker());
        MinecraftForge.EVENT_BUS.register(new MaidDeathHandler());
        MinecraftForge.EVENT_BUS.register(new MaidStatusTicker());
        // 玩家死亡远程救援：死亡登记 + 5s 对账 + 重生位置重定向（服务端权威，多人可用）
        MinecraftForge.EVENT_BUS.register(new PlayerRescueHook());
        // 远程补给：工作期计数 + 血/饱食自动补给 + 手机留痕（需求 2）
        MinecraftForge.EVENT_BUS.register(new RemoteSupplyHook());
        MinecraftForge.EVENT_BUS.register(new TicketManager());
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RegisterCommandsEvent.class, ModCommands::register);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppingEvent.class, e -> MaidMoveData.restoreOnServerStop());
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppingEvent.class, e -> ImmersiveBubbleController.restoreAll());
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppingEvent.class, e -> ChatBannerMuteController.restoreAll());
        // 玩家进入世界时清空其路径点缓存，客户端随后会重新同步当前世界的路径点，
        // 避免上一存档的路径点残留、被 move_to 工具误用（跨存档污染）
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerEvent.PlayerLoggedInEvent.class,
                e -> com.huhutalk.core.waypoint.WaypointCache.clearPlayer(e.getEntity().getUUID()));
        // 开局礼包：每个存档、每个玩家一次，发 Pro 版手机、电话手表、WiFi 方块各一个
        // （标记写在 SavedData 里 → 随存档走）
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerEvent.PlayerLoggedInEvent.class, PlayerStarterKit::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerEvent.PlayerLoggedOutEvent.class, e -> ImmersiveBubbleController.restoreAll());
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerEvent.PlayerLoggedOutEvent.class, e -> ChatBannerMuteController.restoreAll());
        // 退出时释放该玩家的半包重组缓冲/预算；停服时全部清空。
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerEvent.PlayerLoggedOutEvent.class,
                e -> PacketTransport.clearServerPlayer(e.getEntity().getUUID()));
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppingEvent.class, e -> PacketTransport.clearAll());
        // 服务端 tick 统一走 Forge 1.20.1 的 TickEvent.ServerTickEvent END 阶段。
        // 注意：救援/补给 hook 也注册在同一事件总线；本监听器在它们之后注册，
        // MaidNarrativeBus 仍保持“救援/补给 submit 之后 flush”的原顺序。
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, TickEvent.ServerTickEvent.class, HuhuTalk::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppingEvent.class, e -> PhoneChatSourceController.clearAll());
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppingEvent.class,
                e -> C2SRequestArchiveHistoryPacket.clearRateLimits());
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppingEvent.class,
                e -> C2SRequestArchiveAssetPacket.clearRateLimits());
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerEvent.PlayerLoggedOutEvent.class, e -> PhoneChatSourceController.clearAll());

        // 识图平台：服务器起来时补齐本模组需要、而主模组没有的 LLM 站点预设（千帆/混元/阶跃/自定义）。
        // 用 ServerAboutToStart 而不是 mod 构造期，是因为主模组的站点表要到它自己的 common setup
        // 才由 SerializerRegister.init() → AvailableSites.init() 建好；服务器启动必然晚于那一步。
        // 该方法是幂等的「缺失才建」，不会覆盖玩家已有的任何站点配置。
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerAboutToStartEvent.class, e -> VisionSites.ensureDefaults());
        // 「识图走哪个平台」是纯会话偏好（客户端每次登录重推），登出即清、停服全清，避免长开服累积
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerEvent.PlayerLoggedOutEvent.class,
                e -> VisionSettings.clear(e.getEntity().getUUID()));
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppingEvent.class, e -> VisionSettings.clearAll());

        // 女仆叙事总线（任务 1 × 任务 2 的兼容层）：救援与补给的"要她说的话"在这里合并成同一轮注入。
        // ⚠ 必须注册在 PlayerRescueHook / RemoteSupplyHook 之后：它们的 ServerTick 钩子会 submit 事件，
        //   本监听器随后 flush，这样同一 tick 提交的事件就能被合并（见 §12）。
        // 该 flush 已合并进上面的 onServerTick，保持同一 END 阶段与注册顺序。

        // ========== 网络：底层只注册一个 Forge SimpleChannel WirePacket ==========
        PayloadRegistrar registrar = PacketTransport.initCommon();
        // C2S 描述和 handler 由共同端登记；专用服也会登记，但不会加载任何客户端类。
        registerServerboundPayloads(registrar);
        // S2C 描述在双方同顺序登记；专用服只登记 descriptor，不注册客户端 handler。
        registerClientboundDescriptors(registrar);
        // S2C 的真实 handler 绑定属于物理客户端：由 ClientPayloadSetup 上的
        // @Mod.EventBusSubscriber(Dist.CLIENT, bus = MOD) 在 FMLClientSetupEvent 中完成。
        // common 构造不引用任何客户端类，专用服不会加载 ClientPayloadSetup/LocalPlayer。
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        PacketTransport.tick();
        MaidMoveData.tickMoveQueue();
        // 信号延迟队列：每 tick 对账，到点投递延迟的 AI 聊天回复
        SignalDelayManager.tick(event.getServer());
        // 女仆悄悄话气泡抑制：每 tick 对账，到点恢复被抑制女仆的气泡
        ImmersiveBubbleController.tick(event.getServer());
        // 沉浸式聊天公屏抑制：每 tick 对账，到点清除过期静音标记
        ChatBannerMuteController.tick(event.getServer());
        // 手机聊天来源窗口：每 tick 兜底关闭超时窗口（LLM 长时间无响应保护）
        PhoneChatSourceController.tick(event.getServer());
        // 必须最后 flush：救援/补给 hook 已在同一 tick 的 END 阶段 submit。
        MaidNarrativeBus.tick(event.getServer());
    }

    private static void registerServerboundPayloads(PayloadRegistrar registrar) {
        // 客户端 → 服务端：聊天消息
        registrar.playToServer(C2SMaidChatPacket.TYPE, C2SMaidChatPacket.STREAM_CODEC, C2SMaidChatPacket::handle);
        // 客户端 → 服务端：带一张图片的聊天（图片字节内联，分片由 transport 负责）
        registrar.playToServer(C2SMaidImageChatPacket.TYPE, C2SMaidImageChatPacket.STREAM_CODEC, C2SMaidImageChatPacket::handle);
        // 客户端 → 服务端：领取女仆发来的物品（M→P 传文件）
        registrar.playToServer(C2SClaimTransferPacket.TYPE, C2SClaimTransferPacket.STREAM_CODEC, C2SClaimTransferPacket::handle);
        // 客户端 → 服务端：文字 + 随信物品（P→M）
        registrar.playToServer(C2SMaidChatWithItemsPacket.TYPE, C2SMaidChatWithItemsPacket.STREAM_CODEC, C2SMaidChatWithItemsPacket::handle);
        // 客户端 → 服务端：删除会话时清空该会话收件箱（未领物品掉玩家脚下）
        registrar.playToServer(C2SDropTransferInboxPacket.TYPE, C2SDropTransferInboxPacket.STREAM_CODEC, C2SDropTransferInboxPacket::handle);
        // 客户端 → 服务端：删除联系人
        registrar.playToServer(C2SDeleteContactPacket.TYPE, C2SDeleteContactPacket.STREAM_CODEC, C2SDeleteContactPacket::handle);
        // 客户端 → 服务端：请求女仆列表
        registrar.playToServer(C2SRequestMaidListPacket.TYPE, C2SRequestMaidListPacket.STREAM_CODEC, C2SRequestMaidListPacket::handle);
        // 客户端 → 服务端：按需请求女仆状态
        registrar.playToServer(C2SRequestMaidStatusPacket.TYPE, C2SRequestMaidStatusPacket.STREAM_CODEC, C2SRequestMaidStatusPacket::handle);
        // 客户端 → 服务端：按需读取女仆长期聊天档案（只读、固定上界分页）
        registrar.playToServer(C2SRequestArchiveHistoryPacket.TYPE,
                C2SRequestArchiveHistoryPacket.STREAM_CODEC, C2SRequestArchiveHistoryPacket::handle);
        // 客户端 → 服务端：按需读取当前档案会话引用的一张图片
        registrar.playToServer(C2SRequestArchiveAssetPacket.TYPE,
                C2SRequestArchiveAssetPacket.STREAM_CODEC, C2SRequestArchiveAssetPacket::handle);
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
        // 客户端 → 服务端：本条回复的逐条打字显示已播完（关闭手机来源窗口 → 动画放下段）
        registrar.playToServer(C2SPhoneTypingDonePacket.TYPE, C2SPhoneTypingDonePacket.STREAM_CODEC, C2SPhoneTypingDonePacket::handle);
        // 客户端 → 服务端：同步「药水复活」开关（服务端只在会话内缓存该偏好）
        registrar.playToServer(C2SPotionRescueSettingPacket.TYPE, C2SPotionRescueSettingPacket.STREAM_CODEC, C2SPotionRescueSettingPacket::handle);
        // 客户端 → 服务端：同步「远程补给触发女仆回复」开关（同样只在会话内缓存）
        registrar.playToServer(C2SRemoteSupplyReplySettingPacket.TYPE, C2SRemoteSupplyReplySettingPacket.STREAM_CODEC, C2SRemoteSupplyReplySettingPacket::handle);
        // 客户端 → 服务端：识图平台列表（拉取 / 改密钥 / 同步选中的平台与模型）
        registrar.playToServer(C2SRequestVisionSitesPacket.TYPE, C2SRequestVisionSitesPacket.STREAM_CODEC, C2SRequestVisionSitesPacket::handle);
        registrar.playToServer(C2SVisionSiteEditPacket.TYPE, C2SVisionSiteEditPacket.STREAM_CODEC, C2SVisionSiteEditPacket::handle);
        registrar.playToServer(C2SVisionSelectionPacket.TYPE, C2SVisionSelectionPacket.STREAM_CODEC, C2SVisionSelectionPacket::handle);
    }

    /**
     * 共同端登记 15 个 S2C 描述。专用服也会执行这里，以便识别/拒绝错误方向的包；
     * 这里的代码不引用任何 {@code net.minecraft.client.*} 类。
     */
    private static void registerClientboundDescriptors(PayloadRegistrar registrar) {
        registrar.playToClient(S2CMaidChatResponsePacket.TYPE, S2CMaidChatResponsePacket.STREAM_CODEC);
        registrar.playToClient(S2CMaidChatErrorPacket.TYPE, S2CMaidChatErrorPacket.STREAM_CODEC);
        registrar.playToClient(S2CMaidChatInfoPacket.TYPE, S2CMaidChatInfoPacket.STREAM_CODEC);
        registrar.playToClient(S2CMaidDeliveredPacket.TYPE, S2CMaidDeliveredPacket.STREAM_CODEC);
        registrar.playToClient(S2CMaidItemTransferPacket.TYPE, S2CMaidItemTransferPacket.STREAM_CODEC);
        registrar.playToClient(S2CMaidItemClaimResultPacket.TYPE, S2CMaidItemClaimResultPacket.STREAM_CODEC);
        registrar.playToClient(S2CMaidItemsSendResultPacket.TYPE, S2CMaidItemsSendResultPacket.STREAM_CODEC);
        registrar.playToClient(S2CMaidListPacket.TYPE, S2CMaidListPacket.STREAM_CODEC);
        registrar.playToClient(S2CMaidStatusPacket.TYPE, S2CMaidStatusPacket.STREAM_CODEC);
        registrar.playToClient(S2CSosBindPacket.TYPE, S2CSosBindPacket.STREAM_CODEC);
        registrar.playToClient(S2CSosTaskListPacket.TYPE, S2CSosTaskListPacket.STREAM_CODEC);
        registrar.playToClient(S2CPhoneChatSourcePacket.TYPE, S2CPhoneChatSourcePacket.STREAM_CODEC);
        registrar.playToClient(S2CVisionSitesPacket.TYPE, S2CVisionSitesPacket.STREAM_CODEC);
        registrar.playToClient(S2CArchiveHistoryPacket.TYPE, S2CArchiveHistoryPacket.STREAM_CODEC);
        registrar.playToClient(S2CArchiveAssetPacket.TYPE, S2CArchiveAssetPacket.STREAM_CODEC);
    }
}
