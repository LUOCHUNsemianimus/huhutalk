package com.huhutalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
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
 * 当女仆处于「女仆悄悄话」（沉浸手机聊天）状态时，其气泡开关 {@code isChatBubbleShow()} 会被临时置为 false，
 * 此时应跳过聊天栏那条 {@code <女仆名> 回复} 的系统消息（原本用 {@code sendSystemMessage} 发，仅主人可见），
 * 同时保留头顶聊天气泡数据写入（渲染器因为气泡开关为 false 也不会画出来）。
 * 非沉浸时行为完全不变。
 */
@Mixin(ChatBubbleManager.class)
public abstract class ChatBubbleManagerMixin {

    @Shadow
    @Final
    private EntityMaid maid;

    /**
     * 重定向 addLLMChatText 中给主人发聊天栏消息的 sendSystemMessage 调用：
     * 若女仆气泡当前被关闭（沉浸悄悄话），则吞掉该调用，避免聊天栏出现那条 <名字> 回复。
     */
    @Redirect(method = "addLLMChatText", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;sendSystemMessage(Lnet/minecraft/network/chat/Component;)V"
    ))
    private void huhutalkSkipChatBannerIfWhisper(ServerPlayer player, Component message) {
        if (maid != null && maid.getConfigManager().isChatBubbleShow()) {
            player.sendSystemMessage(message);
        }
        // 否则（沉浸悄悄话，气泡已临时关闭）不发送聊天栏消息
    }
}
