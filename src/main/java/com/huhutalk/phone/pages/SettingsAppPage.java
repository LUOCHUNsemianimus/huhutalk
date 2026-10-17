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
    private static final int TITLE_GAP = 3;
    /**
     * 卡片底到下一个分组标题的间距。
     * <p>
     * 作者验收后要求「两项栏目的行间距缩到一半」：本常量与 {@link #TITLE_GAP} 一起减半
     * （10→5、6→3），两栏之间的可见间距因此从约 23px 收到约 15px。
     * <p>
     * ⚠ 收不到精确的 50%：那条间距里还夹着分组标题自己的高度（≈7px），它是字号决定的、缩不了。
     * 想再紧就继续减这两个常量 —— <b>它们只影响"栏与栏之间"，不动卡片内部的行高</b>。
     */
    private static final int SECTION_GAP = 5;
    /** 末尾留白 */
    private static final int BOTTOM_PAD = 12;

    /**
     * 一行设置项：标题键、右侧摘要值、点开去哪里。
     *
     * @param labelKey 标题的<b>语言键</b>（存键而不是翻译结果：SECTIONS 是 static，存结果会冻住语言）
     * @param value    右侧的当前值摘要（动态取，例如"识图模型：阿里云百炼"）
     * @param open     点击时执行（通常是 {@code PhoneOverlay.pushPage(new XxxPage())}）
     */
    private record Entry(String labelKey, Supplier<String> value, Runnable open) {
    }

    /** 一个分组：一个标题键 + 一张卡片（若干行）。 */
    private record Section(String titleKey, List<Entry> entries) {
    }

    /**
     * 全部分组。<b>扩展点</b>：要加条目就在这里加一行。
     */
    private static final List<Section> SECTIONS = List.of(
            new Section("gui.huhutalk.settings.section.ai", List.of(
                    new Entry("gui.huhutalk.settings.vision_model",
                            SettingsAppPage::visionSummary,
                            () -> PhoneOverlay.pushPage(new VisionModelSettingsPage())))),
            new Section("gui.huhutalk.settings.section.desktop", List.of(
                    new Entry("gui.huhutalk.settings.desktop_icons",
                            SettingsAppPage::desktopSummary,
                            () -> PhoneOverlay.pushPage(new DesktopIconPage()))))
    );

    public SettingsAppPage() {
    }

    /** 右侧摘要：当前识图走的平台 + 模型。 */
    private static String visionSummary() {
        String siteId = HuhuTalkClientSettings.getVisionSiteId();
        VisionProvider p = VisionProviders.byId(siteId);
        String name = PhoneText.visionProviderName(siteId,
                p != null ? p.displayName() : siteId);
        String model = HuhuTalkClientSettings.getEffectiveVisionModel();
        return model.isBlank() ? name : name + " · " + model;
    }

    /** 右侧摘要：桌面已占用几格（9 是槽位总数，与手机长宽比无关）。 */
    private static String desktopSummary() {
        return PhoneText.tr("gui.huhutalk.settings.desktop.summary",
                MainPage.desktopUsedCount(), MainPage.desktopSlotCount());
    }

    @Override
    protected String title() {
        return PhoneText.tr("gui.huhutalk.settings.title");
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
            PhoneText.draw(g, font, PhoneText.tr(s.titleKey()), originX + PhoneText.px(CARD_PAD + 2),
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
                // 左标题 / 右当前值 + 「›」。这里一次算清两列宽度：
                // 先用当前语言的**实际**译文量出各自需要多宽，再按"值优先、标题让位"分配，
                // 保证英文长标题与值不会互相压字。渲染与测量用的是同一份译文（同一次 tr()）。
                float ts = PhoneText.scale(1.0f);
                String label = PhoneText.tr(e.labelKey());
                String value = e.value().get();
                int labelPad = PhoneText.px(12);
                int rightPad = PhoneText.px(12);
                int colGap = PhoneText.px(6);
                int chevronW = PhoneText.width(font, "›", ts);
                int valueRight = cardW - rightPad - chevronW - PhoneText.px(4);
                int columns = Math.max(0, valueRight - labelPad);
                // 值最多占 45% 宽度；若值本身很短，就只预留它实际需要的宽度，把空间让给标题
                int valueWant = Math.min(PhoneText.width(font, value, ts), columns * 45 / 100);
                int labelMax = Math.min(Math.max(0, columns - colGap - valueWant),
                        PhoneText.width(font, label, ts));
                int valueMax = Math.max(0, columns - colGap - labelMax);
                PhoneText.drawLeftIn(g, font, label, cardX, ry, labelPad + labelMax + PhoneText.px(4), rowH,
                        labelPad, c.textPrimary(), ts);
                String shown = PhoneText.truncate(font, value, valueMax, ts);
                int vw = PhoneText.width(font, shown, ts);
                int textY = ry + (rowH - PhoneText.height(font, ts)) / 2;
                PhoneText.draw(g, font, shown, cardX + valueRight - vw, textY, c.textSecondary(), ts);
                PhoneText.draw(g, font, "›", cardX + cardW - rightPad - chevronW, textY, c.textMuted(), ts);
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
