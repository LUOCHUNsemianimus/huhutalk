package com.huhutalk.init;

import com.huhutalk.core.load.WifiBlockRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * WiFi 方块。
 * <p>
 * 作用：定义"青春版手机"女仆的覆盖范围。自身不持有大范围票据，仅在放置/移除时
 * 把位置登记/注销到 {@link WifiBlockRegistry}，由票据管理器据此对覆盖内的青春版女仆
 * 所在区块下发 1×1 强制加载票据。
 */
public class BlockWifi extends Block {

    public BlockWifi(Properties properties) {
        super(properties);
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
