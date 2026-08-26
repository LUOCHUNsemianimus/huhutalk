package com.huhutalk.init;

import com.huhutalk.core.load.WifiBlockRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

/**
 * WiFi 方块。
 * <p>
 * 作用：定义"青春版手机"女仆的覆盖范围。自身不持有大范围票据，仅在放置/移除时
 * 把位置登记/注销到 {@link WifiBlockRegistry}，由票据管理器据此对覆盖内的青春版女仆
 * 所在区块下发 1×1 强制加载票据。
 * <p>
 * 方块带水平朝向（东南西北四向），用于调整模型方向；注册时使用 {@code noOcclusion()}
 * 使相邻方块的面不被剔除（否则底部会被视为完整方块而隐藏渲染）。
 */
public class BlockWifi extends Block {

    /** 水平朝向（四向） */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public BlockWifi(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 让模型的正面朝向玩家（与熔炉等方向方块一致）
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel) {
            WifiBlockRegistry.get(serverLevel.getServer()).add(serverLevel.dimension(), new ChunkPos(pos));
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (level instanceof ServerLevel serverLevel && !state.is(newState.getBlock())) {
            WifiBlockRegistry.get(serverLevel.getServer()).remove(serverLevel.dimension(), new ChunkPos(pos));
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
