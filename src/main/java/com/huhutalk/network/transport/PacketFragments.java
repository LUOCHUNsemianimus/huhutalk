package com.huhutalk.network.transport;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 纯 Java 的分片/重组算法，不依赖 Minecraft、Forge、Netty 或文件系统。
 *
 * <p>发送端先把完整的业务负载编码成 byte[]，再按 {@link #MAX_FRAGMENT_BYTES}
 * 切片；接收端按 (发送方, messageId) 重组。每个分组在收到第一片时就按总长度预留预算，
 * 完成、冲突、过期或清理时释放。以下限制都是硬上限：</p>
 * <ul>
 *   <li>完整负载 ≤ 2 MiB，单片 ≤ 24000 字节；</li>
 *   <li>每个发送方最多 2 个进行中的分组、最多 4 MiB 预留；</li>
 *   <li>所有发送方合计最多 32 MiB 预留；</li>
 *   <li>30 秒未收到新片即过期。</li>
 * </ul>
 *
 * <p>分片必须与总长度严格对应：partCount 必须是总长度按 24000 字节切片后的数量，
 * 每一片的长度必须是该索引对应的精确长度。因此重组完成即代表无缺口、无多余字节。</p>
 */
public final class PacketFragments {
    public static final int MAX_FRAGMENT_BYTES = 24000;
    public static final int MAX_TOTAL_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ACTIVE_PER_SENDER = 2;
    public static final long MAX_BYTES_PER_SENDER = 4L * 1024 * 1024;
    public static final long MAX_GLOBAL_BYTES = 32L * 1024 * 1024;
    public static final long TIMEOUT_MILLIS = 30_000L;

    private PacketFragments() {
    }

    public enum Status {
        /** 已接收，但分组尚未完整。 */
        ACCEPTED,
        /** 同一索引、同一字节的重复片，未增加缓冲。 */
        DUPLICATE,
        /** 分组完整，payload 为完整负载。 */
        COMPLETE,
        /** 元数据、预算、冲突或分片长度不合法；分组已释放。 */
        REJECTED
    }

    public record Fragment(String payloadId, UUID messageId, int totalLength,
                           int partIndex, int partCount, byte[] data) {
        public Fragment {
            Objects.requireNonNull(payloadId, "payloadId");
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(data, "data");
        }
    }

    public record Result(Status status, byte[] payload, String reason) {
        public Result {
            Objects.requireNonNull(status, "status");
        }

        public boolean complete() {
            return status == Status.COMPLETE;
        }

        static Result accepted() {
            return new Result(Status.ACCEPTED, null, null);
        }

        static Result duplicate() {
            return new Result(Status.DUPLICATE, null, null);
        }

        static Result complete(byte[] payload) {
            return new Result(Status.COMPLETE, payload, null);
        }

        static Result rejected(String reason) {
            return new Result(Status.REJECTED, null, reason);
        }
    }

    /**
     * 将完整负载切成按顺序排列的分片。返回的列表是新的，数据字节已复制到各分片。
     */
    public static List<Fragment> split(String payloadId, UUID messageId, byte[] payload) {
        Objects.requireNonNull(payloadId, "payloadId");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(payload, "payload");
        if (payload.length > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("payload exceeds " + MAX_TOTAL_BYTES + " bytes: " + payload.length);
        }
        int partCount = expectedPartCount(payload.length);
        List<Fragment> fragments = new ArrayList<>(partCount);
        for (int index = 0; index < partCount; index++) {
            int offset = index * MAX_FRAGMENT_BYTES;
            int length = expectedPartLength(payload.length, index);
            byte[] part = new byte[length];
            System.arraycopy(payload, offset, part, 0, length);
            fragments.add(new Fragment(payloadId, messageId, payload.length, index, partCount, part));
        }
        return fragments;
    }

    public static int expectedPartCount(int totalLength) {
        if (totalLength < 0) {
            throw new IllegalArgumentException("totalLength must be >= 0");
        }
        if (totalLength == 0) {
            return 1;
        }
        return (totalLength + MAX_FRAGMENT_BYTES - 1) / MAX_FRAGMENT_BYTES;
    }

    public static int expectedPartLength(int totalLength, int partIndex) {
        if (totalLength < 0 || totalLength > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("totalLength out of bounds: " + totalLength);
        }
        int partCount = expectedPartCount(totalLength);
        if (partIndex < 0 || partIndex >= partCount) {
            throw new IllegalArgumentException("partIndex out of bounds: " + partIndex + "/" + partCount);
        }
        int offset = partIndex * MAX_FRAGMENT_BYTES;
        return Math.min(MAX_FRAGMENT_BYTES, totalLength - offset);
    }

    /**
     * 单个接收端（一个物理连接/一端）的重组器。服务端用玩家 UUID 作为 senderKey，
     * 客户端用固定的连接键；不同 senderKey 的分组完全隔离。
     */
    public static final class Reassembler {
        private final Map<Key, Assembly> assemblies = new HashMap<>();
        private final Map<Object, Budget> budgets = new HashMap<>();
        private long globalBytes;

        public synchronized Result accept(Object senderKey, Fragment fragment, long nowMillis) {
            Objects.requireNonNull(senderKey, "senderKey");
            Objects.requireNonNull(fragment, "fragment");
            Key key = new Key(senderKey, fragment.messageId());
            String invalid = validate(fragment);
            if (invalid != null) {
                Assembly existing = assemblies.get(key);
                if (existing != null) {
                    release(key, existing);
                }
                return Result.rejected(invalid);
            }

            Assembly existing = assemblies.get(key);
            if (existing != null) {
                return acceptIntoExisting(key, existing, fragment, nowMillis);
            }

            Budget existingBudget = budgets.get(senderKey);
            Budget budget = existingBudget == null ? new Budget() : existingBudget;
            if (budget.active >= MAX_ACTIVE_PER_SENDER) {
                return Result.rejected("too many active groups for sender");
            }
            long total = fragment.totalLength();
            if (budget.bytes + total > MAX_BYTES_PER_SENDER) {
                return Result.rejected("sender byte budget exceeded");
            }
            if (globalBytes + total > MAX_GLOBAL_BYTES) {
                return Result.rejected("global byte budget exceeded");
            }

            Assembly assembly = new Assembly(fragment.payloadId(), fragment.totalLength(),
                    fragment.partCount(), nowMillis);
            assemblies.put(key, assembly);
            budget.active++;
            budget.bytes += total;
            globalBytes += total;
            if (existingBudget == null) {
                budgets.put(senderKey, budget);
            }

            copyPart(assembly, fragment);
            if (assembly.receivedCount == assembly.partCount) {
                release(key, assembly);
                return Result.complete(assembly.data);
            }
            return Result.accepted();
        }

        private Result acceptIntoExisting(Key key, Assembly assembly, Fragment fragment, long nowMillis) {
            if (!assembly.payloadId.equals(fragment.payloadId())
                    || assembly.totalLength != fragment.totalLength()
                    || assembly.partCount != fragment.partCount()) {
                release(key, assembly);
                return Result.rejected("fragment metadata conflicts with active group");
            }

            int offset = fragment.partIndex() * MAX_FRAGMENT_BYTES;
            if (assembly.received.get(fragment.partIndex())) {
                if (regionEquals(assembly.data, offset, fragment.data())) {
                    return Result.duplicate();
                }
                release(key, assembly);
                return Result.rejected("conflicting duplicate fragment");
            }

            copyPart(assembly, fragment);
            assembly.lastTouchedMillis = nowMillis;
            if (assembly.receivedCount == assembly.partCount) {
                release(key, assembly);
                return Result.complete(assembly.data);
            }
            return Result.accepted();
        }

        private static void copyPart(Assembly assembly, Fragment fragment) {
            int offset = fragment.partIndex() * MAX_FRAGMENT_BYTES;
            System.arraycopy(fragment.data(), 0, assembly.data, offset, fragment.data().length);
            assembly.received.set(fragment.partIndex());
            assembly.receivedCount++;
        }

        private static String validate(Fragment fragment) {
            if (fragment.payloadId().isEmpty()) {
                return "empty payload id";
            }
            if (fragment.totalLength() < 0 || fragment.totalLength() > MAX_TOTAL_BYTES) {
                return "total length out of bounds: " + fragment.totalLength();
            }
            int expectedCount = expectedPartCount(fragment.totalLength());
            if (fragment.partCount() != expectedCount) {
                return "part count does not match total length: " + fragment.partCount()
                        + " != " + expectedCount;
            }
            if (fragment.partIndex() < 0 || fragment.partIndex() >= expectedCount) {
                return "part index out of bounds: " + fragment.partIndex();
            }
            int expectedLength = expectedPartLength(fragment.totalLength(), fragment.partIndex());
            if (fragment.data().length != expectedLength) {
                return "part length does not match index: " + fragment.data().length
                        + " != " + expectedLength;
            }
            return null;
        }

        private static boolean regionEquals(byte[] target, int offset, byte[] part) {
            for (int i = 0; i < part.length; i++) {
                if (target[offset + i] != part[i]) {
                    return false;
                }
            }
            return true;
        }

        private void release(Key key, Assembly assembly) {
            if (assemblies.remove(key) == null) {
                return;
            }
            Budget budget = budgets.get(key.sender);
            if (budget != null) {
                budget.active--;
                budget.bytes -= assembly.totalLength;
                if (budget.active <= 0 && budget.bytes <= 0) {
                    budgets.remove(key.sender);
                }
            }
            globalBytes -= assembly.totalLength;
            if (globalBytes < 0) {
                globalBytes = 0;
            }
        }

        /** 清理超过 TTL 的分组，返回清理数量。 */
        public synchronized int expire(long nowMillis) {
            int before = assemblies.size();
            Iterator<Map.Entry<Key, Assembly>> iterator = assemblies.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Key, Assembly> entry = iterator.next();
                Assembly assembly = entry.getValue();
                if (nowMillis - assembly.lastTouchedMillis >= TIMEOUT_MILLIS) {
                    iterator.remove();
                    releaseAfterIteratorRemove(entry.getKey(), assembly);
                }
            }
            return before - assemblies.size();
        }

        private void releaseAfterIteratorRemove(Key key, Assembly assembly) {
            Budget budget = budgets.get(key.sender);
            if (budget != null) {
                budget.active--;
                budget.bytes -= assembly.totalLength;
                if (budget.active <= 0 && budget.bytes <= 0) {
                    budgets.remove(key.sender);
                }
            }
            globalBytes -= assembly.totalLength;
            if (globalBytes < 0) {
                globalBytes = 0;
            }
        }

        public synchronized int clearSender(Object senderKey) {
            int removed = 0;
            Iterator<Map.Entry<Key, Assembly>> iterator = assemblies.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Key, Assembly> entry = iterator.next();
                if (entry.getKey().sender.equals(senderKey)) {
                    iterator.remove();
                    releaseAfterIteratorRemove(entry.getKey(), entry.getValue());
                    removed++;
                }
            }
            budgets.remove(senderKey);
            return removed;
        }

        public synchronized void clearAll() {
            assemblies.clear();
            budgets.clear();
            globalBytes = 0L;
        }

        public synchronized int activeAssemblies() {
            return assemblies.size();
        }

        public synchronized long bufferedBytes() {
            return globalBytes;
        }

        public synchronized int activeForSender(Object senderKey) {
            Budget budget = budgets.get(senderKey);
            return budget == null ? 0 : budget.active;
        }

        public synchronized long bytesForSender(Object senderKey) {
            Budget budget = budgets.get(senderKey);
            return budget == null ? 0L : budget.bytes;
        }

        /** 当前存在预算条目的发送方数量；用于确认拒绝路径不会留下空条目。 */
        public synchronized int senderBudgetCount() {
            return budgets.size();
        }

        private static final class Budget {
            private int active;
            private long bytes;
        }

        private static final class Assembly {
            private final String payloadId;
            private final int totalLength;
            private final int partCount;
            private final byte[] data;
            private final BitSet received = new BitSet();
            private int receivedCount;
            private long lastTouchedMillis;

            private Assembly(String payloadId, int totalLength, int partCount, long nowMillis) {
                this.payloadId = payloadId;
                this.totalLength = totalLength;
                this.partCount = partCount;
                this.data = new byte[totalLength];
                this.lastTouchedMillis = nowMillis;
            }
        }

        private record Key(Object sender, UUID messageId) {
        }
    }
}
