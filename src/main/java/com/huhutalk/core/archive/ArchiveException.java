package com.huhutalk.core.archive;

/**
 * 服务端会话归档的显式失败。调用方只应捕获该类来处理归档错误，
 * 不应把聊天或 LLM 自身的异常一起吞掉。
 */
public final class ArchiveException extends RuntimeException {
    public enum Code {
        IO,
        CORRUPT,
        UNSUPPORTED_SCHEMA,
        INVALID_IDENTIFIER,
        VALIDATION,
        CONFLICT,
        OWNERSHIP,
        NOT_FOUND
    }

    private final Code code;

    public ArchiveException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public ArchiveException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
