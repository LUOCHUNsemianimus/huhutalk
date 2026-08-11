package com.huhutalk.client;

import com.huhutalk.client.screen.PhoneHomeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayDeque;
import java.util.Deque;

public class PhoneScreenManager {
    private static IPhoneScreen activeScreen;
    private static final Deque<IPhoneScreen> screenStack = new ArrayDeque<>();
    private static boolean closingByManager = false;   // 防止递归的关键标志

    public static boolean isClosingByManager() {
        return closingByManager;
    }

    public static void openPhone() {
        if (!screenStack.isEmpty()) {
            activeScreen = screenStack.peek();
            setScreen((Screen) activeScreen);
        } else {
            pushScreen(new PhoneHomeScreen());
        }
    }

    public static void closePhone() {
        if (activeScreen == null) return;
        closingByManager = true;            // 标记：这次关闭由管理器发起
        activeScreen = null;
        // 注意：setScreen(null) 会触发当前屏幕的 onClose()，
        // 但在 onClose 中我们会检查 closingByManager 标志，避免重复调用 closePhone()
        Minecraft.getInstance().setScreen(null);
        closingByManager = false;
    }

    public static void resetPhone() {
        closingByManager = true;
        screenStack.clear();
        activeScreen = null;
        Minecraft.getInstance().setScreen(null);
        closingByManager = false;
    }

    public static void pushScreen(IPhoneScreen screen) {
        if (!(screen instanceof Screen s)) {
            throw new IllegalArgumentException("Phone screen must extend Screen");
        }
        if (activeScreen != null) {
            screenStack.push(activeScreen);
        }
        activeScreen = screen;
        screenStack.push(screen);           // 确保当前屏幕在栈顶
        setScreen(s);
    }

    public static void popScreen() {
        if (screenStack.isEmpty() || screenStack.size() == 1) {
            closePhone();
            return;
        }
        screenStack.pop();                  // 移除当前
        activeScreen = screenStack.peek();
        setScreen((Screen) activeScreen);
    }

    public static boolean isPhoneOpen() {
        return activeScreen != null;
    }

    public static boolean isPhoneActive() {
        return isPhoneOpen();
    }

    // 内部工具：切换屏幕时不触发 closePhone 递归
    private static void setScreen(Screen screen) {
        closingByManager = true;            // 所有通过管理器的屏幕切换都应标记
        Minecraft.getInstance().setScreen(screen);
        closingByManager = false;
    }
}