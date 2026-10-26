package com.huhutalk.core.archive;

import java.util.List;

/**
 * 按 sequence 稳定排序的一页归档记录。
 *
 * @param archiveExists 目标归档文件是否存在；与「存在但无命中」区分。
 * @param coverage      该归档的实际覆盖范围；缺档时为 partial 且 scopes 为空。
 */
public record ArchivePage(
        String archiveId,
        List<ArchiveRecord> records,
        int offset,
        int limit,
        int totalMatching,
        boolean hasMore,
        boolean archiveExists,
        ArchiveCoverage coverage
) {
    public ArchivePage {
        if (offset < 0 || limit <= 0) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "offset must be >= 0 and limit must be > 0");
        }
        records = records == null ? List.of() : List.copyOf(records);
        if (coverage == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "coverage must not be null");
        }
    }
}
