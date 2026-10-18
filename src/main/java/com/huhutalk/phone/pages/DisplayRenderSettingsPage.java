package com.huhutalk.phone.pages;

import com.huhutalk.api.phone.ui.PhonePage;
import com.huhutalk.api.phone.ui.PhoneRow;
import com.huhutalk.api.phone.ui.PhoneShapes;
import com.huhutalk.api.phone.ui.PhoneSlider;
import com.huhutalk.api.phone.ui.PhoneStepper;
import com.huhutalk.api.phone.ui.PhoneSwitch;
import com.huhutalk.api.phone.ui.PhoneText;
import com.huhutalk.api.phone.ui.PhoneWidget;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.client.theme.ColorScheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 「设置」App → 显示与渲染页。
 *
 * <h2>入口迁移</h2>
 * 原先 HuhuTalk 底部「设置」页的显示分组里有屏幕比例、屏幕大小、字体倍率、屏幕亮度与
 * HuhuTalk 图标五项。本页承接其中<b>前四项</b>（外加新增的墨水屏开关），
 * 图标风格留在旧页的显示分组；主题、动画、消息、救援等不属于显示类，不迁移。
 *
 * <h2>复用</h2>
 * 骨架 {@link PhonePage} 负责顶栏/底色/滚动/裁剪，{@link PhoneRow} 统一「标签 + 控件」的
 * 布局与命中，控件直接用公开的 {@link PhoneSwitch} / {@link PhoneStepper} / {@link PhoneSlider}。
 * 屏幕比例沿用旧页的小胶囊切换按钮（{@link SettingsPage.CapsuleButton}），
 * 因为它是旧页多个行共用的同一套控件，不在本页另造第二套。
 *
 * <h2>布局纪律</h2>
 * 渲染与命中都调 {@link #layout()} 拿同一份结果；控件字段级只建一次，
 * 读设置同步不触发 {@code save}，拖动中的滑块不同步，避免把手抖动。
 */
public class DisplayRenderSettingsPage extends PhonePage {

    /** 卡片左右边距 */
    private static final int CARD_PAD = 10;
    /** 行内左右内边距（与旧设置页一致） */
    private static final int ROW_PAD = 12;
    /** 统一行高（基准像素；滑块行需要 ≥27，见 PhoneRow#rowHeight） */
    private static final int ROW_H = 28;
    /** 卡片圆角 */
    private static final int CARD_R = 8;
    /** 内容区顶部留白 */
    private static final int TOP_PAD = 8;
    /** 卡片底留白 */
    private static final int BOTTOM_PAD = 20;
    /** 卡片内分隔线距卡片左右边 */
    private static final int DIVIDER_PAD = 8;

    /** 屏幕比例可选值（与旧页保持一致） */
    private static final List<String> ASPECT_OPTIONS = List.of("20:9", "9:16", "4:3");
    /** 字体倍率可选档位（与 HuhuTalkClientSettings 的档位表一致） */
    private static final double[] FONT_SCALE_STEPS = {0.5, 0.75, 1.0, 1.5, 2.0};

    /** 控件持有状态，必须每页只建一次（字段级），不能每帧新建 */
    private final PhoneSwitch inkScreenSwitch = new PhoneSwitch();
    private final PhoneStepper fontScaleStepper = new PhoneStepper();
    private final PhoneSlider phoneSizeSlider = new PhoneSlider();
    private final PhoneSlider brightnessSlider = new PhoneSlider();

    /** 屏幕比例切换按钮（无内部状态，但仍只建一次） */
    private final PhoneWidget aspectButton;

    /** 单组五行；构造时建好，之后每帧只重新 layout */
    private final List<PhoneRow> rows;

    public DisplayRenderSettingsPage() {
        aspectButton = new SettingsPage.CapsuleButton(
                "gui.huhutalk.phone_settings.button.switch", this::cycleAspect);
        buildWidgets();
        rows = buildRows();
    }

    // ==================== 骨架钩子 ====================

    @Override
    protected String title() {
        return PhoneText.tr("gui.huhutalk.settings.display_render");
    }

    @Override
    protected int pageBackground() {
        return colors().pageBgList();
    }

    /** 与旧设置页/设置 App 主页同品牌色顶栏 */
    @Override
    protected int topBarBackground() {
        return BRAND_TOPBAR_BG;
    }

    // ==================== 控件与行构建 ====================

    private void buildWidgets() {
        inkScreenSwitch.on(HuhuTalkClientSettings.isInkScreenEnabled())
                .onChange(HuhuTalkClientSettings::setInkScreenEnabled);

        fontScaleStepper.steps(FONT_SCALE_STEPS)
                .setValue(HuhuTalkClientSettings.getUiFontScale())
                .onChange(v -> HuhuTalkClientSettings.setUiFontScale(v.floatValue()));

        // 屏幕大小：配置是 0.5~1.0，滑块恒为 0~1，所以在回调里映射
        phoneSizeSlider.setValue(sizeToNormalized(HuhuTalkClientSettings.getPhoneSize()))
                .onChange(t -> HuhuTalkClientSettings.setPhoneSizeRaw(normalizedToSize(t)))
                .onCommit(t -> {
                    HuhuTalkClientSettings.setPhoneSizeRaw(normalizedToSize(t));
                    HuhuTalkClientSettings.save();
                });

        // 屏幕亮度：配置本身就是 0~1，直接用
        brightnessSlider.setValue(HuhuTalkClientSettings.getScreenBrightness())
                .onChange(t -> HuhuTalkClientSettings.setScreenBrightnessRaw((float) t))
                .onCommit(t -> {
                    HuhuTalkClientSettings.setScreenBrightnessRaw((float) t);
                    HuhuTalkClientSettings.save();
                });
    }

    private List<PhoneRow> buildRows() {
        return List.of(
                row("gui.huhutalk.phone_settings.row.ink_screen", inkScreenSwitch),
                row(() -> PhoneText.tr("gui.huhutalk.phone_settings.row.aspect",
                        HuhuTalkClientSettings.getPhoneAspect()), aspectButton),
                row(() -> PhoneText.tr("gui.huhutalk.phone_settings.row.phone_size",
                        Math.round(HuhuTalkClientSettings.getPhoneSize() * 100) + "%"), phoneSizeSlider),
                row(() -> PhoneText.tr("gui.huhutalk.phone_settings.row.font_scale",
                        fontScaleStepper.display()), fontScaleStepper),
                row(() -> PhoneText.tr("gui.huhutalk.phone_settings.row.brightness",
                        Math.round(HuhuTalkClientSettings.getScreenBrightness() * 100) + "%"), brightnessSlider));
    }

    /** 静态标签行：语言键在渲染时解析。 */
    private PhoneRow row(String labelKey, PhoneWidget widget) {
        return row(() -> PhoneText.tr(labelKey), widget);
    }

    /** 动态标签行（标签里带当前值）。 */
    private PhoneRow row(java.util.function.Supplier<String> label, PhoneWidget widget) {
        return PhoneRow.of(() -> fitRowLabel(label.get(), widget), widget).pad(ROW_PAD).rowHeight(ROW_H);
    }

    /**
     * 把标签截断到不与右侧控件重叠的可用宽度（与旧设置页同一算法）。
     * 滑块这类 STACKED 控件独占下一行，标签交给 PhoneRow 按整行宽截断。
     */
    private String fitRowLabel(String text, PhoneWidget widget) {
        if (widget == null || widget.layoutHint() == PhoneWidget.LayoutHint.STACKED) {
            return text;
        }
        Font font = font();
        int pw = widget.preferredWidth(font);
        if (pw <= 0) {
            return text;
        }
        int rowW = width() - PhoneText.px(CARD_PAD) * 2;
        int avail = rowW - PhoneText.px(ROW_PAD) * 2 - pw - PhoneText.px(6);
        return PhoneText.truncate(font, text, Math.max(0, avail), PhoneText.scale(1.0f));
    }

    // ==================== 布局 ====================

    /** 每帧布局结果（内容坐标，已含滚动）。渲染、命中、总高共用。 */
    private static final class Layout {
        /** 卡片矩形：{x, y, w, h} */
        int[] card;
        /** 内容逻辑总高 */
        int totalH;
    }

    private Layout layout() {
        Font font = font();
        int cardX = PhoneText.px(CARD_PAD);
        int cardW = width() - PhoneText.px(CARD_PAD) * 2;

        Layout out = new Layout();
        int y = PhoneText.px(TOP_PAD);
        int cardTop = y;
        for (PhoneRow row : rows) {
            row.layout(font, cardX, scrollY(y), cardW);
            y += row.height();
        }
        out.card = new int[]{cardX, scrollY(cardTop), cardW, y - cardTop};
        out.totalH = y + PhoneText.px(BOTTOM_PAD);
        return out;
    }

    @Override
    protected int contentHeight() {
        return layout().totalH;
    }

    // ==================== 渲染 ====================

    @Override
    protected void renderContent(GuiGraphics g, int originX, int originY, int w, int h, float partialTick) {
        syncFromSettings();
        Layout L = layout();
        ColorScheme c = colors();
        int[] card = L.card;

        PhoneShapes.fillRoundedRect(g, originX + card[0], originY + card[1],
                card[2], card[3], PhoneText.px(CARD_R), c.cardBg());

        for (int i = 0; i < rows.size(); i++) {
            PhoneRow row = rows.get(i);
            if (i < rows.size() - 1) {
                int dy = originY + row.y() + row.height();
                g.fill(originX + card[0] + PhoneText.px(DIVIDER_PAD), dy,
                        originX + card[0] + card[2] - PhoneText.px(DIVIDER_PAD), dy + 1,
                        c.divider());
            }
            row.render(g, originX, originY);
        }
    }

    /**
     * 把控件状态对齐到配置值。
     * <p>setValue 不触发回调，不会反写配置。拖动中的滑块不同步，避免取整往返造成把手抖动；
     * 松手（onCommit）之后下一帧自然对上。
     */
    private void syncFromSettings() {
        inkScreenSwitch.setValue(HuhuTalkClientSettings.isInkScreenEnabled());
        fontScaleStepper.setValue(HuhuTalkClientSettings.getUiFontScale());
        if (!phoneSizeSlider.dragging()) {
            phoneSizeSlider.setValue(sizeToNormalized(HuhuTalkClientSettings.getPhoneSize()));
        }
        if (!brightnessSlider.dragging()) {
            brightnessSlider.setValue(HuhuTalkClientSettings.getScreenBrightness());
        }
    }

    // ==================== 交互 ====================

    @Override
    protected boolean onContentClick(double localX, double localY, int button, int action) {
        layout(); // 命中前重新摆一遍，保证与刚才那次渲染用的是同一份布局
        for (PhoneRow row : rows) {
            if (action == GLFW.GLFW_PRESS && row.mousePressed(localX, localY, button)) {
                return true;
            }
            if (action == GLFW.GLFW_RELEASE && row.mouseReleased(localX, localY, button)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onContentMoved(double localX, double localY) {
        layout();
        for (PhoneRow row : rows) {
            row.mouseDragged(localX, localY); // 只有被按下的那个控件会收到
        }
    }

    // ==================== 数据与文案 ====================

    private static float sizeToNormalized(float size) {
        return (size - 0.5f) / 0.5f;
    }

    private static float normalizedToSize(double t) {
        return (float) (0.5 + t * 0.5);
    }

    /** 在 20:9 / 9:16 / 4:3 之间循环（保持旧页语义）。 */
    private void cycleAspect() {
        String cur = HuhuTalkClientSettings.getPhoneAspect();
        int idx = ASPECT_OPTIONS.indexOf(cur);
        int next = (idx + 1) % ASPECT_OPTIONS.size();
        HuhuTalkClientSettings.setPhoneAspect(ASPECT_OPTIONS.get(idx < 0 ? 0 : next));
    }
}
