package com.huhutalk.client.theme;

import com.huhutalk.client.settings.HuhuTalkClientSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 手机壳渲染器（纯视觉，不影响内部布局）。
 * <p>
 * 在手机屏幕外围画一圈"手机壳"。若玩家在设置里指定了一个完整方块，
 * 则用该方块的粒子贴图（sprite）平铺到外壳边框；未指定时用默认深色壳。
 * 四角以切角近似圆角。读取自 {@link HuhuTalkClientSettings#getPhoneCaseBlock()}。
 */
public final class PhoneCaseRenderer {

    /** 外壳边框厚度（像素） */
    public static final int CASE_THICKNESS = 5;
    /** 四角切角尺寸（像素），近似圆角 */
    public static final int CORNER_CUT = 6;

    private PhoneCaseRenderer() {
    }

    /**
     * 渲染手机壳外壳（在渲染内部屏幕内容之前调用，作为背景层）。
     * 手机屏幕内容区坐标为 (screenX, screenY, w, h)，外壳向外扩展 {@link #CASE_THICKNESS}。
     */
    public static void renderCase(GuiGraphics g, int screenX, int screenY, int w, int h) {
        String blockId = HuhuTalkClientSettings.getPhoneCaseBlock();
        BlockState state = resolveBlockState(blockId);
        int outerX = screenX - CASE_THICKNESS;
        int outerY = screenY - CASE_THICKNESS;
        int outerW = w + CASE_THICKNESS * 2;
        int outerH = h + CASE_THICKNESS * 2;

        TextureAtlasSprite sprite = blockSprite(state);
        int borderColor = 0xFF2E2E2E; // 默认深色壳
        boolean useTexture = sprite != null;

        // 用 scissor 把外壳绘制限制在"外框扣掉内部屏幕"的环形区域。
        // 先画四边：上、下、左、右（不含圆角切角）。
        if (useTexture) {
            // 平铺纹理到四边
            drawTiledRect(g, sprite, outerX, outerY, outerW, CASE_THICKNESS);                        // 上
            drawTiledRect(g, sprite, outerX, outerY + outerH - CASE_THICKNESS, outerW, CASE_THICKNESS); // 下
            drawTiledRect(g, sprite, outerX, outerY + CASE_THICKNESS, CASE_THICKNESS, outerH - CASE_THICKNESS * 2); // 左
            drawTiledRect(g, sprite, outerX + outerW - CASE_THICKNESS, outerY + CASE_THICKNESS, CASE_THICKNESS, outerH - CASE_THICKNESS * 2); // 右
            // 四个切角（近似圆角的小方块）
            int cut = CORNER_CUT;
            g.fill(outerX, outerY, outerX + cut, outerY + cut, borderColor);                             // 左上
            g.fill(outerX + outerW - cut, outerY, outerX + outerW, outerY + cut, borderColor);           // 右上
            g.fill(outerX, outerY + outerH - cut, outerX + cut, outerY + outerH, borderColor);           // 左下
            g.fill(outerX + outerW - cut, outerY + outerH - cut, outerX + outerW, outerY + outerH, borderColor); // 右下
        } else {
            // 纯色外壳
            g.fill(outerX, outerY, outerX + outerW, outerY + CASE_THICKNESS, borderColor);
            g.fill(outerX, outerY + outerH - CASE_THICKNESS, outerX + outerW, outerY + outerH, borderColor);
            g.fill(outerX, outerY, outerX + CASE_THICKNESS, outerY + outerH, borderColor);
            g.fill(outerX + outerW - CASE_THICKNESS, outerY, outerX + outerW, outerY + outerH, borderColor);
        }
    }

    /** 解析方块 ID → BlockState；非法/未设置返回 null */
    private static BlockState resolveBlockState(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return null;
        }
        try {
            ResourceLocation rl = ResourceLocation.parse(blockId);
            Block block = BuiltInRegistries.BLOCK.get(rl);
            if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
                return null;
            }
            return block.defaultBlockState();
        } catch (Exception e) {
            return null;
        }
    }

    /** 获取方块模型的粒子贴图 sprite；失败返回 null */
    private static TextureAtlasSprite blockSprite(BlockState state) {
        if (state == null) {
            return null;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
            return dispatcher.getBlockModel(state).getParticleIcon();
        } catch (Exception e) {
            return null;
        }
    }

    /** 平铺绘制一个矩形区域的贴图（按 16px 一格循环） */
    private static void drawTiledRect(GuiGraphics g, TextureAtlasSprite sprite,
                                      int x, int y, int width, int height) {
        for (int i = 0; i < width; i += 16) {
            int w = Math.min(16, width - i);
            for (int j = 0; j < height; j += 16) {
                int h = Math.min(16, height - j);
                g.blit(x + i, y + j, 0, w, h, sprite);
            }
        }
    }

    /**
     * 校验一个方块物品是否可用于手机壳（必须是完整方块）。
     * 仅方块物品（非完整方块/物品）不允许。用于设置页选壳时的过滤。
     */
    public static boolean isFullBlockItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        try {
            if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem)) {
                return false;
            }
            Block block = blockItem.getBlock();
            return block != null && block.defaultBlockState().isSolid();
        } catch (Exception e) {
            return false;
        }
    }
}
