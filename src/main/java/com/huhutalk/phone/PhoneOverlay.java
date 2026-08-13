package com.huhutalk.phone;

import com.huhutalk.phone.pages.MainPage;
import com.huhutalk.phone.pages.ChatPage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.Stack;

/**
 * 手机悬浮窗总控（保留页面栈版）。
 * <p>
 * 关键变化：toggle() 关闭手机时**不再清空 pageStack**，
 * 只隐藏手机和关闭透明输入屏幕。重新打开时恢复到之前的页面。
 */
public class PhoneOverlay {
    private static boolean visible = false;
    private static boolean altDown = false;
    private static final Stack<IPhonePage> pageStack = new Stack<>();

    private static int phoneWidth;
    private static int phoneHeight;
    private static int phoneX, phoneY;

    private static float scale = 1.0f;
    private static int gridColumns = 2;

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
        // 保存当前聊天页草稿（如果正在聊天）
        if (pageStack.peek() instanceof ChatPage chatPage) {
            chatPage.onPageHide();
        }
        // 关闭透明输入屏幕（其 onClose 会调用 ChatPage.onInputClosed 保存草稿）
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
        phoneHeight = Math.min((int)(phoneWidth * 1.6), screenH - 20);

        phoneX = screenW - phoneWidth - 10;
        phoneY = screenH - phoneHeight - 10;

        // 手机背景
        graphics.fill(phoneX, phoneY, phoneX + phoneWidth, phoneY + phoneHeight, 0xEE111111);
        graphics.fill(phoneX, phoneY, phoneX + phoneWidth, phoneY + 1, 0xFFFFFFFF);

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