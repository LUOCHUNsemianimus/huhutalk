package com.huhutalk.client.screen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.huhutalk.HuhuTalk;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ChatHistoryStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<MaidChatScreen.ChatMessage>>() {}.getType();

    public static List<MaidChatScreen.ChatMessage> load(UUID maidUuid) {
        Path file = getFile(maidUuid);
        if (Files.exists(file)) {
            try {
                String json = Files.readString(file);
                List<MaidChatScreen.ChatMessage> list = GSON.fromJson(json, LIST_TYPE);
                return list != null ? list : new ArrayList<>();
            } catch (IOException e) {
                HuhuTalk.LOGGER.error("Failed to load chat history for {}", maidUuid, e);
            }
        }
        return new ArrayList<>();
    }

    public static void save(UUID maidUuid, List<MaidChatScreen.ChatMessage> messages) {
        Path file = getFile(maidUuid);
        try {
            Files.createDirectories(file.getParent());
            String json = GSON.toJson(messages);
            Files.writeString(file, json);
        } catch (IOException e) {
            HuhuTalk.LOGGER.error("Failed to save chat history for {}", maidUuid, e);
        }
    }

    /**
     * ✅ 修复①：追加单条消息到历史文件（用于界面关闭时收到回复）
     */
    public static void appendMessage(UUID maidUuid, MaidChatScreen.ChatMessage message) {
        List<MaidChatScreen.ChatMessage> messages = load(maidUuid);
        messages.add(message);
        save(maidUuid, messages);
    }

    private static Path getFile(UUID maidUuid) {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("huhutalk")
                .resolve("chat_history")
                .resolve(maidUuid.toString() + ".json");
    }
}