package com.huhutalk.client.theme;

import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.phone.UiShapes;
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
 * 外框为真正的圆角（半径 {@link #CORNER_RADIUS}），内部镂空出屏幕区域。
 * 读取自 {@link HuhuTalkClientSettings#getPhoneCaseBlock()}。
 */
public final class PhoneCaseRenderer {

    /** 外壳边框厚度（像素） */
    public static final int CASE_THICKNESS = 5;
    /** 外壳四角圆角半径（像素） */
    public static final int CORNER_RADIUS = 10;

    private PhoneCaseRenderer() {
    }

    /**
     * 渲染手机壳外壳（在渲染内部屏幕内容之前调用，作为背景层）。
     * 手机屏幕外框坐标为 (screenX, screenY, screenW, screenH)，外壳向外扩展 {@link #CASE_THICKNESS}，
     * 以圆角外框形式包住屏幕。
     * <p>
     * 内部镂空为"手机内容区"：顶部去掉 {@code holeTop}、底部去掉 {@code holeBottom} 高度，
     * 让壳把顶部状态栏与底部水印栏（它们用圆角绘制）的圆角托住，避免圆角三角区透出背后的风景。
     */
    public static void renderCase(GuiGraphics g, int screenX, int screenY, int screenW, int screenH,
                                  int holeTop, int holeBottom) {
        String blockId = HuhuTalkClientSettings.getPhoneCaseBlock();
        BlockState state = resolveBlockState(blockId);
        int outerX = screenX - CASE_THICKNESS;
        int outerY = screenY - CASE_THICKNESS;
        int outerW = screenW + CASE_THICKNESS * 2;
        int outerH = screenH + CASE_THICKNESS * 2;

        int holeX = screenX;
        int holeY = screenY + holeTop;
        int holeW = screenW;
        int holeH = Math.max(1, screenH - holeTop - holeBottom);

        TextureAtlasSprite sprite = blockSprite(state);
        int borderColor = 0xFF2E2E2E; // 默认深色壳
        boolean useTexture = sprite != null;

        if (useTexture) {
            // 自定义材质壳：按行平铺纹理到圆角外框（内部镂空内容区）
            UiShapes.fillRoundedRectFrameTiled(g, sprite, outerX, outerY, outerW, outerH,
                    holeX, holeY, holeW, holeH, CORNER_RADIUS);
        } else {
            // 默认纯色外壳：圆角外框 + 矩形镂空
            UiShapes.fillRoundedRectFrame(g, outerX, outerY, outerW, outerH,
                    holeX, holeY, holeW, holeH, CORNER_RADIUS, borderColor);
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
