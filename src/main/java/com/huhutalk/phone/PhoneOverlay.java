package com.huhutalk.phone;

import com.huhutalk.phone.pages.MainPage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.Stack;

/**
 * 手机悬浮窗总控。
 * <p>
 * 负责：
 * <ul>
 *   <li>手机可见性、Alt 键状态</li>
 *   <li>页面栈管理（push/pop）</li>
 *   <li>每帧动态计算手机尺寸（屏幕宽度 1/3 × 全局缩放因子）</li>
 *   <li>转发鼠标事件</li>
 *   <li>预留滑动/弹出动画接口</li>
 * </ul>
 */
public class PhoneOverlay {
    private static boolean visible = false;
    private static boolean altDown = false;
    private static final Stack<IPhonePage> pageStack = new Stack<>();

    /** 手机实际宽高（每帧计算） */
    private static int phoneWidth;
    private static int phoneHeight;
    /** 手机在屏幕上的左上角坐标 */
    private static int phoneX, phoneY;

    /** 全局缩放因子（1.0f = 默认，玩家可通过滑块调整） */
    private static float scale = 1.0f;
    /** 主界面每行应用图标数量 */
    private static int gridColumns = 2;

    // ---------- 动画预留 ----------
    private static boolean animating = false;
    private static float animProgress = 0f;
    private static IPhonePage pendingPage;

    // ==================== 访问器 ====================

    public static int getPhoneWidth() { return phoneWidth; }
    public static int getPhoneHeight() { return phoneHeight; }
    public static int getPhoneX() { return phoneX; }
    public static int getPhoneY() { return phoneY; }
    public static float getScale() { return scale; }
    public static int getGridColumns() { return gridColumns; }

    /** 设置缩放因子（会被设置页面调用） */
    public static void setScale(float newScale) {
        scale = Math.max(0.5f, Math.min(2.0f, newScale)); // 限制范围
    }
    /** 设置每行列数 */
    public static void setGridColumns(int cols) {
        gridColumns = Math.max(1, Math.min(5, cols)); // 1~5之间
    }

    // ==================== 状态控制 ====================

    /** 手机是否应该捕获鼠标（Alt 按住且无其他 Screen） */
    public static boolean shouldCaptureMouse() {
        return visible && altDown && Minecraft.getInstance().screen == null;
    }

    /** 切换手机显示/隐藏 */
    public static void toggle() {
        visible = !visible;
        if (!visible) {
            // 关闭时清空页面栈并恢复
            while (!pageStack.isEmpty()) {
                pageStack.pop().onClose();
            }
            if (Minecraft.getInstance().screen instanceof PhoneInputScreen) {
                Minecraft.getInstance().setScreen(null);
            }
        } else {
            if (pageStack.isEmpty()) {
                IPhonePage main = new MainPage();
                pageStack.push(main);
                main.onOpen();
            }
        }
        updateCursorMode();
    }

    public static boolean isVisible() { return visible; }

    /** 获取当前页面（栈顶） */
    public static IPhonePage getCurrentPage() {
        return pageStack.isEmpty() ? null : pageStack.peek();
    }

    /** 推入新页面（未来会加入动画） */
    public static void pushPage(IPhonePage page) {
        if (!visible) return;
        // 简单切换：未来可改为启动动画，在动画完成时替换
        if (!pageStack.isEmpty()) {
            pageStack.peek().onClose();
        }
        pageStack.push(page);
        page.onOpen();
    }

    /** 弹出当前页面 (保留至少一层) */
    public static void popPage() {
        if (pageStack.size() <= 1) return;
        IPhonePage closed = pageStack.pop();
        closed.onClose();
        if (!pageStack.isEmpty()) {
            pageStack.peek().onOpen();
        }
    }

    /** Alt 键状态由外部事件更新 */
    public static void setAltDown(boolean down) {
        altDown = down;
        updateCursorMode();
    }

    // ---------- 光标模式 ----------
    private static void updateCursorMode() {
        Minecraft mc = Minecraft.getInstance();
        if (shouldCaptureMouse()) {
            // 真正释放鼠标：光标可见、游戏停止视角转动、窗口不再锁定鼠标
            mc.mouseHandler.releaseMouse();
        } else if (mc.screen == null) {
            // 重新捕获鼠标，回归游戏操控（grabMouse 会把光标移到屏幕中心）
            mc.mouseHandler.grabMouse();
        }
        // 如果有任何 Screen 存在，光标管理交给 Screen
    }

    // ==================== 渲染 ====================

    /**
     * 每帧由 GuiLayer 调用，绘制手机外壳并委托当前页面渲染。
     */
    public static void render(GuiGraphics graphics, float partialTick) {
        if (!visible) return;

        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // 基础尺寸：屏幕宽度三分之一
        int baseW = screenW / 3;
        // 应用缩放因子
        phoneWidth = (int)(baseW * scale);
        // 高度 = 宽度 × 1.6，但不超过屏幕高度
        phoneHeight = Math.min((int)(phoneWidth * 1.6), screenH - 20);

        // 固定右下角，偏移10像素
        phoneX = screenW - phoneWidth - 10;
        phoneY = screenH - phoneHeight - 10;

        // 绘制半透明背景
        graphics.fill(phoneX, phoneY, phoneX + phoneWidth, phoneY + phoneHeight, 0xEE111111);
        // 顶部白边
        graphics.fill(phoneX, phoneY, phoneX + phoneWidth, phoneY + 1, 0xFFFFFFFF);

        // 委托页面绘制
        IPhonePage page = getCurrentPage();
        if (page != null) {
            page.render(graphics, phoneX, phoneY, partialTick);
        }
    }

    // ==================== 鼠标事件转发 ====================

    public static boolean handleMouseClicked(double screenX, double screenY, int button, int action) {
        if (!visible) return false;
        IPhonePage page = getCurrentPage();
        if (page == null) return false;
        double relX = screenX - phoneX;
        double relY = screenY - phoneY;
        if (relX >= 0 && relY >= 0 && relX < phoneWidth && relY < phoneHeight) {
            return page.mouseClicked(relX, relY, button, action);
        }
        // 手机外点击也消费掉（防止误击游戏）
        return true;
    }

    public static boolean handleMouseScroll(double screenX, double screenY, double scrollDelta) {
        if (!visible) return false;
        IPhonePage page = getCurrentPage();
        if (page == null) return false;
        double relX = screenX - phoneX;
        double relY = screenY - phoneY;
        if (relX >= 0 && relY >= 0 && relX < phoneWidth && relY < phoneHeight) {
            return page.mouseScrolled(relX, relY, scrollDelta);
        }
        return true;
    }

    // ==================== 动画 tick（预留） ====================
    public static void tickAnimation() {
        if (animating) {
            animProgress += 0.15f; // 示例速度，未来可能与帧率绑定
            if (animProgress >= 1.0f) {
                animProgress = 1.0f;
                animating = false;
                // 完成页面切换
                if (pendingPage != null) {
                    if (!pageStack.isEmpty()) pageStack.peek().onClose();
                    pageStack.push(pendingPage);
                    pendingPage.onOpen();
                    pendingPage = null;
                }
            }
        }
    }
}