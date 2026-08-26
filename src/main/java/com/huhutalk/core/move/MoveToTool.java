package com.huhutalk.core.move;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.huhutalk.core.waypoint.WaypointCache;
import com.huhutalk.core.waypoint.WaypointEntry;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * 让女仆移动的 LLM 工具。玩家通过聊天让女仆：
 * <ul>
 *   <li>{@code target_type=player}：到自己身边（近走远传）。</li>
 *   <li>{@code target_type=coordinate}：到指定坐标。</li>
 *   <li>{@code target_type=waypoint}：到 JourneyMap 命名路径点（需客户端已同步路径点）。</li>
 * </ul>
 * {@code arrival} 决定到达后的行为（默认：player→跟随；其余→集合点等待）。
 */
public class MoveToTool implements ITool<MoveToTool.Result> {

    public static final String TOOL_ID = "move_to";

    private static final String TARGET_TYPE = "target_type";
    private static final String X = "x";
    private static final String Y = "y";
    private static final String Z = "z";
    private static final String WAYPOINT = "waypoint_name";
    private static final String ARRIVAL = "arrival";
    private static final String TASK = "task";

    private static final String TOOL_DESC = """
            Use this when the user asks the maid to go somewhere, come to the user, or go to a JourneyMap waypoint.
            target_type: 'player' = come to the user; 'coordinate' = go to a specific x,y,z; 'waypoint' = go to a JourneyMap waypoint by name.
            arrival (optional): 'meet' = go there and wait, then follow the user when they arrive; 'home' = go there and stay; 'protect' = defend there.
            task (optional): if the user wants the maid to DO a job there (e.g. "go to the farm and harvest"), set task to the matching work mode id.
            """.trim();

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf(TARGET_TYPE).forGetter(Result::targetType),
            Codec.INT.optionalFieldOf(X, 0).forGetter(Result::x),
            Codec.INT.optionalFieldOf(Y, 0).forGetter(Result::y),
            Codec.INT.optionalFieldOf(Z, 0).forGetter(Result::z),
            Codec.STRING.optionalFieldOf(WAYPOINT, "").forGetter(Result::waypointName),
            Codec.STRING.optionalFieldOf(ARRIVAL, "").forGetter(Result::arrival),
            Codec.STRING.optionalFieldOf(TASK, "").forGetter(Result::task)
    ).apply(instance, Result::new));

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return TOOL_DESC;
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        StringParameter targetType = StringParameter.create()
                .setDescription("Where to send the maid: player / coordinate / waypoint");
        targetType.addEnumValues("player", "coordinate", "waypoint");

        IntegerParameter x = IntegerParameter.create().setDescription("X coordinate (for target_type=coordinate)");
        IntegerParameter y = IntegerParameter.create().setDescription("Y coordinate (for target_type=coordinate)");
        IntegerParameter z = IntegerParameter.create().setDescription("Z coordinate (for target_type=coordinate)");
        StringParameter waypoint = StringParameter.create()
                .setDescription("JourneyMap waypoint name (for target_type=waypoint). Known: "
                        + knownWaypointNames(maid));

        StringParameter arrival = StringParameter.create()
                .setDescription("What to do after arriving (optional): meet / home / protect");
        arrival.addEnumValues("meet", "home", "protect");

        StringParameter task = StringParameter.create()
                .setDescription("Optional work mode id to switch to on arrival (e.g. go farm and harvest). Known: "
                        + knownTaskIds());

        root.addProperties(TARGET_TYPE, targetType);
        root.addProperties(X, x, false);
        root.addProperties(Y, y, false);
        root.addProperties(Z, z, false);
        root.addProperties(WAYPOINT, waypoint, false);
        root.addProperties(ARRIVAL, arrival, false);
        root.addProperties(TASK, task, false);
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, Result result, LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        MoveArrival arrival = parseArrival(result.arrival(), result.targetType());

        switch (result.targetType()) {
            case "player" -> {
                // 延迟到服务端 tick 执行，脱离聊天回调上下文
                LivingEntity owner = maid.getOwner();
                if (owner != null) {
                    MaidMoveData.queueMove(maid, owner.blockPosition(), owner.level().dimension(), arrival);
                }
                return callback.addToolResult("The maid is coming to you.", toolCallId);
            }
            case "coordinate" -> {
                // 钳制 Y 到世界高度范围，防止 LLM 输出的极端 Y（int 溢出/虚空/坠亡）写进女仆移动目标
                int minY = maid.level().getMinBuildHeight();
                int maxY = maid.level().getMaxBuildHeight() - 1;
                int y = Math.max(minY, Math.min(maxY, result.y()));
                BlockPos pos = new BlockPos(result.x(), y, result.z());
                if (hasTask(result)) {
                    MaidMoveData.queueMoveTask(maid, pos, maid.level().dimension(), result.task());
                } else {
                    MaidMoveData.queueMove(maid, pos, maid.level().dimension(), arrival);
                }
                return callback.addToolResult(
                        "The maid is going to %d, %d, %d.".formatted(result.x(), y, result.z()), toolCallId);
            }
            case "waypoint" -> {
                return handleWaypoint(maid, result, callback, toolCallId, arrival);
            }
            default -> {
                return callback.addToolResult(
                        "Invalid target_type '%s'. Use player / coordinate / waypoint.".formatted(result.targetType()),
                        toolCallId);
            }
        }
    }

    private LLMCallback handleWaypoint(EntityMaid maid, Result result, LLMCallback callback,
                                       String toolCallId, MoveArrival arrival) {
        if (result.waypointName().isBlank()) {
            return callback.addToolResult("No waypoint_name provided for target_type=waypoint.", toolCallId);
        }
        LivingEntity owner = maid.getOwner();
        if (!(owner instanceof ServerPlayer serverPlayer)) {
            return callback.addToolResult("The maid has no owner to look up waypoints for.", toolCallId);
        }
        WaypointEntry wp = WaypointCache.find(serverPlayer.getUUID(), result.waypointName());
        if (wp == null) {
            return callback.addToolResult(
                    "No JourneyMap waypoint named '%s' found for the user. Known waypoints: %s"
                            .formatted(result.waypointName(), WaypointCache.getWaypoints(serverPlayer.getUUID()).isEmpty()
                                    ? "(none synced)"
                                    : WaypointCache.getWaypoints(serverPlayer.getUUID()).stream().map(WaypointEntry::name).toList()),
                    toolCallId);
        }
        ResourceKey<Level> dim;
        try {
            dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(wp.dimension()));
        } catch (Exception e) {
            return callback.addToolResult("Waypoint '%s' has an invalid dimension: %s"
                    .formatted(result.waypointName(), wp.dimension()), toolCallId);
        }
        // 钳制 Y 到世界高度范围，防止客户端路径点的极端 Y（int 溢出/虚空）写进移动目标
        int minY = maid.level().getMinBuildHeight();
        int maxY = maid.level().getMaxBuildHeight() - 1;
        int y = Math.max(minY, Math.min(maxY, wp.y()));
        BlockPos wpPos = new BlockPos(wp.x(), y, wp.z());
        if (hasTask(result)) {
            MaidMoveData.queueMoveTask(maid, wpPos, dim, result.task());
        } else {
            MaidMoveData.queueMove(maid, wpPos, dim, arrival);
        }
        return callback.addToolResult(
                "The maid is going to waypoint '%s' at %d, %d, %d.".formatted(wp.name(), wp.x(), y, wp.z()),
                toolCallId);
    }

    private static MoveArrival parseArrival(String arrival, String targetType) {
        if ("player".equals(targetType)) {
            // 过来 → 到身边并跟随
            return MoveArrival.BACK;
        }
        if (arrival == null || arrival.isBlank()) {
            return MoveArrival.MEET;
        }
        return switch (arrival.toLowerCase()) {
            case "home", "stay", "guard", "呆", "守" -> MoveArrival.HOME;
            case "defend", "protect", "守家" -> MoveArrival.PROTECT;
            default -> MoveArrival.MEET;
        };
    }

    private static String knownWaypointNames(EntityMaid maid) {
        if (maid.getOwner() instanceof ServerPlayer sp) {
            var list = WaypointCache.getWaypoints(sp.getUUID());
            if (!list.isEmpty()) {
                return list.stream().map(WaypointEntry::name).toList().toString();
            }
        }
        return "(none synced yet)";
    }

    private static boolean hasTask(Result result) {
        return result.task() != null && !result.task().isBlank();
    }

    /** 列出当前可用的工作模式 id，供 LLM 匹配"收菜/杀猪"等意图 */
    private static String knownTaskIds() {
        return TaskManager.getTaskIndex().stream()
                // isHidden(null) 仅用于"在切换 UI 中是否隐藏"判断；个别任务可能解引用女仆而 NPE，故防御性兜底
                .filter(t -> !safeIsHidden(t))
                .map(t -> t.getUid().getPath() + " (" + t.getMaidActionSummary() + ")")
                .toList().toString();
    }

    private static boolean safeIsHidden(IMaidTask task) {
        try {
            return task.isHidden(null);
        } catch (RuntimeException e) {
            // 某些任务在 null 女仆下判断隐藏会抛异常，保守视为不隐藏（保留在可选列表）
            return false;
        }
    }

    public record Result(String targetType, int x, int y, int z, String waypointName, String arrival, String task) {
    }
}
