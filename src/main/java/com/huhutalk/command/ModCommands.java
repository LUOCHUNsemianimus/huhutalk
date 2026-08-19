package com.huhutalk.command;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.core.move.MaidMoveData;
import com.huhutalk.core.move.MoveArrival;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Collection;

/**
 * 调试命令，用于在接入 move_to 工具前手动测试移动任务。
 * <b>必须用实体选择器显式指定要移动的女仆</b>：
 * <ul>
 *   <li>{@code /huhutalk move <目标> <x> <y> <z>}</li>
 *   <li>{@code /huhutalk come <目标>}</li>
 * </ul>
 * 例：{@code /huhutalk come @e[type=touhou_little_maid:maid]}
 */
public final class ModCommands {

    private ModCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("huhutalk")
                        .requires(src -> src.hasPermission(2))
                        .then(moveCommand())
                        .then(comeCommand())
        );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> moveCommand() {
        return Commands.literal("move")
                .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> {
                                                    Collection<? extends Entity> entities = EntityArgument.getEntities(ctx, "targets");
                                                    int x = IntegerArgumentType.getInteger(ctx, "x");
                                                    int y = IntegerArgumentType.getInteger(ctx, "y");
                                                    int z = IntegerArgumentType.getInteger(ctx, "z");
                                                    int n = apply(entities, new BlockPos(x, y, z), MoveArrival.MEET);
                                                    ctx.getSource().sendSuccess(() ->
                                                            Component.literal("Moved " + n + " maids to " + x + "," + y + "," + z), true);
                                                    return 1;
                                                })))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> comeCommand() {
        return Commands.literal("come")
                .then(Commands.argument("targets", EntityArgument.entities())
                        .executes(ctx -> {
                            Collection<? extends Entity> entities = EntityArgument.getEntities(ctx, "targets");
                            int n = apply(entities, null, MoveArrival.BACK);
                            ctx.getSource().sendSuccess(() ->
                                    Component.literal("Summoning " + n + " maids to your side"), true);
                            return 1;
                        }));
    }

    /** 只对选择器命中的 EntityMaid 生效。@param pos 非空=移动到坐标；null=移动到玩家身边 */
    private static int apply(Collection<? extends Entity> entities, BlockPos pos, MoveArrival arrival) {
        int n = 0;
        for (Entity entity : entities) {
            if (!(entity instanceof EntityMaid maid)) {
                continue;
            }
            if (pos == null) {
                // 近走远传（自动判断）
                MaidMoveData.orderCome(maid, arrival);
            } else {
                MaidMoveData.orderCoordinate(maid, pos, maid.level().dimension(), arrival);
            }
            n++;
        }
        return n;
    }
}
