package com.huhutalk.phone;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 手机内的所有页面必须实现此接口。
 * 鼠标坐标均相对于手机左上角。
 */
public interface IPhonePage {
    /** 页面被推入栈时调用 */
    default void onOpen() {}
    /** 页面被弹出栈时调用 */
    default void onClose() {}

    /** 每帧渲染，baseX/baseY 为手机在屏幕上的左上角坐标 */
    void render(GuiGraphics graphics, int baseX, int baseY, float partialTick);

    /** 鼠标点击：action = GLFW_PRESS(1) 或 GLFW_RELEASE(0) */
    default boolean mouseClicked(double relX, double relY, int button, int action) { return false; }

    /**
     * 鼠标滚轮 / 侧键按住拖动：<b>scrollDelta 正值 = 手势往下（或往右）</b>。
     * <p>
     * 注意这跟"内容往哪边跑"不是一回事：各面板按自己的渲染约定换算（见 ChatPage 里三处 mouseScrolled）。
     * 约定写着"手势方向"是刻意的 —— 唯一喂入这条通路的是<b>拖动</b>（{@code PhoneOverlay.moveCursor}），
     * 而拖动必须跟手：鼠标往下走，内容就得跟着往下走。
     */
    default boolean mouseScrolled(double relX, double relY, double scrollDelta) { return false; }
    /** 鼠标移动：可用于高亮判定 */
    default void mouseMoved(double relX, double relY) {}

    /**
     * 「按住拖动」手势在 (relX,relY) 处的<b>主轴是不是横向</b>（默认纵向）。
     * <p>
     * 只有横向画廊这类面板需要重写（聊天页的图库扩展栏）：默认的纵向判据会把
     * "左右滑"当成"上下滚"，于是玩家横着拖半天，画面纹丝不动。
     * 坐标与 {@link #mouseClicked} 一致：相对<b>手机内容区</b>左上角（不含顶部状态栏）。
     * <p>
     * 为什么放在页面而不是 Overlay：主轴取决于"鼠标此刻落在哪个面板里"，
     * 那是页面的排版知识，Overlay 不该知道聊天页的面板在哪。
     */
    default boolean dragAxisHorizontal(double relX, double relY) { return false; }

    /**
     * 本页是否<b>同时</b>接受横向与纵向拖动（默认 false = 维持 {@link #dragAxisHorizontal} 的旧语义）。
     *
     * <h2>为什么需要它</h2>
     * 旧契约是"<b>整页一个静态主轴</b>"，而那个主轴还兼任"这次拖动算不算滚动"的判据
     * （{@code PhoneOverlay.moveCursor}：{@code primary = horizontal ? dragTotalX : dragTotalY}，
     * 必须 {@code |primary| > |cross|} 才 latch）。所以对一个"横竖都要收"的页面来说，
     * 无论 {@link #dragAxisHorizontal} 返回什么都会坏事：返回 true 则一次以纵向为主的拖动
     * 永远 latch 不上（竖向手势当场报废），返回 false 则永远收不到横向增量。
     *
     * <p>返回 true 时主模组改成"<b>按本次手势的真实主轴分派</b>"：latch 那一刻按
     * {@code |dragTotalX| >= |dragTotalY|} 定轴，并回调
     * {@link #onDragAxisResolved(boolean, double, double)}；之后 {@code mouseScrolled}
     * 收到的就是那个轴的增量。此时 {@link #dragAxisHorizontal} 不再被调用。
     *
     * <p>⚠ {@code both == false} 的路径与旧行为<b>逐字节等价</b>，所以既有页面一个字都不用改。
     */
    default boolean dragAxisBoth() { return false; }

    /**
     * 本次拖动被判为哪个轴（只有 {@link #dragAxisBoth()} 为 true 时才会回调）。
     *
     * <p>时机：latch 成立的那一帧，<b>早于</b>同一帧随后派发的 {@code mouseScrolled}。
     * 一次拖动只回调一次。
     *
     * @param horizontal true = 本次手势以横向为主（{@code mouseScrolled} 收到的将是 X 增量）
     * @param relX       latch 时刻的手机<b>内容区</b>坐标（与 {@link #mouseClicked} 同一坐标系）。
     *                   它用来回答"这次拖动是从哪一块区域开始的" —— 网页类页面要按区域决定
     *                   要不要接管（例如只接管内容区底边那条"滚动条操作带"）。
     * @param relY       同上
     */
    default void onDragAxisResolved(boolean horizontal, double relX, double relY) { }

    /**
     * 右键返回。页面可在此处理"多级返回"（如手册子栏目逐级回退）。
     * 返回 true 表示已消费该右键（上层不再执行 popPage/关闭手机）；false 则交给上层默认逻辑。
     */
    default boolean onBackPressed() { return false; }

    /**
     * 「长按」：按住 ≥ {@link PhoneOverlay#LONG_PRESS_MS} 毫秒、期间<b>没有拖动成滚动</b>，然后松开。
     * <p>
     * 参数是<b>按下那一刻</b>的手机内容区相对坐标（与 {@link #mouseClicked} 同一坐标系）。
     * <p>
     * ⚠ 如果你继承的是 {@code PhonePage}，注意它把坐标系又切了一刀：
     * {@code mouseClicked} / {@code onLongPress} 收的是<b>手机内容区</b>坐标（顶栏在里面），
     * 而 {@code onContentClick} / {@code renderContent} 收的是<b>内容坐标</b>（原点在顶栏下方）。
     * 两个钩子里要往同一套布局做命中时，本方法里记得减掉 {@code contentTop()}。
     *
     * <h2>为什么判定在"松开"而不是"按住到 1 秒"</h2>
     * 这条通路本来就是为"不引入每 tick 计时器"设计的：鼠标按住期间
     * {@code PhoneOverlay} 不向页面转发任何事件，左键的 PRESS/RELEASE 是<b>松开时一起补发</b>的，
     * 所以到松开时才知道按了多久 —— 也就顺便不需要"长按已触发就别再补一次轻点"那套状态。
     * 代价是选中反馈出现在松手瞬间，而不是按住第 1 秒。
     *
     * <h2>页面只在自己关心的位置实现它</h2>
     * 默认返回 false = "没消费"。上层于是照常补一次普通点击，
     * <b>所以现有页面一行都不用改</b>（它们这个时长内松开，行为与以前完全一致）。
     *
     * @return true 表示已消费 —— 上层<b>不会</b>再补一次普通点击
     */
    default boolean onLongPress(double relX, double relY) { return false; }

    /**
     * 「按住拖动」开始时问页面：<b>这次拖动你自己接管吗？</b>
     *
     * <h2>为什么需要这个口子</h2>
     * 手机里唯一的手势通路是"按住拖动 = 滚动"（{@code PhoneOverlay.moveCursor}）：
     * 位移一旦超过 4 像素且主轴占优，整段手势就升级成 {@link #mouseScrolled} 的增量，
     * <b>页面再也收不到光标位置</b>，松开也不补点击。也就是说：光靠现有钩子，
     * 页面<b>做不出真正的拖放</b>（{@link #onLongPress} 要求按住 1 秒且不能移动，同样救不了）。
     * <p>
     * 所以拖放只能是"页面显式声明接管"：在按下那一刻返回 true，
     * Overlay 就会跳过滚动 latch，改为把整段手势转给
     * {@link #dragReorderTo} / {@link #endDragReorder}。
     * 这是 {@link #dragAxisBoth()} 那条"给特殊页面开口子"的同一套做法。
     *
     * <h2>默认返回 false，既有页面零影响</h2>
     * 返回 false 时整条路径与改造前<b>逐字节等价</b>（判据都不再计算一次，
     * 只是多了一次常量 false 的调用）。子类若继承 {@code PhonePage}，
     * 应该覆盖它的 {@code onDragReorderStart} 而不是本方法。
     *
     * @param relX,relY 按下那一刻的<b>手机内容区</b>相对坐标（与 {@link #mouseClicked} 同一坐标系）。
     *                  返回 true 时要记住这个起点 —— 之后只会给你移动与落点，不会再给起点。
     * @return true = 本次手势由本页接管（Overlay 不再滚动、不补点击、不判长按）
     */
    default boolean beginDragReorder(double relX, double relY) { return false; }

    /**
     * 接管中的拖动移动到了这里。坐标同 {@link #beginDragReorder}。
     * <p>只在 {@link #beginDragReorder} 返回过 true 的手势里被调用。
     */
    default void dragReorderTo(double relX, double relY) {}

    /**
     * 接管中的拖动松手了，落点就是这里。坐标同 {@link #beginDragReorder}。
     * <p>页面在这里判"落在哪一格"并落位。之后 Overlay 会清掉接管状态，
     * <b>不会</b>再补一次普通点击（所以不会出现"拖完顺带点开一个 App"）。
     */
    default void endDragReorder(double relX, double relY) {}

    /**
     * 接管中的拖动被<b>打断</b>了（掏出/收起手机、拖动途中换了页面），不是正常落位。
     * <p>页面只该清掉拖动中的临时状态，<b>不要</b>在这里落位 ——
     * 玩家没有在任何一个格子上松手。
     */
    default void cancelDragReorder() {}
}