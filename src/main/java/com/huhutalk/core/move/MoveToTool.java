package com.huhutalk.core.move;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
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

    private static final String TOOL_DESC = """
            Use this when the user asks the maid to go somewhere, come to the user, or go to a JourneyMap waypoint.
            target_type: 'player' = come to the user; 'coordinate' = go to a specific x,y,z; 'waypoint' = go to a JourneyMap waypoint by name.
            arrival (optional): 'meet' = go there and wait, then follow the user when they arrive (default for going somewhere);
            'wait' = go there and idle; 'protect' = switch to attack mode on arrival; 'back' = come to the user and follow.
            """.trim();

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf(TARGET_TYPE).forGetter(Result::targetType),
            Codec.INT.optionalFieldOf(X, 0).forGetter(Result::x),
            Codec.INT.optionalFieldOf(Y, 0).forGetter(Result::y),
            Codec.INT.optionalFieldOf(Z, 0).forGetter(Result::z),
            Codec.STRING.optionalFieldOf(WAYPOINT, "").forGetter(Result::waypointName),
            Codec.STRING.optionalFieldOf(ARRIVAL, "").forGetter(Result::arrival)
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

        root.addProperties(TARGET_TYPE, targetType);
        root.addProperties(X, x, false);
        root.addProperties(Y, y, false);
        root.addProperties(Z, z, false);
        root.addProperties(WAYPOINT, waypoint, false);
        root.addProperties(ARRIVAL, arrival, false);
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
                BlockPos pos = new BlockPos(result.x(), result.y(), result.z());
                MaidMoveData.queueMove(maid, pos, maid.level().dimension(), arrival);
                return callback.addToolResult(
                        "The maid is going to %d, %d, %d.".formatted(result.x(), result.y(), result.z()), toolCallId);
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
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(wp.dimension()));
        MaidMoveData.queueMove(maid, new BlockPos(wp.x(), wp.y(), wp.z()), dim, arrival);
        return callback.addToolResult(
                "The maid is going to waypoint '%s' at %d, %d, %d.".formatted(wp.name(), wp.x(), wp.y(), wp.z()),
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

    public record Result(String targetType, int x, int y, int z, String waypointName, String arrival) {
    }
}
