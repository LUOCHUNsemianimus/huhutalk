package com.huhutalk.core.archive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 归档覆盖范围。文字、图片与物品入口各自声明 scope，合并时稳定去重；
 * 深历史/旧游戏内事件仍可能未采集，因此 partial 保持 true。
 */
public record ArchiveCoverage(boolean partial, List<String> scopes) {
    public static final ArchiveCoverage ONLINE_PHONE_TEXT_PARTIAL =
            new ArchiveCoverage(true, List.of("online_phone_text"));

    /** 物品事件入口声明：与文字/图片既有 scope 合并，partial 保持 true。 */
    public static final ArchiveCoverage ONLINE_PHONE_ITEMS_PARTIAL =
            new ArchiveCoverage(true,
                    List.of("online_phone_text", "online_phone_image", "online_phone_items"));

    public ArchiveCoverage {
        if (scopes == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "coverage.scopes must not be null");
        }
        List<String> copy = new ArrayList<>(scopes.size());
        for (String scope : scopes) {
            copy.add(ArchiveIds.requireToken(scope, "coverage.scope"));
        }
        scopes = List.copyOf(copy);
    }

    /**
     * 合并另一份覆盖声明：scopes 按出现顺序稳定去重，partial 取逻辑或。
     * 同一会话先文字后图片（或反向）时不能丢弃任何一次入口已经声明的覆盖范围。
     */
    public ArchiveCoverage merge(ArchiveCoverage other) {
        if (other == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "coverage to merge must not be null");
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>(scopes);
        merged.addAll(other.scopes);
        return new ArchiveCoverage(partial || other.partial, List.copyOf(merged));
    }

    public static ArchiveCoverage fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT, "coverage must be a JSON object");
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement partialElement = object.get("partial");
        if (partialElement == null || !partialElement.isJsonPrimitive()
                || !partialElement.getAsJsonPrimitive().isBoolean()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT, "coverage.partial must be a boolean");
        }
        JsonElement scopesElement = object.get("scopes");
        if (scopesElement == null || !scopesElement.isJsonArray()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT, "coverage.scopes must be an array");
        }
        List<String> scopes = new ArrayList<>();
        for (JsonElement scope : scopesElement.getAsJsonArray()) {
            if (!scope.isJsonPrimitive() || !scope.getAsJsonPrimitive().isString()) {
                throw new ArchiveException(ArchiveException.Code.CORRUPT,
                        "coverage.scopes entries must be strings");
            }
            scopes.add(scope.getAsString());
        }
        return new ArchiveCoverage(partialElement.getAsBoolean(), scopes);
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("partial", partial);
        JsonArray scopesArray = new JsonArray();
        for (String scope : scopes) {
            scopesArray.add(scope);
        }
        object.add("scopes", scopesArray);
        return object;
    }
}
