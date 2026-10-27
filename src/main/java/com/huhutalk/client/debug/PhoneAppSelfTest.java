package com.huhutalk.client.debug;

import com.huhutalk.HuhuTalk;
import com.huhutalk.api.phone.PhoneApp;
import com.huhutalk.api.phone.PhoneAppRegisterEvent;
import com.huhutalk.api.phone.ui.PhoneDropdown;
import com.huhutalk.api.phone.ui.PhoneImages;
import com.huhutalk.api.phone.ui.PhoneList;
import com.huhutalk.api.phone.ui.PhonePage;
import com.huhutalk.api.phone.ui.PhoneRow;
import com.huhutalk.api.phone.ui.PhoneSlider;
import com.huhutalk.api.phone.ui.PhoneStepper;
import com.huhutalk.api.phone.ui.PhoneSwitch;
import com.huhutalk.api.phone.ui.PhoneText;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌面 App 公开注册 API 的<b>自检</b>：用 API 自己注册两个 App，验证整条通路。
 *
 * <h2>怎么开</h2>
 * 加 JVM 参数 {@code -Dhuhutalk.debugApps=true}（或环境变量 {@code HUHUTALK_DEBUG_APPS=true}）启动客户端，
 * 然后打开手机桌面，会多出四个图标：
 * <ul>
 *   <li><b>自检·文字</b>：走 {@link PhoneApp.Builder#center} 文字/emoji 通路</li>
 *   <li><b>自检·贴图</b>：走 {@link PhoneApp.Builder#icon} 贴图通路（复用 HuhuTalk 自己的图标贴图，不新增资源）</li>
 *   <li><b>自检·骨架</b>：页面基于 {@link PhonePage} + {@link PhoneList}（列表自带滚动，
 *       验证顶栏 / 返回键 / 裁剪 / 悬停 / 行点击命中）</li>
 *   <li><b>自检·滚动</b>：页面基于 {@link PhonePage} 自管滚动（验证 {@code scrollY} / {@code logicalY} 互逆换算）</li>
 * </ul>
 * 四个都点得开、右键都能返回桌面、两个骨架页都能滚能点，就说明
 * 「注册 → 排布 → 渲染 → 命中 → 进页面」与页面骨架全链路正常。
 *
 * <h2>为什么放进正式代码里</h2>
 * 附属模组的作者接入时，最常见的失败是「注册了但图标不出现」，而这类问题的日志往往不可读。
 * 留一个开箱可复现的对照物，比事后猜快得多。默认<b>关闭</b>，对玩家不可见。
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class PhoneAppSelfTest {

    /** 开启自检的系统属性名：{@code -Dhuhutalk.debugApps=true} */
    public static final String ENABLED_PROPERTY = "huhutalk.debugApps";
    /**
     * 开启自检的环境变量名：{@code HUHUTALK_DEBUG_APPS=true}。
     * <p>
     * 和系统属性二选一即可——IDE 的运行配置里改环境变量比改 JVM 参数方便，
     * 而 {@code gradlew runClient} 那种场景直接给 {@code -D} 更顺手。
     */
    public static final String ENABLED_ENV = "HUHUTALK_DEBUG_APPS";

    private static final boolean ENABLED = resolveEnabled();

    /** 自检贴图图标复用 HuhuTalk 自己的 256×256 图标，避免为自检新增资源 */
    private static final ResourceLocation SAMPLE_ICON =
            new net.minecraft.resources.ResourceLocation(HuhuTalk.MOD_ID, "textures/huhutalk_symbol.png");

    private PhoneAppSelfTest() {
    }

    @SubscribeEvent
    public static void onRegisterPhoneApps(PhoneAppRegisterEvent event) {
        if (!ENABLED) {
            return;
        }
        HuhuTalk.LOGGER.info("[HuhuTalk/PhoneApp] 自检已开启（-D{}=true 或 {}），注册四个测试 App",
                ENABLED_PROPERTY, ENABLED_ENV);

        event.register(PhoneApp.builder(id("selftest_text"), "自检·文字")
                .color(0xFF4CAF50)
                .center("API", 1.0f, 0xFFFFFFFF)
                .page(() -> new SelfTestPage("文字图标通路 OK"))
                .order(100)
                .build());

        event.register(PhoneApp.builder(id("selftest_texture"), "自检·贴图")
                .color(0xFF2196F3)
                .icon(SAMPLE_ICON, 256, 256)
                .page(() -> new SelfTestPage("贴图图标通路 OK"))
                .order(101)
                .build());

        event.register(PhoneApp.builder(id("selftest_skeleton"), "自检·骨架")
                .color(0xFF7E57C2)
                .center("UI", 1.0f, 0xFFFFFFFF)
                .page(SkeletonTestPage::new)
                .order(102)
                .build());

        event.register(PhoneApp.builder(id("selftest_scroll"), "自检·滚动")
                .color(0xFF26A69A)
                .center("⇅", 2.0f, 0xFFFFFFFF)
                .page(ScrollTestPage::new)
                .order(103)
                .build());

        event.register(PhoneApp.builder(id("selftest_widgets"), "自检·控件")
                .color(0xFFEF6C00)
                .center("▤", 2.0f, 0xFFFFFFFF)
                .page(WidgetTestPage::new)
                .order(104)
                .build());

        event.register(PhoneApp.builder(id("selftest_images"), "自检·图片")
                .color(0xFF5C6BC0)
                .center("▣", 2.0f, 0xFFFFFFFF)
                .page(ImageTestPage::new)
                .order(105)
                .build());
    }

    /** 系统属性优先，其次环境变量；两者都没有（或显式 false）则关闭。 */
    private static boolean resolveEnabled() {
        if (Boolean.getBoolean(ENABLED_PROPERTY)) {
            return true;
        }
        String env = System.getenv(ENABLED_ENV);
        return env != null && !env.isBlank() && !"false".equalsIgnoreCase(env.trim());
    }

    private static ResourceLocation id(String path) {
        return new net.minecraft.resources.ResourceLocation(HuhuTalk.MOD_ID, "selftest/" + path);
    }

    /** 自检页面：只显示一行说明。右键由 PhoneOverlay 默认逻辑弹回桌面。 */
    private static final class SelfTestPage implements IPhonePage {
        private final String message;

        SelfTestPage(String message) {
            this.message = message;
        }

        @Override
        public void render(GuiGraphics graphics, int baseX, int baseY, float partialTick) {
            int w = PhoneOverlay.getPhoneWidth();
            int h = PhoneOverlay.getPhoneHeight();
            graphics.fill(baseX, baseY, baseX + w, baseY + h, 0xFF101418);
            Font font = Minecraft.getInstance().font;
            graphics.drawCenteredString(font, message, baseX + w / 2, baseY + h / 3, 0xFFFFFFFF);
            graphics.drawCenteredString(font, "右键返回桌面", baseX + w / 2, baseY + h / 3 + 16, 0xFF9AA0A6);
        }
    }

    /**
     * 页面骨架自检：基于 {@link PhonePage} + {@link PhoneList} 写一个 40 行的可滚动列表。
     * <p>
     * 它同时验证四件事：
     * <ol>
     *   <li>顶栏与「‹ 返回」是骨架自己画的，点它应能返回桌面</li>
     *   <li>滚轮能滚（40 行 × 22px 远高于视口），且滚到两端会停住</li>
     *   <li>列表被正确裁剪在内容区内，不会画到顶栏上</li>
     *   <li><b>点哪行就是哪行</b>——渲染与命中共用 {@link PhoneList#itemY(int)} /
     *       {@link PhoneList#indexAt(double, double)} 这一对互逆映射，滚动后依然对得上</li>
     * </ol>
     * 点中某行后，行名会显示在顶栏右侧。
     */
    private static final class SkeletonTestPage extends PhonePage {
        /** 假数据：40 行，够高才滚得起来 */
        private static final int ROW_COUNT = 40;
        private static final int ROW_HEIGHT = 22;

        private final List<String> rows = new ArrayList<>();
        private String clickedRow = null;

        private final PhoneList<String> list = new PhoneList<>() {
            @Override
            protected List<String> items() {
                return rows;
            }

            @Override
            protected int itemHeight() {
                return ROW_HEIGHT;
            }

            @Override
            protected void renderItem(GuiGraphics g, int index, String item,
                                      int x, int y, int w, int h, boolean hovered) {
                if (hovered) {
                    g.fill(x, y, x + w, y + h, 0x22FFFFFF);
                }
                g.drawString(Minecraft.getInstance().font, item, x + 8, y + (h - 8) / 2, 0xFFFFFFFF);
            }

            @Override
            protected void onItemClick(int index, String item) {
                clickedRow = item;
            }
        };

        SkeletonTestPage() {
            for (int i = 1; i <= ROW_COUNT; i++) {
                rows.add("第 " + i + " 行 · 骨架列表自检");
            }
        }

        @Override
        protected String title() {
            return "骨架自检";
        }

        @Override
        protected void renderTopBarExtras(GuiGraphics g, int baseX, int baseY, int w, int barH, int textY) {
            String right = clickedRow == null ? "点一行试试" : clickedRow;
            Font font = Minecraft.getInstance().font;
            g.drawString(font, right, baseX + w - font.width(right) - 4, textY, 0xFFFFFFFF);
        }

        @Override
        protected void renderContent(GuiGraphics g, int originX, int originY, int w, int h, float partialTick) {
            // 视口 = 整个内容区。布局只写这一处，命中用的是同一份值。
            list.setViewport(0, 0, w, h);
            list.render(g, originX, originY);
        }

        @Override
        protected boolean onContentClick(double localX, double localY, int button, int action) {
            return list.mouseClicked(localX, localY, button, action);
        }

        @Override
        protected void onContentMoved(double localX, double localY) {
            list.mouseMoved(localX, localY);
        }

        @Override
        public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
            // 顶栏区域不滚；内容区交给列表自己滚（页面的 scrollOffset 因此保持 0）
            if (relY < contentTop()) {
                return false;
            }
            return list.mouseScrolled(scrollDelta);
        }
    }

    /**
     * 页面自滚自检：<b>不用</b> {@link PhoneList}，让 {@link PhonePage} 自己管滚动
     * （{@link PhonePage#contentHeight()} + {@link PhonePage#scrollY(int)}）。
     * <p>
     * 这条通路适合「分组 + 卡片 + 行」混合排版、用不上统一行高列表的页面。
     * <p>
     * 重点验证：渲染走 {@code scrollY(logical)}、命中走 {@code logicalY(local)}，
     * 这一对互逆换算在滚到任何位置时都应点得准。
     */
    private static final class ScrollTestPage extends PhonePage {
        private static final int ROWS = 40;
        private static final int ROW_H = 22;

        private int clickedRow = -1;

        @Override
        protected String title() {
            return "滚动自检";
        }

        @Override
        protected int contentHeight() {
            return ROWS * ROW_H;
        }

        @Override
        protected void renderTopBarExtras(GuiGraphics g, int baseX, int baseY, int w, int barH, int textY) {
            String right = clickedRow < 0 ? "点一行试试" : ("第 " + clickedRow + " 行");
            Font font = Minecraft.getInstance().font;
            g.drawString(font, right, baseX + w - font.width(right) - 4, textY, 0xFFFFFFFF);
        }

        @Override
        protected void renderContent(GuiGraphics g, int originX, int originY, int w, int h, float partialTick) {
            Font font = Minecraft.getInstance().font;
            int textOffset = (ROW_H - font.lineHeight) / 2;
            for (int i = 0; i < ROWS; i++) {
                int y = scrollY(i * ROW_H);  // ★ 逻辑 y → 内容坐标
                if (y + ROW_H < 0 || y > h) {
                    continue;                // 视口外跳过
                }
                g.drawString(font, "第 " + (i + 1) + " 行 · 页面自滚",
                        originX + 8, originY + y + textOffset, 0xFFFFFFFF);
            }
        }

        @Override
        protected boolean onContentClick(double localX, double localY, int button, int action) {
            if (action != GLFW.GLFW_PRESS || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                return false;
            }
            int logical = logicalY(localY);  // ★ 内容坐标 → 逻辑 y（scrollY 的逆运算）
            int index = logical / ROW_H;
            if (index < 0 || index >= ROWS) {
                return false;
            }
            if (!hit(0, scrollY(index * ROW_H), width(), ROW_H, localX, localY)) {
                return false;
            }
            clickedRow = index + 1;
            return true;
        }
    }

    /**
     * 控件自检：{@link PhoneSwitch} / {@link PhoneStepper} / {@link PhoneSlider} / {@link PhoneDropdown}
     * 四种控件各来一个，全部挂在 {@link PhoneRow} 上，页面本身可滚动。
     * <p>
     * 判定：
     * <ol>
     *   <li>开关能拨；</li>
     *   <li>▲▼ 能加减，标签里的数值同步变；</li>
     *   <li>滑块能<b>按住在轨道上拖动</b>（拖动中显示"拖动中 x%"，松手才显示"落定 x%"）；</li>
     *   <li>下拉能点开、能滚轮选择、下面的行会整体下移；</li>
     *   <li>操作结果实时显示在最后一行。</li>
     * </ol>
     * 同时验证滚动：行数够多时页面可滚，而控件跟着一起滚且<b>依然点得准</b>。
     */
    private static final class WidgetTestPage extends PhonePage {

        private final List<PhoneRow> rows = new ArrayList<>();
        private String status = "操作上面的控件试试";
        /** 各行总高，供 {@link #contentHeight()} 用（由 {@link #layoutRows()} 维护） */
        private int totalHeight = 0;

        private final PhoneStepper stepper = new PhoneStepper()
                .steps(1, 2, 3, 4, 5)
                .setValue(3)
                .onChange(v -> status = "增减 → " + num(v));

        private final PhoneSlider slider = new PhoneSlider()
                .setValue(0.4)
                .onChange(t -> status = "拖动中 " + Math.round(t * 100) + "%")
                .onCommit(t -> status = "滑块落定 → " + Math.round(t * 100) + "%");

        WidgetTestPage() {
            PhoneSwitch switchWidget = new PhoneSwitch()
                    .on(true)
                    .onChange(v -> status = "开关 → " + (v ? "开" : "关"));
            PhoneDropdown dropdown = new PhoneDropdown()
                    .options("选项 A", "选项 B", "选项 C", "选项 D", "选项 E", "选项 F")
                    .selected(0)
                    .onChange(i -> status = "下拉 → " + i);

            rows.add(PhoneRow.of("开关", switchWidget));
            rows.add(PhoneRow.of(() -> "增减：" + stepper.display(), stepper));
            rows.add(PhoneRow.of(() -> "滑块：" + Math.round(slider.value() * 100) + "%", slider));
            rows.add(PhoneRow.of("下拉选择", dropdown));
            rows.add(PhoneRow.of(() -> status));
        }

        @Override
        protected String title() {
            return "控件自检";
        }

        /** 列表/设置形态的页面用略深一档的底色。 */
        @Override
        protected int pageBackground() {
            return colors().pageBgList();
        }

        @Override
        protected int contentHeight() {
            return totalHeight;
        }

        /** 整数省掉小数点，只用于自检页的状态显示。 */
        private static String num(double v) {
            return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
        }

        /**
         * 自上而下堆行，<b>唯一</b>的布局累加处（渲染前与命中前各调一次，保证一致）。
         * 行的 y 已叠加滚动偏移，所以控件会跟着页面一起滚。
         */
        private void layoutRows() {
            int y = PhoneText.px(6);
            for (PhoneRow row : rows) {
                row.background(colors().cardBg()).layout(font(), 0, scrollY(y), width());
                y += row.height() + PhoneText.px(4);
            }
            totalHeight = y;
        }

        @Override
        protected void renderContent(GuiGraphics g, int originX, int originY, int w, int h, float partialTick) {
            layoutRows();
            for (PhoneRow row : rows) {
                row.render(g, originX, originY);
            }
        }

        @Override
        protected boolean onContentClick(double localX, double localY, int button, int action) {
            layoutRows();
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
            for (PhoneRow row : rows) {
                row.mouseDragged(localX, localY);
            }
        }

        @Override
        public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
            if (relY < contentTop()) {
                return false;
            }
            layoutRows();
            // 下拉展开时先让它吃掉滚轮（否则会一边选项一边滚页面）
            for (PhoneRow row : rows) {
                if (row.mouseScrolled(scrollDelta)) {
                    return true;
                }
            }
            return super.mouseScrolled(relX, relY, scrollDelta);
        }
    }

    /**
     * 图片绘制自检：拿同一张贴图的同一块<b>非正方形</b>子区域，用 {@link PhoneImages} 的四种画法
     * 各画一遍，宽高比处理的差异应当一眼可见：
     * <ul>
     *   <li>{@code draw}：拉伸（会变形）</li>
     *   <li>{@code drawFit}：完整可见、上下留白</li>
     *   <li>{@code drawCover}：铺满并居中裁切（头像用这个）</li>
     *   <li>{@code drawRegion}：只取子区域并拉伸</li>
     * </ul>
     */
    private static final class ImageTestPage extends PhonePage {

        /** 源贴图是 256×256；故意取一个很扁的子区域（256×96）来暴露宽高比差异 */
        private static final int TEX_SIZE = 256;
        private static final int REGION_W = 256;
        private static final int REGION_H = 96;

        @Override
        protected String title() {
            return "图片自检";
        }

        @Override
        protected int pageBackground() {
            return colors().pageBgList();
        }

        @Override
        protected void renderContent(GuiGraphics g, int originX, int originY, int w, int h, float partialTick) {
            Font font = font();
            int gap = PhoneText.px(8);
            int cols = 2;
            int box = Math.max(16, (w - gap * (cols + 1)) / cols);
            int labelH = PhoneText.px(12);
            String[] labels = {"draw 拉伸", "drawFit 留白", "drawCover 裁切", "drawRegion 子区"};

            int top = PhoneText.px(12);
            for (int i = 0; i < labels.length; i++) {
                int col = i % cols;
                int row = i / cols;
                int bx = originX + gap + col * (box + gap);
                int by = originY + top + row * (box + labelH + gap);

                switch (i) {
                    case 0 -> PhoneImages.draw(g, SAMPLE_ICON, bx, by, box, box, TEX_SIZE, TEX_SIZE);
                    case 1 -> PhoneImages.drawFit(g, SAMPLE_ICON, bx, by, box, box, REGION_W, REGION_H);
                    case 2 -> PhoneImages.drawCover(g, SAMPLE_ICON, bx, by, box, box, REGION_W, REGION_H);
                    default -> PhoneImages.drawRegion(g, SAMPLE_ICON, bx, by, box, box,
                            0, 0, REGION_W, REGION_H, TEX_SIZE, TEX_SIZE);
                }
                PhoneText.draw(g, font, labels[i], bx, by + box + PhoneText.px(2),
                        colors().textSecondary(), PhoneText.scale(0.75f));
            }

            PhoneText.draw(g, font, "同为 256×96 源区域：留白 / 裁切 / 拉伸 应明显不同",
                    originX + gap, originY + top + 2 * (box + labelH + gap) + PhoneText.px(4),
                    colors().textMuted(), PhoneText.scale(0.8f));
        }
    }
}
