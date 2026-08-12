package com.huhutalk.chat;

import java.time.Instant;
import java.util.UUID;

public class ChatMessage {
    public enum SenderType { PLAYER, MAID }

    private final UUID senderId;       // 玩家或女仆的 UUID
    private final String senderName;   // 显示名（如玩家名、女仆名）
    private final String content;
    private final long timestamp;
    private final SenderType type;

    public ChatMessage(UUID senderId, String senderName, String content, SenderType type) {
        this.senderId = senderId;
        this.senderName = senderName;
        this.content = content;
        this.timestamp = Instant.now().getEpochSecond();
        this.type = type;
    }

    // Getters
    public UUID getSenderId() { return senderId; }
    public String getSenderName() { return senderName; }
    public String getContent() { return content; }
    public long getTimestamp() { return timestamp; }
    public SenderType getType() { return type; }
}