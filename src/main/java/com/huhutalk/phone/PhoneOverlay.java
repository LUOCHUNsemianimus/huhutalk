package com.huhutalk.phone;

import com.huhutalk.HuhuTalk;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.client.theme.ColorScheme;
import com.huhutalk.client.theme.PhoneCaseRenderer;
import com.huhutalk.client.theme.ThemeManager;
import com.huhutalk.phone.anim.AnimEasing;
import com.huhutalk.phone.anim.AnimTween;
import com.huhutalk.phone.pages.ChatPage;
import com.huhutalk.phone.pages.ContactListPage;
import com.huhutalk.phone.pages.MainPage;
import com.huhutalk.phone.pages.MessageListPage;
import com.huhutalk.phone.pages.SettingsPage;
import com.huhutalk.phone.UiShapes;
import com.mojang.math.Axis;
import com.mojang.blaze3d.systems.RenderSystem;
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

    /** 相机拍照临时隐藏手机：隐藏期间手机不渲染（让截图是干净的纯游戏画面） */
    private static boolean captureHidden = false;
    /** 下一帧（手机已隐藏后）是否需要抓取一次主渲染目标 */
    private static boolean grabPending = false;
    private static java.util.function.Consumer<byte[]> grabCallback = null;

    /** 正在拖动手机（按住顶部状态栏左键） */
    private static boolean dragging = false;
    /** 拖拽抓取点：按下瞬间鼠标相对手机左上角的偏移 */
    private static float grabDX, grabDY;

    /** 手机容器动画状态机：呼出/收起为整机 Overlay 动画（唯一需要鼠标逆矩阵矫正的动画） */
    private enum ContainerAnim { IDLE, ENTER, EXIT }
    private static ContainerAnim containerAnim = ContainerAnim.IDLE;
    private static AnimTween containerTween = null;
    /** 本帧手机渲染用左上角 Y（含呼出/收起动画位移）；鼠标命中判定据此做逆矩阵矫正 */
    private static int renderedPhoneY = 0;

    /**
     * 全局内部页面跳转动（聊天/联系人/设置页等统一复用同一套动画，不做区分）。
     * 进场：页面自屏幕右侧外滑入，透明度 0.3→1；退场：向右滑出，透明度 1→0.3。
     * push/pop/tab 切换均触发；播放期间普通点击屏蔽，右键可强制打断并出栈返回。
     */
    private static IPhonePage transFrom = null;
    private static IPhonePage transTo = null;
    private static AnimTween transTween = null; // null = 无转场
    /** true=push（新页从右盖住旧页）；false=pop（当前页向右缩回露出下层页） */
    private static boolean transIsPush = false;

    /**
     * APP 桌面启动动画（桌面点开 HuhuTalk 进入首页）。
     * 在 APP 图标中心生成 APP 色块纯色矩形，由小放大、透明度 1→0 淡出；
     * 同时底层首页 UI 原地透明度 0→1 淡入。仅色块缩放，真实 UI 完全不动，无需鼠标矫正。
     */
    private static boolean launchActive = false;
    private static AnimTween launchTween = null;
    private static float launchCx, launchCy; // 启动 App 图标中心（屏幕坐标）
    private static int launchIconW;          // 启动 App 图标初始边长
    private static int launchBlockColorRgb;  // 启动 App 图标底色（RGB，随 App 变化）
    /** 启动动画时长（秒）：全局 0.25s 对「放大铺满」太快，故作为例外用 0.5s */
    private static final float LAUNCH_DURATION = 0.5f;

    /** 统一状态栏高度（基准像素；运行时经 UiFont.sh 随字体倍率缩放） */
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
    public static int getStatusBarHeight() { return UiFont.sh(STATUSBAR_H); }
    /** 底部导航（胶囊）高度 */
    private static int bottomNavH() {
        return Math.round(Minecraft.getInstance().font.lineHeight * UiFont.sc(1.5f));
    }
    /** 底部导航上抬量：0.25 倍默认字体高，使其浮在水印栏上方留出空隙 */
    private static int navRaise() {
        return Math.round(Minecraft.getInstance().font.lineHeight * UiFont.sc(0.25f));
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
    /** 主界面 APP 网格列数（MainPage 桌面图标每行固定 3 个） */
    public static int getGridColumns() { return 3; }

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

    /** 水平约束：手机至少保留一半宽度在屏内（左右至多滑出半个手机，顶部栏仍留一半可抓） */
    private static int clampPhoneX(int x, int screenW) {
        return Math.max(-phoneWidth / 2, Math.min(screenW - phoneWidth / 2, x));
    }

    /** 垂直约束：顶部状态栏高度必须完整在屏内（向下可把状态栏以下内容全部滑出） */
    private static int clampPhoneY(int y, int screenH) {
        return Math.max(0, Math.min(screenH - UiFont.sh(STATUSBAR_H), y));
    }

    /** 当前鼠标在 GUI 缩放坐标下的 X */
    private static int guiMouseX(Minecraft mc) {
        return (int) Math.round(mc.mouseHandler.xpos()
                * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth());
    }

    /** 当前鼠标在 GUI 缩放坐标下的 Y */
    private static int guiMouseY(Minecraft mc) {
        return (int) Math.round(mc.mouseHandler.ypos()
                * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight());
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
        // 已在收起退场中：丢弃重复指令（不重复开新动画）
        if (uiAnimEnabled() && containerAnim == ContainerAnim.EXIT) {
            return;
        }
        // 切屏动画开启：走整机下滑退场（EXIT），播完才真正隐藏
        if (uiAnimEnabled() && !captureHidden) {
            containerAnim = ContainerAnim.EXIT;
            containerTween = new AnimTween();
            return;
        }
        doHideNow();
    }

    /** 立即隐藏手机（无动画）：清空拖动与容器动画状态，关闭输入屏，恢复鼠标 */
    private static void doHideNow() {
        visible = false;
        dragging = false;
        containerAnim = ContainerAnim.IDLE;
        containerTween = null;
        forceFinishTransition();
        forceFinishLaunch();
        if (Minecraft.getInstance().screen instanceof PhoneInputScreen) {
            Minecraft.getInstance().setScreen(null);
        }
        updateCursorMode();
    }

    /**
     * 显示手机：保留上次关闭时的页面（页面栈不重置）。
     * 仅当栈为空（首次打开 / 新世界重置后）才进入手机桌面 MainPage；
     * 否则回到关闭前所在的页面（含 HuhuTalk 内容区顶层页与内部页）。
     * 切屏动画开启时整机自屏幕底部向上滑入（ENTER），关闭时瞬时出现。
     */
    private static void show() {
        visible = true;
        if (uiAnimEnabled() && !captureHidden) {
            containerAnim = ContainerAnim.ENTER;
            containerTween = new AnimTween();
        } else {
            containerAnim = ContainerAnim.IDLE;
            containerTween = null;
        }
        if (pageStack.isEmpty()) {
            inChatApp = false;
            activeTab = Tab.MESSAGE;
            pageStack.push(new MainPage());
        }
    }

    /** 「切屏动画」全局界面动画总开关是否开启（关闭时所有动画逻辑跳过，瞬时切换） */
    private static boolean uiAnimEnabled() {
        return HuhuTalkClientSettings.isUiAnimationEnabled();
    }

    /**
     * 从桌面进入 HuhuTalk App 内容区（默认消息 tab，显示底部导航）。
     * 委托给通用 {@link #launchApp}，使用 HuhuTalk 图标底色做启动动画。
     */
    public static void openChatApp() {
        launchApp(new MessageListPage(), "HuhuTalk");
    }

    /**
     * 通用 App 启动动画：点开某 App 的首页 target 时，在对应图标中心生成其底色色块，
     * 由小放大并淡出，底层 target 页原地淡入。任何 App 复用同一套动画。
     * 播放期间普通点击屏蔽，右键可强制打断并出栈返回。
     */
    public static void launchApp(IPhonePage target, String appName) {
        if (!visible) return;
        inChatApp = true;
        activeTab = Tab.MESSAGE;
        pageStack.clear();
        pageStack.push(target);
        if (uiAnimEnabled()) {
            int[] c = MainPage.appIconCenterScreen(appName);
            if (c != null) {
                launchCx = c[0];
                launchCy = c[1];
                launchIconW = c[2];
                launchBlockColorRgb = c[3];
                launchActive = true;
                launchTween = new AnimTween(LAUNCH_DURATION);
            }
        }
    }

    /** 强制打断启动动画（右键抢占）：立即收尾，恢复渲染目标首页 */
    private static void forceFinishLaunch() {
        launchActive = false;
        launchTween = null;
    }

    public static boolean isVisible() { return visible; }

    public static IPhonePage getCurrentPage() {
        return pageStack.isEmpty() ? null : pageStack.peek();
    }

    /**
     * 当前是否处于"子页"（页面栈里除底页外还压着页面）。
     * 不限定 {@code inChatApp}：桌面推入的页（如手册/设置）也算子页，
     * 右键应弹回桌面而非直接关手机。底部导航的显示仍需另行判断 {@code inChatApp}。
     */
    public static boolean isInSubPage() {
        return pageStack.size() > 1;
    }

    public static Tab getActiveTab() { return activeTab; }

    /** 是否处于 HuhuTalk App 内容区 */
    public static boolean isInChatApp() { return inChatApp; }

    /** 切换到某个底部 tab（仅 HuhuTalk App 内有效）。Dock 切 tab 不走盖层转场（瞬时切换） */
    public static void switchTab(Tab tab) {
        if (!inChatApp) return;
        if (activeTab == tab && pageStack.size() == 1) {
            return;
        }
        activeTab = tab;
        pageStack.clear();
        pageStack.push(createTopPage(tab));
    }

    /**
     * 进入内部页（聊天/详情/信息设置），叠在当前页之上。
     * push 转场：新页从右侧伸出盖住当前页（当前页不动、被盖住）。
     */
    public static void pushPage(IPhonePage page) {
        if (!visible) return;
        IPhonePage from = null;
        if (!pageStack.isEmpty()) {
            from = pageStack.peek();
            from.onClose();
        }
        pageStack.push(page);
        page.onOpen();
        beginTransition(from, page, true);
    }

    /** 返回上一页；HuhuTalk App 内顶层页右键返回桌面 */
    public static void popPage() {
        if (pageStack.isEmpty()) return;
        if (pageStack.size() > 1) {
            // 内部页 pop 转场：当前页向右缩回，露出下层已渲染的上一级页
            IPhonePage closed = pageStack.pop();
            closed.onClose();
            IPhonePage below = pageStack.peek();
            below.onOpen();
            beginTransition(closed, below, false);
        } else if (inChatApp) {
            // App 顶层页：返回桌面（桌面为另一套动画，此处瞬时切换）
            inChatApp = false;
            activeTab = Tab.MESSAGE;
            pageStack.clear();
            pageStack.push(new MainPage());
        }
        // 桌面模式栈深 1：不弹出（返回交给右键关闭手机）
    }

    /**
     * 启动页面转场。切屏动画关闭时不转场（瞬时切换）；无可视进出页时跳过。
     * from/to 仅用于渲染期捕捉，栈的增删已在上层立即完成。
     *
     * @param isPush true=push（移动的是新页 transTo，盖住静止的 transFrom）；
     *               false=pop（移动的是当前页 transFrom，缩回露出静止的 transTo）
     */
    private static void beginTransition(IPhonePage from, IPhonePage to, boolean isPush) {
        if (!uiAnimEnabled() || from == null || to == null || from == to) {
            return;
        }
        transFrom = from;
        transTo = to;
        transIsPush = isPush;
        transTween = new AnimTween();
    }

    /** 强制打断当前转场（右键抢占）：立即收尾，栈已正确，恢复渲染当前顶页 */
    private static void forceFinishTransition() {
        transTween = null;
        transFrom = null;
        transTo = null;
        transIsPush = false;
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

    // ==================== 相机拍照隐藏 ====================

    /**
     * 相机拍照：临时隐藏手机一帧，让下一帧的抓取层截到干净的纯游戏画面，再把 PNG 字节回调出去。
     * 必须在渲染线程调用。
     */
    public static void hideForCapture(java.util.function.Consumer<byte[]> onDone) {
        captureHidden = true;
        grabPending = true;
        grabCallback = onDone;
    }

    /**
     * 由手机之后注册的 GUI 抓取层每帧调用：若处于"已隐藏待抓取"状态，抓主渲染目标转 PNG 并回调，随后恢复手机。
     * 在渲染线程执行。
     */
    public static void consumeCaptureGrab() {
        if (!grabPending || !captureHidden) {
            return;
        }
        grabPending = false;
        captureHidden = false;
        java.util.function.Consumer<byte[]> cb = grabCallback;
        grabCallback = null;
        if (cb == null) {
            return;
        }
        byte[] png = com.huhutalk.client.PhotoLibrary.captureMainTargetPng();
        cb.accept(png);
    }

    // ==================== 渲染 ====================

    public static void render(GuiGraphics graphics, float partialTick) {
        if (!visible) return;

        // 相机拍照临时隐藏：本帧不渲染手机，让后续抓取层拿到干净的纯游戏画面
        if (captureHidden) return;

        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // 手机尺寸 = 屏幕比例（高:宽，aspectRatio 返回宽/高）× 高度比例（占实际屏幕高 0.5~1.0）
        float size = HuhuTalkClientSettings.getPhoneSize();
        float aspect = aspectRatio(HuhuTalkClientSettings.getPhoneAspect());
        int totalHeight = (int)Math.round(screenH * size);
        int width = (int)Math.round(totalHeight * aspect);
        phoneWidth = Math.max(MIN_PHONE_W, Math.min(width, screenW - 20));
        phoneHeight = Math.max(20, totalHeight - UiFont.sh(STATUSBAR_H) - UiFont.sh(WATERMARK_H));

        // 默认锚点：右下角（右/下各留 10px）
        int defX = screenW - phoneWidth - 10;
        int defY = screenH - totalHeight - 10;

        // 拖动时：手机实时跟随鼠标，并把约束后的位置折算为偏移量落盘（呼出/收起动画期间禁止拖动）
        if (dragging && containerAnim == ContainerAnim.IDLE) {
            int mx = guiMouseX(mc);
            int my = guiMouseY(mc);
            int targetX = (int)Math.round(mx - grabDX);
            int targetY = (int)Math.round(my - grabDY);
            int clampedX = clampPhoneX(targetX, screenW);
            int clampedY = clampPhoneY(targetY, screenH);
            HuhuTalkClientSettings.setPhoneOffsetRaw(clampedX - defX, clampedY - defY);
        }

        phoneX = defX + Math.round(HuhuTalkClientSettings.getPhoneOffsetX());
        phoneY = defY + Math.round(HuhuTalkClientSettings.getPhoneOffsetY());
        // 约束：水平至多半个手机宽度出屏（顶部栏仍留一半可抓）；垂直保证状态栏高度完整在屏内
        phoneX = clampPhoneX(phoneX, screenW);
        phoneY = clampPhoneY(phoneY, screenH);

        // 手机容器动画：呼出/收起沿 Y 轴滑动（唯一需要鼠标逆矩阵矫正的动画）。
        // 起点/终点统一以「屏幕下缘 ↔ 静止位置」计算，故手机无论被拖到哪个位置都从屏幕底部滑入/滑出。
        float animOffsetY = 0f;
        if (uiAnimEnabled()) {
            if (containerAnim != ContainerAnim.IDLE && containerTween != null) {
                float raw = containerTween.raw();
                float baseOffset = (float) (screenH - phoneY); // 静止位置到屏幕下缘的距离
                if (containerAnim == ContainerAnim.ENTER) {
                    // 呼出：ease-out 从屏幕下缘上滑到静止位置
                    animOffsetY = baseOffset * (1f - AnimEasing.easeOut(raw));
                    if (raw >= 1f) {
                        containerAnim = ContainerAnim.IDLE;
                        containerTween = null;
                    }
                } else if (containerAnim == ContainerAnim.EXIT) {
                    // 收起：ease-in 下滑出屏幕下缘，播完才真正隐藏
                    animOffsetY = baseOffset * AnimEasing.easeIn(raw);
                    if (raw >= 1f) {
                        doHideNow();
                        return;
                    }
                }
            }
        } else {
            // 全局总开关关闭：瞬时切换，重置动画状态
            containerAnim = ContainerAnim.IDLE;
            containerTween = null;
            transTween = null;
            transFrom = null;
            transTo = null;
            transIsPush = false;
            launchActive = false;
            launchTween = null;
        }
        int ry = phoneY + Math.round(animOffsetY);
        renderedPhoneY = ry;

        // 手机壳（纯视觉，包住整个手机）。镂空内收状态栏/水印栏高度，托住上下导航栏圆角
        PhoneCaseRenderer.renderCase(graphics, phoneX, ry, phoneWidth, totalHeight,
                UiFont.sh(STATUSBAR_H), UiFont.sh(WATERMARK_H));

        // 当前页面
        IPhonePage page = getCurrentPage();
        // 相机模式（聊天页📷）：手机体透明化，让游戏画面从取景框透出来
        boolean cameraTransparent = page instanceof ChatPage cp && cp.isCameraMode();

        // 手机背景（圆角：上圆角=状态栏高，下圆角=水印栏高）。相机模式下透明，露出游戏画面
        fillRoundedRect(graphics, phoneX, ry, phoneWidth, totalHeight, UiFont.sh(STATUSBAR_H), UiFont.sh(WATERMARK_H),
                cameraTransparent ? 0x00111111 : 0xEE111111);

        // 统一状态栏（圆角顶）
        renderStatusBar(graphics, phoneX, ry, phoneWidth);

        // 当前页面内容区：从状态栏下方开始，到水印栏上方结束。
        // 转场中同时绘制退场页(向右滑出+淡出)与进场页(自右滑入+淡入)，否则只绘制当前顶页。
        int contentBaseX = phoneX;
        int contentBaseY = ry + UiFont.sh(STATUSBAR_H);
        if (uiAnimEnabled() && launchActive && launchTween != null) {
            // APP 桌面启动：底层首页原地淡入 0→1，顶层 APP 色块由小放大、1→0 淡出
            float raw = launchTween.raw();
            float e = AnimEasing.easeOut(raw);
            if (page != null) {
                renderPageTranslated(graphics, page, contentBaseX, contentBaseY, e);
            }
            // 色块裁剪到内容区，避免放大溢出到手机壳/屏幕外
            graphics.enableScissor(contentBaseX, contentBaseY, contentBaseX + phoneWidth, contentBaseY + phoneHeight);
            int cover = Math.round((float) Math.hypot(phoneWidth, phoneHeight) * 2.5f);
            int blockSize = Math.round(launchIconW + (cover - launchIconW) * e);
            int alpha = Math.round((1f - e) * 255);
            int blockColor = (alpha << 24) | (launchBlockColorRgb & 0xFFFFFF);
            UiShapes.fillRoundedRect(graphics, Math.round(launchCx - blockSize / 2f), Math.round(launchCy - blockSize / 2f),
                    blockSize, blockSize, Math.max(2, blockSize / 4), blockColor);
            graphics.disableScissor();
            if (raw >= 1f) {
                launchActive = false;
                launchTween = null;
            }
        } else if (uiAnimEnabled() && transTween != null && transFrom != null && transTo != null) {
            float raw = transTween.raw();
            float ease = transIsPush ? AnimEasing.easeOut(raw) : AnimEasing.easeIn(raw);
            // 移动页在最上层：push=新页自右盖入(alpha 0.3→1)；pop=当前页向右缩回(alpha 1→0.3)
            int moveDx = transIsPush
                    ? Math.round(phoneWidth * (1f - ease))   // +width → 0
                    : Math.round(phoneWidth * ease);         // 0 → +width
            float moveAlpha = transIsPush ? 0.3f + 0.7f * ease : 1f - 0.7f * ease;
            // 静止垫底页在最下层（alpha 恒为 1）
            IPhonePage under = transIsPush ? transFrom : transTo;
            IPhonePage moving = transIsPush ? transTo : transFrom;

            // 裁剪到手机内容区，防止滑动的页面溢出到手机壳/屏幕外
            graphics.enableScissor(contentBaseX, contentBaseY, contentBaseX + phoneWidth, contentBaseY + phoneHeight);
            renderPageTranslated(graphics, under, contentBaseX, contentBaseY, 1f);
            renderPageTranslated(graphics, moving, contentBaseX + moveDx, contentBaseY, moveAlpha);
            graphics.disableScissor();

            if (raw >= 1f) {
                transTween = null;
                transFrom = null;
                transTo = null;
                transIsPush = false;
            }
        } else if (page != null) {
            page.render(graphics, contentBaseX, contentBaseY, partialTick);
        }

        // 底部导航（仅 HuhuTalk App 内且处于顶层页时显示），悬浮于水印栏上方并上抬留出空隙
        if (inChatApp && !isInSubPage()) {
            renderBottomNav(graphics, phoneX, ry + totalHeight - UiFont.sh(WATERMARK_H) - bottomNavH() - navRaise(), phoneWidth);
        }

        // 底部水印栏（圆角底）
        renderWatermark(graphics, phoneX, ry + totalHeight - UiFont.sh(WATERMARK_H), phoneWidth, UiFont.sh(WATERMARK_H));

        // 屏幕亮度滤镜：按设置叠一层黑色圆角蒙版，把「手机屏 + 手机壳」一起变暗（0~70% 不透明度）。
        // 亮度 1 = 满亮度不调；往低调，蒙版越不透明（最低叠 70%）。不拦截点击。
        float brightness = HuhuTalkClientSettings.getScreenBrightness();
        if (brightness < 0.999f) {
            int alpha = Math.round((1f - brightness) * 0.70f * 255); // 0~178 → 0~70%
            int dimColor = (alpha << 24) | 0x000000;
            int caseX = phoneX - PhoneCaseRenderer.CASE_THICKNESS;
            int caseY = ry - PhoneCaseRenderer.CASE_THICKNESS;
            int caseW = phoneWidth + PhoneCaseRenderer.CASE_THICKNESS * 2;
            int caseH = totalHeight + PhoneCaseRenderer.CASE_THICKNESS * 2;
            fillRoundedRect(graphics, caseX, caseY, caseW, caseH,
                    PhoneCaseRenderer.CORNER_RADIUS, PhoneCaseRenderer.CORNER_RADIUS, dimColor);
        }
    }

    /**
     * 以指定屏幕左上角 (x,y) 与全局透明度 alpha 绘制一个页面。
     * <p>
     * 必须把真实屏幕坐标作为 baseX/baseY 直接传给页面，而非用 pose 平移——
     * 因为页面内部的 scissor（滚动裁剪、顶栏遮罩）把 baseX/baseY 当作屏幕绝对坐标，
     * 不感知 pose 变换；用 pose.translate 传 (0,0) 会导致裁剪错乱、背景错位。
     * 透明度经 RenderSystem.setShaderColor 的 alpha 通道统一调制
     * （fill 与 drawString 使用的 position_color/position_tex_color 着色器都会乘上该值），
     * 渲染后务必恢复为不透明，以免污染后续绘制。
     */
    private static void renderPageTranslated(GuiGraphics g, IPhonePage page, int x, int y, float alpha) {
        if (page == null) return;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        page.render(g, x, y, 0f);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    /** 统一状态栏：时间 / 信号 / 电源 / 关闭（圆角顶；内容 0.75 倍×全局倍率、离开圆角范围） */
    private static void renderStatusBar(GuiGraphics graphics, int baseX, int baseY, int w) {
        Font font = Minecraft.getInstance().font;
        fillRoundedRect(graphics, baseX, baseY, w, UiFont.sh(STATUSBAR_H), UiFont.sh(STATUSBAR_H), 0, 0xFF222222);
        // 时间（跟随世界内部时间，向左留出圆角，往中间移一点）
        drawScaledText(graphics, font, Component.literal(gameTimeString()).getVisualOrderText(),
                baseX + 14, baseY + 3, 0xFFFFFFFF, UiFont.sc(0.75f));
        int rightX = baseX + w;
        // 关闭 X（完全透明：不绘制文字，视觉不可见；点击区域逻辑保留于 handleMouseClicked 状态栏右侧）
        // 电池：垂直电池图标（🔋 旋转 90° 仿现实电池，尺寸为原 emoji 的 1.5 倍，位于原 X 左侧）
        renderBattery(graphics, rightX - UiFont.sh(28), baseY + UiFont.sh(STATUSBAR_H) / 2);
        // 信号（0.75 倍，离开右圆角，位于电池左侧）
        renderSignalBars(graphics, rightX - UiFont.sh(50), baseY, UiFont.sh(STATUSBAR_H));
    }

    /** 电池图标：将 🔋 emoji 旋转 90° 变为垂直（正极触点朝上），仿现实电池，尺寸为原 emoji 的 1.5 倍 */
    private static void renderBattery(GuiGraphics g, int centerX, int centerY) {
        Font font = Minecraft.getInstance().font;
        FormattedCharSequence glyph = Component.literal("🔋").getVisualOrderText();
        float gw = font.width(glyph);
        float gh = font.lineHeight;
        float scale = UiFont.sc(0.75f) * 1.5f; // 1.5 倍
        g.pose().pushPose();
        g.pose().translate(centerX, centerY, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(90)); // 顺时针 90°，使正极朝上
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, glyph, -gw / 2f, -gh / 2f, 0xFF4CAF50, false); // 电池绿色
        g.pose().popPose();
    }

    /**
     * 当前世界内部时间，格式化为 HH:MM。
     * Minecraft 时间轴：dayTime%24000，0=6:00、6000=12:00、12000=18:00、18000=0:00。
     */
    private static String gameTimeString() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return "12:00";
        }
        long dayTime = mc.level.getDayTime() % 24000;
        int hours = (int) ((dayTime / 1000 + 6) % 24);
        int minutes = (int) ((dayTime % 1000) * 60 / 1000);
        return String.format("%02d:%02d", hours, minutes);
    }

    /** 底部水印栏：深蓝底 + 白色加粗版本文字（圆角底；文字 0.75 倍、用空格分隔，过宽时缩放适配） */
    private static void renderWatermark(GuiGraphics graphics, int baseX, int baseY, int w, int h) {
        Font font = Minecraft.getInstance().font;
        fillRoundedRect(graphics, baseX, baseY, w, h, 0, UiFont.sh(WATERMARK_H), WATERMARK_BG);
        String version = ModList.get().getModContainerById(HuhuTalk.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("1.0.1");
        Component comp = Component.literal("HUHUTALK  VISION " + version)
                .withStyle(s -> s.withBold(true));
        FormattedCharSequence seq = comp.getVisualOrderText();
        float tw = font.width(seq);
        float cap = 0.75f * UiFont.uiScale();
        float scale = Math.max(0.5f, Math.min(cap, (w - 8f) / tw));
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
        int[] hs = {5, 4, 3}; // 从左到右依次降低；原 {5,7,9} × 0.6 取整
        int barW = 1;         // 变细
        int spacing = 3;      // 与宽度保持紧凑
        ResourceKey<Level> dim = signalDimension();
        // 三条底部对齐，整组在状态栏高度内垂直居中（有信号栏的感觉）
        int maxH = Math.max(Math.max(hs[0], hs[1]), hs[2]);
        int baseline = baseY + (barAreaH - maxH) / 2;
        for (int i = 0; i < 3; i++) {
            int x = startX + i * spacing;
            int h = hs[i];
            int y = baseline + (maxH - h);
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

    /** 底部导航（Dock）：悬浮胶囊，三个选项横向均分（emoji 上 + 0.75 倍×全局倍率文字下），选中项粉色底 */
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
        float ts = UiFont.sc(0.75f); // 文字/图标缩放（随全局倍率）
        int lineH = (int)(font.lineHeight * ts);

        for (int i = 0; i < 3; i++) {
            int itemX = cx + i * slotW;
            int centerX = itemX + slotW / 2;
            // 选中项底色：#de7e7e 圆角胶囊
            if (i == activeIdx) {
                fillRoundedRect(graphics, itemX + 1, baseY + 1, slotW - 2, h - 2, (h - 2) / 2f, (h - 2) / 2f, 0xFFDE7E7E);
            }
            // emoji（上）
            FormattedCharSequence emoji = Component.literal(emojis[i]).getVisualOrderText();
            drawScaledText(graphics, font, emoji, centerX - font.width(emoji) * ts / 2f, baseY + 1, 0xFFFFFFFF, ts);
            // 文字（下，0.75 倍×全局倍率）
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

        // 手机容器动画期间（呼出/收起）：
        // - 收起(EXIT)：吞掉一切输入（手机正在离场）；
        // - 呼出(ENTER)：除「右键强制收起」外全部丢弃（规则 3）。
        if (uiAnimEnabled() && containerAnim != ContainerAnim.IDLE) {
            if (action == GLFW.GLFW_PRESS
                    && button == GLFW.GLFW_MOUSE_BUTTON_RIGHT
                    && containerAnim == ContainerAnim.ENTER) {
                hide();          // 紧急强制收起
                updateCursorMode();
            }
            return true;
        }

        // 页面转场/启动动画期间：普通点击屏蔽；
        // 右键为全局最高抢占，强制打断当前动画并出栈返回（可连续右键连续出栈）。
        if (uiAnimEnabled() && (transTween != null || launchActive)) {
            if (action == GLFW.GLFW_PRESS && button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                forceFinishTransition();
                forceFinishLaunch();
                popPage();
                updateCursorMode();
            }
            return true;
        }

        // 拖拽释放：无论鼠标此刻是否还在手机内都要结束拖拽并落盘
        if (action == GLFW.GLFW_RELEASE && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && dragging) {
            dragging = false;
            HuhuTalkClientSettings.save();
            return true;
        }

        // 逆矩阵矫正：容器动画期间渲染位移了 phoneY，命中判定必须对准渲染位置 renderedPhoneY
        double relX = screenX - phoneX;
        double relY = screenY - renderedPhoneY;
        if (relX < 0 || relY < 0 || relX >= phoneWidth || relY >= phoneHeight + UiFont.sh(STATUSBAR_H) + UiFont.sh(WATERMARK_H)) {
            return false;
        }

        // 状态栏 / 底部导航 / 水印栏 / 右键返回：仅在按下时处理
        if (action == GLFW.GLFW_PRESS) {
            // 状态栏区域：关闭按钮 / 其余按住左键拖动手机
            if (relY < UiFont.sh(STATUSBAR_H)) {
                if (relX >= phoneWidth - 14 && relX <= phoneWidth) {
                    hide();
                    updateCursorMode();
                } else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    dragging = true;
                    grabDX = (float) (screenX - phoneX);
                    grabDY = (float) (screenY - phoneY);
                }
                return true;
            }

            // 底部水印栏区域（最底部）：装饰，不响应点击
            if (relY >= phoneHeight + UiFont.sh(STATUSBAR_H)) {
                return true;
            }

            // 底部导航区域（仅 HuhuTalk App 内顶层页显示时；胶囊范围内可点，已随胶囊上抬）
            if (inChatApp && !isInSubPage()) {
                int navTop = phoneHeight + UiFont.sh(STATUSBAR_H) - bottomNavH() - navRaise();
                int navBottom = phoneHeight + UiFont.sh(STATUSBAR_H) - navRaise();
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

            // 右键：录音中优先取消（丢弃本次语音）；相机模式下先退出拍照界面回聊天；
            // 否则按 App 内部页/顶层页/桌面处理返回
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                IPhonePage curPage = getCurrentPage();
                if (curPage instanceof ChatPage cp && cp.isVoiceRecording()) {
                    cp.cancelVoice();
                    return true;
                }
                // 页面自身处理右键返回（多级回退，如手册子栏目逐级返回）；已消费则不 popPage
                if (curPage != null && curPage.onBackPressed()) {
                    return true;
                }
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
        return page.mouseClicked(relX, relY - UiFont.sh(STATUSBAR_H), button, action);
    }

    public static boolean handleMouseScroll(double screenX, double screenY, double scrollDelta) {
        if (!visible) return false;
        // 容器动画期间吞掉滚轮（呼出/收起时不可滚动页面）
        if (uiAnimEnabled() && containerAnim != ContainerAnim.IDLE) {
            return true;
        }
        // 页面转场/启动动画期间吞掉滚轮
        if (uiAnimEnabled() && (transTween != null || launchActive)) {
            return true;
        }
        IPhonePage page = getCurrentPage();
        if (page == null) return false;
        double relX = screenX - phoneX;
        double relY = screenY - renderedPhoneY;
        if (relX >= 0 && relY >= 0 && relX < phoneWidth && relY < phoneHeight + UiFont.sh(STATUSBAR_H)) {
            if (relY < UiFont.sh(STATUSBAR_H)) {
                return true;
            }
            return page.mouseScrolled(relX, relY - UiFont.sh(STATUSBAR_H), scrollDelta);
        }
        return true;
    }

    /** 进入新世界时调用：关闭手机、清空页面栈（无动画，强制瞬时） */
    public static void resetForNewWorld() {
        visible = false;
        dragging = false;
        inChatApp = false;
        containerAnim = ContainerAnim.IDLE;
        containerTween = null;
        forceFinishTransition();
        forceFinishLaunch();
        pageStack.clear();
        activeTab = Tab.MESSAGE;
        if (Minecraft.getInstance().screen instanceof PhoneInputScreen) {
            Minecraft.getInstance().setScreen(null);
        }
        updateCursorMode();
    }
}
