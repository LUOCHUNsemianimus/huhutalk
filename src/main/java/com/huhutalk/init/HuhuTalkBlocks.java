package com.huhutalk.init;

import com.huhutalk.HuhuTalk;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 本模组方块注册。
 * <p>
 * WiFi 方块：定义"青春版手机"的覆盖范围。放置后仅锚定自身所在区块（1×1），
 * 只有当附近存在佩戴青春版手机的女仆时，才对其所在区块下发强制加载票据。
 * 具体逻辑由票据管理器实现（后续 ticket 模块）。
 */
public final class HuhuTalkBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, HuhuTalk.MOD_ID);

    public static final RegistryObject<Block> WIFI_BLOCK =
            BLOCKS.register("wifi_block",
                    // 不再 ofFullCopy(IRON_BLOCK)：那样会连带继承 requiresCorrectToolForDrops()，
                    // 空手或任何工具挖下来都不掉落（铁块的性质，不是本方块想要的）。
                    // 这里显式给出，等于"空手能挖、任何工具都有掉落"，挖掘手感对齐原木：
                    // 硬度 2.0（原木同值）→ 空手约 3 秒；再靠 mineable/pickaxe 标签让镐子更快。
                    // 抗爆沿用原值 6.0；音效与地图色沿用铁块那套，保持外观/手感不变。
                    () -> new BlockWifi(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .sound(SoundType.METAL)
                            .strength(2.0f, 6.0f)
                            // 非完整方块：相邻方块的面不剔除，底部草地方块能正常渲染
                            .noOcclusion()));

    /** WiFi 方块的物品形态（便于摆放） */
    public static final RegistryObject<BlockItem> WIFI_BLOCK_ITEM =
            HuhuTalkItems.ITEMS.register("wifi_block",
                    () -> new BlockItem(WIFI_BLOCK.get(), new Item.Properties()));

    private HuhuTalkBlocks() {
    }
}
