package com.huhutalk.core.archive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 独立于 Minecraft 的会话归档存储。
 *
 * <p>磁盘布局为 {@code root/<ownerUuid>/<maidStableId>.json}，每个会话一个 JSON
 * 快照。每次追加只重写该会话文件，先写同目录临时文件再原子替换；不支持原子移动时
 * 明确记录降级日志后才普通替换。读取失败、schema 未知、标识非法或冲突都显式抛出
 * {@link ArchiveException}，不会覆盖原文件。</p>
 *
 * <p>本阶段是整文件快照实现，适合当前聊天量；规模扩大后需要分段/索引，不能在
 * 这里静默淘汰旧消息。</p>
 */
public final class ArchiveStore {
    private static final Logger LOG = Logger.getLogger("huhutalk.archive");
    private static final int MAX_PAGE_LIMIT = 1000;

    private final Path root;

    public ArchiveStore(Path root) {
        if (root == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "archive root must not be null");
        }
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Optional<ArchiveSnapshot> load(UUID ownerUuid, String maidStableId) {
        return Optional.ofNullable(loadSnapshot(ownerUuid, maidStableId));
    }

    /**
     * 追加一条记录；会话文件不存在时创建，存在时只重写该会话。
     */
    public ArchiveRecord appendOrCreate(UUID ownerUuid, String maidStableId, String originWorldId,
                                        ArchiveCoverage coverage, ArchiveRecord draft) {
        String owner = requireOwner(ownerUuid);
        String stableId = ArchiveIds.requireStableId(maidStableId, "maidStableId");
        String worldId = ArchiveIds.requireToken(originWorldId, "originWorldId");
        if (coverage == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "coverage must not be null");
        }
        if (draft == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "record must not be null");
        }
        if (!worldId.equals(draft.originWorldId())) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "record originWorldId does not match archive originWorldId");
        }

        ArchiveSnapshot snapshot = loadSnapshot(ownerUuid, stableId);
        if (snapshot == null) {
            snapshot = new ArchiveSnapshot(ArchiveSnapshot.CURRENT_SCHEMA_VERSION,
                    UUID.randomUUID().toString(), owner, stableId, coverage, worldId,
                    System.currentTimeMillis(), List.of());
        } else {
            ArchiveRecord duplicate = find(snapshot, draft.messageId());
            if (duplicate != null) {
                if (sameContent(duplicate, draft)) {
                    return duplicate;
                }
                throw new ArchiveException(ArchiveException.Code.CONFLICT,
                        "messageId already exists with different content: " + draft.messageId());
            }
        }

        ArchiveRecord stored = draft.withSequence(nextSequence(snapshot));
        List<ArchiveRecord> records = new ArrayList<>(snapshot.records());
        records.add(stored);
        // 会话已存在时不能丢弃本次入口声明的 coverage：先文字后图片仍要保留两种 scope。
        ArchiveSnapshot updated = new ArchiveSnapshot(snapshot.schemaVersion(),
                snapshot.archiveId(), snapshot.ownerUuid(), snapshot.maidStableId(),
                snapshot.coverage().merge(coverage), snapshot.originWorldId(),
                snapshot.createdAtEpochMillis(), records);
        writeSnapshot(archiveFile(ownerUuid, stableId), updated);
        return stored;
    }

    /**
     * 追加到已存在的会话；会话不存在时显式失败，避免为孤立回复创建新归档。
     */
    public ArchiveRecord append(UUID ownerUuid, String maidStableId, ArchiveRecord draft) {
        String owner = requireOwner(ownerUuid);
        String stableId = ArchiveIds.requireStableId(maidStableId, "maidStableId");
        if (draft == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "record must not be null");
        }
        ArchiveSnapshot snapshot = loadSnapshot(ownerUuid, stableId);
        if (snapshot == null) {
            throw new ArchiveException(ArchiveException.Code.NOT_FOUND,
                    "archive does not exist for owner=" + owner + ", maid=" + stableId);
        }
        // originWorldId 是事件来源世界，归档初建来源只记录在快照上；跨世界续写允许不同。
        ArchiveRecord duplicate = find(snapshot, draft.messageId());
        if (duplicate != null) {
            if (sameContent(duplicate, draft)) {
                return duplicate;
            }
            throw new ArchiveException(ArchiveException.Code.CONFLICT,
                    "messageId already exists with different content: " + draft.messageId());
        }

        ArchiveRecord stored = draft.withSequence(nextSequence(snapshot));
        List<ArchiveRecord> records = new ArrayList<>(snapshot.records());
        records.add(stored);
        writeSnapshot(archiveFile(ownerUuid, stableId), snapshot.withRecords(records));
        return stored;
    }

    /**
     * 按 sequence 排序的分页查询；role 为 null 表示不过滤角色，keyword 为
     * null/空白表示不过滤原文关键词。
     */
    public ArchivePage query(UUID ownerUuid, String maidStableId, int offset, int limit,
                             ArchiveRole role, String keyword) {
        if (offset < 0) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "offset must be >= 0");
        }
        if (limit <= 0 || limit > MAX_PAGE_LIMIT) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "limit must be between 1 and " + MAX_PAGE_LIMIT);
        }
        ArchiveSnapshot snapshot = loadSnapshot(ownerUuid, maidStableId);
        if (snapshot == null) {
            // 缺档不能冒充已采集：partial 但没有 online_phone_text scope。
            return new ArchivePage(null, List.of(), offset, limit, 0, false, false,
                    new ArchiveCoverage(true, List.of()));
        }
        String normalizedKeyword = keyword == null ? null : keyword.trim().toLowerCase(Locale.ROOT);
        List<ArchiveRecord> matching = new ArrayList<>();
        for (ArchiveRecord record : snapshot.records()) {
            if (role != null && record.role() != role) {
                continue;
            }
            if (normalizedKeyword != null && !normalizedKeyword.isEmpty()
                    && !record.text().toLowerCase(Locale.ROOT).contains(normalizedKeyword)) {
                continue;
            }
            matching.add(record);
        }
        matching.sort(Comparator.comparingLong(ArchiveRecord::sequence));
        int totalMatching = matching.size();
        int from = Math.min(offset, totalMatching);
        int to = Math.min(from + limit, totalMatching);
        List<ArchiveRecord> page = List.copyOf(matching.subList(from, to));
        return new ArchivePage(snapshot.archiveId(), page, offset, limit, totalMatching,
                to < totalMatching, true, snapshot.coverage());
    }

    public Optional<ArchiveRecord> findByMessageId(UUID ownerUuid, String maidStableId, String messageId) {
        String id = ArchiveIds.requireToken(messageId, "messageId");
        ArchiveSnapshot snapshot = loadSnapshot(ownerUuid, maidStableId);
        if (snapshot == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(find(snapshot, id));
    }

    /**
     * 幂等导入：先完成全部校验，再单次落盘。导入保持 archiveId 与记录 ID，
     * 目标归属由调用方显式提供；快照声称的 owner/maid 必须与目标一致。
     */
    public ArchiveImportResult importSnapshot(ArchiveSnapshot incoming, UUID targetOwnerUuid,
                                              String targetMaidStableId) {
        if (incoming == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "snapshot must not be null");
        }
        String owner = requireOwner(targetOwnerUuid);
        String stableId = ArchiveIds.requireStableId(targetMaidStableId, "targetMaidStableId");
        if (!owner.equals(incoming.ownerUuid()) || !stableId.equals(incoming.maidStableId())) {
            throw new ArchiveException(ArchiveException.Code.OWNERSHIP,
                    "snapshot owner/maid does not match explicit import target");
        }

        ArchiveSnapshot target = loadSnapshot(targetOwnerUuid, stableId);
        if (target != null && !target.archiveId().equals(incoming.archiveId())) {
            throw new ArchiveException(ArchiveException.Code.CONFLICT,
                    "target archive is already bound to a different archiveId");
        }

        Map<String, ArchiveRecord> existingById = new HashMap<>();
        long maxSequence = -1L;
        if (target != null) {
            for (ArchiveRecord record : target.records()) {
                existingById.put(record.messageId(), record);
                maxSequence = Math.max(maxSequence, record.sequence());
            }
        }

        // 快照构造已保证 sequence 唯一；按源 sequence 升序追加，不看源列表书写顺序。
        List<ArchiveRecord> incomingRecords = new ArrayList<>(incoming.records());
        incomingRecords.sort(Comparator.comparingLong(ArchiveRecord::sequence));

        long nextSequence = maxSequence;
        List<ArchiveRecord> appended = new ArrayList<>();
        int skipped = 0;
        for (ArchiveRecord incomingRecord : incomingRecords) {
            ArchiveRecord existing = existingById.get(incomingRecord.messageId());
            if (existing != null) {
                if (sameContent(existing, incomingRecord)) {
                    skipped++;
                    continue;
                }
                throw new ArchiveException(ArchiveException.Code.CONFLICT,
                        "messageId already exists with different content: " + incomingRecord.messageId());
            }
            nextSequence = incrementSequence(nextSequence);
            ArchiveRecord stored = incomingRecord.withSequence(nextSequence);
            appended.add(stored);
            existingById.put(stored.messageId(), stored);
        }

        ArchiveSnapshot result;
        if (target == null) {
            result = new ArchiveSnapshot(ArchiveSnapshot.CURRENT_SCHEMA_VERSION, incoming.archiveId(),
                    owner, stableId, incoming.coverage(), incoming.originWorldId(),
                    incoming.createdAtEpochMillis(), appended);
            writeSnapshot(archiveFile(targetOwnerUuid, stableId), result);
        } else if (!appended.isEmpty()) {
            List<ArchiveRecord> merged = new ArrayList<>(target.records());
            merged.addAll(appended);
            // 与 appendOrCreate 一致：导入只允许扩大覆盖范围，不能把已有 scope 覆盖掉。
            result = new ArchiveSnapshot(target.schemaVersion(), target.archiveId(),
                    target.ownerUuid(), target.maidStableId(),
                    target.coverage().merge(incoming.coverage()), target.originWorldId(),
                    target.createdAtEpochMillis(), merged);
            writeSnapshot(archiveFile(targetOwnerUuid, stableId), result);
        } else {
            result = target;
        }
        return new ArchiveImportResult(result.archiveId(), appended.size(), skipped, result.records().size());
    }

    private ArchiveSnapshot loadSnapshot(UUID ownerUuid, String maidStableId) {
        String owner = requireOwner(ownerUuid);
        String stableId = ArchiveIds.requireStableId(maidStableId, "maidStableId");
        Path file = archiveFile(ownerUuid, stableId);
        if (Files.notExists(file)) {
            return null;
        }
        if (!Files.isRegularFile(file)) {
            throw new ArchiveException(ArchiveException.Code.IO,
                    "archive path is not a regular file: " + file);
        }
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ArchiveException(ArchiveException.Code.IO,
                    "cannot read archive: " + file, exception);
        }
        ArchiveSnapshot snapshot;
        try {
            snapshot = ArchiveSnapshot.fromJson(json);
        } catch (ArchiveException exception) {
            throw new ArchiveException(exception.code(),
                    "cannot load archive " + file + ": " + exception.getMessage(), exception);
        }
        if (!owner.equals(snapshot.ownerUuid()) || !stableId.equals(snapshot.maidStableId())) {
            throw new ArchiveException(ArchiveException.Code.OWNERSHIP,
                    "archive owner/maid does not match its storage scope: " + file);
        }
        return snapshot;
    }

    private Path archiveFile(UUID ownerUuid, String maidStableId) {
        String owner = requireOwner(ownerUuid);
        String stableId = ArchiveIds.requireStableId(maidStableId, "maidStableId");
        Path file = root.resolve(owner).resolve(stableId + ".json").normalize();
        if (!file.startsWith(root)) {
            throw new ArchiveException(ArchiveException.Code.INVALID_IDENTIFIER,
                    "archive path escapes root: " + file);
        }
        return file;
    }

    private static String requireOwner(UUID ownerUuid) {
        if (ownerUuid == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "ownerUuid must not be null");
        }
        return ArchiveIds.requireCanonicalUuid(ownerUuid.toString(), "ownerUuid");
    }

    private void writeSnapshot(Path file, ArchiveSnapshot snapshot) {
        Path temp = null;
        try {
            Files.createDirectories(file.getParent());
            temp = Files.createTempFile(file.getParent(), ".archive-", ".tmp");
            Files.writeString(temp, snapshot.toJson(), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                LOG.log(Level.WARNING,
                        "Atomic archive move unsupported; falling back to regular replace: " + file,
                        exception);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new ArchiveException(ArchiveException.Code.IO,
                    "cannot write archive: " + file, exception);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException exception) {
                    LOG.log(Level.WARNING, "cannot delete archive temp file: " + temp, exception);
                }
            }
        }
    }

    private static long nextSequence(ArchiveSnapshot snapshot) {
        long max = -1L;
        for (ArchiveRecord record : snapshot.records()) {
            max = Math.max(max, record.sequence());
        }
        return incrementSequence(max);
    }

    /** sequence 分配显式拒绝耗尽，不允许回绕为负数或覆盖已有顺序。 */
    private static long incrementSequence(long current) {
        if (current == Long.MAX_VALUE) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "archive sequence space exhausted");
        }
        return current + 1L;
    }

    private static ArchiveRecord find(ArchiveSnapshot snapshot, String messageId) {
        for (ArchiveRecord record : snapshot.records()) {
            if (record.messageId().equals(messageId)) {
                return record;
            }
        }
        return null;
    }

    /** 导入时判断「同 ID 同内容」；sequence 是目标顺序键，不参与内容比较。 */
    private static boolean sameContent(ArchiveRecord first, ArchiveRecord second) {
        return first.messageId().equals(second.messageId())
                && first.turnId().equals(second.turnId())
                && Objects.equals(first.relatedMessageId(), second.relatedMessageId())
                && first.source() == second.source()
                && first.role() == second.role()
                && first.text().equals(second.text())
                && first.recordedAtEpochMillis() == second.recordedAtEpochMillis()
                && first.originWorldId().equals(second.originWorldId())
                && first.originGameTime() == second.originGameTime()
                && first.attachmentRefs().equals(second.attachmentRefs())
                && Objects.equals(first.relatedEventId(), second.relatedEventId())
                && Objects.equals(first.event(), second.event());
    }
}
