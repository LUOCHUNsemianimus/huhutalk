package com.huhutalk.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class NotificationManager {
    private static final Map<UUID, Integer> UNREAD = new HashMap<>();

    public static void addUnread(UUID maidUuid) {
        UNREAD.merge(maidUuid, 1, Integer::sum);
    }

    public static int getUnread(UUID maidUuid) {
        return UNREAD.getOrDefault(maidUuid, 0);
    }

    public static void clear(UUID maidUuid) {
        UNREAD.put(maidUuid, 0);
    }
}