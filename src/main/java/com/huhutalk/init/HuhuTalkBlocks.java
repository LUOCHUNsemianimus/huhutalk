package com.huhutalk.init;

import com.huhutalk.HuhuTalk;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组方块注册。
 * <p>
 * WiFi 方块：定义"青春版手机"的覆盖范围。放置后仅锚定自身所在区块（1×1），
 * 只有当附近存在佩戴青春版手机的女仆时，才对其所在区块下发强制加载票据。
 * 具体逻辑由票据管理器实现（后续 ticket 模块）。
 */
public final class HuhuTalkBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(HuhuTalk.MOD_ID);

    public static final DeferredHolder<Block, Block> WIFI_BLOCK =
            BLOCKS.register("wifi_block",
                    () -> new BlockWifi(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                            .strength(2.0f, 6.0f)
                            // 非完整方块：相邻方块的面不剔除，底部草地方块能正常渲染
                            .noOcclusion()));

    /** WiFi 方块的物品形态（便于摆放） */
    public static final DeferredHolder<Item, BlockItem> WIFI_BLOCK_ITEM =
            HuhuTalkItems.ITEMS.register("wifi_block",
                    () -> new BlockItem(WIFI_BLOCK.get(), new Item.Properties()));

    private HuhuTalkBlocks() {
    }
}
