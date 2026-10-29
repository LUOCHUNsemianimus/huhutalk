package com.huhutalk.core.archive;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.common.PhoneHelper;
import com.huhutalk.common.StableIdProvider;
import com.huhutalk.core.chat.HuhuTalkChatCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 手机档案工具的共享授权与错误映射。
 *
 * <p>只接受 HuhuTalk 手机聊天回调：sender 必须是当前在线且未被替换的同一
 * {@link ServerPlayer}，女仆必须存活、已加载、stableId 与实体一致、当前仍归属该玩家、
 * 并仍佩戴手机。模型参数里没有 owner/stable/path，不能借此跨会话读取。</p>
 */
public final class PhoneMemoryToolSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger("huhutalk.phone-memory");

    private PhoneMemoryToolSupport() {
    }

    public record Scope(MinecraftServer server, ServerPlayer sender, EntityMaid maid,
                        String stableId) {
    }

    /** 可预期的工具上下文/归属失败；由工具层转成固定小 JSON。 */
    static final class ToolFailure extends RuntimeException {
        private final String status;

        ToolFailure(String status) {
            super(status, null, false, false);
            this.status = status;
        }

        String status() {
            return status;
        }
    }

    static Scope requireScope(LLMCallback callback) {
        if (!(callback instanceof HuhuTalkChatCallback phoneCallback)) {
            throw new ToolFailure("context_unavailable");
        }
        EntityMaid maid = phoneCallback.getMaid();
        ServerPlayer sender = phoneCallback.getSender();
        String stableId = phoneCallback.getStableId();
        if (maid == null || sender == null || stableId == null || stableId.isBlank()) {
            throw new ToolFailure("context_unavailable");
        }
        if (!(maid.level() instanceof ServerLevel level)) {
            throw new ToolFailure("context_unavailable");
        }
        MinecraftServer server = level.getServer();
        if (server == null || !server.isSameThread()) {
            throw new ToolFailure("context_unavailable");
        }
        if (sender.server != server
                || server.getPlayerList().getPlayer(sender.getUUID()) != sender
                || !sender.isAlive() || sender.isRemoved()) {
            throw new ToolFailure("context_unavailable");
        }
        if (!maid.isAlive() || maid.isRemoved()) {
            throw new ToolFailure("context_unavailable");
        }
        if (!(maid instanceof StableIdProvider provider)
                || !stableId.equals(provider.getStableId())) {
            throw new ToolFailure("forbidden");
        }
        if (MaidStableIdHelper.findMaid(server, stableId) != maid) {
            throw new ToolFailure("forbidden");
        }
        if (!ArchiveAccess.mayRead(server, sender, stableId)) {
            throw new ToolFailure("forbidden");
        }
        if (!PhoneHelper.hasPhone(maid)) {
            throw new ToolFailure("forbidden");
        }
        return new Scope(server, sender, maid, stableId);
    }

    /** 只把归档异常映射为固定状态；服务端日志保留原始异常，响应不携带路径/原文异常。 */
    static String mapArchiveStatus(ArchiveException exception) {
        ArchiveException.Code code = exception.code();
        if (code == ArchiveException.Code.IO
                || code == ArchiveException.Code.CORRUPT
                || code == ArchiveException.Code.UNSUPPORTED_SCHEMA) {
            LOGGER.warn("[HuhuTalk] phone memory archive access failed: {}", code, exception);
        }
        return switch (code) {
            case VALIDATION, INVALID_IDENTIFIER -> "invalid_argument";
            case OWNERSHIP -> "forbidden";
            case NOT_FOUND, IO, CORRUPT, UNSUPPORTED_SCHEMA, CONFLICT -> "archive_unavailable";
        };
    }
}
