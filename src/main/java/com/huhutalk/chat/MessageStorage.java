package com.huhutalk.chat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
        try (Reader reader = Files.newBufferedReader(file)) {
            Type listType = new TypeToken<List<ChatMessage>>(){}.getType();
            List<ChatMessage> list = GSON.fromJson(reader, listType);
            if (list == null) {
                return new ArrayList<>();
            }
            // 过滤反序列化产生的 null 元素，避免下游遍历 NPE
            list.removeIf(Objects::isNull);
            return list;
        } catch (Exception e) {
            e.printStackTrace();
            // 损坏文件改名备份，避免下次继续读坏文件 / 被后续 save 覆盖永久丢失
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
            return new ArrayList<>();
        }
    }

    public static void saveMessages(UUID maidUuid, List<ChatMessage> messages) {
        if (messages == null) {
            messages = new ArrayList<>();
        }
        Path file = getFilePath(maidUuid);
        try {
            Files.createDirectories(file.getParent());
            // 原子写入：先写临时文件再重命名，避免写一半崩溃导致 JSON 损坏
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            GSON.toJson(messages, Files.newBufferedWriter(tmp));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicEx) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static Path getFilePath(UUID maidUuid) {
        // 统一到游戏目录（与 ChatHistoryStorage/settings/PhotoLibrary 一致），避免依赖进程 CWD
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("huhutalk").resolve("messages")
                .resolve(maidUuid.toString() + ".json");
    }
}
