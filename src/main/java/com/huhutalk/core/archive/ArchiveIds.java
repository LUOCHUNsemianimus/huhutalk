package com.huhutalk.core.archive;

import java.util.UUID;
import java.util.regex.Pattern;

/** 归档标识符校验：既用于 JSON 字段，也用于磁盘路径，禁止路径穿越。 */
final class ArchiveIds {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern STABLE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    private ArchiveIds() {
    }

    static String requireToken(String value, String field) {
        if (value == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, field + " must not be null");
        }
        if (!TOKEN.matcher(value).matches()) {
            throw new ArchiveException(ArchiveException.Code.INVALID_IDENTIFIER,
                    field + " is not a safe identifier: " + value);
        }
        return value;
    }

    static String requireStableId(String value, String field) {
        if (value == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, field + " must not be null");
        }
        if (!STABLE_ID.matcher(value).matches()) {
            throw new ArchiveException(ArchiveException.Code.INVALID_IDENTIFIER,
                    field + " is not a safe stable id: " + value);
        }
        return value;
    }

    static String requireCanonicalUuid(String value, String field) {
        if (value == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, field + " must not be null");
        }
        try {
            UUID uuid = UUID.fromString(value);
            String canonical = uuid.toString();
            if (!canonical.equals(value)) {
                throw new ArchiveException(ArchiveException.Code.INVALID_IDENTIFIER,
                        field + " must use canonical UUID form: " + value);
            }
            return canonical;
        } catch (IllegalArgumentException exception) {
            throw new ArchiveException(ArchiveException.Code.INVALID_IDENTIFIER,
                    field + " is not a UUID: " + value, exception);
        }
    }
}
