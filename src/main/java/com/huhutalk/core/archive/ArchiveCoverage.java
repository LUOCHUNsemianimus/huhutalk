package com.huhutalk.core.archive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 归档覆盖范围。当前只接通 online_phone_text，因此 partial 固定为 true；
 * 空归档与历史未覆盖部分都沿用这一标记，不能对外声称完整历史。
 */
public record ArchiveCoverage(boolean partial, List<String> scopes) {
    public static final ArchiveCoverage ONLINE_PHONE_TEXT_PARTIAL =
            new ArchiveCoverage(true, List.of("online_phone_text"));

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
