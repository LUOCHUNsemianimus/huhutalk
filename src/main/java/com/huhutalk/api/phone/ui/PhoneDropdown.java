package com.huhutalk.api.phone.ui;

import com.huhutalk.client.theme.ThemeManager;
import com.huhutalk.phone.UiShapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.IntConsumer;

/**
 * 点击展开的下拉选择器。形态与内置「SOS 工作模式」一致：
 * 收起时是一个圆角框（当前项 + 右侧 ▼），点开后在其<b>下方</b>列出选项，滚动或点击选择。
 *
 * <h2>⚠ 展开时会画到自己的矩形之外</h2>
 * 展开的列表绘制在控件<b>下方</b>，高度是 {@link #expandedHeight()}。因此：
 * <ol>
 *   <li>页面布局要为它<b>额外预留</b> {@link #expandedHeight()} 的高度
 *       （{@link PhoneRow} 的 {@code extraHeight()} 已经处理了这一点）；</li>
 *   <li>它必须<b>最后渲染</b>，否则会被后面的行盖住。</li>
 * </ol>
 *
 * <pre>{@code
 * PhoneDropdown dd = new PhoneDropdown()
 *         .options("种地", "伐木", "采石")
 *         .selected(index)
 *         .onChange(i -> send(i));
 * PhoneRow row = PhoneRow.of("工作模式", dd).bounds(0, y, w, rowH);
 * }</pre>
 */
public final class PhoneDropdown extends PhoneWidget {

    /** 展开时最多同时显示多少行 */
    public static final int VISIBLE_ROWS = 5;
    /** 每行高度（像素，随字体倍率缩放） */
    private static final int ROW_HEIGHT_PX = 14;
    /** 收起态框体高度（像素，随字体倍率缩放） */
    private static final int BOX_HEIGHT_PX = 20;
    /** 文字左右留白 */
    private static final int PAD_PX = 6;

    private String[] options = new String[0];
    private int selected = 0;
    private boolean expanded;
    private IntConsumer onChanged;
    private Integer boxBg;
    private Integer boxText;
    private Integer listBg;
    private Integer highlightBg;

    /** 设置选项（会重置选中项为 0，除非之前选中仍在范围内）。 */
    public PhoneDropdown options(String... values) {
        this.options = values == null ? new String[0] : values.clone();
        if (selected >= options.length) {
            selected = 0;
        }
        return this;
    }

    /** 设为选中项（自动钳制）；<b>不</b>触发回调。 */
    public PhoneDropdown selected(int index) {
        if (options.length == 0) {
            this.selected = 0;
        } else {
            this.selected = Math.max(0, Math.min(index, options.length - 1));
        }
        return this;
    }

    public int selected() {
        return selected;
    }

    /** 当前选中项的显示文本；无选项时为空串。 */
    public String selectedText() {
        return (selected >= 0 && selected < options.length) ? options[selected] : "";
    }

    public boolean expanded() {
        return expanded;
    }

    /** 程序化开合（不会触发 {@link #onChange}）。 */
    public PhoneDropdown setExpanded(boolean open) {
        this.expanded = open;
        return this;
    }

    /** 用户选中某项时回调。 */
    public PhoneDropdown onChange(IntConsumer listener) {
        this.onChanged = listener;
        return this;
    }

    /** 自定义配色；不设则取主题的 {@code cardBg} / {@code textPrimary} / {@code accentSoft}。 */
    public PhoneDropdown colors(int boxBg, int boxText, int listBg, int highlightBg) {
        this.boxBg = boxBg;
        this.boxText = boxText;
        this.listBg = listBg;
        this.highlightBg = highlightBg;
        return this;
    }

    /** 每行高度。 */
    public int rowHeight() {
        return PhoneText.px(ROW_HEIGHT_PX);
    }

    /** 展开时列表占用的额外高度（未展开为 0）。 */
    public int expandedHeight() {
        return expanded ? Math.min(VISIBLE_ROWS, options.length) * rowHeight() : 0;
    }

    @Override
    public LayoutHint layoutHint() {
        return LayoutHint.STACKED;
    }

    @Override
    public int preferredWidth(Font font) {
        return 0; // 撑满可用宽度
    }

    @Override
    public int preferredHeight(Font font) {
        return PhoneText.px(BOX_HEIGHT_PX);
    }

    @Override
    public void render(GuiGraphics g, int originX, int originY) {
        int w = width();
        int h = height();
        if (w <= 0 || h <= 0) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int x = originX + x();
        int y = originY + y();

        int bg = boxBg != null ? boxBg : ThemeManager.getActive().cardBg();
        int text = boxText != null ? boxText : ThemeManager.getActive().textPrimary();
        int list = listBg != null ? listBg : ThemeManager.getActive().cardBg();
        int hi = highlightBg != null ? highlightBg : ThemeManager.getActive().accentSoft();

        // 收起态框体
        int radius = Math.min(6, h / 2);
        UiShapes.fillRoundedRect(g, x, y, w, h, radius, bg);
        PhoneText.drawLeftIn(g, font, selectedText(), x, y, w - PhoneText.px(14), h,
                PhoneText.px(PAD_PX), text, PhoneText.scale(1.0f));
        PhoneText.drawCenteredIn(g, font, expanded ? "▲" : "▼",
                x + w - PhoneText.px(14), y, PhoneText.px(14), h,
                ThemeManager.getActive().accent(), PhoneText.scale(0.6f));

        // 展开态列表（画在自己的矩形之外，调用方必须最后渲染它）
        if (!expanded || options.length == 0) {
            return;
        }
        int rowH = rowHeight();
        int rows = Math.min(VISIBLE_ROWS, options.length);
        int listTop = y + h;
        UiShapes.fillRoundedRect(g, x, listTop, w, rows * rowH, radius, list);
        for (int i = 0; i < rows; i++) {
            int rowY = listTop + i * rowH;
            if (i == selected) {
                UiShapes.fillRoundedRect(g, x + 1, rowY, w - 2, rowH, Math.min(4, rowH / 2), hi);
            }
            PhoneText.drawLeftIn(g, font, options[i], x, rowY, w, rowH,
                    PhoneText.px(PAD_PX), text, PhoneText.scale(1.0f));
        }
    }

    @Override
    public boolean mousePressed(double px, double py, int button) {
        if (!enabled() || !isPrimaryPress(button)) {
            return false;
        }
        if (expanded) {
            int index = listIndexAt(px, py);
            if (index >= 0) {
                expanded = false;
                select(index);
                return true;
            }
            if (hit(px, py)) {
                expanded = false;
                return true;
            }
            return false;
        }
        if (hit(px, py)) {
            expanded = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double delta) {
        if (!expanded || options.length == 0) {
            return false;
        }
        int next = Math.max(0, Math.min(selected + (delta > 0 ? 1 : -1), options.length - 1));
        if (next != selected) {
            select(next);
        }
        return true; // 展开时吃掉滚轮，避免同时把页面滚了
    }

    /** 点落在展开列表的第几行；不在则 -1。 */
    private int listIndexAt(double px, double py) {
        if (!expanded || options.length == 0) {
            return -1;
        }
        int rowH = rowHeight();
        int rows = Math.min(VISIBLE_ROWS, options.length);
        int listTop = y() + height();
        if (px < x() || px >= x() + width()) {
            return -1;
        }
        if (py < listTop || py >= listTop + rows * rowH) {
            return -1;
        }
        return (int) ((py - listTop) / rowH);
    }

    private void select(int index) {
        if (index < 0 || index >= options.length || index == selected) {
            return;
        }
        selected = index;
        if (onChanged != null) {
            onChanged.accept(index);
        }
    }
}
