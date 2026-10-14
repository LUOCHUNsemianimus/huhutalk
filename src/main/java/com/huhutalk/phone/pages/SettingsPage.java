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
import com.huhutalk.client.theme.ThemeManager;
import com.huhutalk.network.C2SPotionRescueSettingPacket;
import com.huhutalk.network.C2SRemoteSupplyReplySettingPacket;
import com.huhutalk.network.C2SSOSPacket;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局设置页（底部导航「设置」tab）。
 * <p>
 * 顶栏 #de7e7e、标题「HuhuTalk设置」左对齐；设置项为圆角卡片。
 * SOS 紧急呼叫胶囊置于页面顶部首位。
 * 分组：消息 / 救援 / 动画 / 配色 / 显示。</p>
 *
 * <h2>改用公开骨架（批次 2）</h2>
 * 旧实现 724 行，其中<b>真正的设置内容只有 16 行</b>，其余全是每个页面各抄一遍的样板：
 * 手绘顶栏与 SOS 胶囊、手写 {@code navH()} / {@code rowH()} / {@code textH} / {@code contH} /
 * {@code btnW}、手写 {@code drawScaledText} / {@code truncateUi}、
 * 手写开关（圆角胶囊 + 圆点）、手写滑块（轨道 + 把手）、手写 stepper（▲▼ + 命中）、
 * 手写裁剪配对、以及<b>渲染与命中各算一遍</b>的每一个行坐标
 * （{@code Layout} 里 5 个分组 × 每行的 y，在 {@code mouseClicked} 里靠
 * {@code hit(relX, ly, rectX, L.xxxRow[i], rectW, rowH())} 再写一遍）。
 * <p>
 * 现在：
 * <ul>
 *   <li>顶栏 / 底色 / 滚动 / 裁剪 / 主题取色 → {@link PhonePage}；</li>
 *   <li>每一行「标签 + 右侧控件」→ {@link PhoneRow}
 *       （行高、控件矩形、命中都是同一份计算的产物）；</li>
 *   <li>开关 → {@link PhoneSwitch}、增减 → {@link PhoneStepper}、滑块 → {@link PhoneSlider}；</li>
 *   <li>「切换 / 选壳」小胶囊 → 本文件内的 {@link CapsuleButton}（一个 {@link PhoneWidget}）。</li>
 * </ul>
 * <b>两个 {@code dragging} 布尔量消失了</b>：谁在被拖动由 {@link PhoneRow} 代管，
 * 落盘时机由 {@code PhoneSlider#onCommit} 承担。
 *
 * <h2>与旧实现的差异（刻意接受）</h2>
 * <ul>
 *   <li>页面底色 / 分组标题色 / 分隔线色改走主题色板（略有色差）；</li>
 *   <li>行高统一为 {@code sh(28)}（{@link PhoneRow#rowHeight(int)}）。旧实现是 {@code sh(24)}，
 *       但 {@link PhoneRow} 的滑块行要求 ≥27px 轨道才不会切过标签（见 {@link #ROW_H}）；</li>
 *   <li>每字耗时 / 单句上限改用 <b>离散档位</b>（50ms / 100ms 一档，上限 3000 / 10000ms），
 *       旧实现是无限 +50 —— 档位表必须有上界，而默认值 500 / 2400 都落在网格上；</li>
 *   <li>字体倍率档位仍是 0.5 / 0.75 / 1.0 / 1.5 / 2.0（与 {@code HuhuTalkClientSettings} 一致）。</li>
 * </ul>
 */
public class SettingsPage extends PhonePage {

    // ==================== 常量（基准像素，随全局字体倍率缩放） ====================

    /** 卡片左右边距 */
    private static final int CARD_PAD = 10;
    /** 行内左右内边距（与 {@link PhoneRow} 的默认值一致，显式写出以免依赖默认值） */
    private static final int ROW_PAD = 12;
    /**
     * 统一行高（基准像素）。
     * <p>
     * 旧实现就是所有行等高（{@code sh(24)}），所以「开关 / stepper / 滑块 / 按钮」四种控件
     * 混在一起看着是齐的。{@link PhoneRow} 默认按内容自动算，会得到 4 种不同行高
     * （开关 8px、按钮 8px、stepper 18px、滑块另起一行 13px），一页里"每行都不一样高"。
     * <p>
     * ⚠ 这里用 <b>28</b> 而不是旧值 24：滑块行的最小无碰撞高度是
     * {@code 上留白 5 + 标签 9 + 滑块控件 13 = 27}（控件贴底），
     * 取 24 会让 3px 的轨道正好横穿标签文字。28 留了 1px 余量。
     */
    private static final int ROW_H = 28;
    /** 分组标题的左边距 */
    private static final int TITLE_PAD = 12;
    /** 卡片圆角 */
    private static final int CARD_R = 8;
    /** 内容区顶部留白 */
    private static final int TOP_PAD = 8;
    /** SOS 胶囊高度 */
    private static final int SOS_H = 26;
    /** 分组标题 → 卡片顶 */
    private static final int TITLE_GAP = 6;
    /** 卡片底 → 下一个分组标题 */
    private static final int SECTION_GAP = 10;
    /** 卡片内分隔线距卡片左右边（与内容留白对齐） */
    private static final int DIVIDER_PAD = 8;
    /** 分组标题缩放 */
    private static final float GROUP_SCALE = 0.75f;
    /** 滚动速度：1 单位滚轮 = 多少逻辑像素（保持旧实现的值） */
    private static final int SCROLL_SPEED = 20;
    /** 底部留白：让最后一行能滑出悬浮的底部导航胶囊 */
    private static final int BOTTOM_PAD = 30;

    /** 顶栏标题（左对齐，见 {@link #renderTopBarExtras}） */
    private static final String TITLE = "HuhuTalk设置";

    /** 屏幕比例可选值 */
    private static final List<String> ASPECT_OPTIONS = List.of("20:9", "9:16", "4:3");
    /** 字体倍率可选档位（与 {@code HuhuTalkClientSettings} 的档位表一致） */
    private static final double[] FONT_SCALE_STEPS = {0.5, 0.75, 1.0, 1.5, 2.0};
    /** 每字耗时档位：50ms 起、50ms 一档。上限只为给 stepper 一个有限档位表，远超实用范围 */
    private static final double[] CHAR_TYPING_STEPS = range(50, 3000, 50);
    /** 单句上限档位：100ms 起、100ms 一档 */
    private static final double[] MAX_TYPING_STEPS = range(100, 10000, 100);

    /** 生成等差数列，供 {@code PhoneStepper#steps} 使用。 */
    private static double[] range(double min, double max, double step) {
        int count = Math.max(1, (int) Math.floor((max - min) / step) + 1);
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            out[i] = min + i * step;
        }
        return out;
    }

    // ==================== 控件 ====================
    //
    // 控件持有自己的状态，所以必须每页只建一次（字段级），不能每帧新建 —— 否则
    // 拖动中的捕获、stepper 的档位下标都会每帧丢失。

    private final PhoneSwitch dndSwitch = new PhoneSwitch();
    private final PhoneSwitch whisperSwitch = new PhoneSwitch();
    private final PhoneSwitch immersiveSwitch = new PhoneSwitch();
    private final PhoneSwitch potionRescueSwitch = new PhoneSwitch();
    private final PhoneSwitch supplyReplySwitch = new PhoneSwitch();
    private final PhoneSwitch messageAnimSwitch = new PhoneSwitch();
    private final PhoneSwitch uiAnimSwitch = new PhoneSwitch();

    private final PhoneStepper charTypingStepper = new PhoneStepper();
    private final PhoneStepper maxTypingStepper = new PhoneStepper();
    private final PhoneStepper fontScaleStepper = new PhoneStepper();

    private final PhoneSlider phoneSizeSlider = new PhoneSlider();
    private final PhoneSlider brightnessSlider = new PhoneSlider();

    /** 分组（标题 + 行）。构造时建好，之后每帧只重新 layout。 */
    private final List<Section> sections;

    /** 一个分组：标题 + 若干「标签 + 控件」行。 */
    private record Section(String title, List<PhoneRow> rows) {
    }

    public SettingsPage() {
        buildWidgets();
        sections = buildSections();
    }

    /**
     * 每次打开设置页都把两个服务端判定用的偏好同步一次。
     * <p>服务端只在会话内缓存它们（见 {@code RescueSettings} / {@code RemoteSupplySettings}），
     * 所以重连后必须重新告知；这里同时兜住"登录同步包因故没发出去"的极端情况 ——
     * 玩家只要开过设置页就一定同步过。
     */
    @Override
    public void onOpen() {
        PacketDistributor.sendToServer(
                new C2SPotionRescueSettingPacket(HuhuTalkClientSettings.isPotionRescueEnabled()));
        PacketDistributor.sendToServer(
                new C2SRemoteSupplyReplySettingPacket(HuhuTalkClientSettings.isRemoteSupplyReplyEnabled()));
    }

    // ==================== 骨架钩子 ====================

    /**
     * 顶栏不写居中标题：本页是底部导航的顶层页，标题按内置页面惯例<b>左对齐</b>，
     * 所以交给 {@link #renderTopBarExtras} 画（骨架的 {@code title()} 是居中的）。
     */
    @Override
    protected String title() {
        return "";
    }

    /** 顶层 tab 页没有「返回上一页」这回事（切 tab 由底部导航负责）。 */
    @Override
    protected boolean showBackButton() {
        return false;
    }

    @Override
    protected int pageBackground() {
        return colors().pageBgList();
    }

    /**
     * 顶栏显式锁成品牌色：以后新增配色方案时，本页仍与聊天 / 消息 / 联系人是同一台手机
     * （旧实现硬编码 {@code 0xFFDE7E7E}）。
     */
    @Override
    protected int topBarBackground() {
        return BRAND_TOPBAR_BG;
    }

    /** 底部导航是悬浮层，内容滚到底时最后一行会被盖住 —— 留一段可滚动余量。 */
    @Override
    protected int bottomInset() {
        return PhoneOverlay.getBottomNavHeight() + PhoneText.px(BOTTOM_PAD);
    }

    /** 保持旧实现的滚动速度。 */
    @Override
    protected double scrollSpeed() {
        return SCROLL_SPEED;
    }

    @Override
    protected void renderTopBarExtras(GuiGraphics graphics, int baseX, int baseY, int w, int barH, int textY) {
        PhoneText.draw(graphics, font(), TITLE, baseX + PhoneText.px(10), textY,
                colors().topBarText(), PhoneText.scale(1.0f));
    }

    // ==================== 控件与分组构建 ====================

    private void buildWidgets() {
        dndSwitch.on(HuhuTalkClientSettings.isDndEnabled())
                .onChange(HuhuTalkClientSettings::setDndEnabled);
        whisperSwitch.on(HuhuTalkClientSettings.isMaidWhisperEnabled())
                .onChange(HuhuTalkClientSettings::setMaidWhisperEnabled);
        immersiveSwitch.on(HuhuTalkClientSettings.isImmersiveChatEnabled())
                .onChange(HuhuTalkClientSettings::setImmersiveChatEnabled);

        // 救援的两个开关判定都在服务端（死亡瞬间 / 补给链路），所以拨动后必须立刻同步
        potionRescueSwitch.on(HuhuTalkClientSettings.isPotionRescueEnabled())
                .onChange(v -> {
                    HuhuTalkClientSettings.setPotionRescueEnabled(v);
                    PacketDistributor.sendToServer(new C2SPotionRescueSettingPacket(v));
                });
        supplyReplySwitch.on(HuhuTalkClientSettings.isRemoteSupplyReplyEnabled())
                .onChange(v -> {
                    HuhuTalkClientSettings.setRemoteSupplyReplyEnabled(v);
                    PacketDistributor.sendToServer(new C2SRemoteSupplyReplySettingPacket(v));
                });

        messageAnimSwitch.on(HuhuTalkClientSettings.isMessageAnimationEnabled())
                .onChange(HuhuTalkClientSettings::setMessageAnimation);
        uiAnimSwitch.on(HuhuTalkClientSettings.isUiAnimationEnabled())
                .onChange(HuhuTalkClientSettings::setUiAnimation);

        charTypingStepper.steps(CHAR_TYPING_STEPS)
                .setValue(HuhuTalkClientSettings.getCharTypingMs())
                .onChange(v -> HuhuTalkClientSettings.setCharTypingMs(v.longValue()));
        maxTypingStepper.steps(MAX_TYPING_STEPS)
                .setValue(HuhuTalkClientSettings.getMaxTypingMs())
                .onChange(v -> HuhuTalkClientSettings.setMaxTypingMs(v.longValue()));
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

    private List<Section> buildSections() {
        List<Section> out = new ArrayList<>();

        out.add(new Section("消息", List.of(
                row("消息免打扰", dndSwitch),
                row("女仆悄悄话", whisperSwitch),
                row("沉浸式聊天", immersiveSwitch))));

        out.add(new Section("救援", List.of(
                row("药水复活", potionRescueSwitch),
                row("远程补给女仆回复", supplyReplySwitch))));

        out.add(new Section("动画", List.of(
                row("消息动画", messageAnimSwitch),
                row(() -> "每次耗时(ms)：" + (long) charTypingStepper.value(), charTypingStepper),
                row(() -> "单句上限(ms)：" + (long) maxTypingStepper.value(), maxTypingStepper),
                row("切屏动画", uiAnimSwitch))));

        out.add(new Section("配色", List.of(
                row(() -> "配色方案：" + ThemeManager.getActive().displayName(),
                        new CapsuleButton("切换", ThemeManager::cycleNext)),
                row(this::phoneCaseLabel,
                        new CapsuleButton("选壳", () -> PhoneOverlay.pushPage(new PhoneCasePickerPage()))))));

        out.add(new Section("显示", List.of(
                row(() -> "屏幕比例：" + HuhuTalkClientSettings.getPhoneAspect(),
                        new CapsuleButton("切换", this::cycleAspect)),
                row(() -> "屏幕大小：" + Math.round(HuhuTalkClientSettings.getPhoneSize() * 100) + "%",
                        phoneSizeSlider),
                row(() -> "字体倍率：×" + fontScaleStepper.display(), fontScaleStepper),
                row(() -> "屏幕亮度：" + Math.round(HuhuTalkClientSettings.getScreenBrightness() * 100) + "%",
                        brightnessSlider),
                row(() -> "HuhuTalk图标：" + iconStyleLabel(),
                        new CapsuleButton("切换", this::cycleIconStyle)))));

        return List.copyOf(out);
    }

    private static PhoneRow row(String label, PhoneWidget widget) {
        return PhoneRow.of(label, widget).pad(ROW_PAD).rowHeight(ROW_H);
    }

    private static PhoneRow row(java.util.function.Supplier<String> label, PhoneWidget widget) {
        return PhoneRow.of(label, widget).pad(ROW_PAD).rowHeight(ROW_H);
    }

    // ==================== 布局 ====================

    /**
     * 每帧的布局结果。<b>渲染与命中都调 {@link #layout()} 拿它</b>，
     * 于是「行画在哪」与「点在哪算哪一行」在结构上不可能不同步。
     * <p>
     * 所有 y 都是<b>内容坐标（已含滚动）</b>：{@link PhonePage#renderContent} 的
     * {@code originY} 加上它即屏幕坐标；而点击回调拿到的 {@code localY} 恰好就是同一个值
     * （依据 {@code scrollY(logicalY(localY)) == localY}）。
     */
    private static final class Layout {
        /** 各分组标题的 y（内容坐标） */
        final List<Integer> titleYs = new ArrayList<>();
        /** 各分组卡片的矩形（内容坐标）：{x, y, w, h} */
        final List<int[]> cards = new ArrayList<>();
        /** 内容逻辑总高（不含滚动） */
        int totalH;
    }

    private Layout layout() {
        Font font = font();
        int cardX = PhoneText.px(CARD_PAD);
        int cardW = width() - PhoneText.px(CARD_PAD) * 2;

        Layout out = new Layout();
        // 逻辑游标（未滚动），只在算高度用；写进 PhoneRow 的 y 要换算成内容坐标
        int y = sosY() + sosH() + PhoneText.px(SECTION_GAP);

        for (Section section : sections) {
            out.titleYs.add(scrollY(y));
            y += PhoneText.height(font, PhoneText.scale(GROUP_SCALE)) + PhoneText.px(TITLE_GAP);

            int cardTop = y;
            for (PhoneRow row : section.rows()) {
                row.layout(font, cardX, scrollY(y), cardW);
                y += row.height();
            }
            out.cards.add(new int[]{cardX, scrollY(cardTop), cardW, y - cardTop});
            y += PhoneText.px(SECTION_GAP);
        }
        out.totalH = y;
        return out;
    }

    @Override
    protected int contentHeight() {
        return layout().totalH;
    }

    // ==================== SOS 胶囊 ====================
    //
    // 它是「整条一行、底色是危险红」的胶囊，不符合 PhoneRow 的「左标签 + 右控件」形态，
    // 所以自绘。渲染与命中共用下面三个几何方法（而不是各写一遍算术）。

    private int sosX() {
        return PhoneText.px(CARD_PAD);
    }

    private int sosW() {
        return width() - PhoneText.px(CARD_PAD) * 2;
    }

    private int sosH() {
        return PhoneText.px(SOS_H);
    }

    private int sosY() {
        return PhoneText.px(TOP_PAD);
    }

    // ==================== 渲染 ====================

    @Override
    protected void renderContent(GuiGraphics g, int originX, int originY, int w, int h, float partialTick) {
        syncFromSettings();
        Layout L = layout();
        ColorScheme c = colors();
        Font font = font();

        // SOS 紧急呼叫胶囊（页面顶部首位）
        PhoneShapes.fillRoundedRect(g, originX + sosX(), originY + scrollY(sosY()),
                sosW(), sosH(), sosH() / 2, c.sosBg());
        PhoneText.drawLeftIn(g, font, "🚨 SOS 紧急呼叫",
                originX + sosX(), originY + scrollY(sosY()), sosW(), sosH(),
                PhoneText.px(ROW_PAD), c.sosText(), PhoneText.scale(1.0f));

        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            PhoneText.draw(g, font, section.title(),
                    originX + PhoneText.px(TITLE_PAD), originY + L.titleYs.get(i),
                    c.textMuted(), PhoneText.scale(GROUP_SCALE));

            int[] card = L.cards.get(i);
            PhoneShapes.fillRoundedRect(g, originX + card[0], originY + card[1],
                    card[2], card[3], PhoneText.px(CARD_R), c.cardBg());

            List<PhoneRow> rows = section.rows();
            for (int r = 0; r < rows.size(); r++) {
                PhoneRow row = rows.get(r);
                if (r < rows.size() - 1) {
                    int dy = originY + row.y() + row.height();
                    g.fill(originX + card[0] + PhoneText.px(DIVIDER_PAD), dy,
                            originX + card[0] + card[2] - PhoneText.px(DIVIDER_PAD), dy + 1,
                            c.divider());
                }
                row.render(g, originX, originY);
            }
        }
    }

    /**
     * 把控件状态对齐到配置值。
     * <p>
     * 旧实现每帧都直接从 {@code HuhuTalkClientSettings} 读值来画，所以"别处改了配置，设置页立刻反映"。
     * 控件持有自己的状态，于是每帧同步一次来保住这个性质
     * （{@code setValue} 不触发回调，不会反写配置）。
     * <p>
     * ⚠ 拖动中的滑块<b>不同步</b>：拖动值先写进配置再读回来会有取整往返，
     * 会造成把手抖动。松手（{@code onCommit}）之后下一帧自然对上。
     */
    private void syncFromSettings() {
        dndSwitch.setValue(HuhuTalkClientSettings.isDndEnabled());
        whisperSwitch.setValue(HuhuTalkClientSettings.isMaidWhisperEnabled());
        immersiveSwitch.setValue(HuhuTalkClientSettings.isImmersiveChatEnabled());
        potionRescueSwitch.setValue(HuhuTalkClientSettings.isPotionRescueEnabled());
        supplyReplySwitch.setValue(HuhuTalkClientSettings.isRemoteSupplyReplyEnabled());
        messageAnimSwitch.setValue(HuhuTalkClientSettings.isMessageAnimationEnabled());
        uiAnimSwitch.setValue(HuhuTalkClientSettings.isUiAnimationEnabled());

        charTypingStepper.setValue(HuhuTalkClientSettings.getCharTypingMs());
        maxTypingStepper.setValue(HuhuTalkClientSettings.getMaxTypingMs());
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

        if (action == GLFW.GLFW_PRESS
                && hit(sosX(), scrollY(sosY()), sosW(), sosH(), localX, localY)) {
            PacketDistributor.sendToServer(new C2SSOSPacket());
            return true;
        }
        for (Section section : sections) {
            for (PhoneRow row : section.rows()) {
                if (action == GLFW.GLFW_PRESS && row.mousePressed(localX, localY, button)) {
                    return true;
                }
                if (action == GLFW.GLFW_RELEASE && row.mouseReleased(localX, localY, button)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected void onContentMoved(double localX, double localY) {
        layout();
        for (Section section : sections) {
            for (PhoneRow row : section.rows()) {
                row.mouseDragged(localX, localY); // 只有被按下的那个控件会收到
            }
        }
    }

    // ==================== 数据与文案 ====================

    private static float sizeToNormalized(float size) {
        return (size - 0.5f) / 0.5f;
    }

    private static float normalizedToSize(double t) {
        return (float) (0.5 + t * 0.5);
    }

    /** 手机壳行：物品 id 可能很长，在留出右侧「选壳」按钮的前提下截断。 */
    private String phoneCaseLabel() {
        String id = HuhuTalkClientSettings.getPhoneCaseBlock();
        String text = id.isBlank() ? "手机壳：默认" : "手机壳：" + shortName(id);
        Font font = font();
        int buttonW = PhoneText.width(font, "选壳", PhoneText.scale(CapsuleButton.TEXT_SCALE))
                + PhoneText.px(10);
        int avail = width() - PhoneText.px(CARD_PAD) * 2 - PhoneText.px(ROW_PAD) * 2
                - buttonW - PhoneText.px(8);
        return PhoneText.truncate(font, text, Math.max(0, avail), PhoneText.scale(1.0f));
    }

    private static String shortName(String id) {
        int idx = id.indexOf(':');
        return idx >= 0 ? id.substring(idx + 1) : id;
    }

    /** 在 20:9 / 9:16 / 4:3 之间循环。 */
    private void cycleAspect() {
        String cur = HuhuTalkClientSettings.getPhoneAspect();
        int idx = ASPECT_OPTIONS.indexOf(cur);
        int next = (idx + 1) % ASPECT_OPTIONS.size();
        HuhuTalkClientSettings.setPhoneAspect(ASPECT_OPTIONS.get(idx < 0 ? 0 : next));
    }

    /** 图标风格显示名 */
    private static String iconStyleLabel() {
        return switch (HuhuTalkClientSettings.getHuhutalkIconStyle()) {
            case "symbol" -> "低清图标";
            case "fox" -> "狐狸emoji";
            default -> "高清图标";
        };
    }

    /** 在 高清图标 / 低清图标 / 狐狸emoji 三档间循环 */
    private void cycleIconStyle() {
        String cur = HuhuTalkClientSettings.getHuhutalkIconStyle();
        String next = switch (cur) {
            case "symbol" -> "fox";
            case "fox" -> "symbol2";
            default -> "symbol";
        };
        HuhuTalkClientSettings.setHuhutalkIconStyle(next);
    }

    // ==================== 行内小胶囊按钮 ====================

    /**
     * 行右侧的小胶囊按钮（「切换」/「选壳」）。
     * <p>
     * 它替代了旧实现里的 {@code btnW()} / {@code contH()} / {@code capsuleBtn()} /
     * {@code hitButton()} 四段算术 —— 宽度与高度由控件自报，矩形由 {@link PhoneRow} 摆，
     * 命中由 {@link PhoneWidget#hit} 判，三者是同一份数据。
     */
    private static final class CapsuleButton extends PhoneWidget {

        /** 按钮文字相对字号（与旧实现 {@code BTN_TEXT_SCALE} 一致） */
        static final float TEXT_SCALE = 0.7f;
        /** 按钮高度占字行高的比例（与旧实现 {@code contH} 一致） */
        private static final float BOX_SCALE = 0.9f;
        /** 文字两侧留白 */
        private static final int PAD_PX = 10;

        private final String text;
        private final Runnable action;

        CapsuleButton(String text, Runnable action) {
            this.text = text;
            this.action = action;
        }

        @Override
        public int preferredWidth(Font font) {
            return PhoneText.width(font, text, PhoneText.scale(TEXT_SCALE)) + PhoneText.px(PAD_PX);
        }

        @Override
        public int preferredHeight(Font font) {
            return Math.max(1, PhoneText.height(font, PhoneText.scale(BOX_SCALE)));
        }

        @Override
        public void render(GuiGraphics g, int originX, int originY) {
            int w = width();
            int h = height();
            if (w <= 0 || h <= 0) {
                return;
            }
            int x = originX + x();
            int y = originY + y();
            // 取主题强调色（与旧实现硬编码的 #de7e7e 同值），换配色方案时会跟着变
            PhoneShapes.fillRoundedRect(g, x, y, w, h, h / 2, ThemeManager.getActive().accent());
            PhoneText.drawCenteredIn(g, Minecraft.getInstance().font, text, x, y, w, h,
                    0xFFFFFFFF, PhoneText.scale(TEXT_SCALE));
        }

        @Override
        public boolean mousePressed(double px, double py, int button) {
            if (!enabled() || !isPrimaryPress(button) || !hit(px, py)) {
                return false;
            }
            action.run();
            return true;
        }
    }
}
