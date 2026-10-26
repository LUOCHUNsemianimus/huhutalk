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
 *
 * <p>本类提供在线文字/图片/物品事件、识图观察/失败、结构化事件追加和授权资产读取
 * 等可复用基础接口；物品库仍由现有 transfer 服务唯一持有，历史未覆盖部分继续标 partial。</p>
 */
public final class ArchiveService {
    private static final Logger LOG = Logger.getLogger("huhutalk.archive");
    private static final String ARCHIVE_DIR = "huhutalk_archive";
    private static final String ORIGIN_WORLD_ID_FILE = "origin_world_id.txt";
    private static final int MAX_CAPTION_LENGTH = 2_000;
    private static final int MAX_OBSERVATION_LENGTH = 8_000;
    private static final ArchiveCoverage ONLINE_PHONE_IMAGE_PARTIAL =
            new ArchiveCoverage(true, List.of("online_phone_text", "online_phone_image"));

    private ArchiveService() {
    }

    public static ArchiveStore store(MinecraftServer server) {
        requireServerThread(server);
        return new ArchiveStore(server.getWorldPath(LevelResource.ROOT).resolve(ARCHIVE_DIR));
    }

    /** 与快照同根的内容寻址 PNG 存储；附件目录为 {@code assets/}。 */
    public static ArchiveAssetStore assetStore(MinecraftServer server) {
        requireServerThread(server);
        return new ArchiveAssetStore(store(server).root());
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

    private static void requirePlayerMaidScope(MinecraftServer server, ServerPlayer player,
                                               EntityMaid maid, String maidStableId) {
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
    }

    private static void requireMaidTurn(MinecraftServer server, EntityMaid maid,
                                        ArchiveTurnContext turn) {
        if (maid == null || turn == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "maid and archive turn must not be null");
        }
        requireServerLevel(server, maid, "maid");
        if (!(maid instanceof StableIdProvider provider)
                || !turn.maidStableId().equals(provider.getStableId())
                || !turn.ownerUuid().equals(maid.getOwnerUUID())) {
            throw new ArchiveException(ArchiveException.Code.OWNERSHIP,
                    "maid ownership changed before archiving reply");
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
        requirePlayerMaidScope(server, player, maid, maidStableId);

        String worldId = originWorldId(server);
        String turnId = UUID.randomUUID().toString();
        String playerMessageId = UUID.randomUUID().toString();
        ArchiveRecord draft = new ArchiveRecord(playerMessageId, turnId, null,
                ArchiveSource.ONLINE_PHONE_TEXT, ArchiveRole.PLAYER, text,
                System.currentTimeMillis(), worldId, player.serverLevel().getGameTime(),
                0L, List.of(), null);
        store(server).appendOrCreate(player.getUUID(), maidStableId, worldId,
                ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL, draft);
        return new ArchiveTurnContext(player.getUUID(), maidStableId, turnId, playerMessageId,
                worldId, ArchiveSource.ONLINE_PHONE_TEXT);
    }

    /**
     * 开始一个图片 turn：先保存内容寻址 PNG，再以 image_sent 事件和 attachmentRefs
     * 创建玩家消息。caption 可为 null/空。资产先落盘而消息写失败时会留下未被引用
     * 的孤立资产，由后续迁移/清理处理，不把失败图标记为成功。
     */
    public static ArchiveTurnContext recordPlayerImage(MinecraftServer server, ServerPlayer player,
                                                        EntityMaid maid, String maidStableId,
                                                        byte[] pngBytes, String caption) {
        requireServerThread(server);
        requirePlayerMaidScope(server, player, maid, maidStableId);
        String safeCaption = caption == null ? "" : caption;
        if (safeCaption.length() > MAX_CAPTION_LENGTH) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "image caption exceeds " + MAX_CAPTION_LENGTH + " characters");
        }

        String worldId = originWorldId(server);
        String hash = new ArchiveAssetStore(store(server).root()).store(pngBytes);
        String turnId = UUID.randomUUID().toString();
        String playerMessageId = UUID.randomUUID().toString();
        ArchiveRecord draft = new ArchiveRecord(playerMessageId, turnId, null,
                ArchiveSource.ONLINE_PHONE_IMAGE, ArchiveRole.PLAYER, safeCaption,
                System.currentTimeMillis(), worldId, player.serverLevel().getGameTime(),
                0L, List.of(hash), null, ArchiveEvent.imageSent());
        store(server).appendOrCreate(player.getUUID(), maidStableId, worldId,
                ONLINE_PHONE_IMAGE_PARTIAL, draft);
        return new ArchiveTurnContext(player.getUUID(), maidStableId, turnId, playerMessageId,
                worldId, ArchiveSource.ONLINE_PHONE_IMAGE);
    }

    /**
     * 记录一次已实际执行的 P→M 物品转移：玩家消息原文只保存一次（可为空），
     * 主事件只列实际接收数量。部分失败时另追加 SYSTEM 失败事件关联该玩家消息；
     * 全部失败时主事件直接标 FAILED 且数量表示未送达部分。
     */
    public static ArchiveTurnContext recordPlayerTransfer(MinecraftServer server, ServerPlayer player,
                                                           EntityMaid maid, String maidStableId,
                                                           String caption,
                                                           List<ArchiveItemSummary> receivedItems,
                                                           List<ArchiveItemSummary> failedItems,
                                                           String transferId) {
        requireServerThread(server);
        requirePlayerMaidScope(server, player, maid, maidStableId);
        String safeCaption = caption == null ? "" : caption;
        if (safeCaption.length() > MAX_CAPTION_LENGTH) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "item caption exceeds " + MAX_CAPTION_LENGTH + " characters");
        }
        List<ArchiveItemSummary> received = copySummaries(receivedItems, "receivedItems");
        List<ArchiveItemSummary> failed = copySummaries(failedItems, "failedItems");
        if (received.isEmpty() && failed.isEmpty()) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "player transfer needs at least one item fact");
        }

        ArchiveTransferOutcome outcome;
        List<ArchiveItemSummary> mainItems;
        List<ArchiveItemSummary> failureItems;
        if (received.isEmpty()) {
            outcome = ArchiveTransferOutcome.FAILED;
            mainItems = failed;
            failureItems = List.of();
        } else if (failed.isEmpty()) {
            outcome = ArchiveTransferOutcome.SUCCESS;
            mainItems = received;
            failureItems = List.of();
        } else {
            outcome = ArchiveTransferOutcome.PARTIAL;
            mainItems = received;
            failureItems = failed;
        }

        String worldId = originWorldId(server);
        String turnId = UUID.randomUUID().toString();
        String playerMessageId = UUID.randomUUID().toString();
        long gameTime = player.serverLevel().getGameTime();
        ArchiveEvent event = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                ArchiveTransferDirection.PLAYER_TO_MAID, outcome, mainItems, transferId);
        ArchiveRecord draft = new ArchiveRecord(playerMessageId, turnId, null,
                ArchiveSource.ONLINE_PHONE_ITEMS, ArchiveRole.PLAYER, safeCaption,
                System.currentTimeMillis(), worldId, gameTime, 0L, List.of(), null, event);
        store(server).appendOrCreate(player.getUUID(), maidStableId, worldId,
                ArchiveCoverage.ONLINE_PHONE_ITEMS_PARTIAL, draft);

        if (!failureItems.isEmpty()) {
            ArchiveEvent failureEvent = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                    ArchiveTransferDirection.PLAYER_TO_MAID, ArchiveTransferOutcome.FAILED,
                    failureItems, transferId);
            ArchiveRecord failureDraft = new ArchiveRecord(UUID.randomUUID().toString(), turnId,
                    playerMessageId, ArchiveSource.ONLINE_PHONE_ITEMS, ArchiveRole.SYSTEM,
                    "transfer_not_delivered", System.currentTimeMillis(), worldId, gameTime,
                    0L, List.of(), null, failureEvent);
            try {
                store(server).append(player.getUUID(), maidStableId, failureDraft);
            } catch (ArchiveException exception) {
                // 主玩家消息已经落盘；失败补充事件失败只记日志，不能回头重复玩家原话。
                LOG.log(Level.WARNING,
                        "Cannot append transfer failure event; main player message kept", exception);
            }
        }
        return new ArchiveTurnContext(player.getUUID(), maidStableId, turnId, playerMessageId,
                worldId, ArchiveSource.ONLINE_PHONE_ITEMS);
    }

    /**
     * 物品动作完成后的独立结构化事件入口（M→P 入箱/领取/丢地）。
     * 女仆可能已卸载或死亡，所以只按当前玩家 + ArchiveAccess 归属守卫，不依赖 EntityMaid；
     * 旧批次没有归档时 appendOrCreate 并声明 partial，不伪造玩家文字。
     */
    public static ArchiveRecord recordTransferEvent(MinecraftServer server, ServerPlayer player,
                                                     String maidStableId, ArchiveTurnContext turn,
                                                     ArchiveRole role, ArchiveSource source,
                                                     String text, String relatedMessageId,
                                                     ArchiveEvent event) {
        requireServerThread(server);
        if (player == null || player.serverLevel().getServer() != server) {
            throw new ArchiveException(ArchiveException.Code.OWNERSHIP,
                    "player does not belong to the target server");
        }
        String stableId = ArchiveIds.requireStableId(maidStableId, "maidStableId");
        if (!ArchiveAccess.mayRead(server, player, stableId)) {
            throw new ArchiveException(ArchiveException.Code.OWNERSHIP,
                    "player does not currently own the transfer archive scope");
        }
        if (role == null || source == null || text == null || event == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "transfer event role/source/text/event must not be null");
        }

        String turnId;
        String worldId;
        if (turn != null) {
            if (!player.getUUID().equals(turn.ownerUuid())
                    || !stableId.equals(turn.maidStableId())) {
                throw new ArchiveException(ArchiveException.Code.OWNERSHIP,
                        "transfer event turn does not match player/maid scope");
            }
            turnId = turn.turnId();
            worldId = turn.originWorldId();
        } else {
            turnId = UUID.randomUUID().toString();
            worldId = originWorldId(server);
        }
        if (relatedMessageId != null) {
            ArchiveRecord related = store(server)
                    .findByMessageId(player.getUUID(), stableId, relatedMessageId)
                    .orElseThrow(() -> new ArchiveException(ArchiveException.Code.NOT_FOUND,
                            "related transfer message does not exist: " + relatedMessageId));
            if (!turnId.equals(related.turnId())) {
                throw new ArchiveException(ArchiveException.Code.OWNERSHIP,
                        "related transfer message does not belong to the archive turn");
            }
        }

        ArchiveRecord draft = new ArchiveRecord(UUID.randomUUID().toString(), turnId,
                relatedMessageId, source, role, text, System.currentTimeMillis(), worldId,
                player.serverLevel().getGameTime(), 0L, List.of(), null, event);
        return store(server).appendOrCreate(player.getUUID(), stableId, worldId,
                ArchiveCoverage.ONLINE_PHONE_ITEMS_PARTIAL, draft);
    }

    private static List<ArchiveItemSummary> copySummaries(List<ArchiveItemSummary> items, String field) {
        if (items == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    field + " must not be null");
        }
        List<ArchiveItemSummary> copy = new java.util.ArrayList<>(items.size());
        for (ArchiveItemSummary item : items) {
            if (item == null) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        field + " must not contain null");
            }
            copy.add(item);
        }
        return List.copyOf(copy);
    }

    /**
     * 记录本轮回调最终文本回复。即使 sender 已离线/死亡也保留已经得到的回复，
     * 但女仆当前归属必须仍然是本轮主人。来源沿用 turn.source。
     */
    public static void recordMaidReply(MinecraftServer server, EntityMaid maid,
                                       ArchiveTurnContext turn, String maidMessageId, String replyText) {
        recordMaidReply(server, maid, turn, maidMessageId, replyText, null);
    }

    /**
     * 记录本轮回调最终文本回复，可选附带本次回复选中的表情资源。
     * 表情只保存资源名文本，不复制图片、不请求模型；空/null 与旧行为一致。
     */
    public static void recordMaidReply(MinecraftServer server, EntityMaid maid,
                                       ArchiveTurnContext turn, String maidMessageId, String replyText,
                                       String emoteResource) {
        requireServerThread(server);
        if (maid == null || turn == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "maid and archive turn must not be null");
        }
        requireMaidTurn(server, maid, turn);
        if (replyText == null || replyText.isBlank()) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "maid reply text must not be blank");
        }
        ArchiveEvent event = null;
        if (emoteResource != null && !emoteResource.isBlank()) {
            event = ArchiveEvent.emote(emoteResource.trim());
        }

        ArchiveRecord draft = new ArchiveRecord(maidMessageId, turn.turnId(),
                turn.playerMessageId(), turn.source(), ArchiveRole.MAID,
                replyText, System.currentTimeMillis(), turn.originWorldId(),
                maid.level().getGameTime(), 0L, List.of(), null, event);
        store(server).append(turn.ownerUuid(), turn.maidStableId(), draft);
    }

    /**
     * 记录一次成功的识图观察。必须存在同一 turn/owner/stableId 的图片玩家消息，
     * 观察本身使用 OBSERVATION 角色和来源，不额外调用 LLM。
     */
    public static ArchiveRecord recordVisionObservation(MinecraftServer server, EntityMaid maid,
                                                         ArchiveTurnContext turn, String messageId,
                                                         String description, String site, String model) {
        requireServerThread(server);
        requireMaidTurn(server, maid, turn);
        if (description == null || description.isBlank()) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "vision observation text must not be blank");
        }
        if (description.length() > MAX_OBSERVATION_LENGTH) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "vision observation exceeds " + MAX_OBSERVATION_LENGTH + " characters");
        }
        ArchiveRecord parent = requireImageTurnParent(server, turn);
        ArchiveRecord draft = new ArchiveRecord(messageId, turn.turnId(), parent.messageId(),
                ArchiveSource.OBSERVATION, ArchiveRole.OBSERVATION, description,
                System.currentTimeMillis(), turn.originWorldId(), maid.level().getGameTime(),
                0L, List.of(), null, ArchiveEvent.visionObservation(site, model));
        return store(server).append(turn.ownerUuid(), turn.maidStableId(), draft);
    }

    /**
     * 记录识图失败。失败是 SYSTEM 状态，不写成成功观察描述，也不会触发模型调用。
     */
    public static ArchiveRecord recordVisionFailure(MinecraftServer server, EntityMaid maid,
                                                     ArchiveTurnContext turn, String messageId,
                                                     String reason, String site, String model) {
        requireServerThread(server);
        requireMaidTurn(server, maid, turn);
        if (reason == null || reason.isBlank()) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "vision failure reason must not be blank");
        }
        if (reason.length() > MAX_OBSERVATION_LENGTH) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "vision failure reason exceeds " + MAX_OBSERVATION_LENGTH + " characters");
        }
        ArchiveRecord parent = requireImageTurnParent(server, turn);
        ArchiveRecord draft = new ArchiveRecord(messageId, turn.turnId(), parent.messageId(),
                ArchiveSource.OBSERVATION, ArchiveRole.SYSTEM, reason,
                System.currentTimeMillis(), turn.originWorldId(), maid.level().getGameTime(),
                0L, List.of(), null, ArchiveEvent.visionFailed(site, model));
        return store(server).append(turn.ownerUuid(), turn.maidStableId(), draft);
    }

    /**
     * 共用的结构化事件追加路径。图片与物品事件现已接通；旧 system 注入等入口仍可能
     * 传 null turn，本轮没有做到全调用点覆盖。relatedMessageId 若存在必须属于同一 turn。
     */
    public static ArchiveRecord recordEvent(MinecraftServer server, EntityMaid maid,
                                            ArchiveTurnContext turn, String messageId,
                                            ArchiveRole role, ArchiveSource source, String text,
                                            String relatedMessageId, ArchiveEvent event) {
        requireServerThread(server);
        requireMaidTurn(server, maid, turn);
        if (role == null || source == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "event role and source must not be null");
        }
        if (text == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "event text must not be null");
        }
        if (event == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "structured event must not be null");
        }
        if (relatedMessageId != null) {
            ArchiveRecord related = store(server)
                    .findByMessageId(turn.ownerUuid(), turn.maidStableId(), relatedMessageId)
                    .orElseThrow(() -> new ArchiveException(ArchiveException.Code.NOT_FOUND,
                            "related archive message does not exist: " + relatedMessageId));
            if (!turn.turnId().equals(related.turnId())) {
                throw new ArchiveException(ArchiveException.Code.OWNERSHIP,
                        "related message does not belong to the archive turn");
            }
        }
        ArchiveRecord draft = new ArchiveRecord(messageId, turn.turnId(), relatedMessageId,
                source, role, text, System.currentTimeMillis(), turn.originWorldId(),
                maid.level().getGameTime(), 0L, List.of(), null, event);
        return store(server).append(turn.ownerUuid(), turn.maidStableId(), draft);
    }

    /**
     * 授权读取：hash 必须由当前 owner+stableId 会话的某条记录 attachmentRefs 引用。
     * 仅猜中 hash 不能读取其他会话的资产。
     */
    public static byte[] readAsset(MinecraftServer server, ServerPlayer player, EntityMaid maid,
                                   String maidStableId, String assetHash) {
        requireServerThread(server);
        requirePlayerMaidScope(server, player, maid, maidStableId);
        return readAsset(server, player.getUUID(), maidStableId, assetHash);
    }

    public static byte[] readAsset(MinecraftServer server, UUID ownerUuid, String maidStableId,
                                   String assetHash) {
        requireServerThread(server);
        if (ownerUuid == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "ownerUuid must not be null");
        }
        String owner = ArchiveIds.requireCanonicalUuid(ownerUuid.toString(), "ownerUuid");
        String stableId = ArchiveIds.requireStableId(maidStableId, "maidStableId");
        String hash = ArchiveAssetStore.requireHash(assetHash);
        ArchiveSnapshot snapshot = store(server).load(ownerUuid, stableId)
                .orElseThrow(() -> new ArchiveException(ArchiveException.Code.NOT_FOUND,
                        "archive does not exist for asset read: owner=" + owner + ", maid=" + stableId));
        boolean referenced = snapshot.records().stream()
                .anyMatch(record -> record.attachmentRefs().contains(hash));
        if (!referenced) {
            throw new ArchiveException(ArchiveException.Code.OWNERSHIP,
                    "asset is not referenced by the current archive scope: " + hash);
        }
        return new ArchiveAssetStore(store(server).root()).read(hash);
    }

    /** 列出当前会话快照引用的资产 hash，供后续迁移打包；缺档返回空列表。 */
    public static List<String> referencedAssets(MinecraftServer server, UUID ownerUuid,
                                                 String maidStableId) {
        requireServerThread(server);
        return store(server).load(ownerUuid, maidStableId)
                .map(ArchiveAssetStore::referencedHashes)
                .orElse(List.of());
    }

    private static ArchiveRecord requireImageTurnParent(MinecraftServer server,
                                                        ArchiveTurnContext turn) {
        ArchiveRecord parent = store(server)
                .findByMessageId(turn.ownerUuid(), turn.maidStableId(), turn.playerMessageId())
                .orElseThrow(() -> new ArchiveException(ArchiveException.Code.NOT_FOUND,
                        "image archive turn has no player message"));
        if (!turn.turnId().equals(parent.turnId())
                || parent.role() != ArchiveRole.PLAYER
                || parent.source() != ArchiveSource.ONLINE_PHONE_IMAGE
                || turn.source() != ArchiveSource.ONLINE_PHONE_IMAGE
                || !turn.originWorldId().equals(parent.originWorldId())
                || parent.event() == null
                || parent.event().kind() != ArchiveEventKind.IMAGE_SENT
                || parent.attachmentRefs().isEmpty()) {
            throw new ArchiveException(ArchiveException.Code.OWNERSHIP,
                    "vision event parent does not belong to the expected image turn");
        }
        return parent;
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
