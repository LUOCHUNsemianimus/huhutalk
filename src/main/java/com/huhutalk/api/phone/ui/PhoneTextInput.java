package com.huhutalk.api.phone.ui;

import com.huhutalk.client.theme.ThemeManager;
import com.huhutalk.phone.PhoneTextCaptureScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.Consumer;

/**
 * 内联文本输入控件——点一下就在原地编辑，形态与 {@link PhoneDropdown} 一致（同一行里占满宽度）。
 *
 * <h2>为什么需要配套的 {@link PhoneTextCaptureScreen}</h2>
 * 手机页面（{@link PhonePage}）<b>收不到任何键盘事件</b>：{@code IPhonePage} 上根本没有
 * {@code keyPressed} / {@code charTyped} 钩子。手机的文字输入一直是靠
 * 「打开一个（半）透明的 {@code Screen} 抢键盘」实现的（聊天页的 {@code PhoneInputScreen}、
 * 联系人页的 {@code PhoneGroupNameInputScreen} 都是这个套路）。
 * <p>
 * 所以本控件是「内联的样子 + 屏幕级键盘捕获」的组合：
 * <ul>
 *   <li><b>样子是内联的</b>：控件就在行里原地渲染，焦点态画光标，不是弹窗；</li>
 *   <li><b>打字靠 Screen</b>：点它时开一个<b>完全透明</b>的捕获屏，把按键转发给本控件。
 *       手机界面照常渲染在底下，玩家看到的就是"这一格在闪光标、我在往里打字"。</li>
 * </ul>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * PhoneTextInput input = new PhoneTextInput()
 *         .placeholder("粘贴 API Key")
 *         .masked(true)
 *         .onCommit(v -> save(v));
 * // 页面布局里
 * input.bounds(x, y, w, PhoneText.px(20));
 * }</pre>
 *
 * @see PhoneTextCaptureScreen
 */
public final class PhoneTextInput extends PhoneWidget {

    /** 框体高度（像素，随字体倍率缩放） */
    private static final int BOX_HEIGHT_PX = 20;
    /** 文字左右留白 */
    private static final int PAD_PX = 6;
    /** 默认最大长度（TLM 自己的站点编辑器也是 512） */
    private static final int DEFAULT_MAX_LENGTH = 512;

    private String value = "";
    private String placeholder = "";
    private boolean masked;
    private boolean focused;
    private int maxLength = DEFAULT_MAX_LENGTH;
    private Consumer<String> onCommit;
    /** 捕获屏"取消"时用来回滚 */
    private String valueBeforeEdit = "";

    // ==================== 配置 ====================

    /** 当前文本。 */
    public String value() {
        return value;
    }

    /** 直接设置文本（不触发 {@link #onCommit}）。 */
    public PhoneTextInput value(String v) {
        this.value = v == null ? "" : v;
        return this;
    }

    /** 空值时的灰色占位提示。 */
    public PhoneTextInput placeholder(String text) {
        this.placeholder = text == null ? "" : text;
        return this;
    }

    /** 是否掩码显示（用 {@code •} 代替字符），用于 API Key。 */
    public PhoneTextInput masked(boolean m) {
        this.masked = m;
        return this;
    }

    public PhoneTextInput maxLength(int len) {
        this.maxLength = Math.max(1, len);
        return this;
    }

    /** 编辑结束（回车 / 点别处 / 失焦提交）时回调，参数是最终文本。 */
    public PhoneTextInput onCommit(Consumer<String> listener) {
        this.onCommit = listener;
        return this;
    }

    // ==================== 编辑期（由捕获屏驱动） ====================

    /** 捕获屏打开时调用：记下原值，供"取消"回滚。 */
    public void beginEdit() {
        valueBeforeEdit = value;
        focused = true;
    }

    /** 编辑中同步文本（每敲一个字调一次）。 */
    public void updateEditingText(String text) {
        this.value = text == null ? "" : text;
    }

    /** 捕获屏关闭：{@code commit=true} 则提交并回调，否则回滚。 */
    public void endEdit(boolean commit) {
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

    public int maxLengthValue() {
        return maxLength;
    }

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
        return PhoneText.px(BOX_HEIGHT_PX);
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

        // 框体：聚焦时用主题强调色描一圈，让"正在编辑哪一格"一眼可见
        int bg = ThemeManager.getActive().cardBg();
        PhoneShapes.fillRoundedRect(g, x, y, w, h, Math.min(6, h / 2), bg);
        if (focused) {
            PhoneShapes.fillRoundedRect(g, x, y, w, 1, 0, ThemeManager.getActive().accent());
            PhoneShapes.fillRoundedRect(g, x, y + h - 1, w, 1, 0, ThemeManager.getActive().accent());
            PhoneShapes.fillRoundedRect(g, x, y, 1, h, 0, ThemeManager.getActive().accent());
            PhoneShapes.fillRoundedRect(g, x + w - 1, y, 1, h, 0, ThemeManager.getActive().accent());
        }

        String shown;
        int color;
        if (value.isEmpty()) {
            shown = placeholder;
            color = ThemeManager.getActive().textMuted();
        } else if (masked) {
            shown = "•".repeat(Math.min(value.length(), 64));
            color = ThemeManager.getActive().textPrimary();
        } else {
            shown = value;
            color = ThemeManager.getActive().textPrimary();
        }
        // 聚焦时末尾补一个光标（真光标位置由 EditBox 管，这里只做视觉提示）
        if (focused) {
            shown = shown + "|";
        }
        PhoneText.drawLeftIn(g, font, shown, x, y, w, h,
                PhoneText.px(PAD_PX), color, PhoneText.scale(1.0f));
    }

    // ==================== 交互 ====================

    @Override
    public boolean mousePressed(double px, double py, int button) {
        if (!enabled() || !isPrimaryPress(button) || !hit(px, py)) {
            return false;
        }
        openCaptureScreen();
        return true;
    }

    /**
     * 打开透明键盘捕获屏。
     * <p>
     * 与 {@code MaidInfoPage} 打开分组名输入屏是同一个时机（内容区点击回调里直接换 Screen），
     * 所以行为上已经被现有页面验证过。
     */
    private void openCaptureScreen() {
        Minecraft.getInstance().setScreen(new PhoneTextCaptureScreen(this));
    }
}
