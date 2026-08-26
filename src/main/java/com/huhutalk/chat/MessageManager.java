package com.huhutalk.chat;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 管理所有聊天消息的内存映像，并提供持久化接口。
 */
public class MessageManager {
    // key: 女仆 UUID
    private static final Map<UUID, List<ChatMessage>> conversations = new HashMap<>();

    /** 获取与某女仆的消息列表（按时间排序） */
    public static List<ChatMessage> getMessages(UUID maidUuid) {
        return conversations.computeIfAbsent(maidUuid, uuid -> {
            List<ChatMessage> loaded = MessageStorage.loadMessages(uuid);
            return new CopyOnWriteArrayList<>(loaded);
        });
    }

    /** 添加一条消息并自动存储 */
    public static void addMessage(UUID maidUuid, ChatMessage message) {
        if (maidUuid == null || message == null) {
            return;
        }
        List<ChatMessage> list = getMessages(maidUuid);
        list.add(message);
        MessageStorage.saveMessages(maidUuid, list);
    }

    /** 清除某女仆的聊天记录（内存+文件） */
    public static void clearConversation(UUID maidUuid) {
        conversations.remove(maidUuid);
        MessageStorage.saveMessages(maidUuid, new ArrayList<>());
    }
}