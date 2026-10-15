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
 * 本页把那个占位填上，<b>用公开骨架</b>（{@link PhonePage} + {@link PhoneText} + {@link PhoneShapes}）绘制，
 * 配色全部取自主题（{@link ColorScheme}），因此换配色方案时会跟着变。
 *
 * <h2>页面结构：分组 + 卡片行，全数据驱动</h2>
 * 版式是「一个分组标题 + 一张卡片」，卡片里每行是「左标题 / 右当前值 + ›」。
 * 要加入口就往 {@link #SECTIONS} 里加一条 —— 渲染、命中、总高都从同一份数据算出来，
 * 不需要再动几何代码。
 *
 * <h2>与「HuhuTalk 设置页」的分工</h2>
 * 底部导航那个 {@code SettingsPage} 是<b>手机本身的设置</b>（屏幕/亮度/配色/动画…），
 * 本页是<b>桌面 App 级的设置</b>（AI 识图、桌面图标管理…）。
 * 两边的条目不要重复：谁的用户预期是"我在设置 App 里找"，就放本页。
 */
public class SettingsAppPage extends PhonePage {

    /** 卡片内一行的高度 */
    private static final int ROW_H = 32;
    /** 卡片左右边距 */
    private static final int CARD_PAD = 10;
    /** 卡片内上下留白 */
    private static final int CARD_PAD_V = 6;
    /** 分组标题缩放 */
    private static final float GROUP_SCALE = 0.75f;
    /** 内容区顶部留白 */
    private static final int TOP_PAD = 8;
    /** 分组标题到卡片顶的间距 */
    private static final int TITLE_GAP = 6;
    /** 卡片底到下一个分组标题的间距 */
    private static final int SECTION_GAP = 10;
    /** 末尾留白 */
    private static final int BOTTOM_PAD = 12;

    /**
     * 一行设置项：标题、右侧摘要值、点开去哪里。
     *
     * @param label 标题
     * @param value 右侧的当前值摘要（动态取，例如"识图模型：阿里云百炼"）
     * @param open  点击时执行（通常是 {@code PhoneOverlay.pushPage(new XxxPage())}）
     */
    private record Entry(String label, Supplier<String> value, Runnable open) {
    }

    /** 一个分组：一个标题 + 一张卡片（若干行）。 */
    private record Section(String title, List<Entry> entries) {
    }

    /**
     * 全部分组。<b>扩展点</b>：要加条目就在这里加一行。
     */
    private static final List<Section> SECTIONS = List.of(
            new Section("AI", List.of(
                    new Entry("识图模型",
                            SettingsAppPage::visionSummary,
                            () -> PhoneOverlay.pushPage(new VisionModelSettingsPage())))),
            new Section("桌面", List.of(
                    new Entry("桌面图标管理",
                            SettingsAppPage::desktopSummary,
                            () -> PhoneOverlay.pushPage(new DesktopIconPage()))))
    );

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

    /** 右侧摘要：桌面已占用几格（9 是槽位总数，与手机长宽比无关）。 */
    private static String desktopSummary() {
        return MainPage.desktopUsedCount() + "/" + MainPage.desktopSlotCount() + " 格";
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
    //
    // 所有 y 都是**逻辑坐标（未滚动）**：渲染时过一遍 scrollY()，命中时过一遍 logicalY()。

    private static int titleH(Font font) {
        return PhoneText.height(font, PhoneText.scale(GROUP_SCALE));
    }

    /** 一行的高度（像素）。 */
    private static int rowH() {
        return PhoneText.px(ROW_H);
    }

    /** 一张卡片的高度（像素）。 */
    private static int cardHeight(Section s) {
        return PhoneText.px(CARD_PAD_V) * 2 + s.entries().size() * rowH();
    }

    /** 每帧的布局结果。渲染与命中都调 {@link #layout(Font)} 拿它。 */
    private static final class Layout {
        /** 各分组标题的 y（逻辑坐标） */
        final List<Integer> titleYs = new ArrayList<>();
        /** 各分组卡片：{y, h}（逻辑坐标） */
        final List<int[]> cards = new ArrayList<>();
        /** 内容总高（逻辑像素） */
        int totalH;
    }

    private static Layout layout(Font font) {
        Layout out = new Layout();
        int y = PhoneText.px(TOP_PAD);
        for (Section s : SECTIONS) {
            out.titleYs.add(y);
            y += titleH(font) + PhoneText.px(TITLE_GAP);
            int h = cardHeight(s);
            out.cards.add(new int[]{y, h});
            y += h + PhoneText.px(SECTION_GAP);
        }
        // 末尾那个 SECTION_GAP 是"卡片底 → 下一个标题"的间距，最后一张卡片后面不该留
        out.totalH = Math.max(0, y - PhoneText.px(SECTION_GAP) + PhoneText.px(BOTTOM_PAD));
        return out;
    }

    /** 第 {@code section} 组第 {@code index} 行的 y（逻辑坐标）。 */
    private static int rowY(Layout L, int section, int index) {
        return L.cards.get(section)[0] + PhoneText.px(CARD_PAD_V) + index * rowH();
    }

    @Override
    protected int contentHeight() {
        return layout(font()).totalH;
    }

    // ==================== 渲染 ====================

    @Override
    protected void renderContent(GuiGraphics g, int originX, int originY, int w, int h, float partialTick) {
        Font font = font();
        ColorScheme c = colors();
        Layout L = layout(font);
        int cardX = originX + PhoneText.px(CARD_PAD);
        int cardW = w - PhoneText.px(CARD_PAD) * 2;
        int rowH = rowH();

        for (int si = 0; si < SECTIONS.size(); si++) {
            Section s = SECTIONS.get(si);
            PhoneText.draw(g, font, s.title(), originX + PhoneText.px(CARD_PAD + 2),
                    originY + scrollY(L.titleYs.get(si)), c.textMuted(), PhoneText.scale(GROUP_SCALE));

            int[] card = L.cards.get(si);
            int top = originY + scrollY(card[0]);
            PhoneShapes.fillRoundedRect(g, cardX, top, cardW, card[1], PhoneText.px(8), c.cardBg());

            for (int i = 0; i < s.entries().size(); i++) {
                Entry e = s.entries().get(i);
                int ry = originY + scrollY(rowY(L, si, i));
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
    }

    // ==================== 交互 ====================

    @Override
    protected boolean onContentClick(double localX, double localY, int button, int action) {
        if (action != GLFW.GLFW_PRESS || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        Layout L = layout(font());
        int cardX = PhoneText.px(CARD_PAD);
        int cardW = width() - PhoneText.px(CARD_PAD) * 2;
        int rowH = rowH();
        int logical = logicalY(localY);
        for (int si = 0; si < SECTIONS.size(); si++) {
            List<Entry> entries = SECTIONS.get(si).entries();
            for (int i = 0; i < entries.size(); i++) {
                if (hit(cardX, rowY(L, si, i), cardW, rowH, localX, logical)) {
                    entries.get(i).open().run();
                    return true;
                }
            }
        }
        return false;
    }
}
