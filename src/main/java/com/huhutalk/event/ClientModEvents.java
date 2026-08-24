package com.huhutalk.event;

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
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(value = Dist.CLIENT)
public class ClientModEvents {
    public static final KeyMapping TOGGLE_PHONE = new KeyMapping(
            "key.phone.toggle", GLFW.GLFW_KEY_O, "key.category.phone");
    public static final KeyMapping QUICK_VOICE = new KeyMapping(
            "key.phone.voice", GLFW.GLFW_KEY_F, "key.category.phone");

    /** 正在按住 F 说话的聊天页（用于松手时停止录音） */
    private static ChatPage quickVoicePage = null;

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_PHONE);
        event.register(QUICK_VOICE);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        while (TOGGLE_PHONE.consumeClick()) {
            PhoneOverlay.toggle();
        }
        handleQuickVoice(mc);

        long window = mc.getWindow().getWindow();
        boolean altDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT) ||
                InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
        PhoneOverlay.setAltDown(altDown);
    }

    /**
     * F 键"按住说话"：仅当手机可见且当前页为聊天页时生效。
     * 按住 F → 开始录音；松开 F → 停止并发送。是否打开扩展面板均可用。
     */
    private static void handleQuickVoice(Minecraft mc) {
        boolean down = InputConstants.isKeyDown(mc.getWindow().getWindow(), QUICK_VOICE.getKey().getValue());
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