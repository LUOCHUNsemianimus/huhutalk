package com.huhutalk.phone;

import com.huhutalk.phone.pages.MainPage;
import com.huhutalk.phone.pages.ChatPage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

import java.util.Stack;

/**
 * 手机悬浮窗总控（保留页面栈版）。
 * <p>
 * toggle() 关闭手机时不清空 pageStack，只隐藏。
 * 右键（button==1）= 返回上一页，主界面右键 = 关闭手机。
 * <p>
 * <b>统一状态栏</b>：在手机顶部叠加渲染"时间 / 信号 / 电量"系统状态栏，
 * 页面整体下移 {@link #STATUSBAR_H} 高度（做加法，页面自身坐标不变），所有页面自动共享。
 * 事件坐标同样向下偏移，页面无感知。
 */
public class PhoneOverlay {
    private static boolean visible = false;
    private static boolean altDown = false;
    private static final Stack<IPhonePage> pageStack = new Stack<>();

    private static int phoneWidth;
    private static int phoneHeight;
    private static int phoneX, phoneY;

    /** 统一状态栏高度（像素） */
    private static final int STATUSBAR_H = 16;

    private static float scale = 1.0f;
    private static int gridColumns = 2;

    private static boolean animating = false;
    private static float animProgress = 0f;
    private static IPhonePage pendingPage;

    // ==================== 访问器 ====================

    /** 手机内容区宽度 */
    public static int getPhoneWidth() { return phoneWidth; }
    /** 手机内容区高度（不含状态栏），页面布局均基于此 */
    public static int getPhoneHeight() { return phoneHeight; }
    /** 统一状态栏高度（像素），页面命中判定需要据此与渲染基准对齐 */
    public static int getStatusBarHeight() { return STATUSBAR_H; }
    public static int getPhoneX() { return phoneX; }
    public static int getPhoneY() { return phoneY; }
    public static float getScale() { return scale; }
    public static int getGridColumns() { return gridColumns; }

    public static void setScale(float newScale) {
        scale = Math.max(0.5f, Math.min(2.0f, newScale));
    }
    public static void setGridColumns(int cols) {
        gridColumns = Math.max(1, Math.min(5, cols));
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

    /** 隐藏手机：保留页面栈，只关闭透明输入屏幕并保存草稿 */
    private static void hide() {
        visible = false;
        if (!pageStack.isEmpty() && pageStack.peek() instanceof ChatPage chatPage) {
            chatPage.onPageHide();
        }
        if (Minecraft.getInstance().screen instanceof PhoneInputScreen) {
            Minecraft.getInstance().setScreen(null);
        }
    }

    /** 显示手机：栈为空才回主界面 */
    private static void show() {
        visible = true;
        if (pageStack.isEmpty()) {
            IPhonePage main = new MainPage();
            pageStack.push(main);
            main.onOpen();
        }
    }

    public static boolean isVisible() { return visible; }

    public static IPhonePage getCurrentPage() {
        return pageStack.isEmpty() ? null : pageStack.peek();
    }

    public static void pushPage(IPhonePage page) {
        if (!visible) return;
        if (!pageStack.isEmpty()) {
            pageStack.peek().onClose();
        }
        pageStack.push(page);
        page.onOpen();
    }

    public static void popPage() {
        if (pageStack.size() <= 1) return;
        IPhonePage closed = pageStack.pop();
        closed.onClose();
        if (!pageStack.isEmpty()) {
            pageStack.peek().onOpen();
        }
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

    // ==================== 渲染 ====================

    public static void render(GuiGraphics graphics, float partialTick) {
        if (!visible) return;

        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int baseW = screenW / 3;
        phoneWidth = (int)(baseW * scale);
        // 手机总高度 = 内容区 + 状态栏，二者之和不超过屏高
        int totalHeight = Math.min((int)(phoneWidth * 1.6), screenH - 20);
        phoneHeight = Math.max(20, totalHeight - STATUSBAR_H);

        phoneX = screenW - phoneWidth - 10;
        phoneY = screenH - totalHeight - 10;

        // 手机背景（含状态栏区域）
        graphics.fill(phoneX, phoneY, phoneX + phoneWidth, phoneY + totalHeight, 0xEE111111);
        graphics.fill(phoneX, phoneY, phoneX + phoneWidth, phoneY + 1, 0xFFFFFFFF);

        // 统一状态栏（时间 / 信号 / 电量）
        renderStatusBar(graphics, phoneX, phoneY, phoneWidth);

        // 页面内容区：从状态栏下方开始（baseY 下移 STATUSBAR_H）
        IPhonePage page = getCurrentPage();
        if (page != null) {
            page.render(graphics, phoneX, phoneY + STATUSBAR_H, partialTick);
        }
    }

    /**
     * 统一状态栏：左侧时间、右侧信号（三竖杠）+ 电量（满格装饰）+ 关闭按钮。
     * 信号强度按玩家所在维度判定（主世界满格 / 其他维度中等 / 地狱差），与信号延迟一致。
     */
    private static void renderStatusBar(GuiGraphics graphics, int baseX, int baseY, int w) {
        Font font = Minecraft.getInstance().font;

        // 状态栏背景
        graphics.fill(baseX, baseY, baseX + w, baseY + STATUSBAR_H, 0xFF222222);

        // 左侧：时间
        graphics.drawString(font, "12:00", baseX + 4, baseY + 3, 0xFFFFFFFF);

        // 右侧：信号条 + 电量 + 关闭
        int rightX = baseX + w;

        // 关闭按钮（最右）
        graphics.drawString(font, "X", rightX - 10, baseY + 3, 0xFFFF0000);

        // 电量（满格装饰）：电池框 + 满格填充
        int batW = 18, batH = 9;
        int batX = rightX - 30;
        int batY = baseY + (STATUSBAR_H - batH) / 2;
        graphics.fill(batX, batY, batX + batW, batY + batH, 0xFF000000);
        graphics.fill(batX + batW, batY + 2, batX + batW + 2, batY + batH - 2, 0xFF888888);
        graphics.fill(batX + 1, batY + 1, batX + batW - 1, batY + batH - 1, 0xFF00FF00);

        // 信号：三竖杠（右高左低），按维度染色
        renderSignalBars(graphics, rightX - 52, baseY, STATUSBAR_H);
    }

    /**
     * 渲染三竖杠信号条（左低右高）。强度三档：
     * <ul>
     *   <li>满格（主世界）：三杠全绿</li>
     *   <li>中等（其他维度）：第一杠灰、后两杠黄</li>
     *   <li>差（地狱）：前两杠灰、最后一杠红</li>
     * </ul>
     */
    private static void renderSignalBars(GuiGraphics graphics, int startX, int baseY, int barAreaH) {
        // 每杠宽 3、间距 2；高度从低到高递增（左低右高）
        int[] heights = {6, 9, 12};
        int color;
        ResourceKey<Level> dim = signalDimension();
        for (int i = 0; i < 3; i++) {
            int x = startX + i * 5;
            int h = heights[i];
            int y = baseY + barAreaH - h;
            color = signalBarColor(dim, i);
            graphics.fill(x, y, x + 3, y + h, color);
        }
    }

    /** 玩家当前所在维度（客户端直接判定）；无玩家时按满格处理 */
    private static ResourceKey<Level> signalDimension() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.level() != null) {
            return mc.player.level().dimension();
        }
        return Level.OVERWORLD;
    }

    /** 某根信号杠的颜色：0 为最左（最低），2 为最右（最高） */
    private static int signalBarColor(ResourceKey<Level> dim, int barIndex) {
        boolean full = dim.equals(Level.OVERWORLD);
        boolean nether = dim.equals(Level.NETHER);
        if (full) {
            return 0xFF00FF00; // 满格：全绿
        }
        if (nether) {
            // 差：前两灰、最后一红
            return barIndex < 2 ? 0xFF888888 : 0xFFFF0000;
        }
        // 中等：第一灰、后两黄
        return barIndex == 0 ? 0xFF888888 : 0xFFFFFF00;
    }

    // ==================== 鼠标事件转发 ====================

    public static boolean handleMouseClicked(double screenX, double screenY, int button, int action) {
        if (!visible) return false;
        if (action != GLFW.GLFW_PRESS) return false;

        double relX = screenX - phoneX;
        double relY = screenY - phoneY;
        if (relX < 0 || relY < 0 || relX >= phoneWidth || relY >= phoneHeight + STATUSBAR_H) {
            return false; // 手机外点击不拦截
        }

        // 状态栏区域：只处理关闭按钮
        if (relY < STATUSBAR_H) {
            int w = phoneWidth;
            // 关闭按钮（右上角 X）
            if (relX >= w - 14 && relX <= w && relY >= 0 && relY <= STATUSBAR_H) {
                if (pageStack.size() <= 1) {
                    hide();
                    updateCursorMode();
                }
            }
            return true; // 状态栏其余区域不穿透给页面
        }

        // ✅ 右键 = 返回上一页 / 主界面右键关闭手机
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (pageStack.size() > 1) {
                popPage();
            } else {
                hide();
                updateCursorMode();
            }
            return true;
        }

        IPhonePage page = getCurrentPage();
        if (page == null) return true;
        // 页面事件坐标相对内容区：向下偏移状态栏高度
        return page.mouseClicked(relX, relY - STATUSBAR_H, button, action);
    }

    public static boolean handleMouseScroll(double screenX, double screenY, double scrollDelta) {
        if (!visible) return false;
        IPhonePage page = getCurrentPage();
        if (page == null) return false;
        double relX = screenX - phoneX;
        double relY = screenY - phoneY;
        if (relX >= 0 && relY >= 0 && relX < phoneWidth && relY < phoneHeight + STATUSBAR_H) {
            // 状态栏区域不响应滚动
            if (relY < STATUSBAR_H) {
                return true;
            }
            return page.mouseScrolled(relX, relY - STATUSBAR_H, scrollDelta);
        }
        return true;
    }

    // ==================== 动画 tick（预留） ====================
    public static void tickAnimation() {
        if (animating) {
            animProgress += 0.15f;
            if (animProgress >= 1.0f) {
                animProgress = 1.0f;
                animating = false;
                if (pendingPage != null) {
                    if (!pageStack.isEmpty()) pageStack.peek().onClose();
                    pageStack.push(pendingPage);
                    pendingPage.onOpen();
                    pendingPage = null;
                }
            }
        }
    }

    /** 进入新世界时调用：关闭手机、清空页面栈 */
    public static void resetForNewWorld() {
        visible = false;
        while (!pageStack.isEmpty()) {
            pageStack.pop().onClose();
        }
        if (Minecraft.getInstance().screen instanceof PhoneInputScreen) {
            Minecraft.getInstance().setScreen(null);
        }
        updateCursorMode();
    }
}
