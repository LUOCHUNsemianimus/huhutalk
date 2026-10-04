package com.huhutalk.event;

import com.huhutalk.client.render.PhoneScreenTarget;
import com.huhutalk.client.render.ProPhoneHandRenderer;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.pages.ChatPage;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(value = Dist.CLIENT)
public class ClientModEvents {
    public static final KeyMapping TOGGLE_PHONE = new KeyMapping(
            "key.huhutalk.toggle_phone", GLFW.GLFW_KEY_O, "key.category.huhutalk");
    /** 按住释放鼠标（进入狐狐手机鼠标操控模式）。默认为 Alt，可在按键设置中更改 */
    public static final KeyMapping RELEASE_CURSOR = new KeyMapping(
            "key.huhutalk.release_cursor", GLFW.GLFW_KEY_LEFT_ALT, "key.category.huhutalk");
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

    /** 正在按住 H 说话的聊天页（用于松手时停止录音） */
    private static ChatPage quickVoicePage = null;

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_PHONE);
        event.register(RELEASE_CURSOR);
        event.register(QUICK_VOICE);
        event.register(PHONE_CLICK);
        event.register(PHONE_BACK);
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

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        while (TOGGLE_PHONE.consumeClick()) {
            PhoneOverlay.toggle();
        }
        handleQuickVoice(mc);

        // 按住释放鼠标：通过可改键 RELEASE_CURSOR 检测按住状态（默认 Alt，可在按键设置更改）
        PhoneOverlay.setAltDown(RELEASE_CURSOR.isDown());
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
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        if (ProPhoneHandRenderer.isActive()) {
            PhoneScreenTarget.update(event.getPartialTick().getGameTimeDeltaTicks());
        } else {
            PhoneScreenTarget.markDirty();
        }
    }

    @SubscribeEvent
    public static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath("huhutalk", "phone_overlay"),
                (guiGraphics, deltaTracker) -> PhoneOverlay.render(guiGraphics, deltaTracker.getGameTimeDeltaTicks())
        );
        // 相机拍照抓取层：位于手机层之后；手机被临时隐藏的那一帧，这里抓取干净的纯游戏画面
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath("huhutalk", "phone_capture_grab"),
                (guiGraphics, deltaTracker) -> PhoneOverlay.consumeCaptureGrab()
        );
    }
}