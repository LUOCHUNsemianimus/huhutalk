package com.huhutalk.client.debug;

import com.huhutalk.HuhuTalk;
import com.huhutalk.api.phone.PhoneApp;
import com.huhutalk.api.phone.PhoneAppRegisterEvent;
import com.huhutalk.api.phone.ui.PhoneList;
import com.huhutalk.api.phone.ui.PhonePage;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
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
            ResourceLocation.fromNamespaceAndPath(HuhuTalk.MOD_ID, "textures/huhutalk_symbol.png");

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
        return ResourceLocation.fromNamespaceAndPath(HuhuTalk.MOD_ID, "selftest/" + path);
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
}
