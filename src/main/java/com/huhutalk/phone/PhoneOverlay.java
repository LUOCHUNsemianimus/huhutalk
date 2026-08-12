package com.huhutalk.phone;

import com.huhutalk.phone.pages.MainPage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.Stack;

public class PhoneOverlay {
    private static boolean visible = false;
    private static boolean altDown = false;
    private static final Stack<IPhonePage> pageStack = new Stack<>();

    public static final int PHONE_WIDTH = 220;
    public static final int PHONE_HEIGHT = 340;
    private static int phoneX, phoneY;

    public static boolean shouldCaptureMouse() {
        return visible && altDown && Minecraft.getInstance().screen == null;
    }

    public static void toggle() {
        visible = !visible;
        if (!visible) {
            while (!pageStack.isEmpty()) {
                pageStack.pop().onClose();
            }
            if (Minecraft.getInstance().screen instanceof PhoneInputScreen) {
                Minecraft.getInstance().setScreen(null);
            }
        } else {
            if (pageStack.isEmpty()) {
                pageStack.push(new MainPage());
                pageStack.peek().onOpen();
            }
        }
        updateCursorMode();
    }

    public static void pushPage(IPhonePage page) {
        if (!visible) return;
        if (!pageStack.isEmpty()) pageStack.peek().onClose();
        pageStack.push(page);
        page.onOpen();
    }

    public static void popPage() {
        if (pageStack.size() <= 1) return;
        pageStack.pop().onClose();
        pageStack.peek().onOpen();
    }

    public static boolean isVisible() { return visible; }
    public static IPhonePage getCurrentPage() { return pageStack.isEmpty() ? null : pageStack.peek(); }

    public static void setAltDown(boolean down) {
        altDown = down;
        updateCursorMode();
    }

    private static void updateCursorMode() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        if (shouldCaptureMouse()) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        } else if (Minecraft.getInstance().screen == null) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        }
    }

    // 🔁 改为接受 float partialTick（与 GuiLayer 兼容）
    public static void render(GuiGraphics graphics, float partialTick) {
        if (!visible) return;
        Minecraft mc = Minecraft.getInstance();
        phoneX = mc.getWindow().getGuiScaledWidth() - PHONE_WIDTH - 10;
        phoneY = mc.getWindow().getGuiScaledHeight() - PHONE_HEIGHT - 10;

        graphics.fill(phoneX, phoneY, phoneX + PHONE_WIDTH, phoneY + PHONE_HEIGHT, 0xEE111111);
        graphics.fill(phoneX, phoneY, phoneX + PHONE_WIDTH, phoneY + 1, 0xFFFFFFFF);

        IPhonePage page = getCurrentPage();
        if (page != null) {
            page.render(graphics, phoneX, phoneY, partialTick);
        }
    }

    public static boolean handleMouseClicked(double screenX, double screenY, int button, int action) {
        if (!visible) return false;
        IPhonePage page = getCurrentPage();
        if (page == null) return false;
        double relX = screenX - phoneX;
        double relY = screenY - phoneY;
        if (relX >= 0 && relY >= 0 && relX < PHONE_WIDTH && relY < PHONE_HEIGHT) {
            return page.mouseClicked(relX, relY, button, action);
        }
        return true; // 手机外也消费，防止误触
    }

    public static boolean handleMouseScroll(double screenX, double screenY, double scrollDelta) {
        if (!visible) return false;
        IPhonePage page = getCurrentPage();
        if (page == null) return false;
        double relX = screenX - phoneX;
        double relY = screenY - phoneY;
        if (relX >= 0 && relY >= 0 && relX < PHONE_WIDTH && relY < PHONE_HEIGHT) {
            return page.mouseScrolled(relX, relY, scrollDelta);
        }
        return true;
    }

    // 移除 openInputScreen 方法 —— 改由 ChatPage 自己创建 Screen

    public static int getPhoneX() { return phoneX; }
    public static int getPhoneY() { return phoneY; }
}