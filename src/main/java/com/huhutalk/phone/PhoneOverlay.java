package com.huhutalk.phone;

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
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
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

    /** 统一状态栏高度（像素） */
    private static final int STATUSBAR_H = 16;
    /** 底部导航高度（像素） */
    private static final int BOTTOM_NAV_H = 24;
    /** 手机最小宽度（像素） */
    private static final int MIN_PHONE_W = 60;

    private static float scale = 1.0f;

    // ==================== 访问器 ====================

    /** 手机内容区宽度 */
    public static int getPhoneWidth() { return phoneWidth; }
    /** 手机内容区高度（不含状态栏），页面布局基于此 */
    public static int getPhoneHeight() { return phoneHeight; }
    /** 统一状态栏高度（像素），页面命中判定据此与渲染基准对齐 */
    public static int getStatusBarHeight() { return STATUSBAR_H; }
    /** 底部导航高度（像素） */
    public static int getBottomNavHeight() { return BOTTOM_NAV_H; }
    public static int getPhoneX() { return phoneX; }
    public static int getPhoneY() { return phoneY; }
    public static float getScale() { return scale; }
    /** 主界面 APP 网格列数（MainPage 占位用，固定 2） */
    public static int getGridColumns() { return 2; }

    /** 屏幕比例字符串 → 返回 宽/高 比值；未知值回退 20:9。各选项均按实际手机形状编码 */
    public static float aspectRatio(String aspect) {
        if (aspect == null) {
            return 9f / 20f;
        }
        return switch (aspect) {
            case "20:9" -> 9f / 20f;   // 高20 宽9 → 窄高竖屏
            case "9:16" -> 9f / 16f;   // 高16 宽9 → 标准竖屏
            case "4:3" -> 4f / 3f;     // 宽4 高3 → 接近方形
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
        phoneHeight = Math.max(20, totalHeight - STATUSBAR_H);
        phoneX = screenW - phoneWidth - 10;
        phoneY = Math.max(2, screenH - totalHeight - 10);

        // 手机壳（纯视觉，包住整个手机）
        PhoneCaseRenderer.renderCase(graphics, phoneX, phoneY, phoneWidth, totalHeight);

        // 手机背景（含状态栏区域）
        graphics.fill(phoneX, phoneY, phoneX + phoneWidth, phoneY + totalHeight, 0xEE111111);
        graphics.fill(phoneX, phoneY, phoneX + phoneWidth, phoneY + 1, 0xFFFFFFFF);

        // 统一状态栏
        renderStatusBar(graphics, phoneX, phoneY, phoneWidth);

        // 当前页面内容区：从状态栏下方开始
        IPhonePage page = getCurrentPage();
        if (page != null) {
            page.render(graphics, phoneX, phoneY + STATUSBAR_H, partialTick);
        }

        // 底部导航（仅 HuhuTalk App 内且处于顶层页时显示）
        if (inChatApp && !isInSubPage()) {
            renderBottomNav(graphics, phoneX, phoneY + totalHeight - BOTTOM_NAV_H, phoneWidth);
        }
    }

    /** 统一状态栏：时间 / 信号 / 电量 / 关闭 */
    private static void renderStatusBar(GuiGraphics graphics, int baseX, int baseY, int w) {
        Font font = Minecraft.getInstance().font;
        graphics.fill(baseX, baseY, baseX + w, baseY + STATUSBAR_H, 0xFF222222);
        graphics.drawString(font, "12:00", baseX + 4, baseY + 3, 0xFFFFFFFF);
        int rightX = baseX + w;
        graphics.drawString(font, "X", rightX - 10, baseY + 3, 0xFFFF0000);
        // 电量（满格装饰）
        int batW = 18, batH = 9;
        int batX = rightX - 30, batY = baseY + (STATUSBAR_H - batH) / 2;
        graphics.fill(batX, batY, batX + batW, batY + batH, 0xFF000000);
        graphics.fill(batX + batW, batY + 2, batX + batW + 2, batY + batH - 2, 0xFF888888);
        graphics.fill(batX + 1, batY + 1, batX + batW - 1, batY + batH - 1, 0xFF00FF00);
        // 信号三竖杠
        renderSignalBars(graphics, rightX - 52, baseY, STATUSBAR_H);
    }

    private static void renderSignalBars(GuiGraphics graphics, int startX, int baseY, int barAreaH) {
        int[] heights = {6, 9, 12};
        ResourceKey<Level> dim = signalDimension();
        for (int i = 0; i < 3; i++) {
            int x = startX + i * 5;
            int h = heights[i];
            int y = baseY + barAreaH - h;
            graphics.fill(x, y, x + 3, y + h, signalBarColor(dim, i));
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

    /** 底部导航：消息 / 联系人 / 设置（高亮粉底 + 白字，选中高亮） */
    private static void renderBottomNav(GuiGraphics graphics, int baseX, int baseY, int w) {
        ColorScheme scheme = ThemeManager.getActive();
        Font font = Minecraft.getInstance().font;
        int h = BOTTOM_NAV_H;
        graphics.fill(baseX, baseY, baseX + w, baseY + h, scheme.topBarBg());

        String[] labels = {"消息", "联系人", "设置"};
        int slotW = w / 3;
        for (int i = 0; i < 3; i++) {
            boolean active = (i == activeTab.ordinal());
            int centerX = baseX + i * slotW + slotW / 2;
            int centerY = baseY + h / 2;
            int color = active ? scheme.topBarText() : scheme.navText();
            // 选中项加高亮底
            if (active) {
                graphics.fill(baseX + i * slotW + 3, baseY + 3, baseX + i * slotW + slotW - 3, baseY + h - 3, 0x33FFFFFF);
            }
            graphics.drawCenteredString(font, labels[i], centerX, centerY - font.lineHeight / 2, color);
        }
    }

    // ==================== 鼠标事件转发 ====================

    public static boolean handleMouseClicked(double screenX, double screenY, int button, int action) {
        if (!visible) return false;

        double relX = screenX - phoneX;
        double relY = screenY - phoneY;
        if (relX < 0 || relY < 0 || relX >= phoneWidth || relY >= phoneHeight + STATUSBAR_H) {
            return false;
        }

        // 状态栏 / 底部导航 / 右键返回：仅在按下时处理
        if (action == GLFW.GLFW_PRESS) {
            // 状态栏区域：只处理关闭按钮
            if (relY < STATUSBAR_H) {
                if (relX >= phoneWidth - 14 && relX <= phoneWidth) {
                    hide();
                    updateCursorMode();
                }
                return true;
            }

            // 底部导航区域（仅 HuhuTalk App 内顶层页显示时）
            if (inChatApp && !isInSubPage() && relY >= phoneHeight + STATUSBAR_H - BOTTOM_NAV_H) {
                int slotW = phoneWidth / 3;
                int idx = (int)(relX / slotW);
                if (idx >= 0 && idx < 3) {
                    switchTab(Tab.values()[idx]);
                }
                return true;
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
