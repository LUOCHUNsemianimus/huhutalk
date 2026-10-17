package com.huhutalk.api.phone.ui;

import com.huhutalk.client.theme.ThemeManager;
import com.huhutalk.phone.PhoneTextCaptureScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 内联<b>多行</b>文本输入控件 —— {@link PhoneTextInput} 的多行版，键盘走同一个透明捕获屏。
 *
 * <h2>为什么需要它（为什么不直接用 PhoneTextInput）</h2>
 * {@link PhoneTextInput} 是<b>单行</b>控件，渲染走 {@link PhoneText#drawLeftIn}，
 * 而 {@link PhoneText} 整个类<b>只有 {@code truncate}，没有 wrap</b>。
 * 手机内容区宽约 110~137 GUI 像素、一个 CJK 字约 9px ⇒ 单行只能看到约 11 个字，
 * 编辑一段 200 字的外貌描述等于盲打。
 * <p>
 * 本控件把文本按可用宽度折行后一次显示 {@link #visibleLines(int) N} 行，
 * 仍是<b>内联</b>形态（不是弹窗），与 {@link PhoneTextInput} 是同一套设计语言。
 *
 * <h2>折行与光标</h2>
 * <ul>
 *   <li>折行按<b>实际字宽</b>累加（{@code font.width}），所以 CJK 与窄字符混排也不会溢出；
 *       文本里若真有 {@code '\n'} 也照样断行；</li>
 *   <li>编辑期<b>窗口跟着光标走</b>：光标在哪一行，就把那一行滚进可见区。
 *       非编辑期则总是显示开头，并在被截断时于末行补「…」。</li>
 * </ul>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * PhoneTextArea area = new PhoneTextArea()
 *         .placeholder("描述一下她的发色、瞳色、服饰…")
 *         .maxLength(200)
 *         .onCommit(v -> save(v));
 * area.bounds(x, y, w, area.preferredHeight(font));
 * }</pre>
 *
 * <h2>⚠ 坐标规矩与 {@link PhoneTextInput} 完全一样</h2>
 * {@code bounds} 用<b>内容坐标</b>，{@link #render} 自己会加 {@code originX/originY}；
 * 页面自绘的 {@code fill} / {@link PhoneText#draw} 用<b>屏幕坐标</b>。
 * 写错的表现见 {@code phone-ui-toolkit-handoff.md} §0.1-⑦b（灰条整体上移一行，盖住别的行）。
 *
 * @see PhoneTextInput
 * @see PhoneTextEditable
 * @see PhoneTextCaptureScreen
 */
public final class PhoneTextArea extends PhoneWidget implements PhoneTextEditable {

    /** 文字四周留白 */
    private static final int PAD_PX = 6;
    /** 默认最大长度：两段外貌描述各占一份提示词，200 字 ≈ 400 token，是刻意的上限 */
    private static final int DEFAULT_MAX_LENGTH = 200;
    /** 默认可见行数 */
    private static final int DEFAULT_VISIBLE_LINES = 5;

    private String value = "";
    private String placeholder = "";
    private boolean focused;
    private int maxLength = DEFAULT_MAX_LENGTH;
    private int visibleLines = DEFAULT_VISIBLE_LINES;
    private Consumer<String> onCommit;
    /** 捕获屏"取消"时用来回滚 */
    private String valueBeforeEdit = "";
    /** 编辑期光标位置（字符索引；由捕获屏每敲一次同步一次），用于决定滚动窗口 */
    private int cursorPos;

    // ==================== 配置 ====================

    /** 当前文本。 */
    @Override
    public String value() {
        return value;
    }

    /** 直接设置文本（不触发 {@link #onCommit}）。 */
    public PhoneTextArea value(String v) {
        this.value = v == null ? "" : v;
        return this;
    }

    /** 空值时的灰色占位提示。 */
    public PhoneTextArea placeholder(String text) {
        this.placeholder = text == null ? "" : text;
        return this;
    }

    public PhoneTextArea maxLength(int len) {
        this.maxLength = Math.max(1, len);
        return this;
    }

    /** 可见行数（默认 {@value #DEFAULT_VISIBLE_LINES} 行）。 */
    public PhoneTextArea visibleLines(int lines) {
        this.visibleLines = Math.max(1, lines);
        return this;
    }

    /** 编辑结束（点别处 / 取消之外的关闭）时回调，参数是最终文本。 */
    public PhoneTextArea onCommit(Consumer<String> listener) {
        this.onCommit = listener;
        return this;
    }

    // ==================== 编辑期（由捕获屏驱动） ====================

    @Override
    public void beginEdit() {
        valueBeforeEdit = value;
        cursorPos = value.length();
        focused = true;
    }

    @Override
    public void updateEditingText(String text, int cursor) {
        this.value = text == null ? "" : text;
        this.cursorPos = Math.clamp(cursor, 0, this.value.length());
    }

    @Override
    public void endEdit(boolean commit) {
        if (!focused) {
            return;
        }
        focused = false;
        if (commit) {
            String v = value.length() > maxLength ? value.substring(0, maxLength) : value;
            value = v;
            if (onCommit != null) {
                onCommit.accept(v);
            }
        } else {
            value = valueBeforeEdit;
        }
    }

    @Override
    public int maxLengthValue() {
        return maxLength;
    }

    @Override
    public boolean focused() {
        return focused;
    }

    // ==================== 布局 ====================

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
        int lineH = Math.max(1, PhoneText.height(font, PhoneText.scale(1.0f)));
        return PhoneText.px(PAD_PX) * 2 + lineH * visibleLines;
    }

    // ==================== 渲染 ====================

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

        int bg = ThemeManager.getActive().cardBg();
        PhoneShapes.fillRoundedRect(g, x, y, w, h, Math.min(6, h / 2), bg);
        if (focused) {
            PhoneShapes.fillRoundedRect(g, x, y, w, 1, 0, ThemeManager.getActive().accent());
            PhoneShapes.fillRoundedRect(g, x, y + h - 1, w, 1, 0, ThemeManager.getActive().accent());
            PhoneShapes.fillRoundedRect(g, x, y, 1, h, 0, ThemeManager.getActive().accent());
            PhoneShapes.fillRoundedRect(g, x + w - 1, y, 1, h, 0, ThemeManager.getActive().accent());
        }

        float ts = PhoneText.scale(1.0f);
        int lineH = Math.max(1, PhoneText.height(font, ts));
        int pad = PhoneText.px(PAD_PX);
        int innerW = w - pad * 2;
        int maxRows = Math.max(1, (h - pad * 2) / lineH);
        if (innerW <= 0) {
            return;
        }

        boolean empty = value.isEmpty();
        int color = empty ? ThemeManager.getActive().textMuted() : ThemeManager.getActive().textPrimary();

        if (empty) {
            // 占位提示只在绘制路径走原生 Font.split：英文等以空格分词的语言按词折行；
            // 真实输入的折行/光标映射仍走 wrap(...)，文本存储与编辑算法完全不变。
            renderPlaceholder(g, font, x, y, w, pad, innerW, lineH, maxRows, ts, color);
            return;
        }

        Wrapped wp = wrap(font, value, (int) (innerW / ts));
        int cursorLine = cursorLine(wp, cursorPos);

        // 可见窗口：编辑期跟着光标
        int first = 0;
        if (focused && cursorLine >= maxRows) {
            first = cursorLine - maxRows + 1;
        }
        int last = Math.min(wp.lines().size(), first + maxRows);

        for (int row = first; row < last; row++) {
            String line = wp.lines().get(row);
            // 编辑期在光标所在的字符列插入一个竖线（真光标由 EditBox 管，这里只做视觉提示）。
            if (focused && row == cursorLine) {
                int col = Math.clamp(cursorPos - wp.starts().get(row), 0, line.length());
                line = line.substring(0, col) + "|" + line.substring(col);
            }
            PhoneText.draw(g, font, line, x + pad, y + pad + (row - first) * lineH, color, ts);
        }

        // 非编辑期被截断：末行右端补省略号提示"下面还有"。
        // ⚠ 只在塞得下时才画 —— 折行后的末行通常正好占满宽度，硬画会盖住真实的字。
        if (!focused && last < wp.lines().size()) {
            String more = "…";
            int moreW = PhoneText.width(font, more, ts);
            int lastLineW = PhoneText.width(font, wp.lines().get(last - 1), ts);
            if (pad * 2 + lastLineW + moreW <= w) {
                PhoneText.draw(g, font, more, x + w - pad - moreW,
                        y + pad + (last - 1 - first) * lineH, ThemeManager.getActive().textMuted(), ts);
            }
        }
    }

    /**
     * 空值占位提示：用 {@link Font#split} 按词折行后以同字号绘制。
     * <p>占位文字不参与光标映射（编辑期的竖线只补在第一行末尾），所以这里只要"折好的行"。
     */
    private void renderPlaceholder(GuiGraphics g, Font font, int x, int y, int w, int pad,
                                   int innerW, int lineH, int maxRows, float ts, int color) {
        int wrapW = Math.max(1, (int) (innerW / ts));
        List<FormattedCharSequence> lines = font.split(Component.literal(placeholder), wrapW);
        int last = Math.min(lines.size(), maxRows);
        if (last <= 0) {
            // Font.split 返回空行列表：非编辑期不画；编辑期仍在第一行起点画竖线，保留焦点提示
            if (focused) {
                PhoneText.draw(g, font, "|", x + pad, y + pad, color, ts);
            }
            return;
        }
        for (int row = 0; row < last; row++) {
            PhoneText.draw(g, font, lines.get(row), x + pad, y + pad + row * lineH, color, ts);
        }
        // 空值时真光标没有对应字符：编辑期在第一行占位文字末尾补一个竖线（与旧实现一致）
        if (focused) {
            int lineW = PhoneText.width(font, lines.get(0), ts);
            PhoneText.draw(g, font, "|", x + pad + lineW, y + pad, color, ts);
        }
        // 非编辑期被截断：末行右端补省略号，同样只在塞得下时才画
        if (!focused && last < lines.size()) {
            String more = "…";
            int moreW = PhoneText.width(font, more, ts);
            int lastLineW = PhoneText.width(font, lines.get(last - 1), ts);
            if (pad * 2 + lastLineW + moreW <= w) {
                PhoneText.draw(g, font, more, x + w - pad - moreW,
                        y + pad + (last - 1) * lineH, ThemeManager.getActive().textMuted(), ts);
            }
        }
    }

    // ==================== 折行 ====================

    /** 折行结果：{@code lines} 为每行文本，{@code starts} 为每行首字符在原文里的索引。 */
    private record Wrapped(List<String> lines, List<Integer> starts) {
    }

    /**
     * 按<b>实际字宽</b>把文本折成若干行（{@code maxWidth} 传的是<b>字体像素</b>，未乘缩放）。
     * <p>不用 {@code Font.split} 是因为它只给"折好的行"，不给"每行首字符的索引" ——
     * 而没有索引就无法把 EditBox 的光标位置映射到行上。
     */
    private static Wrapped wrap(Font font, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        if (maxWidth <= 0) {
            lines.add(text);
            starts.add(0);
            return new Wrapped(lines, starts);
        }
        StringBuilder cur = new StringBuilder();
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int cc = Character.charCount(cp);
            if (cp == '\n') {
                lines.add(cur.toString());
                starts.add(start);
                cur.setLength(0);
                i += cc;
                start = i;
                continue;
            }
            String ch = text.substring(i, i + cc);
            if (!cur.isEmpty() && font.width(cur + ch) > maxWidth) {
                lines.add(cur.toString());
                starts.add(start);
                start = i;
                cur.setLength(0);
            }
            cur.append(ch);
            i += cc;
        }
        lines.add(cur.toString());
        starts.add(start);
        return new Wrapped(lines, starts);
    }

    /** 光标落在第几行（最后一个"起始索引 ≤ cursorPos"的行）。 */
    private static int cursorLine(Wrapped wp, int cursorPos) {
        int line = 0;
        for (int k = 0; k < wp.starts().size(); k++) {
            if (wp.starts().get(k) <= cursorPos) {
                line = k;
            } else {
                break;
            }
        }
        return line;
    }

    // ==================== 交互 ====================

    @Override
    public boolean mousePressed(double px, double py, int button) {
        if (!enabled() || !isPrimaryPress(button) || !hit(px, py)) {
            return false;
        }
        startEditing();
        return true;
    }

    /**
     * 以编程方式进入编辑态（等价于点一下本控件）。
     * <p>
     * 给「<b>页面自己管热区</b>」的场合用：控件框体通常比它所在的那一行矮，
     * 玩家点在行内空白处时页面的矩形命中会成立、而 {@link #mousePressed} 的框体命中不成立。
     * 这时页面按自己的行矩形判定完直接调本方法即可。已经处于编辑态时重复调用无副作用。
     */
    public void startEditing() {
        if (!enabled()) {
            return;
        }
        Minecraft.getInstance().setScreen(new PhoneTextCaptureScreen(this));
    }
}
