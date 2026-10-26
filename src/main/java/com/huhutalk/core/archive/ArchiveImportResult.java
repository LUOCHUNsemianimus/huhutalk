package com.huhutalk.core.archive;

/** 幂等导入结果；被同 ID 同内容跳过的记录不再次写入。 */
public record ArchiveImportResult(
        String archiveId,
        int importedCount,
        int skippedCount,
        int totalRecordCount
) {
}
