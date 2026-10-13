package com.huhutalk.phone.pages;

import com.huhutalk.api.phone.ui.PhonePage;
import com.huhutalk.api.phone.ui.PhoneShapes;
import com.huhutalk.api.phone.ui.PhoneText;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.client.theme.ColorScheme;
import com.huhutalk.common.vision.VisionProvider;
import com.huhutalk.common.vision.VisionProviders;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 桌面「设置」App 的主页（槽位 5）。
 *
 * <h2>它是什么</h2>
 * 桌面槽位 5 的「设置」图标以前是个空占位（{@code pageFactory == null} → 「敬请期待」）。
 * 本页把那个占位填上，<b>用公开骨架</b>（{@link PhonePage} + {@link PhoneText} + {@code UiShapes}）绘制，
 * 配色全部取自主题（{@link ColorScheme}），因此换配色方案时会跟着变。
 *
 * <h2>这一轮的范围</h2>
 * 按约定，本轮<b>只建设置 App 的壳 + 「识图模型」这一栏</b>：
 * 底部导航那个老设置页（{@code SettingsPage}）一行不改，不受影响。
 * 本页的行是数据驱动的（见 {@link Entry}），以后要把老设置页的条目搬过来，往 {@link #ENTRIES} 里加即可。
 */
public class SettingsAppPage extends PhonePage {

    /** 卡片内一行的高度 */
    private static final int ROW_H = 32;
    /** 卡片左右边距 */
    private static final int CARD_PAD = 10;
    /** 分组标题缩放 */
    private static final float GROUP_SCALE = 0.75f;

    /**
     * 一行设置项：标题、右侧摘要值、点开去哪里。
     *
     * @param label 标题
     * @param value 右侧的当前值摘要（动态取，例如"识图模型：阿里云百炼"）
     * @param open  点击时执行（通常是 {@code PhoneOverlay.pushPage(new XxxPage())}）
     */
    private record Entry(String label, Supplier<String> value, Runnable open) {
    }

    /**
     * 全部条目。<b>扩展点</b>：后续要从老设置页搬条目过来，在这里加一行即可。
     */
    private static final List<Entry> ENTRIES = new ArrayList<>(List.of(
            new Entry("识图模型",
                    SettingsAppPage::visionSummary,
                    () -> PhoneOverlay.pushPage(new VisionModelSettingsPage()))
    ));

    public SettingsAppPage() {
    }

    /** 右侧摘要：当前识图走的平台 + 模型。 */
    private static String visionSummary() {
        String siteId = HuhuTalkClientSettings.getVisionSiteId();
        VisionProvider p = VisionProviders.byId(siteId);
        String name = p != null ? p.displayName() : siteId;
        String model = HuhuTalkClientSettings.getEffectiveVisionModel();
        return model.isBlank() ? name : name + " · " + model;
    }

    @Override
    protected String title() {
        return "设置";
    }

    /**
     * 列表/设置形态的页面用略深一档的底色（与公开文档里给设置类页面的建议一致）。
     * 顶栏<b>显式锁成品牌色</b>，这样以后新增配色方案时，本页与聊天/消息/老设置页仍是一台手机。
     */
    @Override
    protected int pageBackground() {
        return colors().pageBgList();
    }

    @Override
    protected int topBarBackground() {
        return BRAND_TOPBAR_BG;
    }

    // ==================== 布局 ====================
    //
    // 渲染与命中共用同一份计算：renderContent / onContentClick / contentHeight 都调 layout()。
    // 这是骨架类注释里反复强调的那条纪律，违反它就会出现"画在一处、点在另一处"。

    /** 分组标题的 y（逻辑坐标） */
    private static final int TITLE_Y = 8;
    /** 分组标题到卡片顶的间距 */
    private static final int TITLE_GAP = 6;

    private static int titleH(Font font) {
        return PhoneText.height(font, PhoneText.scale(GROUP_SCALE));
    }

    private static int cardTopY(Font font) {
        return TITLE_Y + titleH(font) + TITLE_GAP;
    }

    private static int cardHeight() {
        return PhoneText.px(6) * 2 + ENTRIES.size() * PhoneText.px(ROW_H);
    }

    private int rowY(int index, Font font) {
        return cardTopY(font) + PhoneText.px(6) + index * PhoneText.px(ROW_H);
    }

    @Override
    protected int contentHeight() {
        return cardTopY(font()) + cardHeight() + PhoneText.px(12);
    }

    // ==================== 渲染 ====================

    @Override
    protected void renderContent(GuiGraphics g, int originX, int originY, int w, int h, float partialTick) {
        Font font = font();
        ColorScheme c = colors();
        int cardX = originX + PhoneText.px(CARD_PAD);
        int cardW = w - PhoneText.px(CARD_PAD) * 2;

        PhoneText.draw(g, font, "AI", originX + PhoneText.px(CARD_PAD + 2),
                originY + scrollY(TITLE_Y), c.textMuted(), PhoneText.scale(GROUP_SCALE));

        int top = originY + scrollY(cardTopY(font));
        PhoneShapes.fillRoundedRect(g, cardX, top, cardW, cardHeight(), PhoneText.px(8), c.cardBg());

        int rowH = PhoneText.px(ROW_H);
        for (int i = 0; i < ENTRIES.size(); i++) {
            Entry e = ENTRIES.get(i);
            int ry = originY + scrollY(rowY(i, font));
            if (i > 0) {
                g.fill(cardX + PhoneText.px(8), ry, cardX + cardW - PhoneText.px(8), ry + 1, c.divider());
            }
            PhoneText.drawLeftIn(g, font, e.label(), cardX, ry, cardW, rowH,
                    PhoneText.px(12), c.textPrimary(), PhoneText.scale(1.0f));
            // 右侧：当前值 + 一个「›」表示可点进去。值过长时截断，保证「›」永远可见
            int chevronW = PhoneText.width(font, "›", PhoneText.scale(1.0f));
            int valueRight = cardW - PhoneText.px(12) - chevronW - PhoneText.px(4);
            String value = PhoneText.truncate(font, e.value().get(),
                    Math.max(0, valueRight - PhoneText.px(60)), PhoneText.scale(1.0f));
            int vw = PhoneText.width(font, value, PhoneText.scale(1.0f));
            PhoneText.draw(g, font, value,
                    cardX + valueRight - vw, ry + (rowH - PhoneText.height(font, PhoneText.scale(1.0f))) / 2f,
                    c.textSecondary(), PhoneText.scale(1.0f));
            PhoneText.draw(g, font, "›",
                    cardX + cardW - PhoneText.px(12) - chevronW,
                    ry + (rowH - PhoneText.height(font, PhoneText.scale(1.0f))) / 2f,
                    c.textMuted(), PhoneText.scale(1.0f));
        }
    }

    // ==================== 交互 ====================

    @Override
    protected boolean onContentClick(double localX, double localY, int button, int action) {
        if (action != GLFW.GLFW_PRESS || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        Font font = font();
        int cardX = PhoneText.px(CARD_PAD);
        int cardW = width() - PhoneText.px(CARD_PAD) * 2;
        int rowH = PhoneText.px(ROW_H);
        int logical = logicalY(localY);
        for (int i = 0; i < ENTRIES.size(); i++) {
            if (hit(cardX, rowY(i, font), cardW, rowH, localX, logical)) {
                ENTRIES.get(i).open().run();
                return true;
            }
        }
        return false;
    }
}
