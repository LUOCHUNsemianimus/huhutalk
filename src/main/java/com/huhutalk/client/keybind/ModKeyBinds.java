package com.huhutalk.client.keybind;

import com.huhutalk.HuhuTalk;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public class ModKeyBinds {
    public static KeyMapping openPhoneKey;

    public static void registerKeybinds(RegisterKeyMappingsEvent event) {
        openPhoneKey = new KeyMapping(
                "key." + HuhuTalk.MOD_ID + ".open_phone",
                GLFW.GLFW_KEY_H,
                "category." + HuhuTalk.MOD_ID
        );
        event.register(openPhoneKey);
    }

    public static void handleKeyInput() {
        if (openPhoneKey == null) return;
        while (openPhoneKey.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            // 手机已打开则关闭；未打开且没有其他 GUI 时打开
            if (PhoneOverlay.isVisible()) {
                PhoneOverlay.toggle();
            } else if (mc.screen == null) {
                PhoneOverlay.toggle();
            }
        }
    }
}