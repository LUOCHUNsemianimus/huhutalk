package com.huhutalk.core.archive;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.StableIdProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 服务端归档适配层：只从当前存档目录取根路径，不持有静态 MinecraftServer，
 * 也不向模型暴露可传任意 ownerUuid 的查询入口。读写要求调用方在 server 线程执行。
 */
public final class ArchiveService {
    private static final Logger LOG = Logger.getLogger("huhutalk.archive");
    private static final String ARCHIVE_DIR = "huhutalk_archive";
    private static final String ORIGIN_WORLD_ID_FILE = "origin_world_id.txt";

    private ArchiveService() {
    }

    public static ArchiveStore store(MinecraftServer server) {
        requireServerThread(server);
        return new ArchiveStore(server.getWorldPath(LevelResource.ROOT).resolve(ARCHIVE_DIR));
    }

    /** 所有接触 server 世界或存储的入口都先经过这里，禁止跨线程快照读写。 */
    private static void requireServerThread(MinecraftServer server) {
        if (server == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "server must not be null");
        }
        if (!server.isSameThread()) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "archive access must run on the server thread");
        }
    }

    private static void requireServerLevel(MinecraftServer server, net.minecraft.world.entity.Entity entity,
                                           String what) {
        if (!(entity.level() instanceof ServerLevel level) || level.getServer() != server) {
            throw new ArchiveException(ArchiveException.Code.OWNERSHIP,
                    what + " does not belong to the target server");
        }
    }

    /**
     * 持久化当前世界独有的归档 originWorldId。文件缺失时生成一次；内容非法时
     * 显式失败而不是重新生成覆盖。
     */
    public static String originWorldId(MinecraftServer server) {
        ArchiveStore store = store(server);
        Path file = store.root().resolve(ORIGIN_WORLD_ID_FILE);
        try {
            if (Files.exists(file)) {
                return ArchiveIds.requireCanonicalUuid(
                        Files.readString(file, StandardCharsets.UTF_8).trim(), "originWorldId");
            }
            String created = UUID.randomUUID().toString();
            Files.createDirectories(store.root());
            Path temp = Files.createTempFile(store.root(), ".origin-world-", ".tmp");
            try {
                Files.writeString(temp, created + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    LOG.log(Level.WARNING,
                            "Atomic originWorldId move unsupported; falling back to regular replace: " + file,
                            exception);
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return created;
        } catch (IOException exception) {
            throw new ArchiveException(ArchiveException.Code.IO,
                    "cannot persist archive originWorldId: " + file, exception);
        }
    }

    /**
     * 记录一条通过现有手机/归属/长度/AI 校验的在线纯文本玩家原话。
     * 归档失败抛出异常，由聊天接通点限捕获并记录日志。
     */
    public static ArchiveTurnContext recordPlayerText(MinecraftServer server, ServerPlayer player,
                                                       EntityMaid maid, String maidStableId, String text) {
        requireServerThread(server);
        if (player == null || maid == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "player and maid must not be null");
        }
        if (player.serverLevel().getServer() != server) {
            throw new ArchiveException(ArchiveException.Code.OWNERSHIP,
                    "player does not belong to the target server");
        }
        requireServerLevel(server, maid, "maid");
        String stableId = ArchiveIds.requireStableId(maidStableId, "maidStableId");
        if (!(maid instanceof StableIdProvider provider) || !stableId.equals(provider.getStableId())) {
            throw new ArchiveException(ArchiveException.Code.OWNERSHIP,
                    "maid stable id does not match archive scope");
        }
        if (maid.getOwnerUUID() == null || !maid.getOwnerUUID().equals(player.getUUID())) {
            throw new ArchiveException(ArchiveException.Code.OWNERSHIP,
                    "maid ownership changed before archiving player message");
        }

        String worldId = originWorldId(server);
        String turnId = UUID.randomUUID().toString();
        String playerMessageId = UUID.randomUUID().toString();
        ArchiveRecord draft = new ArchiveRecord(playerMessageId, turnId, null,
                ArchiveSource.ONLINE_PHONE_TEXT, ArchiveRole.PLAYER, text,
                System.currentTimeMillis(), worldId, player.serverLevel().getGameTime(),
                0L, List.of(), null);
        store(server).appendOrCreate(player.getUUID(), stableId, worldId,
                ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL, draft);
        return new ArchiveTurnContext(player.getUUID(), stableId, turnId, playerMessageId, worldId);
    }

    /**
     * 记录本轮回调最终文本回复。即使 sender 已离线/死亡也保留已经得到的回复，
     * 但女仆当前归属必须仍然是本轮主人。
     */
    public static void recordMaidReply(MinecraftServer server, EntityMaid maid,
                                       ArchiveTurnContext turn, String maidMessageId, String replyText) {
        requireServerThread(server);
        if (maid == null || turn == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "maid and archive turn must not be null");
        }
        requireServerLevel(server, maid, "maid");
        if (replyText == null || replyText.isBlank()) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "maid reply text must not be blank");
        }
        if (!(maid instanceof StableIdProvider provider)
                || !turn.maidStableId().equals(provider.getStableId())
                || !turn.ownerUuid().equals(maid.getOwnerUUID())) {
            throw new ArchiveException(ArchiveException.Code.OWNERSHIP,
                    "maid ownership changed before archiving reply");
        }

        ArchiveRecord draft = new ArchiveRecord(maidMessageId, turn.turnId(),
                turn.playerMessageId(), ArchiveSource.ONLINE_PHONE_TEXT, ArchiveRole.MAID,
                replyText, System.currentTimeMillis(), turn.originWorldId(),
                maid.level().getGameTime(), 0L, List.of(), null);
        store(server).append(turn.ownerUuid(), turn.maidStableId(), draft);
    }

    public static ArchivePage query(MinecraftServer server, UUID ownerUuid, String maidStableId,
                                    int offset, int limit, ArchiveRole role, String keyword) {
        return store(server).query(ownerUuid, maidStableId, offset, limit, role, keyword);
    }

    public static Optional<ArchiveRecord> findByMessageId(MinecraftServer server, UUID ownerUuid,
                                                          String maidStableId, String messageId) {
        return store(server).findByMessageId(ownerUuid, maidStableId, messageId);
    }

    public static Optional<ArchiveSnapshot> snapshot(MinecraftServer server, UUID ownerUuid,
                                                     String maidStableId) {
        return store(server).load(ownerUuid, maidStableId);
    }

    public static Optional<String> exportSnapshotJson(MinecraftServer server, UUID ownerUuid,
                                                      String maidStableId) {
        return snapshot(server, ownerUuid, maidStableId).map(ArchiveSnapshot::toJson);
    }

    public static ArchiveImportResult importSnapshotJson(MinecraftServer server, String snapshotJson,
                                                         UUID targetOwnerUuid, String targetMaidStableId) {
        requireServerThread(server);
        return store(server).importSnapshot(ArchiveSnapshot.fromJson(snapshotJson),
                targetOwnerUuid, targetMaidStableId);
    }

    public static ArchiveImportResult importSnapshot(MinecraftServer server, ArchiveSnapshot snapshot,
                                                     UUID targetOwnerUuid, String targetMaidStableId) {
        return store(server).importSnapshot(snapshot, targetOwnerUuid, targetMaidStableId);
    }
}
