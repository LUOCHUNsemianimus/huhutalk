package com.huhutalk.event;

import com.huhutalk.phone.PhoneOverlay;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(value = Dist.CLIENT)
public class ClientModEvents {
    public static final KeyMapping TOGGLE_PHONE = new KeyMapping(
            "key.phone.toggle", GLFW.GLFW_KEY_O, "key.category.phone");
    public static final KeyMapping QUICK_VOICE = new KeyMapping(
            "key.phone.voice", GLFW.GLFW_KEY_F, "key.category.phone");

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
        while (QUICK_VOICE.consumeClick()) {
            // TODO: 快捷语音
        }

        long window = mc.getWindow().getWindow();
        boolean altDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT) ||
                InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
        PhoneOverlay.setAltDown(altDown);
    }

    @SubscribeEvent
    public static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath("huhutalk", "phone_overlay"),
                (guiGraphics, deltaTracker) -> PhoneOverlay.render(guiGraphics, deltaTracker.getGameTimeDeltaTicks())
        );
    }
}