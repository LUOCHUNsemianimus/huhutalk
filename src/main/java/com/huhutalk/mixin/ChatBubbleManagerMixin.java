package com.huhutalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.core.chat.ChatBannerMuteController;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 拦截 TLM 女仆 AI 聊天气泡落地点 {@link ChatBubbleManager#addLLMChatText}。
 * <p>
 * 当女仆处于「沉浸式聊天」状态（由 {@link ChatBannerMuteController} 标记）时，
 * 跳过聊天栏那条 {@code <女仆名> 回复} 的系统消息（原本用 {@code sendSystemMessage} 发，仅主人可见）。
 * 该抑制独立于「女仆悄悄话」（气泡抑制）：头顶气泡由渲染器按 {@code isChatBubbleShow()} 控制，
 * 这里只负责公屏聊天栏。非沉浸时行为完全不变。
 */
@Mixin(ChatBubbleManager.class)
public abstract class ChatBubbleManagerMixin {

    @Shadow
    @Final
    private EntityMaid maid;

    /**
     * 重定向 addLLMChatText 中给主人发聊天栏消息的 sendSystemMessage 调用：
     * 若该女仆处于「沉浸式聊天」静音标记中，则吞掉该调用，避免公屏出现那条 <名字> 回复。
     */
    @Redirect(method = "addLLMChatText", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;sendSystemMessage(Lnet/minecraft/network/chat/Component;)V"
    ))
    private void huhutalkSkipChatBannerIfImmersive(ServerPlayer player, Component message) {
        if (maid == null || !ChatBannerMuteController.isMuted(maid.getUUID())) {
            player.sendSystemMessage(message);
        }
        // 否则（沉浸式聊天）不发送公屏聊天栏消息
    }
}
