package com.huhutalk.client;

import com.huhutalk.core.waypoint.WaypointEntry;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 纯 JDK 行为检查：不 mock Minecraft / JourneyMap，不读取源码字符串。
 * <p>
 * 运行方式（由上游用真实映射 classpath 执行）：
 * {@code java -cp <main+test classes+deps> com.huhutalk.client.WaypointProjectionBehaviorCheck}
 */
public final class WaypointProjectionBehaviorCheck {

    private static int checks;

    private WaypointProjectionBehaviorCheck() {
    }

    public static void main(String[] args) {
        overworldKeepsRawCoordinates();
        netherDividesRawCoordinates();
        netherUsesFloorDivForNegatives();
        dimensionOrderDoesNotMatter();
        emptyOrInvalidDimensionsAreSkipped();
        yAndNameArePreserved();
        sameRawDiffersByPrimaryDimension();
        System.out.println("WaypointProjectionBehaviorCheck: OK (" + checks + " checks)");
    }

    private static void overworldKeepsRawCoordinates() {
        WaypointEntry entry = project("base",
                set("minecraft:overworld"), 1024.5D, 70, -8.5D);
        expect("overworld raw X", 1024, entry.x());
        expect("overworld raw Z", -9, entry.z());
        expect("overworld dimension", "minecraft:overworld", entry.dimension());
    }

    private static void netherDividesRawCoordinates() {
        WaypointEntry entry = project("portal",
                set("minecraft:the_nether"), 1024.5D, 64, 512.5D);
        expect("nether X / 8", 128, entry.x());
        expect("nether Z / 8", 64, entry.z());
        expect("nether dimension", "minecraft:the_nether", entry.dimension());
    }

    private static void netherUsesFloorDivForNegatives() {
        // rawCenter -1.5 -> floor -2 -> floorDiv(-2, 8) = -1；若用截断除法会得到 0。
        WaypointEntry small = project("negative-small",
                set("minecraft:the_nether"), -1.5D, 40, -1.5D);
        expect("negative small X", -1, small.x());
        expect("negative small Z", -1, small.z());

        // rawCenter -8.5 -> floor -9 -> floorDiv(-9, 8) = -2。
        WaypointEntry edge = project("negative-edge",
                set("minecraft:the_nether"), -8.5D, 40, -8.5D);
        expect("negative edge X", -2, edge.x());
        expect("negative edge Z", -2, edge.z());

        // rawCenter -64.5 -> floor -65 -> floorDiv(-65, 8) = -9。
        WaypointEntry far = project("negative-far",
                set("minecraft:the_nether"), -64.5D, 40, -64.5D);
        expect("negative far X", -9, far.x());
        expect("negative far Z", -9, far.z());
    }

    private static void dimensionOrderDoesNotMatter() {
        Collection<String> netherFirst = set("minecraft:the_nether", "minecraft:overworld");
        Collection<String> overworldFirst = set("minecraft:overworld", "minecraft:the_nether");

        WaypointEntry a = project("order", netherFirst, 80.5D, 50, 80.5D);
        WaypointEntry b = project("order", overworldFirst, 80.5D, 50, 80.5D);
        // 字典序 minecraft:overworld 在 minecraft:the_nether 之前，因此两个集合都选主世界、保持 raw。
        expect("order A X", 80, a.x());
        expect("order B X", 80, b.x());
        expect("order A dimension", "minecraft:overworld", a.dimension());
        expect("order B dimension", "minecraft:overworld", b.dimension());
        expect("order A == order B", a, b);
    }

    private static void emptyOrInvalidDimensionsAreSkipped() {
        expect("null dimensions", Optional.empty(),
                WaypointProjection.fromRawCenters("x", null, 1.5D, 1, 1.5D));
        expect("empty dimensions", Optional.empty(),
                WaypointProjection.fromRawCenters("x", Collections.emptyList(), 1.5D, 1, 1.5D));

        // 首个字符串不是合法 ResourceLocation，必须跳过并选择后面的有效维度。
        Set<String> mixed = new LinkedHashSet<>(Arrays.asList(
                "not a valid dimension", "minecraft:the_end"));
        WaypointEntry entry = project("valid-after-invalid", mixed, 16.5D, 90, 16.5D);
        expect("invalid skipped dimension", "minecraft:the_end", entry.dimension());
        expect("invalid skipped X", 16, entry.x());

        expect("all invalid", Optional.empty(),
                WaypointProjection.fromRawCenters("x",
                        set("bad dimension", "minecraft:"), 1.5D, 1, 1.5D));
    }

    private static void yAndNameArePreserved() {
        WaypointEntry entry = project("我的传送门", set("minecraft:the_nether"), 40.5D, -17, -40.5D);
        expect("name preserved", "我的传送门", entry.name());
        expect("y preserved", -17, entry.y());
        expect("x computed", 5, entry.x());
        expect("z computed", -6, entry.z());
    }

    private static void sameRawDiffersByPrimaryDimension() {
        double rawX = 160.5D;
        double rawZ = -160.5D;
        WaypointEntry overworld = project("same", set("minecraft:overworld"), rawX, 60, rawZ);
        WaypointEntry nether = project("same", set("minecraft:the_nether"), rawX, 60, rawZ);

        // 同一 raw 在两个维度集合下结果不同，且不能是 8 倍误差。
        expect("overworld same X", 160, overworld.x());
        expect("nether same X", 20, nether.x());
        expect("overworld same Z", -161, overworld.z());
        expect("nether same Z", -21, nether.z());
        expect("overworld not nether", false, overworld.x() == nether.x());
    }

    private static WaypointEntry project(String name, Collection<String> dimensions,
                                         double rawCenterX, int y, double rawCenterZ) {
        Optional<WaypointEntry> result =
                WaypointProjection.fromRawCenters(name, dimensions, rawCenterX, y, rawCenterZ);
        if (result.isEmpty()) {
            throw new AssertionError("expected non-empty projection for " + name);
        }
        return result.get();
    }

    private static Set<String> set(String... dimensions) {
        return new LinkedHashSet<>(List.of(dimensions));
    }

    private static void expect(String label, Object expected, Object actual) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
