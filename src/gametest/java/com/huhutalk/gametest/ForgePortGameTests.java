package com.huhutalk.gametest;

import com.github.tartaricacid.touhoulittlemaid.crafting.AltarRecipe;
import com.huhutalk.HuhuTalk;
import com.huhutalk.core.rescue.RescuePotion;
import com.huhutalk.core.transfer.MaidTransferData;
import com.huhutalk.init.HuhuTalkBlocks;
import com.huhutalk.init.HuhuTalkItems;
import com.huhutalk.phone.ItemTransfer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 原生 Forge 1.20.1 GameTest：只验证本模组的真实物品/NBT、药水结算和配方加载。
 *
 * <p>本类位于独立 {@code gametest} 源集，不进入正式 jar；不发送真实 LLM 请求，
 * 也不依赖客户端或鼠标。空模板 {@code data/huhutalk/structures/port_empty.nbt}
 * 由 {@code tools/generate_port_gametest_structure.py} 生成。</p>
 */
@GameTestHolder(HuhuTalk.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ForgePortGameTests {

    private static final String MAID_ID = "gametest-maid";
    private static final String CUSTOM_NBT_KEY = "huhutalk_gametest";
    private static final String CUSTOM_NBT_VALUE = "kept";

    private ForgePortGameTests() {
    }

    /**
     * 第 1 组：MaidTransferData 持久化 + ItemTransfer.Preview 的真实物品 NBT。
     *
     * <p>37 个钻石是<b>一个数量为 37 的栈</b>，预览只应携带 1 个；真实栈数量不能被预览改动。</p>
     */
    @GameTest(template = "port_empty")
    public static void nativeItemNbtRoundTrip(GameTestHelper helper) {
        ItemStack diamonds = new ItemStack(Items.DIAMOND, 37);
        diamonds.setHoverName(Component.literal("gametest diamonds"));
        diamonds.enchant(Enchantments.SHARPNESS, 3);
        diamonds.getOrCreateTag().putString(CUSTOM_NBT_KEY, CUSTOM_NBT_VALUE);

        ItemStack potion = new ItemStack(Items.POTION);
        PotionUtils.setPotion(potion, Potions.HEALING);
        potion.getOrCreateTag().putInt(PotionUtils.TAG_CUSTOM_POTION_COLOR, 0x3366CC);

        MaidTransferData data = new MaidTransferData();
        String transferId = data.addBatch(MAID_ID, List.of(diamonds, potion));
        check(transferId != null, "addBatch should return a transfer id");

        MaidTransferData loaded = MaidTransferData.load(data.save(new CompoundTag()));
        List<ItemStack> restored = loaded.getBatch(MAID_ID, transferId);
        check(restored.size() == 2, "loaded batch should contain diamond stack and potion, got " + restored.size());

        ItemStack restoredDiamonds = findStack(restored, Items.DIAMOND);
        check(restoredDiamonds != null, "loaded batch should contain the diamond stack");
        check(restoredDiamonds.getCount() == 37, "diamond stack count should stay 37, got " + restoredDiamonds.getCount());
        check("gametest diamonds".equals(restoredDiamonds.getHoverName().getString()),
                "custom name should survive save/load");
        check(EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SHARPNESS, restoredDiamonds) == 3,
                "sharpness level should survive save/load");
        check(restoredDiamonds.getTag() != null
                        && CUSTOM_NBT_VALUE.equals(restoredDiamonds.getTag().getString(CUSTOM_NBT_KEY)),
                "custom NBT should survive save/load");

        ItemStack restoredPotion = findStack(restored, Items.POTION);
        check(restoredPotion != null, "loaded batch should contain the potion");
        check(PotionUtils.getPotion(restoredPotion) == Potions.HEALING,
                "potion type should survive save/load");
        check(PotionUtils.getColor(restoredPotion) == 0x3366CC,
                "custom potion color should survive save/load");

        // addBatch 存的是 copy，原栈仍是 37；Preview 只编码 1 个，但 count 仍为 37。
        ItemTransfer.Preview preview = ItemTransfer.Preview.of(diamonds);
        check(diamonds.getCount() == 37, "original stack count must remain 37");
        check(preview.count == 37, "preview count should be 37, got " + preview.count);
        ItemStack decoded = ItemTransfer.Preview.decodeStack(preview.stackNbt);
        check(!decoded.isEmpty(), "preview stack NBT should decode to a non-empty stack");
        check(decoded.getCount() == 1, "preview decoded stack should contain exactly 1 item, got " + decoded.getCount());
        check("gametest diamonds".equals(decoded.getHoverName().getString()),
                "preview should preserve custom name");
        check(EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SHARPNESS, decoded) == 3,
                "preview should preserve enchantment");
        check(decoded.getTag() != null
                        && CUSTOM_NBT_VALUE.equals(decoded.getTag().getString(CUSTOM_NBT_KEY)),
                "preview should preserve custom NBT");

        helper.succeed();
    }

    /** 第 2 组：真实药水的回血量、使用方式、持续效果过滤与自定义颜色。 */
    @GameTest(template = "port_empty")
    public static void nativePotionBehavior(GameTestHelper helper) {
        ItemStack healing = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.HEALING);
        check(RescuePotion.isPotion(healing), "healing potion should be recognized");
        check(RescuePotion.healAmount(healing) == 4, "HEALING should heal 4");
        check(RescuePotion.instantHealAmount(healing) == 4, "HEALING instant heal should be 4");
        check(RescuePotion.usageOf(healing) == RescuePotion.Usage.DRINK, "normal potion should be DRINK");

        ItemStack strongHealing = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.STRONG_HEALING);
        check(RescuePotion.healAmount(strongHealing) == 8, "STRONG_HEALING should heal 8");
        check(RescuePotion.instantHealAmount(strongHealing) == 8, "STRONG_HEALING instant heal should be 8");

        ItemStack splash = PotionUtils.setPotion(new ItemStack(Items.SPLASH_POTION), Potions.HEALING);
        check(RescuePotion.usageOf(splash) == RescuePotion.Usage.SPLASH, "splash potion should be SPLASH");

        ItemStack lingering = PotionUtils.setPotion(new ItemStack(Items.LINGERING_POTION), Potions.HEALING);
        check(RescuePotion.usageOf(lingering) == RescuePotion.Usage.LINGERING,
                "lingering potion should be LINGERING");

        ItemStack custom = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD);
        PotionUtils.setCustomEffects(custom, List.of(
                new MobEffectInstance(MobEffects.REGENERATION, 900, 1),
                new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 600),
                new MobEffectInstance(MobEffects.HARM)));
        custom.getOrCreateTag().putInt(PotionUtils.TAG_CUSTOM_POTION_COLOR, 0x22CC44);

        check(RescuePotion.healAmount(custom) == 36,
                "regeneration 900t amp1 should be worth 36, got " + RescuePotion.healAmount(custom));
        check(RescuePotion.instantHealAmount(custom) == 0,
                "no instant HEAL means instantHealAmount 0");
        check(RescuePotion.isFireResistance(custom), "custom potion should contain fire resistance");

        List<MobEffectInstance> lasting = RescuePotion.lastingEffects(custom);
        check(lasting.size() == 2, "lasting effects should keep regeneration and fire resistance, got " + lasting.size());
        boolean hasRegeneration = false;
        boolean hasFireResistance = false;
        for (MobEffectInstance effect : lasting) {
            hasRegeneration |= effect.getEffect() == MobEffects.REGENERATION;
            hasFireResistance |= effect.getEffect() == MobEffects.FIRE_RESISTANCE;
        }
        check(hasRegeneration, "lasting effects should contain regeneration");
        check(hasFireResistance, "lasting effects should contain fire resistance");
        check(PotionUtils.getColor(custom) == 0x22CC44, "CustomPotionColor should be preserved");

        ItemStack water = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER);
        check(RescuePotion.isPotion(water), "WATER should still be a potion");
        check(RescuePotion.healAmount(water) == 0, "WATER should heal 0");

        helper.succeed();
    }

    /** 第 3 组：真实 RecipeManager 加载 3 个 TLM 祭坛配方，并检查 WiFi 方块挖掘 tag。 */
    @GameTest(template = "port_empty")
    public static void nativeAltarRecipesAndBlockTag(GameTestHelper helper) {
        RecipeManager recipes = helper.getLevel().getRecipeManager();

        checkAltarRecipe(helper, recipes, "huhutalk:altar_recipe/phone_watch",
                HuhuTalkItems.PHONE_WATCH.get(), 0.5F,
                Items.CLOCK, Items.GOLD_INGOT, Items.GOLD_INGOT,
                Items.REDSTONE, Items.REDSTONE, Items.GLASS);
        checkAltarRecipe(helper, recipes, "huhutalk:altar_recipe/phone_smart",
                HuhuTalkItems.PHONE_SMART.get(), 4.0F,
                HuhuTalkItems.PHONE_WATCH.get(), Items.NETHER_STAR, Items.ENCHANTED_GOLDEN_APPLE,
                Items.ENDER_EYE, Items.ENDER_EYE, Items.DIAMOND);
        checkAltarRecipe(helper, recipes, "huhutalk:altar_recipe/wifi_block",
                HuhuTalkBlocks.WIFI_BLOCK_ITEM.get(), 1.0F,
                Items.IRON_BLOCK, Items.REDSTONE, Items.REDSTONE,
                Items.QUARTZ, Items.LEVER, Items.LIGHTNING_ROD);

        check(HuhuTalkBlocks.WIFI_BLOCK.get().defaultBlockState().is(BlockTags.MINEABLE_WITH_PICKAXE),
                "wifi_block default state should be mineable with pickaxe");

        helper.succeed();
    }

    private static void checkAltarRecipe(GameTestHelper helper, RecipeManager recipes, String recipeId,
                                         Item expectedOutput, float expectedPower,
                                         Item... expectedIngredients) {
        ResourceLocation id = new ResourceLocation(recipeId);
        Recipe<?> raw = recipes.byKey(id).orElseThrow(
                () -> new AssertionError("recipe should be loaded: " + recipeId));
        check(raw instanceof AltarRecipe, recipeId + " should be a TLM AltarRecipe, got " + raw.getClass().getName());
        AltarRecipe recipe = (AltarRecipe) raw;

        check(recipe.getEntityType() == EntityType.ITEM, recipeId + " should output an item entity");
        CompoundTag extraData = recipe.getExtraData();
        check(extraData != null, recipeId + " should carry item output NBT");
        ItemStack output = ItemStack.of(extraData.getCompound("Item"));
        check(!output.isEmpty(), recipeId + " output Item should decode to a non-empty stack");
        check(output.getCount() == 1, recipeId + " output count should be 1, got " + output.getCount());
        check(BuiltInRegistries.ITEM.getKey(output.getItem())
                        .equals(BuiltInRegistries.ITEM.getKey(expectedOutput)),
                recipeId + " output item mismatch: " + BuiltInRegistries.ITEM.getKey(output.getItem()));
        check(Math.abs(recipe.getPowerCost() - expectedPower) < 1.0E-6F,
                recipeId + " power should be " + expectedPower + ", got " + recipe.getPowerCost());

        check(recipe.getIngredients().size() == expectedIngredients.length,
                recipeId + " should have " + expectedIngredients.length + " ingredients, got "
                        + recipe.getIngredients().size());
        for (int i = 0; i < expectedIngredients.length; i++) {
            check(recipe.getIngredients().get(i).test(new ItemStack(expectedIngredients[i])),
                    recipeId + " ingredient " + i + " should match "
                            + BuiltInRegistries.ITEM.getKey(expectedIngredients[i]));
        }
    }

    private static ItemStack findStack(List<ItemStack> stacks, Item item) {
        List<ItemStack> matches = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack.getItem() == item) {
                matches.add(stack);
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
