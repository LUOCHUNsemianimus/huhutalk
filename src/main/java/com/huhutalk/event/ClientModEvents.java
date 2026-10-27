package com.huhutalk.event;

import com.huhutalk.HuhuTalk;
import com.huhutalk.client.render.PhoneScreenTarget;
import com.huhutalk.client.render.ProPhoneHandRenderer;
import com.huhutalk.client.render.SelfieCameraTarget;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.client.vision.VisionSiteCache;
import com.huhutalk.network.C2SPotionRescueSettingPacket;
import com.huhutalk.network.C2SRemoteSupplyReplySettingPacket;
import com.huhutalk.network.C2SVisionSelectionPacket;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.phone.PhoneInputScreen;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.pages.ChatPage;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import com.huhutalk.network.transport.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = HuhuTalk.MOD_ID, value = Dist.CLIENT)
public class ClientModEvents {
    public static final KeyMapping TOGGLE_PHONE = new KeyMapping(
            "key.huhutalk.toggle_phone", GLFW.GLFW_KEY_O, "key.category.huhutalk");
    public static final KeyMapping QUICK_VOICE = new KeyMapping(
            "key.huhutalk.quick_voice", GLFW.GLFW_KEY_H, "key.category.huhutalk");
    /**
     * 手机「左键」：独立光标下的点击/确认。默认鼠标侧键 4。
     * <p>
     * 之所以用侧键而不是原生左右键：那样鼠标左右键就能<b>完整留给游戏</b>（攻击/放置照常），
     * 手机只额外接收侧键这一路 —— 视角转动、走路、攻击全都不受影响。
     */
    public static final KeyMapping PHONE_CLICK = new KeyMapping(
            "key.huhutalk.phone_click", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_4, "key.category.huhutalk");
    /** 手机「右键」：独立光标下的返回上一页。默认鼠标侧键 5 */
    public static final KeyMapping PHONE_BACK = new KeyMapping(
            "key.huhutalk.phone_back", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_5, "key.category.huhutalk");
    /**
     * 相机「前后摄切换」（默认 V，可重绑定）。
     * <p>
     * 相机界面没有光标，页内那颗切换按钮点不到，所以它必须有一条键位通路 ——
     * 这就是 V 键存在的原因。页内按钮只负责"显示当前前/后摄状态 + 提示按哪个键"。
     * 只有支持切换的相机页（{@code PhoneCameraHost#supportsCameraFacingSwitch()}）响应它。
     */
    public static final KeyMapping PHONE_CAMERA_FACING = new KeyMapping(
            "key.huhutalk.camera_facing", GLFW.GLFW_KEY_V, "key.category.huhutalk");

    /** 正在按住 H 说话的聊天页（用于松手时停止录音） */
    private static ChatPage quickVoicePage = null;

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_PHONE);
        event.register(QUICK_VOICE);
        event.register(PHONE_CLICK);
        event.register(PHONE_BACK);
        event.register(PHONE_CAMERA_FACING);
    }

    /**
     * 登录（进入世界 / 重连）时把本机的偏好同步给服务端。
     * <p>
     * 服务端只在<b>会话内</b>缓存这些偏好（见 {@code RescueSettings} / {@code RemoteSupplySettings}），
     * 所以每次登录都必须重新告知，否则重连后开关会静默退回默认值。
     * 设置页打开时与拨动开关时还会各推一次，作为冗余保障。
     */
    @SubscribeEvent
    public static void onClientLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        pushPotionRescueSetting();
        pushRemoteSupplyReplySetting();
        pushVisionSelection();
    }

    /**
     * 退出服务器时清掉识图平台缓存。
     * <p>
     * 否则换一个服务器进游戏时，会先看到上一台服务器的站点列表（内容不一定错，但会误导），
     * 直到新服务器把快照推回来。
     */
    @SubscribeEvent
    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        VisionSiteCache.clear();
    }

    /**
     * 把本机的「识图走哪个平台 / 用哪个模型 / 主人长什么样」推给服务端（幂等，可重复调用）。
     * <p>
     * 服务端只在会话内按 UUID 缓存（见 {@code VisionSettings}），所以每次登录、
     * 每次在设置里改动都要推一次。
     */
    public static void pushVisionSelection() {
        String siteId = HuhuTalkClientSettings.getVisionSiteId();
        PacketDistributor.sendToServer(
                new C2SVisionSelectionPacket(siteId, HuhuTalkClientSettings.getVisionModel(siteId),
                        HuhuTalkClientSettings.getPlayerAppearance()));
    }

    /** 把本机的「药水复活」偏好推给服务端（幂等，可重复调用）。 */
    public static void pushPotionRescueSetting() {
        PacketDistributor.sendToServer(
                new C2SPotionRescueSettingPacket(HuhuTalkClientSettings.isPotionRescueEnabled()));
    }

    /** 把本机的「远程补给触发女仆回复」偏好推给服务端（幂等，可重复调用）。 */
    public static void pushRemoteSupplyReplySetting() {
        PacketDistributor.sendToServer(
                new C2SRemoteSupplyReplySettingPacket(HuhuTalkClientSettings.isRemoteSupplyReplyEnabled()));
    }

    /** 该鼠标按钮是否被玩家绑定为「手机左键」 */
    public static boolean isPhoneClickButton(int button) {
        return matchesMouseKey(PHONE_CLICK, button);
    }

    /** 该鼠标按钮是否被玩家绑定为「手机右键 / 返回」 */
    public static boolean isPhoneBackButton(int button) {
        return matchesMouseKey(PHONE_BACK, button);
    }

    private static boolean matchesMouseKey(KeyMapping mapping, int button) {
        return mapping.getKey().getType() == InputConstants.Type.MOUSE && mapping.getKey().getValue() == button;
    }

    /** 「操控屏幕」态下视角转动速度倍率（1.0 = 原版） */
    private static final double CAMERA_TURN_SCALE = 0.25;

    /**
     * 在「操控屏幕」态下压低视角转速。
     * <p>
     * <b>硬约束</b>：不处于该状态时<b>完全不改</b>这个输入值 —— 既保持原版行为，也不覆盖其他模组的修改。
     * MODEL 态要求<b>三件事同时成立</b>：「UI 已掏出 + 主手举着 Pro 手机 + 低头 ≥ 阈值」
     * （见 §5.7-35）。这里再额外放行一次"光标在顶栏"。
     * 可见性与"手里到底有没有手机"两关一起查 ⇒ 绝不可能污染徒手 / 拿着别的物品 / 第三人称 / 开着界面等任何其他状态。
     * <p>
     * <b>FLOATING 直接放行</b>：悬浮窗的独立光标与侧键不要求低头或持有手机
     * （{@link PhoneOverlay#isScreenControl()} 对 FLOATING 恒真），但<b>视角照常转、不降速</b> ——
     * 降速只保留给 MODEL（手持 Pro + 低头的那一路）。所以这里先对 FLOATING 早退。
     * <p>
     * <b>顶栏放行</b>：光标被推到顶部状态栏时<b>也</b>不降速 —— 给玩家一条快速松手的出路
     * （低头 90° 掏出手机时，靠 0.25 倍速抬头爬回阈值以上太难受）。此时光标仍可动，
     * 拉下来就重新进入降速。
     * <p>
     * 原版曲线：{@code d2 = sens*0.6+0.2}、{@code d3 = d2³}、{@code d4 = d3*8}，最终转动量 ∝ d4。
     * 想让转动变成 k 倍，需要 {@code d2' = d2 · k^(1/3)}，反解得 {@code sens' = (d2'−0.2)/0.6}。
     */
    public static double adjustMouseSensitivity(double sensitivity) {
        // FLOATING：O 显式召唤的屏幕空间窗口，光标/侧键照用，但视角不因它降速
        if (PhoneOverlay.isFloatingScreen()
                || !PhoneOverlay.isScreenControl()
                || PhoneOverlay.isCursorInTopBar()) {
            return sensitivity;
        }
        double d2 = sensitivity * 0.6 + 0.2;
        double scaled = d2 * Math.cbrt(CAMERA_TURN_SCALE);
        return Math.max(0.0, (scaled - 0.2) / 0.6);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        while (TOGGLE_PHONE.consumeClick()) {
            PhoneOverlay.toggle();
        }
        // 前/后摄切换（默认 V，可重绑定）：相机没有光标，切换按钮只能靠键位触达
        while (PHONE_CAMERA_FACING.consumeClick()) {
            PhoneOverlay.toggleCameraFacing();
        }
        // 掏出即渲染：主手开始举着 Pro 手机的那一刻自动打开手机 UI
        PhoneOverlay.onHolderTick();
        // 相机模式长按侧键 4 = 存相册（需在按住期间逐 tick 检查是否到达阈值）
        PhoneOverlay.tickCameraHold();
        handleQuickVoice(mc);
    }

    /**
     * 按住说话键（默认 H）"按住说话"：仅当手机可见且当前页为聊天页时生效。
     * 按住 → 开始录音；松开 → 停止并发送。是否打开扩展面板均可用。
     */
    private static void handleQuickVoice(Minecraft mc) {
        boolean down = QUICK_VOICE.isDown();
        if (down && quickVoicePage == null) {
            if (PhoneOverlay.isVisible()) {
                IPhonePage cur = PhoneOverlay.getCurrentPage();
                if (cur instanceof ChatPage cp) {
                    quickVoicePage = cp;
                    cp.startVoice(ChatPage.VoiceSource.KEY);
                }
            }
        } else if (!down && quickVoicePage != null) {
            quickVoicePage.stopVoice(false);
            quickVoicePage = null;
        }
    }

    /**
     * 模型屏出图：每帧最早的时刻（GL 上下文有效、主渲染靶仍绑定、世界尚未渲染），
     * 把手机 UI 画进离屏靶，保证本帧稍后贴到 3D 模型上的纹理已是最新。
     * <p>
     * 主手没拿 Pro 手机（或第三人称 / 开着界面）时完全不跑离屏渲染，做到关屏零开销。
     */
    @SubscribeEvent
    public static void onRenderTickStart(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        final float partialTick = event.renderTickTime;
        // 自拍 pass 自身是通过 renderLevel 嵌套渲染的，这个钩子不应在 pass 内再被触发；
        // 保险起见直接早退，免得任何状态在自拍帧里被二次推进。
        if (SelfieCameraTarget.isRendering()) {
            return;
        }
        // 上一帧若没等到 AFTER_LEVEL（切页/不持机/F3/第三人称/无 world pass），
        // 这里先作废暂存的离屏屏幕，避免旧帧四边形被补画到本帧。
        PhoneScreenTarget.beginFrame();
        // 俯仰低通：供「操控屏幕」状态机判定，须在离屏出图之前更新
        PhoneOverlay.updateLookFilter();

        // 前摄取景：只有「当前页确实在前摄取景」且「第一人称主手真的举着手机」时才开离屏 pass。
        // setActive 只在开关真的变化时清理；update 内部自带 30fps 节流。
        // 第三人称、手机不在手上、手机 UI 收起时 PhoneOverlay.isSelfieMode() 为假，
        // setActive(false) 会兑现未完成请求并作废画面 —— 空闲/不可见时不渲染。
        // 界面遮挡（真实 Screen 打开）也暂停：透明的手机输入屏不算遮挡（那时世界/手持照常渲染）。
        Minecraft mc = Minecraft.getInstance();
        boolean occluded = mc.screen != null && !(mc.screen instanceof PhoneInputScreen);
        boolean selfie = !occluded && PhoneOverlay.isSelfieMode() && ProPhoneHandRenderer.isActive()
                && PhoneOverlay.isModelScreen();
        SelfieCameraTarget.setActive(selfie);
        if (selfie) {
            SelfieCameraTarget.update(partialTick);
            // 自拍靶刚更新（或至少这一帧有有效画面）：标脏模型屏，
            // 让稍后 PhoneScreenTarget.update 把最新一帧镜像预览复制进手机屏纹理。
            PhoneScreenTarget.markDirty();
        }

        if (ProPhoneHandRenderer.isActive()) {
            PhoneScreenTarget.update(partialTick);
        } else {
            PhoneScreenTarget.markDirty();
        }

        // 拍照抓图帧：光影（Iris）把第一人称手 / 手机提前画进了世界 pass，
        // 使 AFTER_LEVEL 不再是"手还没画"的时刻。这里（主场景渲染之前）临时关掉 renderHand，
        // 抓完在 consumeWorldCapture 里立刻恢复；无光影时该帧画面与原来完全一致。
        PhoneOverlay.beginWorldCaptureFrame();
    }

    public static void registerGuiOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll(
                "phone_overlay",
                (gui, guiGraphics, partialTick, width, height) -> PhoneOverlay.render(guiGraphics, partialTick)
        );
    }

    /**
     * 相机拍照的抓图时机：{@code AFTER_LEVEL}。
     * <p>
     * 这一刻本帧的世界已经画完，而<b>手 / 手机 / HUD 都还没有画</b>：
     * {@code GameRenderer.renderLevel} 里 hand 段排在 {@code AFTER_LEVEL} 之后
     * （1.21.1 源：L1279 派发 AFTER_LEVEL，L1281 才是 {@code renderItemInHand}），
     * HUD 更是在整段 {@code renderLevel} 之后由 {@code Gui.render} 画。
     * 所以这里抓到的就是「玩家眼睛里那片干净的世界」，不需要隐藏任何东西。
     * <p>
     * ⚠ <b>光影（Iris）例外</b>：Iris 会把第一人称手 / 手机提前画进世界 pass，并在
     * {@code renderLevel} RETURN 处把最终合成写进主渲染靶 —— 那时 AFTER_LEVEL 还没到。
     * 所以拍照帧由 {@code PhoneOverlay.beginWorldCaptureFrame()} 在帧首临时关掉 {@code renderHand}，
     * 抓完立即恢复。HUD 不受影响（它仍在本钩子之后才画）。
     * <p>
     * ⚠ 早先走的是「临时隐藏手机 + 在 GUI 层抓图」，结果是整屏原版截图（含 HUD / 物品栏 / 手臂 / 手机本身）。
     * 也<b>不能</b>靠 {@code options.hideGui} 去藏 HUD —— 它会让整个 GUI 层（含本模组的 GUI 层回调）
     * 都不绘制，抓图回调永远不触发。详见 {@link PhoneOverlay#requestWorldCapture}。
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        // ⚠ 自拍 pass 内部会再次调用 renderLevel，于是 AFTER_LEVEL 会被嵌套派发一次。
        // 那一层绝不能被当成"后摄抓图"的时机：此时主渲染靶是自拍 FBO，
        // 消费抓图会把自拍靶的画面当成主画面抓走。自拍有自己的 requestCapture 通路。
        if (SelfieCameraTarget.isRendering()) {
            return;
        }
        // 先兑现拍照抓图：抓到的必须是「不含手机 UI」的干净世界；
        // 再补画本帧 Iris 世界 pass 中暂存的手机屏幕（机身/手仍已由 Iris 世界 pass 绘制）。
        PhoneOverlay.consumeWorldCapture();
        PhoneScreenTarget.renderAfterLevel();
    }

    /**
     * MOD 总线专属注册：键位与 GUI Overlay 都必须在 MOD 初始化阶段登记。
     * <p>
     * 外层类继续承载默认 GAME 总线的游戏事件；这里只保留转调，避免把注册类事件
     * 误挂到 GAME 总线导致键位/Overlay 不生效。
     */
    @EventBusSubscriber(modid = HuhuTalk.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModBusEvents {
        private ModBusEvents() {
        }

        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            ClientModEvents.registerKeys(event);
        }

        @SubscribeEvent
        public static void registerGuiOverlays(RegisterGuiOverlaysEvent event) {
            ClientModEvents.registerGuiOverlays(event);
        }
    }
}