package com.huhutalk.chat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 负责本地消息的 JSON 文件读写。
 * 存储路径：gameDir/huhutalk/messages/<maidUuid>.json
 */
public class MessageStorage {
    private static final Gson GSON = new Gson();

    public static List<ChatMessage> loadMessages(UUID maidUuid) {
        Path file = getFilePath(maidUuid);
        if (!Files.exists(file)) return new ArrayList<>();
        try (Reader reader = new FileReader(file.toFile())) {
            Type listType = new TypeToken<List<ChatMessage>>(){}.getType();
            return GSON.fromJson(reader, listType);
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public static void saveMessages(UUID maidUuid, List<ChatMessage> messages) {
        Path file = getFilePath(maidUuid);
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = new FileWriter(file.toFile())) {
                GSON.toJson(messages, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static Path getFilePath(UUID maidUuid) {
        return Path.of("huhutalk", "messages", maidUuid.toString() + ".json");
    }
}