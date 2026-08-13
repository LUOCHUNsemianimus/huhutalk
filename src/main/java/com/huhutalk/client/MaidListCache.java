package com.huhutalk.client;

import com.huhutalk.network.S2CMaidListPacket;

import java.util.ArrayList;
import java.util.List;

public class MaidListCache {
    private static List<S2CMaidListPacket.MaidDisplayInfo> list = new ArrayList<>();

    public static void setList(List<S2CMaidListPacket.MaidDisplayInfo> newList) {
        list = newList != null ? newList : new ArrayList<>();
    }

    public static List<S2CMaidListPacket.MaidDisplayInfo> getList() {
        return list;
    }

    /** 清空列表（进入新世界时调用） */
    public static void clear() {
        list = new ArrayList<>();
    }
}