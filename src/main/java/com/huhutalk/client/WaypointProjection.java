package com.huhutalk.client;

import com.huhutalk.core.waypoint.WaypointEntry;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 把 JourneyMap 的「原生 raw 坐标」投影成目标维度下的整数坐标。
 *
 * <h2>为什么需要单独一层</h2>
 * JourneyMap 5.10 的 {@code Waypoint} 使用一个维度集合（{@code TreeSet<String>}），
 * 没有单独的 primaryDimension 字段；{@code getX()/getZ()} 又会按<b>当前玩家所在维度</b>
 * 做下界换算，跨维度读取时会差 8 倍。因此同步路径必须只读
 * {@code getRawCenterX()/getRawCenterZ()}，再由这里按确定的主维度换算。
 *
 * <h2>主维度选择</h2>
 * 与 JourneyMap 原生 copy/{@code toModWaypoint} 的 {@code dimensions.first()} 语义一致：
 * 取维度集合<b>字典序</b>首个有效 {@link ResourceLocation}。这样集合的迭代顺序不影响结果，
 * 也不依赖玩家当前世界，玩家在哪个维度都不会把同一路径点算错。
 *
 * <h2>坐标换算</h2>
 * {@code getRawCenterX() = 内部 raw 整数 + 0.5}，所以先 {@link Math#floor(double)} 还原整数。
 * 主维度为 {@code minecraft:the_nether} 时，JourneyMap 在 {@code setLocation} 阶段已把
 * 下界坐标乘以 8 存入 raw，这里用 {@link Math#floorDiv(int, int)} 除回 8（负坐标同样向负无穷取整）；
 * 其他维度保持 raw，y 不变。
 *
 * <p>本类刻意不依赖 {@code Minecraft.getInstance()} 或任何 JourneyMap 类型，
 * 便于用纯 JDK 行为检查覆盖。
 */
public final class WaypointProjection {

    private static final String NETHER = "minecraft:the_nether";

    private WaypointProjection() {
    }

    /**
     * @param name        路径点名称，原样保留
     * @param dimensions  JourneyMap 的维度集合；可乱序，内部会按字典序挑首个有效项
     * @param rawCenterX  {@code Waypoint.getRawCenterX()} 的值（内部 raw 整数 + 0.5）
     * @param y           原始 y，原样保留
     * @param rawCenterZ  {@code Waypoint.getRawCenterZ()} 的值（内部 raw 整数 + 0.5）
     * @return 主维度 + 换算后整数坐标；没有有效维度时返回 {@link Optional#empty()}
     */
    public static Optional<WaypointEntry> fromRawCenters(
            String name,
            Collection<String> dimensions,
            double rawCenterX,
            int y,
            double rawCenterZ) {
        if (name == null || dimensions == null || dimensions.isEmpty()) {
            return Optional.empty();
        }

        // 与原生 TreeSet 的字典序 first() 对齐，同时消除调用方集合迭代顺序的影响。
        List<String> ordered = new ArrayList<>(dimensions.size());
        for (String dimension : dimensions) {
            if (dimension != null) {
                ordered.add(dimension);
            }
        }
        ordered.sort(String::compareTo);

        ResourceLocation primary = null;
        for (String dimension : ordered) {
            ResourceLocation parsed = ResourceLocation.tryParse(dimension);
            // tryParse("minecraft:") 会返回 path 为空的 ResourceLocation；空 path 不是有效维度。
            if (parsed != null && !parsed.getPath().isEmpty()) {
                primary = parsed;
                break;
            }
        }
        if (primary == null) {
            return Optional.empty();
        }

        int x = (int) Math.floor(rawCenterX);
        int z = (int) Math.floor(rawCenterZ);
        if (NETHER.equals(primary.toString())) {
            x = Math.floorDiv(x, 8);
            z = Math.floorDiv(z, 8);
        }
        return Optional.of(new WaypointEntry(name, primary.toString(), x, y, z));
    }
}
