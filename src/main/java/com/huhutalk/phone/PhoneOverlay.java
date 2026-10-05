package com.huhutalk.phone;

import com.huhutalk.HuhuTalk;
import com.huhutalk.api.phone.camera.PhoneCameraHost;
import com.huhutalk.client.render.PhoneScreenTarget;
import com.huhutalk.client.render.ProPhoneHandRenderer;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.client.theme.ColorScheme;
import com.huhutalk.client.theme.PhoneCaseRenderer;
import com.huhutalk.client.theme.ThemeManager;
import com.huhutalk.init.HuhuTalkItems;
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

    /**
     * 手机 UI 的<b>渲染形式</b>（期 1：两种屏幕互斥）。
     * <p>
     * 与「开/关」（{@link #phoneOpen}）正交：{@code phoneOpen} 说的是"手机的页面栈开没开、
     * 停留在哪一页"，{@link #renderMode} 说的是"这开着的 UI 画到哪里去"。
     * <p>
     * 拆开之后「掏出即渲染」才成立：玩家把手伸进主手 = 掏手机，
     * 不需要再按一次 O 才算"打开"，也<b>不再需要低头到阈值才亮屏</b> ——
     * 低头阈值从此只管「操控」（光标能动 + 相机降速），不管「显示」。
     */
    public enum RenderMode {
        /** 不画任何东西（但 {@link #phoneOpen} 可以仍是 true，页面栈保留） */
        NONE,
        /**
         * 贴到玩家主手那台 3D 手机的屏幕面上（离屏纹理）。
         * 判据见 {@link #hasModelScreen()}：主手真的是 Pro 手机 + 第一人称。
         */
        MODEL,
        /**
         * 原版悬浮窗（屏幕空间）。由 O 键<b>显式</b>召唤 —— 这是刻意保留的"特殊手段"：
         * 玩家不想用模型屏可以不用，但查看消息这类场景不能没有它。
         */
        FLOATING
    }

    /** 页面栈是否开着（手机的"逻辑开/关"）；真正的可见性见 {@link #isVisible()} */
    private static boolean phoneOpen = false;
    /** 这一开着的 UI 画到哪里去；与 {@link #phoneOpen} 正交 */
    private static RenderMode renderMode = RenderMode.NONE;
    /**
     * 上一 tick 主手是否<b>真的</b>在举着 Pro 手机（{@link ProPhoneHandRenderer#isActive()}）。
     * <p>
     * 只用于「掏出即渲染」的<b>上升沿</b>检测：从"没举着"变成"举着"的那一刻自动打开手机。
     * 必须存边沿而不是存状态 —— 否则玩家按 O 收起手机后，下一 tick 又会被无条件打开，
     * 形成"关不掉"的循环。
     */
    private static boolean holderWasActive = false;
    /** 是否处于 HuhuTalk App 内容区（显示底部导航）；false = 桌面模式 */
    private static boolean inChatApp = false;

    /** 顶层 tab 页 + 内部页共用一个栈；栈底为当前 tab 的顶层页 */
    private static final Stack<IPhonePage> pageStack = new Stack<>();
    private static Tab activeTab = Tab.MESSAGE;

    private static int phoneWidth;
    private static int phoneHeight;
    private static int phoneX, phoneY;

    /** 相机拍照：已受理一次「抓主渲染目标」的请求，等本帧 {@code AFTER_LEVEL} 去执行 */
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

    /**
     * 离屏（模型屏）模式的画布尺寸；{@code > 0} 表示当前这一帧是在为模型屏渲染。
     * <p>
     * 置位期间：尺寸直接取画布大小、位置钉在 (0,0)、不画手机壳、不做呼出/收起滑动、
     * 亮度蒙版不外扩、拖动与拖动落盘全部跳过。除此以外与屏幕模式**完全共用**同一套绘制逻辑。
     */
    private static int externalW = -1;
    private static int externalH = -1;

    /** 本帧是否正在为模型屏离屏渲染 */
    public static boolean isExternalRender() {
        return externalH > 0;
    }

    // ==================== 独立光标（旁路输入） ====================
    //
    // 设计原则：手机只做加法，从不做减法 —— 鼠标移动照常转视角，左右键照常攻击/放置，
    // 只有「手机侧键」这一路额外被手机接收。唯一例外是玩家主动点进文本输入框（真 Screen）。
    // 光标画在 Overlay 自己的坐标系里，因此会随 Overlay 一起进入离屏纹理，
    // 悬浮窗与模型屏两边的表现天然完全等价。

    /** 拖动滚动的判定阈值（GUI 单位）：超过它才从「点击」升级为「滚动」 */
    private static final float DRAG_SCROLL_THRESHOLD = 4f;
    /** 拖动多少 GUI 单位折算 1 个单位滚轮（各页 mouseScrolled 按 scrollDelta×10 像素滚动） */
    private static final double DRAG_PIXELS_PER_SCROLL_UNIT = 10.0;

    // -------------------- 「操控屏幕」状态机 --------------------
    //
    // 目的：鼠标同时驱动视角与光标时，玩家想把光标移到屏幕上沿，视角却先抬起来看不到屏幕了。
    // 量化过：光标走完全屏需要约 1950 鼠标像素，同样的位移会让俯仰变化约 292° —— 两个速度差 5~20 倍，
    // 所以只能压低视角转速（或改手机倾角），单纯调光标灵敏度在数学上不可能成立。
    //
    // 判定：持有 Pro 手机 + 掏出手机（Overlay 已开）+ 低头 >= LOOK_DOWN_DEG。
    //   · 在内：光标可动、视角转速 ×CAMERA_TURN_SCALE
    //   · 在外：光标冻结、视角完全原版
    //
    // ⚠ 进入与退出**共用同一个阈值**（无滞回带）。这是刻意的取舍：
    //   状态是俯仰角的纯函数 → 玩家随时抬头越过阈值就能退出，绝不会"进了出不来"，
    //   而且他感到变慢的那一刻必然正低头看着手机屏幕，能自然归因。

    /**
     * 进入/退出「操控屏幕」的低头阈值（度）。MC 约定：xRot <b>正值</b> 代表低头。
     * <p>
     * 取 40 而不是 30 的原因（实机结论）：从 0° 低头到 30° 的过程正是<b>手机模型从前倾转到正对视线</b>
     * 的过程 —— 30° 时屏幕虽然能操作，但还没回正、读着别扭。40° 基本就是"屏幕正对你"的姿态。
     * <p>
     * <b>人工微调入口</b>：改这一个常量即可（没有别处硬编码 30/40）。
     */
    private static final float LOOK_DOWN_DEG = 40f;
    /**
     * 俯仰低通时间常数（毫秒）。
     * <p>
     * 单阈值 + 「光标能动/不能动」是硬状态，若玩家俯仰恰好停在阈值附近，
     * 鼠标微动会让光标在能动与冻结之间高频来回跳。对俯仰做低通再比较阈值即可消掉，
     * 阈值本身仍是单值、仍随时可退出 —— 这不是滞回带。
     */
    private static final float PITCH_FILTER_MS = 120f;

    /** 低通后的俯仰角（度） */
    private static float filteredPitch = 0f;
    private static long lastPitchSampleAt = 0L;

    /**
     * 光标位置，<b>归一化</b>到手机矩形的 0~1（而不是手机局部像素）。
     * <p>
     * 必须归一化：手机逻辑尺寸在两个模式下并不相同 —— 模型屏画布由 PhoneScreenTarget 定
     * （例如 119×219），悬浮窗由「屏幕逻辑高 × phoneSize」定（例如 142×252）。
     * 若按局部像素存储，光标会被两套矩形<b>交替钳制</b>、最终取到两者的交集，
     * 于是较大那个屏幕上永远够不到右边和底边（实测踩到）。
     * 归一化后两个模式看到的是同一个「相对位置」，各自映射到自己的矩形即可。
     */
    private static float cursorNX = 0.5f;
    private static float cursorNY = 0.5f;
    /** 手机整体高度（状态栏 + 内容 + 水印栏）；渲染时更新，供光标映射与钳制 */
    private static int phoneTotalHeight = 0;
    /**
     * 待重置标记：手机刚被掏出（或刚换世界）时置位，由下一次 {@link #render} 在算出
     * {@link #phoneTotalHeight} 之后消费 —— 顶栏高度的换算需要手机整体高度，
     * 而掏出那一刻布局还没算（切换模式时窗口尺寸还会变），所以不能当场换算。
     */
    private static boolean cursorResetPending = false;
    /** 侧键按住中 */
    private static boolean cursorPressing = false;

    /**
     * 相机模式长按「保存到相册」的阈值（毫秒）。
     * <p>
     * 取 350：短于常见"手抖"时长（约 100ms）不会误触；又短到玩家按住时能立刻看到气泡，
     * 不需要刻意"按久一点"。要改手感就改这一个数。
     */
    private static final long CAM_HOLD_SAVE_MS = 350L;
    /** 相机模式当前按住的侧键：0=无，1=侧键4（左键），2=侧键5（右键） */
    private static int cameraHoldButton = 0;
    /** 该次按住开始的时间戳（毫秒） */
    private static long cameraHoldStartAt = 0L;
    /** 本次按住是否已经触发过"长按保存"，用于松开时不再补一次轻点动作 */
    private static boolean cameraHoldFired = false;
    private static float dragTotalX = 0f;
    private static float dragTotalY = 0f;
    private static boolean dragIsScroll = false;
    /**
     * 本次拖动滚动的<b>主轴是否为横向</b>。
     * <p>
     * 在"升级为滚动"的那一刻锁定，之后整个手势不再换轴 —— 否则鼠标一抖动，
     * 横向画廊会突然改用纵向分量，手感直接碎掉。
     * 由页面的 {@link IPhonePage#dragAxisHorizontal} 提供（默认纵向）。
     */
    private static boolean dragHorizontal = false;
    /** 拖动滚动的未消费余量（像素），避免小幅拖动被取整丢掉 */
    private static double scrollRemainder = 0.0;

    /** 光标在手机局部像素坐标下的 X */
    private static float cursorLocalX() {
        return cursorNX * phoneWidth;
    }

    /** 光标在手机局部像素坐标下的 Y */
    private static float cursorLocalY() {
        return cursorNY * phoneTotalHeight;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /**
     * 光标的「出生点」归一化 Y —— <b>顶部状态栏的竖直中点</b>（X 一律取 0.5，即水平正中）。
     * <p>
     * 为什么不放在正中：光标默认停在正中时，玩家低头进入操控态的第一下鼠标移动
     * （多半是继续往下划）会把视角继续压下去，等他反应过来屏幕已经跑出视野了。
     * 出生点放在顶栏 = 出生即处于{@link #isCursorInTopBar() 「顶栏放行」}状态：
     * <ul>
     *   <li>上划 → 光标仍留在顶栏 → 视角<b>全速</b>，玩家一眼看到屏幕被"放走"，能立刻归因；</li>
     *   <li>下划 → 光标离开顶栏、进入降速操控，这才是他真正想操作屏幕时的动作。</li>
     * </ul>
     * 也就是说：<b>出生点即松手出口</b>，玩家永远不需要先"学会"怎么退出。
     * <p>
     * 取值用比例而非固定像素，所以模型屏画布与悬浮窗两个模式各自映射、都落在自己的顶栏里。
     * 上限 0.5 只是防呆（极端字体倍率下顶栏高到占满手机时，至少不越出手机）。
     */
    private static float defaultCursorNY() {
        if (phoneTotalHeight <= 0) {
            return 0f;
        }
        return clamp01(UiFont.sh(STATUSBAR_H) * 0.5f / phoneTotalHeight);
    }

    /**
     * 把独立光标送回出生点（顶栏正中），并清掉按住/拖动残留。
     * <p>
     * 由 {@link #show()} 与 {@link #resetForNewWorld()} 调用；同时立刻按当前已知尺寸换算一次，
     * 使「手机可见的同一帧」光标就已经在顶栏上（不在屏幕模式的错误尺寸下闪一帧），
     * 并按待重置标记在下次渲染时用真实手机高度再校正一次。
     */
    private static void resetCursorToSpawn() {
        cursorNX = 0.5f;
        cursorNY = defaultCursorNY();
        cursorPressing = false;
        dragIsScroll = false;
        dragTotalX = 0f;
        dragTotalY = 0f;
        scrollRemainder = 0.0;
        cursorResetPending = true;
    }

    /**
     * 每帧更新俯仰低通（由 {@code ClientModEvents.onRenderFramePre} 调用）。
     * 用真实时间做指数平滑，与帧率无关。
     */
    public static void updateLookFilter() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            filteredPitch = 0f;
            lastPitchSampleAt = 0L;
            return;
        }
        float pitch = mc.player.getXRot();
        long now = System.nanoTime();
        if (lastPitchSampleAt == 0L) {
            filteredPitch = pitch;
            lastPitchSampleAt = now;
            return;
        }
        float dtMs = (now - lastPitchSampleAt) / 1_000_000f;
        lastPitchSampleAt = now;
        float alpha = Math.min(1f, dtMs / PITCH_FILTER_MS);
        filteredPitch += (pitch - filteredPitch) * alpha;
    }

    /**
     * 主手此刻是否真的拿着 Pro 手机 —— 就是字面意义的"手里有没有这台手机"。
     * <p>
     * 刻意<b>只查物品、不看相机类型</b>（与 {@link ProPhoneHandRenderer#isActive()} 的区别就在这）：
     * <ul>
     *   <li>渲染判据 {@code isActive()} 额外要求第一人称（模型屏只在第一人称贴得上）；</li>
     *   <li>而"手里有没有手机"是<b>状态机</b>的事：第三人称下手机照样在手里，
     *       O 召唤的悬浮窗也照样能用，没理由把它判成"没拿手机"。</li>
     * </ul>
     * 两件事必须分开：拿 {@code isActive()} 当"手持"会让第三人称的悬浮窗一起失效。
     */
    public static boolean isHoldingPhone() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getMainHandItem().is(HuhuTalkItems.PHONE_SMART.get());
    }

    /**
     * 当前是否处于「操控屏幕」态。三个条件<b>同时</b>成立才算：
     * <ol>
     *   <li>手机 UI 已掏出（{@link #isVisible()}）；</li>
     *   <li><b>主手确实拿着 Pro 手机</b>（{@link #isHoldingPhone()}）；</li>
     *   <li>低头 ≥ {@link #LOOK_DOWN_DEG} 度。</li>
     * </ol>
     * 另外「光标停在顶栏」由调用方 {@code ClientModEvents.onCalculatePlayerTurn} 单独放行
     * （见 {@link #isCursorInTopBar()}）。
     * <p>
     * <b>⚠ 第 2 条不能省</b>（实机 bug）：曾经只判前两条，于是"空手低头"也会进这个态 ——
     * 视角被静默降到 0.25 倍速，玩家却找不到任何理由。
     * 可见性那条<b>不够用</b>：{@link #isVisible()} 只管"UI 该不该画"，
     * 而这里是"玩家此刻有没有在操控一台真机"。两条判据要一起查，别只留一条。
     * <p>
     * 这是<b>纯函数</b>（除俯仰低通外无内部状态），所以随时抬头就能退出，
     * 不存在"进了出不来"的情况。
     */
    public static boolean isScreenControl() {
        return isVisible() && isHoldingPhone() && filteredPitch >= LOOK_DOWN_DEG;
    }

    /**
     * 独立光标是否停在顶部状态栏里。
     * <p>
     * 用途：作为「顶栏放行相机」的判据 —— 光标被推到顶栏时，视角转速<b>恢复正常</b>
     * （但光标仍可动，可以再拉下来重新进入限速）。
     * <p>
     * 为什么必须有这条：玩家若在低头 90° 时掏出手机，退出操控态要靠抬头越过
     * {@link #LOOK_DOWN_DEG} —— 而在 0.25 倍转速下爬完这 50° 要四倍的鼠标位移，很难受。
     * 把光标推到顶栏就是那条"快速松手"的出路（实机反馈补上）。
     */
    public static boolean isCursorInTopBar() {
        return cursorLocalY() < UiFont.sh(STATUSBAR_H);
    }

    /**
     * 独立光标：接收鼠标原始增量。
     * <p>
     * 由 {@code MouseHandlerMixin} 在 {@code turnPlayer} 处喂入 —— 那里视角<b>照样</b>转，
     * 所以这是「同源双路」：视角走原版的 {@code sensitivity³×8} 曲线，
     * 光标走自己的 {@code 原始增量 ÷ GUI Scale × 手机光标灵敏度}，两者互不干扰。
     */
    public static void onMouseDelta(double rawDx, double rawDy) {
        if (!isVisible() || phoneTotalHeight <= 0 || phoneWidth <= 0) {
            return;
        }
        // turnPlayer 每帧都会被调用（鼠标没动时增量为 0）。
        // 这里必须提前返回：否则每帧都会标脏，dirty 机制直接失效。
        if (rawDx == 0.0 && rawDy == 0.0) {
            return;
        }
        // 不在「操控屏幕」态（没低头看屏幕）时光标冻结 ——
        // 这是「手机只做加法」原则的唯一例外，且严格限定在持有 Pro 手机 + 掏出手机时。
        if (!isScreenControl()) {
            return;
        }
        double guiScale = Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale());
        float sensitivity = HuhuTalkClientSettings.getPhoneCursorSensitivity();
        moveCursor((float) (rawDx / guiScale * sensitivity), (float) (rawDy / guiScale * sensitivity));
    }

    /** 按手机局部坐标移动光标，并处理「按住拖动 = 滚动」手势 */
    private static void moveCursor(float dx, float dy) {
        // dx/dy 是手机局部像素增量，按当前矩形折算成归一化增量 ——
        // 这样两个模式下「划同样距离 = 走同样比例」，且两边都能走到四边。
        cursorNX = clamp01(cursorNX + dx / Math.max(1f, phoneWidth));
        cursorNY = clamp01(cursorNY + dy / Math.max(1f, phoneTotalHeight));

        if (cursorPressing) {
            dragTotalX += dx;
            dragTotalY += dy;
            // 主轴由页面决定：图库是横向画廊，其余（消息列表、表情网格）都是纵向列表。
            // 坐标与 mouseClicked/mouseScrolled 一致：内容区相对坐标（扣掉顶部状态栏）。
            boolean horizontal = false;
            IPhonePage curPage = getCurrentPage();
            if (curPage != null) {
                horizontal = curPage.dragAxisHorizontal(cursorLocalX(), cursorLocalY() - UiFont.sh(STATUSBAR_H));
            }
            // 方向判定：主轴位移占优才升级为滚动（横向面板比较 X，纵向面板比较 Y）
            if (!dragIsScroll) {
                float primary = horizontal ? dragTotalX : dragTotalY;
                float cross = horizontal ? dragTotalY : dragTotalX;
                if (Math.abs(primary) > DRAG_SCROLL_THRESHOLD && Math.abs(primary) > Math.abs(cross)) {
                    dragIsScroll = true;
                    dragHorizontal = horizontal;
                }
            }
            if (dragIsScroll) {
                // 只喂**主轴**的增量：手势跟手（往下拖 = 看更早的内容；往右拖 = 看更前的内容）。
                // 页面按自己的渲染约定换算成内容位移（见 IPhonePage#mouseScrolled 的说明）。
                scrollRemainder += dragHorizontal ? dx : dy;
                double units = scrollRemainder / DRAG_PIXELS_PER_SCROLL_UNIT;
                double whole = units >= 0 ? Math.floor(units) : Math.ceil(units);
                if (whole != 0.0) {
                    scrollRemainder -= whole * DRAG_PIXELS_PER_SCROLL_UNIT;
                    // 交出去的是**手势方向**（往下/往右为正），不是"内容往哪跑"：
                    // 各面板按自己的渲染约定换算（消息列表加、表情网格与图库减），见 §5.6-32。
                    handlePhoneLocalScroll(cursorLocalX(), cursorLocalY(), whole);
                }
            }
        } else {
            // 悬停高亮：把移动转发给页面（坐标与点击完全一致）
            IPhonePage page = getCurrentPage();
            if (page != null && cursorLocalY() >= UiFont.sh(STATUSBAR_H)) {
                page.mouseMoved(cursorLocalX(), cursorLocalY() - UiFont.sh(STATUSBAR_H));
            }
        }
        PhoneScreenTarget.markDirty();
    }

    /**
     * 独立光标：侧键事件（由 {@code MouseHandlerMixin} 转发，只有玩家绑定的侧键会走到这里）。
     * <p>
     * 手势：轻点 = 一次完整左键；按住拖动 = 滚动（主轴纵向，横向面板由
     * {@link IPhonePage#dragAxisHorizontal} 改成横向；松开不再触发点击）；
     * 另一颗侧键 = 一次完整右键（返回）。
     */
    public static void onPhoneSideButton(boolean clickButton, int action) {
        if (!isVisible()) {
            return;
        }
        PhoneScreenTarget.markDirty();
        // 相机模式：整条绕开光标，直接用侧键驱动 (见 cameraSideButton)。
        // 于是"抬头取景"和"按下拍照"不再抢同一个鼠标：视角照常自由转，动作交给侧键。
        if (isCameraMode()) {
            cameraSideButton(clickButton, action);
            return;
        }
        if (action == GLFW.GLFW_PRESS) {
            if (clickButton) {
                cursorPressing = true;
                dragIsScroll = false;
                dragTotalX = 0f;
                dragTotalY = 0f;
                scrollRemainder = 0.0;
            } else {
                handlePhoneLocalMouse(cursorLocalX(), cursorLocalY(), GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS);
                handlePhoneLocalMouse(cursorLocalX(), cursorLocalY(), GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_RELEASE);
            }
            return;
        }
        if (action == GLFW.GLFW_RELEASE && clickButton && cursorPressing) {
            cursorPressing = false;
            if (!dragIsScroll) {
                handlePhoneLocalMouse(cursorLocalX(), cursorLocalY(), GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS);
                handlePhoneLocalMouse(cursorLocalX(), cursorLocalY(), GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE);
            }
            dragIsScroll = false;
        }
    }

    /**
     * 手机局部坐标点击。实现上直接借用屏幕坐标那条通路（把局部坐标加回手机左上角），
     * 因此动画守卫、状态栏/底部导航命中、页面转发等逻辑<b>零重复</b>。
     */
    public static boolean handlePhoneLocalMouse(double relX, double relY, int button, int action) {
        if (!isVisible()) {
            return false;
        }
        // 相机模式不吃独立光标：见 {@link onPhoneSideButton} 的说明 ——
        // 那个界面全程用侧键操作，光标既不画也不参与命中，鼠标位移全部留给视角。
        if (isCameraMode()) {
            return false;
        }
        return handleMouseClicked(relX + phoneX, relY + renderedPhoneY, button, action);
    }

    /**
     * 相机模式的侧键状态机（<b>不经光标、不经坐标命中</b>）。
     *
     * <h2>为什么相机模式要整条绕开光标</h2>
     * 拍照需要自由转动视角去取景，而视角能转的前提是进「操控屏幕」态；可一旦进了操控态，
     * 视角就被降到 0.25 倍速、且光标要一寸寸挪过去才能点到按钮 ——「抬头取景」和「点按钮」
     * 这两件事在同一个鼠标上直接互斥。与其做一套光标灵敏度与视角灵敏度的耦合
     * （代价大、手感也未必好），不如让相机界面压根不用光标：<b>鼠标只管视角，动作全交给侧键</b>。
     *
     * <h2>完整语义</h2>
     * <table border="1">
     *   <tr><th>当前状态</th><th>侧键 4（手机左键）</th><th>侧键 5（手机右键/返回）</th></tr>
     *   <tr><td>取景中</td><td>轻点 = 拍照</td><td>退出相机模式，回聊天</td></tr>
     *   <tr><td>已拍成</td><td>轻点 = 确认选中这张（回聊天，随文字一起发送）<br>
     *       <b>长按 = 存入相册</b>（弹「保存成功」气泡，留在已拍成页）</td>
     *       <td><b>取消这张</b>，回到取景中重拍</td></tr>
     * </table>
     * <p>
     * <b>为什么"取消"必须留在相机里</b>：已拍成页的侧键 5 语义与真机相机一致 ——
     * 它是"这张不要了，重新取景"，而不是"退出相机回聊天"。退出相机是<b>取景中</b>按侧键 5 的事。
     * 早先两个状态共用一个 {@code exitCameraMode()}，于是"重拍"直接把人扔回了聊天页（实机反馈）。
     * <p>
     * <b>为什么保存要长按、且不离开</b>：确认（发送）与保存（存档）是两种意图，
     * 但屏幕上只有一颗胶囊按钮。把"更重、更少见"的那个放到长按，既能区分意图，
     * 又不需要给窄手机再加一颗按钮。保存后<b>刻意留在已拍成页</b>：玩家接着轻点 4 确认发送，
     * 或点 5 重拍，两个方向都还留着选择。
     *
     * <h2>为什么动作要"合成点击"而不是直接调页面方法</h2>
     * 早先的实现让 {@code PhoneOverlay} 把侧键翻译成按钮中心坐标、再走 {@link #handleMouseClicked}。
     * 那等于把"键位 → 意图"的映射埋进坐标算术里 —— 排版一改，命中区就可能挪位或与邻区重叠，
     * 表现为"按了这个键却触发了那个动作"或"根本没反应"。
     * <p>
     * 现在改为<b>直接调用 {@link PhoneCameraHost} 的语义方法</b>：键位映射是显式的，
     * 与渲染排版彻底解耦（踩坑见交接文档 §5.3-18）。判据是接口而不是具体页面类，
     * 所以附属模组写的相机页也走同一条通路。
     */
    private static void cameraSideButton(boolean clickButton, int action) {
        if (action == GLFW.GLFW_PRESS) {
            cameraHoldButton = clickButton ? 1 : 2;
            cameraHoldStartAt = System.currentTimeMillis();
            cameraHoldFired = false;
            return;
        }
        if (action != GLFW.GLFW_RELEASE) {
            return;
        }
        int which = cameraHoldButton;
        cameraHoldButton = 0;
        if (which == 0) {
            return;
        }
        // 长按已触发过（保存）：松开时不再补一次轻点动作
        if (cameraHoldFired) {
            cameraHoldFired = false;
            return;
        }
        cameraHoldFired = false;
        IPhonePage page = getCurrentPage();
        if (!(page instanceof PhoneCameraHost host)) {
            return;
        }
        if (which == 1) {
            host.cameraPrimaryAction();
        } else {
            host.cameraBackAction();
        }
    }

    /**
     * 相机模式长按检测：按住侧键 4 到阈值即"存相册"，不必等松开。
     * <p>
     * 由 {@code ClientModEvents.onClientTick} 每 tick 调用（见 {@link #CAM_HOLD_SAVE_MS}）。
     * 只对侧键 4 生效；没有照片时 {@code cameraSaveAction()} 内部自然无事发生。
     * <p>
     * {@link PhoneCameraHost#handlesLongPress()} 返回 false 的相机（"按下即存"那种）
     * <b>根本不进这套手势</b>：这里直接返回，于是 {@code cameraHoldFired} 永不为真，
     * 松开时必定走一次 {@link PhoneCameraHost#cameraPrimaryAction()}。
     * 否则玩家按住超过 350ms 就会「长按没动作、轻点也被吞」——按了没反应。
     */
    public static void tickCameraHold() {
        if (!isCameraMode() || cameraHoldButton != 1 || cameraHoldFired) {
            return;
        }
        IPhonePage page = getCurrentPage();
        if (!(page instanceof PhoneCameraHost host) || !host.handlesLongPress()) {
            return;
        }
        if (System.currentTimeMillis() - cameraHoldStartAt < CAM_HOLD_SAVE_MS) {
            return;
        }
        cameraHoldFired = true;
        host.cameraSaveAction();
    }

    /** 手机局部坐标滚轮（独立光标拖动滚动用），守卫条件与 {@link #handleMouseScroll} 保持一致 */
    private static boolean handlePhoneLocalScroll(double relX, double relY, double scrollDelta) {
        if (uiAnimEnabled() && containerAnim != ContainerAnim.IDLE) {
            return true;
        }
        if (uiAnimEnabled() && (transTween != null || launchActive)) {
            return true;
        }
        IPhonePage page = getCurrentPage();
        if (page == null) {
            return false;
        }
        if (relY < UiFont.sh(STATUSBAR_H)) {
            return true;
        }
        return page.mouseScrolled(relX, relY - UiFont.sh(STATUSBAR_H), scrollDelta);
    }

    /**
     * 独立光标本体：白色圆点 + 粉色芯，按住时外圈放大。
     * <p>
     * 画在 {@link #render} 内部（而不是 GUI 层），所以它会自动进入模型屏的离屏纹理 ——
     * 这正是「光标活在 Overlay 坐标系里」的关键好处：两个模式零额外适配。
     */
    private static void renderCursor(GuiGraphics graphics, int baseX, int baseY) {
        // 相机模式不画光标：那个界面全程侧键操作（见 cameraSideButton），
        // 画出来只会让玩家误以为可以挪过去点
        if (isCameraMode()) {
            return;
        }
        int cx = baseX + Math.round(cursorLocalX());
        int cy = baseY + Math.round(cursorLocalY());
        // 状态可视化：操控态画亮、冻结态画暗 —— 让玩家一眼看出现在在哪个模式，
        // 永远不需要猜"为什么光标不动了 / 为什么视角变慢了"。
        boolean active = isScreenControl();
        int outer = active && cursorPressing && !dragIsScroll ? 15 : 11;
        UiShapes.fillRoundedRect(graphics, cx - outer / 2, cy - outer / 2, outer, outer, outer / 2,
                active ? 0x55FFFFFF : 0x22FFFFFF);
        UiShapes.fillRoundedRect(graphics, cx - 3, cy - 3, 6, 6, 3, active ? 0xFFFFFFFF : 0x88FFFFFF);
        UiShapes.fillRoundedRect(graphics, cx - 1, cy - 1, 2, 2, 1, active ? 0xFFDE7E7E : 0x66DE7E7E);
    }

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

    /**
     * 「掏出即渲染」：主手开始举着 Pro 手机的那一 tick，自动把手机 UI 打开。
     * <p>
     * 由 {@code ClientModEvents.onClientTick} 调用（不需要渲染上下文，只改状态）。
     * <p>
     * <b>为什么可以不做意图门</b>：主手被手机占着时玩家根本没法挖矿/攻击，
     * "把手机拿在手里"本身就是一个足够强的意图。等到将来做副手持有时才需要重新评估 ——
     * 副手的地图式持握始终垂直于视角，那时低头阈值这套判据会被彻底绕开。
     * <p>
     * <b>为什么只认上升沿</b>：见 {@link #holderWasActive}。
     * 收起（O）之后只要不放下手机就不再自动打开，玩家说了算。
     * <p>
     * 刻意不管"举着手机但正在开 Screen"的情形：那属于输入屏（打字）场景，
     * 手机本来就该保持可见，不需要在这里做任何事。
     * <p>
     * <b>手机离手 = 整台 UI 收起</b>（下面那个分支）：这是让
     * 「{@code phoneOpen} ⇒ {@link #isHoldingPhone()}」这条不变式在每个 tick 都成立的关键。
     * 它<b>不是</b>简单的"下降沿"判断 —— 按 O 空手开悬浮窗时压根没有下降沿（一直是 false→false），
     * 所以必须查状态而不是查边沿。
     */
    public static void onHolderTick() {
        boolean active = ProPhoneHandRenderer.isActive();
        boolean held = isHoldingPhone();
        boolean rising = active && !holderWasActive;
        holderWasActive = active;
        if (rising && !phoneOpen) {
            // 掏出即渲染：走与 show() 同一套页面初始化规则（保留上次关闭时的页面）
            if (pageStack.isEmpty()) {
                inChatApp = false;
                activeTab = Tab.MESSAGE;
                pageStack.push(new MainPage());
            }
            phoneOpen = true;
            renderMode = RenderMode.MODEL;
            containerAnim = ContainerAnim.IDLE;
            containerTween = null;
            // 掏出手机 = 光标重新出生：回到顶栏正中（见 defaultCursorNY 的取舍说明）
            resetCursorToSpawn();
        } else if (!held && phoneOpen) {
            // 手机离手（换物品 / 丢掉 / 被别的逻辑抽走）⇒ 整台 UI 收起。**这是必要的**：
            //   ① MODEL 屏是"贴在手里那台手机上"的，手机不在手里它就无处可贴
            //      —— hasModelScreen() 为假、离屏靶都不再出图，可 renderMode 与可见性却还留着，
            //      于是 isVisible() 撒谎：低头照样进「操控屏幕」态、视角被无故降到 0.25 倍速；
            //   ② 按 O 空手开悬浮窗同理：那颗光标根本动不了（isScreenControl 要求手持），
            //      开出来只会是一块点不动的假屏幕。
            // 用 doHideNow 而不是 hide()：退场动画是给"手里那台手机"看的，手机都没了没人看它。
            doHideNow();
        }
    }

    /**
     * 重新武装"掏出即渲染"的上升沿检测：把 {@link #holderWasActive} 归零，
     * 下一次举着 Pro 手机就会被当成一次新的"掏出"。
     * <p>
     * 由 {@link #resetForNewWorld()} 调用（进新世界时手机 UI 已被强制关闭，
     * 若玩家进世界时本来就举着手机，应当重新亮屏）。
     */
    public static void armHolderAutoOpen() {
        holderWasActive = false;
    }

    /** 当前渲染形式（不会返回 null） */
    public static RenderMode getRenderMode() {
        return renderMode;
    }

    /**
     * 手机 UI 此刻是否要画出来。
     * <p>
     * <b>这是唯一的可见性判据</b>：{@code render}、光标、输入转发全部以它为准，
     * 所以「两种屏幕互斥」是结构上成立的，而不是靠在各个分支里手动 if。
     */
    public static boolean isVisible() {
        return phoneOpen && renderMode != RenderMode.NONE;
    }

    /** 是否正在把 UI 贴到主手那台 3D 手机上 */
    public static boolean isModelScreen() {
        return isVisible() && renderMode == RenderMode.MODEL;
    }

    /** 是否正在画原版悬浮窗 */
    public static boolean isFloatingScreen() {
        return isVisible() && renderMode == RenderMode.FLOATING;
    }

    /**
     * 当前页面是否处于「相机模式」。
     * <p>
     * 单独抽出来是因为它有两个<b>跨模块</b>的用途，且都必须与页面逻辑保持一致：
     * <ul>
     *   <li>{@link #render}：相机模式下手机体背景透明（{@code cameraTransparent}），让游戏画面从取景框透出；</li>
     *   <li>{@link ProPhoneHandRenderer}：相机模式下换用擦除版纹理的手机模型，
     *       让 3D 机壳在玩家侧直接透出真实场景（见 {@code ItemPhoneCamera}）。</li>
     * </ul>
     * 两处必须同源，否则会出现"透明屏上仍是机壳"或"机壳透了但页面还是不透明"这类半边生效的怪象。
     * <p>
     * <b>判据是公开接口 {@link PhoneCameraHost}，不是具体的页面类</b>：
     * 任何页面（含附属模组的相机页）只要实现它并返回 true，上面这些行为就全部自动生效。
     * 早先这里写死 {@code instanceof ChatPage}，把"相机"这件能力锁在了内置聊天页上。
     */
    public static boolean isCameraMode() {
        return getCurrentPage() instanceof PhoneCameraHost host && host.isCameraMode();
    }

    /**
     * 「贴到模型上」这件事此刻是否成立。
     * <p>
     * 委托给 {@link ProPhoneHandRenderer#isActive()} —— 即「主手真的是 Pro 手机 + 第一人称」。
     * 刻意<b>不</b>包含低头阈值：掏出来就该亮屏，阈值只管操控（见 {@link #isScreenControl()}）。
     * <p>
     * 之所以必须复用这一个判据：离屏出图（{@link PhoneScreenTarget#update}）与贴图
     * （{@link ProPhoneHandRenderer}）只要有一边判据不一致，就会出现
     * "FBO 每帧出图却没人采样"或"采样到一张没人更新的纹理"这两类哑故障。
     */
    public static boolean hasModelScreen() {
        return phoneOpen && ProPhoneHandRenderer.isActive();
    }

    /**
     * O 键：唯一的显式通道。
     * <ul>
     *   <li>没掏出手机 → 掏出。主手有 Pro 手机则直接贴模型屏，否则退回悬浮窗（特殊手段）；</li>
     *   <li>已掏出且<b>正在模型屏</b> → 切到悬浮窗（"O = 我就是要那块悬浮窗"）；</li>
     *   <li>已掏出且正在悬浮窗 → 收起。</li>
     * </ul>
     */
    public static void toggle() {
        if (!phoneOpen) {
            show();
            return;
        }
        if (renderMode == RenderMode.MODEL) {
            // 模型屏优先，但 O 是玩家按下的显式意图 —— 让位给悬浮窗，手机仍拿在手里
            renderMode = RenderMode.FLOATING;
            containerAnim = ContainerAnim.IDLE;
            containerTween = null;
            PhoneScreenTarget.markDirty();
            return;
        }
        hide();
    }

    private static void hide() {
        // 已在收起退场中：丢弃重复指令（不重复开新动画）
        if (uiAnimEnabled() && containerAnim == ContainerAnim.EXIT) {
            return;
        }
        // 切屏动画开启：走整机下滑退场（EXIT），播完才真正隐藏
        if (uiAnimEnabled()) {
            containerAnim = ContainerAnim.EXIT;
            containerTween = new AnimTween();
            return;
        }
        doHideNow();
    }

    /** 立即隐藏手机（无动画）：清空拖动与容器动画状态，关闭输入屏 */
    private static void doHideNow() {
        phoneOpen = false;
        renderMode = RenderMode.NONE;
        dragging = false;
        // 收屏时清掉侧键按住状态，避免下次打开残留「按住中」
        cursorPressing = false;
        // 相机长按同理：若在"按住侧键4"期间手机被收起，不能让状态悬挂到下次打开
        cameraHoldButton = 0;
        cameraHoldFired = false;
        dragIsScroll = false;
        containerAnim = ContainerAnim.IDLE;
        containerTween = null;
        forceFinishTransition();
        forceFinishLaunch();
        if (Minecraft.getInstance().screen instanceof PhoneInputScreen) {
            Minecraft.getInstance().setScreen(null);
        }
    }

    /**
     * 掏出手机：保留上次关闭时的页面（页面栈不重置）。
     * 仅当栈为空（首次打开 / 新世界重置后）才进入手机桌面 MainPage；
     * 否则回到关闭前所在的页面（含 HuhuTalk 内容区顶层页与内部页）。
     * <p>
     * 显示形式按「手里有没有手机」自动选：主手 Pro 手机 + 第一人称 → 模型屏（即掏即用，
     * 不需要按 O）；否则退回悬浮窗 —— 这就是 O 作为特殊手段的价值所在。
     * 切屏动画（整机自屏幕底部上滑）只属于悬浮窗：模型屏是贴在手里那台模型上的，
     * 走整机位移动画会连模型一起推走，没有意义。
     */
    private static void show() {
        phoneOpen = true;
        renderMode = ProPhoneHandRenderer.isActive() ? RenderMode.MODEL : RenderMode.FLOATING;
        // 掏出手机 = 光标重新出生：回到顶栏正中（见 defaultCursorNY 的取舍说明）
        resetCursorToSpawn();
        if (renderMode == RenderMode.FLOATING && uiAnimEnabled()) {
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
     * <p>
     * 定位走的是<b>槽位常量</b>而不是 {@code "HuhuTalk"} 这个名字：HuhuTalk 恒定占
     * {@link MainPage#SLOT_TALK}，而名字是玩家可见文案——附属模组起个同名 App 时，
     * 按名字查会把启动动画的原点抢到别人的图标上。
     */
    public static void openChatApp() {
        launchApp(new MessageListPage(), MainPage.SLOT_TALK);
    }

    /**
     * 通用 App 启动动画：点开某 App 的首页 target 时，在对应图标中心生成其底色色块，
     * 由小放大并淡出，底层 target 页原地淡入。任何 App 复用同一套动画。
     * 播放期间普通点击屏蔽，右键可强制打断并出栈返回。
     *
     * @param slot 图标所在槽位（见 {@link MainPage#SLOT_COUNT}）
     */
    public static void launchApp(IPhonePage target, int slot) {
        launchAppAt(target, MainPage.appIconCenterScreen(slot), true);
    }

    /**
     * 同 {@link #launchApp(IPhonePage, int)}，但按 App <b>名字</b>定位图标。
     * 保留给外部按名字调用的旧入口；内部走槽位版本。
     */
    public static void launchApp(IPhonePage target, String appName) {
        launchAppAt(target, MainPage.appIconCenterScreen(appName), true);
    }

    /**
     * <b>桌面 App 的公开启动入口</b>：播放「图标色块放大淡出 + 目标页原地淡入」的启动动画，
     * 但<b>不进入 HuhuTalk 内容区</b>。
     * <p>
     * 与 {@link #launchApp(IPhonePage, int)} 的唯一区别就是后者会置 {@code inChatApp = true}
     * （于是显示底部导航）。桌面上的普通 App 不该出现 HuhuTalk 的底部导航，
     * 所以它们走这条；页栈里<b>保留桌面</b>，右键照常回桌面而不是直接关掉手机。
     * <p>
     * 桌面上点开 App 时，HuhuTalk 会按 {@code PhoneApp.launchAnimation()} 自动在
     * 本方法与 {@link #pushPage} 之间选；你也可以自己调它（例如从别处程序化打开某个 App）。
     *
     * @param slot 图标所在槽位，动画的色块从这里生成；槽位为空则跳过动画直接进页
     */
    public static void launchDesktopApp(IPhonePage target, int slot) {
        launchAppAt(target, MainPage.appIconCenterScreen(slot), false);
    }

    private static void launchAppAt(IPhonePage target, int[] iconCenter, boolean enterContentArea) {
        if (!isVisible()) return;
        if (enterContentArea) {
            inChatApp = true;
            activeTab = Tab.MESSAGE;
            pageStack.clear();
            pageStack.push(target);
        } else {
            // 桌面 App：不进内容区（无底部导航），但栈底压回桌面 ——
            // 否则右键会走到 popPage 的 "栈深 1 且不在内容区 → hide()" 分支，直接把手机关掉，
            // 而不是回到桌面（那是内置「设置/手册」一直以来的行为）。
            inChatApp = false;
            activeTab = Tab.MESSAGE;
            pageStack.clear();
            pageStack.push(new MainPage());
            pageStack.push(target);
        }
        if (uiAnimEnabled() && iconCenter != null) {
            launchCx = iconCenter[0];
            launchCy = iconCenter[1];
            launchIconW = iconCenter[2];
            launchBlockColorRgb = iconCenter[3];
            launchActive = true;
            launchTween = new AnimTween(LAUNCH_DURATION);
        }
    }

    /** 强制打断启动动画（右键抢占）：立即收尾，恢复渲染目标首页 */
    private static void forceFinishLaunch() {
        launchActive = false;
        launchTween = null;
    }

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
        if (!isVisible()) return;
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

    /** 创建某个 tab 的顶层页 */
    private static IPhonePage createTopPage(Tab tab) {
        return switch (tab) {
            case MESSAGE -> new MessageListPage();
            case CONTACT -> new ContactListPage();
            case SETTINGS -> new SettingsPage();
        };
    }

    // ==================== 相机拍照抓图 ====================

    /**
     * 相机拍照：登记一次「抓主渲染目标」的请求，本帧稍后由
     * {@code RenderLevelStageEvent.Stage.AFTER_LEVEL} 上的 {@link #consumeWorldCapture()} 兑现。
     *
     * <h2>为什么在 AFTER_LEVEL 抓</h2>
     * 那一刻本帧的世界已经画完，而<b>手 / 手机 / HUD 都还没画</b>（见 ClientModEvents 里的说明），
     * 抓到的就是「玩家眼睛里那片干净的世界」—— 不需要临时隐藏任何东西，
     * 也就不会出现旧版的两种病：整屏原版截图（HUD/物品栏/手臂/手机全在里面），或抓图回调不触发。
     *
     * <h2>⚠ 别改用 options.hideGui</h2>
     * {@code Gui} 的 layerManager 条件就是 {@code !options.hideGui}，置真会让<b>整个 GUI 层</b>
     * （含本模组注册的 GUI 层回调）全部不绘制 —— 靠 GUI 层回调抓图的路会永远不触发，
     * 表现为「按下拍照后手机永久消失」。旧版的 {@code captureHidden} 通路正是死在这里，已整体删除。
     *
     * @param onDone 拿到 PNG 字节（可能为 null）后的回调，在渲染线程执行
     */
    public static void requestWorldCapture(java.util.function.Consumer<byte[]> onDone) {
        grabPending = true;
        grabCallback = onDone;
    }

    /** 由 {@code RenderLevelStageEvent.AFTER_LEVEL} 每帧调用：有请求就抓一张并回调。在渲染线程执行。 */
    public static void consumeWorldCapture() {
        if (!grabPending) {
            return;
        }
        grabPending = false;
        java.util.function.Consumer<byte[]> cb = grabCallback;
        grabCallback = null;
        if (cb == null) {
            return;
        }
        cb.accept(com.huhutalk.client.PhotoLibrary.captureMainTargetPng());
    }

    // ==================== 屏内气泡 ====================

    /** 气泡文案（null = 不显示） */
    private static String bubbleText = null;
    private static long bubbleStartAt = 0L;
    private static long bubbleUntilAt = 0L;
    /** 气泡总时长 / 淡入 / 淡出（毫秒） */
    private static final long BUBBLE_MS = 1400L;
    private static final long BUBBLE_FADE_IN_MS = 120L;
    private static final long BUBBLE_FADE_OUT_MS = 380L;

    /**
     * 在手机屏内弹一个半透明气泡（"保存成功"这类结果提示）。
     * <p>
     * 为什么不用原版 {@code SystemToast}：气泡画在 Overlay 内部 ⇒ 会随手机 UI 一起进模型屏的离屏纹理，
     * 玩家<b>在自己的手机屏幕上</b>看到反馈；原版 toast 挂在屏幕角落，和手里那台手机没有视觉联系。
     */
    public static void showBubble(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        bubbleText = text;
        bubbleStartAt = System.currentTimeMillis();
        bubbleUntilAt = bubbleStartAt + BUBBLE_MS;
        PhoneScreenTarget.markDirty();
    }

    /** 气泡是否还在显示期内（含淡出） */
    private static boolean bubbleVisible() {
        return bubbleText != null && System.currentTimeMillis() < bubbleUntilAt;
    }

    /** 画气泡：手机屏内容区水平居中、约 58% 高处，避免压住取景框中心的十字 */
    private static void renderBubble(GuiGraphics graphics, int baseX, int baseY, int w, int h) {
        if (!bubbleVisible()) {
            if (bubbleText != null) {
                bubbleText = null;
            }
            return;
        }
        long now = System.currentTimeMillis();
        float a = 1f;
        if (now - bubbleStartAt < BUBBLE_FADE_IN_MS) {
            a = (now - bubbleStartAt) / (float) BUBBLE_FADE_IN_MS;
        } else if (bubbleUntilAt - now < BUBBLE_FADE_OUT_MS) {
            a = (bubbleUntilAt - now) / (float) BUBBLE_FADE_OUT_MS;
        }
        a = Math.max(0f, Math.min(1f, a));

        Font font = Minecraft.getInstance().font;
        float ts = UiFont.sc(1.0f);
        FormattedCharSequence seq = Component.literal(bubbleText).getVisualOrderText();
        int textW = (int) (font.width(seq) * ts);
        int textH = (int) Math.ceil(font.lineHeight * ts);
        int padX = UiFont.sh(9);
        int padY = UiFont.sh(5);
        int bubbleW = Math.min(w - UiFont.sh(8), textW + padX * 2);
        int bubbleH = textH + padY * 2;
        int bx = baseX + (w - bubbleW) / 2;
        int by = baseY + Math.round(h * 0.58f) - bubbleH / 2;
        int bgAlpha = Math.round(a * 0.70f * 255f);
        UiShapes.fillRoundedRect(graphics, bx, by, bubbleW, bubbleH, bubbleH / 2, bgAlpha << 24);
        int fgAlpha = (Math.round(a * 255f) << 24) | 0x00FFFFFF;
        drawScaledText(graphics, font, seq,
                bx + (bubbleW - font.width(seq) * ts) / 2f,
                by + (bubbleH - font.lineHeight * ts) / 2f + 1,
                fgAlpha, ts);
    }

    // ==================== 渲染 ====================

    public static void render(GuiGraphics graphics, float partialTick) {
        boolean external = externalH > 0;

        // ⚠ 两种屏幕互斥的**执行点**（顺序有意义）：
        //   · 离屏出图（external）：只有「该贴到模型上」时才画。若此刻 renderMode 已被 O 切成
        //     FLOATING，这里必须让位 —— 否则会把悬浮窗（带机壳、带屏幕空间坐标）画进纹理，
        //     而模型屏那张四边形已经不再采样它，纯属浪费且会污染下次切回的残留画面。
        //   · GUI 层：只有「正在画悬浮窗」时才画。renderMode == MODEL 时手机 UI 走的是
        //     ProPhoneHandRenderer → PhoneScreenTarget.renderExternal 那条路，GUI 层插不上手，
        //     所以这里必须退让，否则悬浮窗会和模型屏同时出现在屏幕上。
        if (external) {
            if (!hasModelScreen()) return;
        } else if (!isFloatingScreen()) {
            return;
        }

        // 相机拍照的抓图不在这里做：它走 RenderLevelStageEvent.AFTER_LEVEL（世界已画完、手与 HUD 都还没画），
        // 见 requestWorldCapture / consumeWorldCapture 的说明。旧版那条「本帧不画手机」的早退已删除。

        Minecraft mc = Minecraft.getInstance();
        int screenW = external ? externalW : mc.getWindow().getGuiScaledWidth();
        int screenH = external ? externalH : mc.getWindow().getGuiScaledHeight();

        int totalHeight;
        int width;
        if (external) {
            // 模型屏：画布由 PhoneScreenTarget 钉死，手机铺满整块画布
            totalHeight = externalH;
            width = externalW;
        } else {
            // 手机尺寸 = 屏幕比例（高:宽，aspectRatio 返回宽/高）× 高度比例（占实际屏幕高 0.5~1.0）
            float size = HuhuTalkClientSettings.getPhoneSize();
            float aspect = aspectRatio(HuhuTalkClientSettings.getPhoneAspect());
            totalHeight = (int)Math.round(screenH * size);
            width = (int)Math.round(totalHeight * aspect);
        }
        phoneWidth = external ? externalW : Math.max(MIN_PHONE_W, Math.min(width, screenW - 20));
        phoneHeight = Math.max(20, totalHeight - UiFont.sh(STATUSBAR_H) - UiFont.sh(WATERMARK_H));
        phoneTotalHeight = totalHeight;
        // 独立光标为归一化存储，天然自钳制；两个模式各自映射到自己的矩形
        // 待重置标记：布局尺寸此时才确定（且两个模式的手机高度不同），在这里把出生点校正到位
        if (cursorResetPending) {
            cursorResetPending = false;
            cursorNY = defaultCursorNY();
        }

        // 默认锚点：屏幕模式锚右下角（右/下各留 10px）；模型屏锚画布左上角
        int defX = external ? 0 : screenW - phoneWidth - 10;
        int defY = external ? 0 : screenH - totalHeight - 10;

        // 拖动时：手机实时跟随鼠标，并把约束后的位置折算为偏移量落盘（呼出/收起动画期间禁止拖动）。
        // 模型屏没有拖动概念，跳过。
        if (!external && dragging && containerAnim == ContainerAnim.IDLE) {
            int mx = guiMouseX(mc);
            int my = guiMouseY(mc);
            int targetX = (int)Math.round(mx - grabDX);
            int targetY = (int)Math.round(my - grabDY);
            int clampedX = clampPhoneX(targetX, screenW);
            int clampedY = clampPhoneY(targetY, screenH);
            HuhuTalkClientSettings.setPhoneOffsetRaw(clampedX - defX, clampedY - defY);
        }

        if (external) {
            phoneX = 0;
            phoneY = 0;
        } else {
            phoneX = defX + Math.round(HuhuTalkClientSettings.getPhoneOffsetX());
            phoneY = defY + Math.round(HuhuTalkClientSettings.getPhoneOffsetY());
            // 约束：水平至多半个手机宽度出屏（顶部栏仍留一半可抓）；垂直保证状态栏高度完整在屏内
            phoneX = clampPhoneX(phoneX, screenW);
            phoneY = clampPhoneY(phoneY, screenH);
        }

        // 手机容器动画：呼出/收起沿 Y 轴滑动（唯一需要鼠标逆矩阵矫正的动画）。
        // 起点/终点统一以「屏幕下缘 ↔ 静止位置」计算，故手机无论被拖到哪个位置都从屏幕底部滑入/滑出。
        // 模型屏不做呼出/收起滑动（那是屏幕模式的整机位移）；若正处于 EXIT，立即结算隐藏
        if (external) {
            if (containerAnim == ContainerAnim.EXIT) {
                doHideNow();
                return;
            }
            containerAnim = ContainerAnim.IDLE;
            containerTween = null;
        }

        float animOffsetY = 0f;
        // 呼出/收起（整机滑动）只属于悬浮窗：模型屏是贴在手里那台模型上的，
        // 走整机位移动画没有意义（会把"贴在模型上的屏幕"一起推走）。
        // 刻意用外部模式的判据而不是 renderMode：出图时 renderMode 可能是 MODEL 而
        // external 为真，此时也不该有整机位移。
        boolean containerAnimEligible = !external && renderMode == RenderMode.FLOATING;
        if (containerAnimEligible && uiAnimEnabled()) {
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
        } else if (!uiAnimEnabled()) {
            // 全局总开关关闭：瞬时切换，重置动画状态。
            // ⚠ 必须是 else-if 而不是 else：离屏（模型屏）模式下第一支不成立，
            // 若落到 else 就会**每帧**把 transTween / launchActive 清掉 ——
            // 于是「点击 App 的切屏动画」在悬浮窗和模型屏两边都会消失（曾实测踩到，期 1d 又踩了一次）。
            // 所以这里只重置**容器**动画，绝不碰 transTween / transFrom / transTo / launchActive / launchTween
            // —— 那些是页面转场与启动动画的状态，与「整机呼出/收起」完全无关。
            containerAnim = ContainerAnim.IDLE;
            containerTween = null;
        } else if (renderMode != RenderMode.FLOATING) {
            // 切到模型屏（或别的非悬浮窗形式）：整机滑动没有意义，丢容器动画状态即可。
            // 同样**不碰**页面转场/启动动画 —— 模型屏里页面跳转照样要有切屏动画。
            containerAnim = ContainerAnim.IDLE;
            containerTween = null;
        }
        int ry = phoneY + Math.round(animOffsetY);
        renderedPhoneY = ry;

        // 手机壳（纯视觉，包住整个手机）。镂空内收状态栏/水印栏高度，托住上下导航栏圆角。
        // 模型屏不画壳：3D 模型自带机身边框，再叠一层壳会糊住边缘，也违背「屏幕直接画在模型上」的初衷。
        if (!external) {
            PhoneCaseRenderer.renderCase(graphics, phoneX, ry, phoneWidth, totalHeight,
                    UiFont.sh(STATUSBAR_H), UiFont.sh(WATERMARK_H));
        }

        // 当前页面
        IPhonePage page = getCurrentPage();
        // 相机模式（聊天页📷）：手机体透明化，让游戏画面从取景框透出来
        boolean cameraTransparent = isCameraMode();

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
            if (external) {
                // 模型屏：不画壳，蒙版就只盖手机本体，不能按壳的厚度外扩（否则黑块比屏幕大一圈）
                fillRoundedRect(graphics, phoneX, ry, phoneWidth, totalHeight,
                        UiFont.sh(STATUSBAR_H), UiFont.sh(WATERMARK_H), dimColor);
            } else {
                int caseX = phoneX - PhoneCaseRenderer.CASE_THICKNESS;
                int caseY = ry - PhoneCaseRenderer.CASE_THICKNESS;
                int caseW = phoneWidth + PhoneCaseRenderer.CASE_THICKNESS * 2;
                int caseH = totalHeight + PhoneCaseRenderer.CASE_THICKNESS * 2;
                fillRoundedRect(graphics, caseX, caseY, caseW, caseH,
                        PhoneCaseRenderer.CORNER_RADIUS, PhoneCaseRenderer.CORNER_RADIUS, dimColor);
            }
        }

        // 屏内气泡（"保存成功"这类结果提示）画在光标之下、一切页面内容之上
        renderBubble(graphics, phoneX, ry + UiFont.sh(STATUSBAR_H), phoneWidth, phoneHeight);

        // 独立光标画在最上层。必须在 render 内部（而不是 GUI 层），才能随 Overlay 一起进离屏纹理。
        renderCursor(graphics, phoneX, ry);
    }

    /**
     * 把整块手机 UI 渲染到指定尺寸的离屏画布（模型屏模式）。
     * <p>
     * 与屏幕模式共用 {@link #render} 的全部绘制逻辑，只是把「屏幕尺寸/位置」换成「画布尺寸/左上角」。
     * 调用方负责：绑定离屏帧缓冲、设置与之匹配的正交投影与模型视图。
     */
    public static void renderExternal(GuiGraphics graphics, float partialTick, int viewportW, int viewportH) {
        externalW = viewportW;
        externalH = viewportH;
        try {
            render(graphics, partialTick);
        } finally {
            externalW = -1;
            externalH = -1;
        }
    }

    /**
     * 当前是否有「正在播放、需要逐帧重绘」的界面动画。
     * <p>
     * 模型屏离屏渲染据此决定是否必须每帧重绘（否则只按 {@code MAX_IDLE_MS} 空转刷新，
     * 省掉绝大部分 GPU/CPU 开销）。
     */
    public static boolean hasActiveAnimation() {
        if (dragging) {
            return true;
        }
        // 气泡有淡入/淡出：让它期间逐帧重绘，否则只按 MAX_IDLE_MS 空转刷新（约 10fps）会显得一顿一顿
        if (bubbleVisible()) {
            return true;
        }
        if (!uiAnimEnabled()) {
            return false;
        }
        return containerAnim != ContainerAnim.IDLE || transTween != null || launchActive;
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
        if (!isVisible()) return false;
        // 有输入 → 模型屏下一帧必须重绘（悬停/点击都会改变画面）
        PhoneScreenTarget.markDirty();

        // 手机容器动画期间（呼出/收起）：
        // - 收起(EXIT)：吞掉一切输入（手机正在离场）；
        // - 呼出(ENTER)：除「右键强制收起」外全部丢弃（规则 3）。
        if (uiAnimEnabled() && containerAnim != ContainerAnim.IDLE) {
            if (action == GLFW.GLFW_PRESS
                    && button == GLFW.GLFW_MOUSE_BUTTON_RIGHT
                    && containerAnim == ContainerAnim.ENTER) {
                hide();          // 紧急强制收起
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
        if (!isVisible()) return false;
        PhoneScreenTarget.markDirty();
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
        phoneOpen = false;
        renderMode = RenderMode.NONE;
        armHolderAutoOpen();
        dragging = false;
        // 新世界：光标回出生点（顶栏正中），并在下次渲染按真实手机高度校正
        resetCursorToSpawn();
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
    }
}
