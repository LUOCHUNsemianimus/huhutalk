package com.huhutalk.core.archive;

/**
 * 单栈转移的真实数量事实。
 *
 * <p>调用方必须在插入/扣取前保存 beforeCount，并在操作后用剩余数量 leftoverCount
 * 计算实际接收量。helper 可能修改原 ItemStack，因此不能事后从同一个栈重新取 before。
 * 只接受 {@code 0 <= leftover <= before}，越界说明调用方读取了错误的事实，直接拒绝。</p>
 */
public record ArchiveTransferFacts(int received, int remaining) {
    public ArchiveTransferFacts {
        if (received < 0 || remaining < 0) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "transfer facts must not be negative");
        }
    }

    /** 由操作前数量与实际剩余数量推导接收/未接收数量。 */
    public static ArchiveTransferFacts of(int beforeCount, int leftoverCount) {
        if (beforeCount < 0) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "beforeCount must not be negative");
        }
        if (leftoverCount < 0 || leftoverCount > beforeCount) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "leftoverCount must be between 0 and beforeCount");
        }
        return new ArchiveTransferFacts(beforeCount - leftoverCount, leftoverCount);
    }

    public boolean fullyReceived() {
        return remaining == 0;
    }

    public boolean nothingReceived() {
        return received == 0;
    }
}
