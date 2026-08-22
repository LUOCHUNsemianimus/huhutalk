package com.huhutalk.phone;

import com.huhutalk.HuhuTalk;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.client.theme.ColorScheme;
import com.huhutalk.client.theme.PhoneCaseRenderer;
import com.huhutalk.client.theme.ThemeManager;
import com.huhutalk.phone.pages.ContactListPage;
import com.huhutalk.phone.pages.MainPage;
import com.huhutalk.phone.pages.MessageListPage;
import com.huhutalk.phone.pages.SettingsPage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import org.lwjgl.glfw.GLFW;

import java.util.Stack;

/**
 * 手机悬浮窗总控。
 * <p>
 * <b>底部导航</b>：HuhuTalk 内容区底部固定三栏——消息 / 联系人 / 设置。
 * 每次打开默认进入「消息」页。底部导航仅当处于顶层页（栈深 1）时显示；
 * 点击女仆进入聊天/详情/信息页（内部页）后导航隐藏，返回后恢复。
 * <p>
 * <b>页面栈</b>：栈底始终是当前 tab 的顶层页；内部页 push 叠上，pop 回到 tab 页。
 * <p>
 * <b>统一状态栏</b>：手机顶部叠加"时间 / 信号 / 电量"系统状态栏，页面整体下移
 * {@link #STATUSBAR_H}，事件坐标同步偏移，页面无感知。
 * <p>
 * 手机壳在手机外围渲染（纯视觉）。右键 = 返回上一页 / 顶层页右键关闭手机。
 */
public class PhoneOverlay {
    /** 底部导航三栏 */
    public enum Tab {
        MESSAGE, CONTACT, SETTINGS
    }

    private static boolean visible = false;
    private static boolean altDown = false;
    /** 是否处于 HuhuTalk App 内容区（显示底部导航）；false = 桌面模式 */
    private static boolean inChatApp = false;

    /** 顶层 tab 页 + 内部页共用一个栈；栈底为当前 tab 的顶层页 */
    private static final Stack<IPhonePage> pageStack = new Stack<>();
    private static Tab activeTab = Tab.MESSAGE;

    private static int phoneWidth;
    private static int phoneHeight;
    private static int phoneX, phoneY;

    /** 统一状态栏高度（像素，0.75 倍） */
    private static final int STATUSBAR_H = 12;
    /** 手机最小宽度（像素） */
    private static final int MIN_PHONE_W = 60;
    /** 底部水印栏高度（与状态栏同高；圆角半径 = 栏高） */
    private static final int WATERMARK_H = STATUSBAR_H;
    /** 底部水印栏背景色 */
    private static final int WATERMARK_BG = 0xFF192A54;

    private static float scale = 1.0f;

    // ==================== 访问器 ====================

    /** 手机内容区宽度 */
    public static int getPhoneWidth() { return phoneWidth; }
    /** 手机内容区高度（不含状态栏），页面布局基于此 */
    public static int getPhoneHeight() { return phoneHeight; }
    /** 统一状态栏高度（像素），页面命中判定据此与渲染基准对齐 */
    public static int getStatusBarHeight() { return STATUSBAR_H; }
    /** 底部导航（胶囊）高度 */
    private static int bottomNavH() {
        return Math.round(Minecraft.getInstance().font.lineHeight * 1.5f);
    }
    /** 底部导航上抬量：0.25 倍默认字体高，使其浮在水印栏上方留出空隙 */
    private static int navRaise() {
        return Math.round(Minecraft.getInstance().font.lineHeight * 0.25f);
    }
    /**
     * 页面底部预留的导航高度。
     * 返回 0：页面内容区一直延伸到水印栏（真正与下部接壤），
     * 导航胶囊作为悬浮层覆盖在内容之上（非占用色块）。
     */
    public static int getBottomNavHeight() { return 0; }
    public static int getPhoneX() { return phoneX; }
    public static int getPhoneY() { return phoneY; }
    public static float getScale() { return scale; }
    /** 主界面 APP 网格列数（MainPage 占位用，固定 2） */
    public static int getGridColumns() { return 2; }

    /** 屏幕比例字符串 → 返回 宽/高 比值；未知值回退 20:9。约定：长边为高、短边为宽，故 宽/高 = 后数/前数 */
    public static float aspectRatio(String aspect) {
        if (aspect == null) {
            return 9f / 20f;
        }
        return switch (aspect) {
            case "20:9" -> 9f / 20f;   // 高20 宽9 → 窄高竖屏
            case "9:16" -> 9f / 16f;   // 高16 宽9 → 标准竖屏
            case "4:3" -> 3f / 4f;     // 高4 宽3 → 竖长略窄
            default -> 9f / 20f;
        };
    }

    public static void setScale(float newScale) {
        scale = Math.max(0.5f, Math.min(2.0f, newScale));
    }

    // ==================== 状态控制 ====================

    public static boolean shouldCaptureMouse() {
        return visible && altDown && Minecraft.getInstance().screen == null;
    }

    /** 打开/关闭手机（关闭时不清空页面栈） */
    public static void toggle() {
        if (visible) {
            hide();
        } else {
            show();
        }
        updateCursorMode();
    }

    private static void hide() {
        visible = false;
        if (Minecraft.getInstance().screen instanceof PhoneInputScreen) {
            Minecraft.getInstance().setScreen(null);
        }
    }

    /**
     * 显示手机：保留上次关闭时的页面（页面栈不重置）。
     * 仅当栈为空（首次打开 / 新世界重置后）才进入手机桌面 MainPage；
     * 否则回到关闭前所在的页面（含 HuhuTalk 内容区顶层页与内部页）。
     */
    private static void show() {
        visible = true;
        if (pageStack.isEmpty()) {
            inChatApp = false;
            activeTab = Tab.MESSAGE;
            pageStack.push(new MainPage());
        }
    }

    /** 从桌面进入 HuhuTalk App 内容区（默认消息 tab，显示底部导航） */
    public static void openChatApp() {
        if (!visible) return;
        inChatApp = true;
        activeTab = Tab.MESSAGE;
        pageStack.clear();
        pageStack.push(new MessageListPage());
    }

    public static boolean isVisible() { return visible; }

    public static IPhonePage getCurrentPage() {
        return pageStack.isEmpty() ? null : pageStack.peek();
    }

    /** 当前是否处于 HuhuTalk App 内的内部页（隐藏底部导航） */
    public static boolean isInSubPage() {
        return inChatApp && pageStack.size() > 1;
    }

    public static Tab getActiveTab() { return activeTab; }

    /** 是否处于 HuhuTalk App 内容区 */
    public static boolean isInChatApp() { return inChatApp; }

    /** 切换到某个底部 tab（仅 HuhuTalk App 内有效） */
    public static void switchTab(Tab tab) {
        if (!inChatApp) return;
        if (activeTab == tab && pageStack.size() == 1) {
            return;
        }
        activeTab = tab;
        pageStack.clear();
        pageStack.push(createTopPage(tab));
    }

    /** 进入内部页（聊天/详情/信息设置），叠在当前页之上 */
    public static void pushPage(IPhonePage page) {
        if (!visible) return;
        if (!pageStack.isEmpty()) {
            pageStack.peek().onClose();
        }
        pageStack.push(page);
        page.onOpen();
    }

    /** 返回上一页；HuhuTalk App 内顶层页右键返回桌面 */
    public static void popPage() {
        if (pageStack.isEmpty()) return;
        if (pageStack.size() > 1) {
            IPhonePage closed = pageStack.pop();
            closed.onClose();
            if (!pageStack.isEmpty()) {
                pageStack.peek().onOpen();
            }
        } else if (inChatApp) {
            // App 顶层页：返回桌面
            inChatApp = false;
            activeTab = Tab.MESSAGE;
            pageStack.clear();
            pageStack.push(new MainPage());
        }
        // 桌面模式栈深 1：不弹出（返回交给右键关闭手机）
    }

    public static void setAltDown(boolean down) {
        altDown = down;
        updateCursorMode();
    }

    private static void updateCursorMode() {
        Minecraft mc = Minecraft.getInstance();
        if (shouldCaptureMouse()) {
            mc.mouseHandler.releaseMouse();
        } else if (mc.screen == null) {
            mc.mouseHandler.grabMouse();
        }
    }

    /** 创建某个 tab 的顶层页 */
    private static IPhonePage createTopPage(Tab tab) {
        return switch (tab) {
            case MESSAGE -> new MessageListPage();
            case CONTACT -> new ContactListPage();
            case SETTINGS -> new SettingsPage();
        };
    }

    // ==================== 渲染 ====================

    public static void render(GuiGraphics graphics, float partialTick) {
        if (!visible) return;

        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // 手机尺寸 = 屏幕比例（高:宽，aspectRatio 返回宽/高）× 高度比例（占实际屏幕高 0.5~1.0）
        float size = HuhuTalkClientSettings.getPhoneSize();
        float aspect = aspectRatio(HuhuTalkClientSettings.getPhoneAspect());
        int totalHeight = (int)Math.round(screenH * size);
        int width = (int)Math.round(totalHeight * aspect);
        phoneWidth = Math.max(MIN_PHONE_W, Math.min(width, screenW - 20));
        phoneHeight = Math.max(20, totalHeight - STATUSBAR_H - WATERMARK_H);
        phoneX = screenW - phoneWidth - 10;
        phoneY = Math.max(2, screenH - totalHeight - 10);

        // 手机壳（纯视觉，包住整个手机）
        PhoneCaseRenderer.renderCase(graphics, phoneX, phoneY, phoneWidth, totalHeight);

        // 手机背景（圆角：上圆角=状态栏高，下圆角=水印栏高）
        fillRoundedRect(graphics, phoneX, phoneY, phoneWidth, totalHeight, STATUSBAR_H, WATERMARK_H, 0xEE111111);

        // 统一状态栏（圆角顶）
        renderStatusBar(graphics, phoneX, phoneY, phoneWidth);

        // 当前页面内容区：从状态栏下方开始，到水印栏上方结束
        IPhonePage page = getCurrentPage();
        if (page != null) {
            page.render(graphics, phoneX, phoneY + STATUSBAR_H, partialTick);
        }

        // 底部导航（仅 HuhuTalk App 内且处于顶层页时显示），悬浮于水印栏上方并上抬留出空隙
        if (inChatApp && !isInSubPage()) {
            renderBottomNav(graphics, phoneX, phoneY + totalHeight - WATERMARK_H - bottomNavH() - navRaise(), phoneWidth);
        }

        // 底部水印栏（圆角底）
        renderWatermark(graphics, phoneX, phoneY + totalHeight - WATERMARK_H, phoneWidth, WATERMARK_H);
    }

    /** 统一状态栏：时间 / 信号 / 电源 / 关闭（圆角顶；内容 0.75 倍、离开圆角范围） */
    private static void renderStatusBar(GuiGraphics graphics, int baseX, int baseY, int w) {
        Font font = Minecraft.getInstance().font;
        fillRoundedRect(graphics, baseX, baseY, w, STATUSBAR_H, STATUSBAR_H, 0, 0xFF222222);
        // 时间（向左留出圆角，往中间移一点）
        drawScaledText(graphics, font, Component.literal("12:00").getVisualOrderText(),
                baseX + 14, baseY + 3, 0xFFFFFFFF, 0.75f);
        int rightX = baseX + w;
        // 关闭 X（离开右圆角）
        drawScaledText(graphics, font, Component.literal("X").getVisualOrderText(),
                rightX - 12, baseY + 3, 0xFFFF0000, 0.75f);
        // 电源：emoji 🔋（离开右圆角，位于 X 左侧）
        drawScaledText(graphics, font, Component.literal("🔋").getVisualOrderText(),
                rightX - 32, baseY + 3, 0xFFFFFFFF, 0.75f);
        // 信号（0.75 倍，离开右圆角，位于电源左侧）
        renderSignalBars(graphics, rightX - 50, baseY, STATUSBAR_H);
    }

    /** 底部水印栏：深蓝底 + 白色加粗版本文字（圆角底；文字 0.75 倍、用空格分隔，过宽时缩放适配） */
    private static void renderWatermark(GuiGraphics graphics, int baseX, int baseY, int w, int h) {
        Font font = Minecraft.getInstance().font;
        fillRoundedRect(graphics, baseX, baseY, w, h, 0, WATERMARK_H, WATERMARK_BG);
        String version = ModList.get().getModContainerById(HuhuTalk.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("1.0.1");
        Component comp = Component.literal("HUHUTALK  VISION " + version)
                .withStyle(s -> s.withBold(true));
        FormattedCharSequence seq = comp.getVisualOrderText();
        float tw = font.width(seq);
        float scale = Math.max(0.5f, Math.min(0.75f, (w - 8f) / tw));
        float renderW = tw * scale;
        float drawX = (baseX + (w - renderW) / 2f) / scale;
        float drawY = (baseY + (h - font.lineHeight) / 2f + 1) / scale;
        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1.0f);
        graphics.drawString(font, seq, drawX, drawY, 0xFFFFFFFF, false);
        graphics.pose().popPose();
    }

    /** 以指定倍率绘制文本（无阴影），坐标按 1/scale 换算保证落位 */
    private static void drawScaledText(GuiGraphics g, Font font, FormattedCharSequence text, float x, float y, int color, float scale) {
        float inv = 1f / scale;
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, text, x * inv, y * inv, color, false);
        g.pose().popPose();
    }

    /** 绘制圆角矩形（topR/bottomR 为上下圆角半径），逐行 fill 实现 */
    private static void fillRoundedRect(GuiGraphics g, int x, int y, int w, int h, float topR, float bottomR, int color) {
        for (int py = y; py < y + h; py++) {
            int left = 0, right = 0;
            if (topR > 0 && py < y + topR) {
                float dy = py - y;
                float inset = topR - (float)Math.sqrt(Math.max(0f, topR * topR - (topR - dy) * (topR - dy)));
                int cut = (int)Math.ceil(inset);
                left = Math.max(left, cut);
                right = Math.max(right, cut);
            }
            if (bottomR > 0 && py >= y + h - bottomR) {
                float dy = (y + h - py);
                float inset = bottomR - (float)Math.sqrt(Math.max(0f, bottomR * bottomR - (bottomR - dy) * (bottomR - dy)));
                int cut = (int)Math.ceil(inset);
                left = Math.max(left, cut);
                right = Math.max(right, cut);
            }
            g.fill(x + left, py, x + w - right, py + 1, color);
        }
    }

    private static void renderSignalBars(GuiGraphics graphics, int startX, int baseY, int barAreaH) {
        int[] hs = {5, 7, 9}; // 原 {6,9,12} × 0.75 取整
        int barW = 2;         // 原 3 × 0.75
        int spacing = 4;      // 原 5 × 0.75
        ResourceKey<Level> dim = signalDimension();
        for (int i = 0; i < 3; i++) {
            int x = startX + i * spacing;
            int h = hs[i];
            int y = baseY + barAreaH - h;
            graphics.fill(x, y, x + barW, y + h, signalBarColor(dim, i));
        }
    }

    private static ResourceKey<Level> signalDimension() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.level() != null) {
            return mc.player.level().dimension();
        }
        return Level.OVERWORLD;
    }

    private static int signalBarColor(ResourceKey<Level> dim, int barIndex) {
        boolean full = dim.equals(Level.OVERWORLD);
        boolean nether = dim.equals(Level.NETHER);
        if (full) {
            return 0xFF00FF00;
        }
        if (nether) {
            return barIndex < 2 ? 0xFF888888 : 0xFFFF0000;
        }
        return barIndex == 0 ? 0xFF888888 : 0xFFFFFF00;
    }

    /** 底部导航（Dock）：悬浮胶囊，三个选项横向均分（emoji 上 + 0.75 倍文字下），选中项粉色底 */
    private static void renderBottomNav(GuiGraphics graphics, int baseX, int baseY, int w) {
        Font font = Minecraft.getInstance().font;
        int h = bottomNavH();
        float r = h / 2f;
        // 悬浮胶囊：水平留边；白描边大胶囊与底小胶囊均 70% 透明度（保留透视感）
        int margin = 8;
        int cx = baseX + margin;
        int cw = w - margin * 2;
        fillRoundedRect(graphics, cx - 1, baseY - 1, cw + 2, h + 2, r + 1, r + 1, 0xB3FFFFFF);   // 白描边 70%
        fillRoundedRect(graphics, cx, baseY, cw, h, r, r, 0xB3D9C3D4);                          // #d9c3d4 70%

        String[] emojis = {"✉", "🦊", "⚙"};
        String[] labels = {"消息", "联系人", "设置"};
        int slotW = cw / 3;
        int activeIdx = activeTab.ordinal();
        float ts = 0.75f; // 文字/图标缩放
        int lineH = (int)(font.lineHeight * ts);

        for (int i = 0; i < 3; i++) {
            int itemX = cx + i * slotW;
            int centerX = itemX + slotW / 2;
            // 选中项底色：#de6f8d 圆角胶囊
            if (i == activeIdx) {
                fillRoundedRect(graphics, itemX + 1, baseY + 1, slotW - 2, h - 2, (h - 2) / 2f, (h - 2) / 2f, 0xFFDE6F8D);
            }
            // emoji（上）
            FormattedCharSequence emoji = Component.literal(emojis[i]).getVisualOrderText();
            drawScaledText(graphics, font, emoji, centerX - font.width(emoji) * ts / 2f, baseY + 1, 0xFFFFFFFF, ts);
            // 文字（下，0.75 倍）
            FormattedCharSequence label = Component.literal(labels[i]).getVisualOrderText();
            drawScaledText(graphics, font, label, centerX - font.width(label) * ts / 2f, baseY + 1 + lineH, 0xFFFFFFFF, ts);
        }

        // 淡灰竖线分隔三个选项
        int lineColor = 0x99AAAAAA;
        for (int i = 1; i < 3; i++) {
            int lx = cx + i * slotW;
            graphics.fill(lx, baseY + 3, lx + 1, baseY + h - 3, lineColor);
        }
    }

    // ==================== 鼠标事件转发 ====================

    public static boolean handleMouseClicked(double screenX, double screenY, int button, int action) {
        if (!visible) return false;

        double relX = screenX - phoneX;
        double relY = screenY - phoneY;
        if (relX < 0 || relY < 0 || relX >= phoneWidth || relY >= phoneHeight + STATUSBAR_H + WATERMARK_H) {
            return false;
        }

        // 状态栏 / 底部导航 / 水印栏 / 右键返回：仅在按下时处理
        if (action == GLFW.GLFW_PRESS) {
            // 状态栏区域：只处理关闭按钮
            if (relY < STATUSBAR_H) {
                if (relX >= phoneWidth - 14 && relX <= phoneWidth) {
                    hide();
                    updateCursorMode();
                }
                return true;
            }

            // 底部水印栏区域（最底部）：装饰，不响应点击
            if (relY >= phoneHeight + STATUSBAR_H) {
                return true;
            }

            // 底部导航区域（仅 HuhuTalk App 内顶层页显示时；胶囊范围内可点，已随胶囊上抬）
            if (inChatApp && !isInSubPage()) {
                int navTop = phoneHeight + STATUSBAR_H - bottomNavH() - navRaise();
                int navBottom = phoneHeight + STATUSBAR_H - navRaise();
                if (relY >= navTop && relY < navBottom) {
                    int capsuleLeft = 8;
                    int capsuleRight = phoneWidth - 8;
                    if (relX >= capsuleLeft && relX <= capsuleRight) {
                        int slotW = (capsuleRight - capsuleLeft) / 3;
                        int idx = (int)((relX - capsuleLeft) / slotW);
                        if (idx >= 0 && idx < 3) {
                            switchTab(Tab.values()[idx]);
                        }
                    }
                    return true;
                }
            }

            // 右键：App 内部页返回上一页；App 顶层页返回桌面；桌面关闭手机
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                if (isInSubPage()) {
                    popPage();
                } else if (inChatApp) {
                    popPage(); // 顶层页 → 返回桌面
                } else {
                    hide();
                    updateCursorMode();
                }
                return true;
            }
        }

        // 按下与松开都转发给页面（页面自行按 action 区分；松开用于结束拖拽）
        IPhonePage page = getCurrentPage();
        if (page == null) return true;
        return page.mouseClicked(relX, relY - STATUSBAR_H, button, action);
    }

    public static boolean handleMouseScroll(double screenX, double screenY, double scrollDelta) {
        if (!visible) return false;
        IPhonePage page = getCurrentPage();
        if (page == null) return false;
        double relX = screenX - phoneX;
        double relY = screenY - phoneY;
        if (relX >= 0 && relY >= 0 && relX < phoneWidth && relY < phoneHeight + STATUSBAR_H) {
            if (relY < STATUSBAR_H) {
                return true;
            }
            return page.mouseScrolled(relX, relY - STATUSBAR_H, scrollDelta);
        }
        return true;
    }

    /** 进入新世界时调用：关闭手机、清空页面栈 */
    public static void resetForNewWorld() {
        visible = false;
        inChatApp = false;
        pageStack.clear();
        activeTab = Tab.MESSAGE;
        if (Minecraft.getInstance().screen instanceof PhoneInputScreen) {
            Minecraft.getInstance().setScreen(null);
        }
        updateCursorMode();
    }
}
