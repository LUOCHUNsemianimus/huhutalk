package com.huhutalk.phone.pages;

import com.huhutalk.api.phone.ui.PhoneGrid;
import com.huhutalk.api.phone.ui.PhonePage;
import com.huhutalk.api.phone.ui.PhoneText;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.client.theme.ColorScheme;
import com.huhutalk.client.theme.PhoneCaseRenderer;
import com.huhutalk.client.theme.ThemeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 手机壳方块选择页（从设置页进入）。
 * <p>
 * 列出玩家背包里的所有<b>完整方块</b>（非完整方块不允许，用 {@link PhoneCaseRenderer#isFullBlockItem} 过滤），
 * 点击某个方块即设置为手机壳贴图（存 {@link HuhuTalkClientSettings#setPhoneCaseBlock}），实时生效。
 * 纯视觉，不影响内部布局。
 *
 * <h2>改用公开骨架（批次 1）</h2>
 * 旧实现是 189 行裸 {@code IPhonePage}，其中真正属于本页的只有「列背包里的完整方块」这一件事，
 * 其余全是每个页面各抄一遍的样板：手绘顶栏 / 返回键、手写 {@code navH()}、
 * 手写 {@code drawScaledText}、手写 {@code enableScissor}/{@code disableScissor} 配对、
 * 以及<b>渲染与命中各算一遍</b>的网格坐标（{@code row/col → x/y} 写两次）。
 * <p>
 * 现在：顶栏 / 底色 / 返回键 / 裁剪 → {@link PhonePage}；
 * 网格的滚动、命中、悬停、可见性剔除 → {@link PhoneGrid}
 * （{@link PhoneGrid#cellX(int)} / {@link PhoneGrid#cellY(int)} 与
 * {@link PhoneGrid#indexAt(double, double)} 是唯一一对互逆映射，渲染与命中不可能不同步）。
 * <p>
 * ⚠ 滚轮速度从「一格 = 格子高 + 间距」变成统一的 10px/单位（与 {@code PhoneList} 一致）——
 * 旧值是约 40px/单位，明显更快。
 */
public class PhoneCasePickerPage extends PhonePage {

    /** 每行方块数 */
    private static final int COLUMNS = 4;
    /** 格子高度（基准像素，随全局字体倍率缩放） */
    private static final int CELL_H = 34;
    /** 网格四周与格间留白（基准像素） */
    private static final int GAP = 6;
    /** 内容区顶部「当前：…」一行占的高度（基准像素） */
    private static final int TOP_LINE_H = 16;

    /** 完整方块 id（去重，保持出现顺序） */
    private final List<String> blocks = new ArrayList<>();

    /**
     * 网格视口宽度。由 {@link #renderContent} 每帧写入。
     * <p>
     * 为什么不直接在匿名网格里调 {@code width()}：那是个 {@code protected} 方法，
     * 在匿名内部类里访问要绕一层外层实例；用一个私有字段更直白，也避免"每帧多次取全局宽度"。
     */
    private int gridW = 0;

    private final PhoneGrid<String> grid = new PhoneGrid<>() {
        @Override
        protected List<String> items() {
            return blocks;
        }

        @Override
        protected int columns() {
            return COLUMNS;
        }

        @Override
        protected int cellWidth() {
            int gap = PhoneText.px(GAP);
            return Math.max(1, (gridW - gap * (COLUMNS + 1)) / COLUMNS);
        }

        @Override
        protected int cellHeight() {
            return PhoneText.px(CELL_H);
        }

        @Override
        protected int gapX() {
            return PhoneText.px(GAP);
        }

        @Override
        protected int gapY() {
            return PhoneText.px(GAP);
        }

        @Override
        protected int padLeft() {
            return PhoneText.px(GAP);
        }

        @Override
        protected void renderCell(GuiGraphics g, int index, String id,
                                  int x, int y, int w, int h, boolean hovered) {
            ColorScheme scheme = ThemeManager.getActive();
            // 当前选中的手机壳：强调色底；其余：半透明白底
            boolean selected = id.equals(HuhuTalkClientSettings.getPhoneCaseBlock());
            g.fill(x, y, x + w, y + h, selected ? scheme.accentSoftActive() : 0x33FFFFFF);

            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
            if (item != null) {
                g.renderItem(new ItemStack(item), x + 3, y + 3);
            }
            PhoneText.drawLeftIn(g, Minecraft.getInstance().font, shortName(id), x, y, w, h,
                    PhoneText.px(22), scheme.textPrimary(), PhoneText.scale(1.0f));
        }

        @Override
        protected void onCellClick(int index, String id) {
            if (HuhuTalkClientSettings.getPhoneCaseBlock().equals(id)) {
                HuhuTalkClientSettings.setPhoneCaseBlock(""); // 点已选中的 → 恢复默认
            } else {
                HuhuTalkClientSettings.setPhoneCaseBlock(id);
            }
        }
    };

    public PhoneCasePickerPage() {
        refreshFromInventory();
    }

    /** 从背包收集完整方块的 block id（去重，保持出现顺序） */
    private void refreshFromInventory() {
        blocks.clear();
        Set<String> seen = new HashSet<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            for (ItemStack stack : mc.player.getInventory().items) {
                if (!PhoneCaseRenderer.isFullBlockItem(stack)) {
                    continue;
                }
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (seen.add(id)) {
                    blocks.add(id);
                }
            }
        }
    }

    // ==================== 骨架 ====================

    @Override
    protected String title() {
        return "选择手机壳方块";
    }

    // contentHeight() 保持默认的 0：滚动交给 PhoneGrid，页面自己再滚一次会变成双重滚动。

    // ==================== 渲染 ====================

    @Override
    protected void renderContent(GuiGraphics g, int originX, int originY, int w, int h, float partialTick) {
        gridW = w;
        ColorScheme scheme = colors();
        int topLine = PhoneText.px(TOP_LINE_H);

        PhoneText.draw(g, font(), "当前：" + currentDisplay(),
                originX + PhoneText.px(4), originY + PhoneText.px(4),
                scheme.textPrimary(), PhoneText.scale(1.0f));

        grid.setViewport(0, topLine, w, Math.max(0, h - topLine));
        grid.render(g, originX, originY);
    }

    // ==================== 交互 ====================

    @Override
    protected boolean onContentClick(double localX, double localY, int button, int action) {
        return grid.mouseClicked(localX, localY, button, action);
    }

    @Override
    protected void onContentMoved(double localX, double localY) {
        grid.mouseMoved(localX, localY);
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        if (relY < contentTop()) {
            return false; // 顶栏不滚
        }
        return grid.mouseScrolled(scrollDelta);
    }

    // ==================== 数据 ====================

    private String currentDisplay() {
        String id = HuhuTalkClientSettings.getPhoneCaseBlock();
        return id.isBlank() ? "默认" : shortName(id);
    }

    private static String shortName(String id) {
        int idx = id.indexOf(':');
        return idx >= 0 ? id.substring(idx + 1) : id;
    }
}
