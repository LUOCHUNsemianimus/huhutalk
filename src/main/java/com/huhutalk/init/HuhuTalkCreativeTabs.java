package com.huhutalk.init;

import com.huhutalk.HuhuTalk;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 创造模式物品栏。
 */
public final class HuhuTalkCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, HuhuTalk.MOD_ID);

    public static final RegistryObject<CreativeModeTab> HUHU_TAB =
            TABS.register("huhu_tab", () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(HuhuTalkItems.PHONE_SMART.get()))
                    .title(Component.translatable("itemGroup.huhutalk"))
                    .displayItems((params, output) -> {
                        output.accept(HuhuTalkItems.PHONE_WATCH.get());
                        output.accept(HuhuTalkItems.PHONE_SMART.get());
                        output.accept(HuhuTalkBlocks.WIFI_BLOCK_ITEM.get());
                    })
                    .build());

    private HuhuTalkCreativeTabs() {
    }
}
