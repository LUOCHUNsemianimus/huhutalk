package com.huhutalk.phone.pages;

import com.huhutalk.api.phone.ui.PhoneGrid;
import com.huhutalk.api.phone.ui.PhonePage;
import com.huhutalk.api.phone.ui.PhoneShapes;
import com.huhutalk.api.phone.ui.PhoneText;
import com.huhutalk.client.theme.ColorScheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 「桌面图标管理」页（设置 → 桌面图标管理 → 管理 进入）。
 *
 * <h2>它是什么</h2>
 * 一张与桌面 App 槽位<b>一一对应</b>的 3×3 网格：网格第 {@code i} 格 = 桌面槽位 {@code i}。
 * 桌面只有 {@link MainPage#SLOT_COUNT}（9）个槽位、每行 {@link MainPage#APP_COLS}（3）个，
 * 所以这里的下标空间与桌面<b>完全相同</b>，不需要任何映射表。
 * <ul>
 *   <li><b>拖动换位</b>：按住一个图标拖到另一格松手。目标格有人 = 两人换位。</li>
 *   <li><b>移除</b>：点格子右上角的 ✕（也可以把图标拖进底部「已移除」横栏）。</li>
 *   <li><b>放回</b>：点底部「已移除」横栏里的图标。</li>
 *   <li><b>主应用 HuhuTalk 固定在第一排第一位</b>：不画 ✕、不接受拖动起点，也不接受别人落在它那格。</li>
 *   <li><b>「设置」可拖动换位、但不可移除</b>：不画 ✕，拖进回收栏也不生效；
 *       它的「不可移除」走 {@link MainPage#isDesktopRemovable}，与「不可拖动」的
 *       {@link MainPage#isDesktopFixed} 是两回事。</li>
 * </ul>
 *
 * <h2>为什么用 {@link PhoneGrid} 而不是自己写格子循环</h2>
 * 与传文件页同源：{@link PhoneGrid#cellX(int)} / {@link PhoneGrid#cellY(int)} 与
 * {@link PhoneGrid#indexAt(double, double)} 是唯一一对互逆映射，渲染与命中在结构上不可能不同步；
 * 而且"格子画在哪"与"光标在哪一格"是同一份算术 —— 拖放最怕的正是这两者对不上。
 *
 * <h2>拖动为什么能成立（这次新开的口子）</h2>
 * 手机里唯一的手势通路是"按住拖动 = 滚动"，一旦位移超过阈值整段手势就变成
 * {@code mouseScrolled} 的增量、页面再也收不到光标位置。所以本页在按下那一刻通过
 * {@link #onDragReorderStart} 声明「这次拖动我接管」，Overlay 随即跳过滚动 latch，
 * 改为把移动与落点原样转过来（见 {@code IPhonePage#beginDragReorder}）。
 *
 * <h2>空槽与主应用为什么不接管拖动</h2>
 * 没东西可拖的时候把手势还给页面滚动体系，是更保守的选择 ——
 * 玩家在空白处按住一划，预期是"滚一下"，不是"什么都没发生"。
 */
public class DesktopIconPage extends PhonePage {

    /** 与桌面每行图标数一致（{@link MainPage#APP_COLS}）：本页的网格就是桌面的网格 */
    private static final int COLS = MainPage.APP_COLS;
    /** 底部「已移除」栏每行格数 */
    private static final int RECYCLE_COLS = 6;

    /** 全部槽位下标（0..8）——恒定数据源，建一次即可 */
    private static final List<Integer> SLOTS =
            IntStream.range(0, MainPage.SLOT_COUNT).boxed().toList();

    /** 空格子底色（浅一档，与有图标的实心方块区分） */
    private static final int SLOT_EMPTY_BG = 0x33FFFFFF;
    /** 落点提示环色 */
    private static final int DROP_RING = 0xFFFFFFFF;
    /** ✕ 按钮底色 */
    private static final int REMOVE_BTN_BG = 0xCC3A2E33;
    /** 分区横栏底色 */
    private static final int BAR_BG = 0x22FFFFFF;

    // ==================== 每帧快照 ====================
    //
    // 渲染、命中、拖放三处都要读同一份桌面状态。每帧（以及每次手势）刷新一次，
    // 之后一律读缓存 —— 避免"渲染时读到的是第 3 格是手册、命中时读到的是空的"这种撕裂。

    private List<MainPage.DesktopSlot> slotCache = List.of();
    private List<MainPage.DesktopSlot> removed = List.of();
    /** 回收栏网格的数据源（下标 0..removed.size()-1），随 {@link #refresh()} 同步 */
    private final List<Integer> recycleItems = new ArrayList<>();

    // ==================== 拖动态 ====================

    /** 是否正在拖动（由 Overlay 通过 beginDragReorder 授权） */
    private boolean dragging = false;
    /** 拖动起点槽位，-1 = 无 */
    private int dragFrom = -1;
    /** 当前悬停的目标槽位，-1 = 不在任何格上 */
    private int dragOver = -1;
    /** 是否悬停在底部「已移除」栏（= 松手就移除） */
    private boolean dragOverRecycle = false;

    private final PhoneGrid<Integer> slotGrid = new PhoneGrid<>() {
        @Override
        protected List<Integer> items() {
            return SLOTS;
        }

        @Override
        protected int columns() {
            return COLS;
        }

        @Override
        protected int cellWidth() {
            return cell();
        }

        @Override
        protected int cellHeight() {
            return cell();
        }

        @Override
        protected int gapX() {
            return gap();
        }

        @Override
        protected int gapY() {
            return gap();
        }

        @Override
        protected int padLeft() {
            return gridX0();
        }

        @Override
        protected void renderCell(GuiGraphics g, int index, Integer slot,
                                  int x, int y, int w, int h, boolean hovered) {
            MainPage.DesktopSlot s = slotAt(index);
            if (s == null) {
                return;
            }
            boolean source = dragging && index == dragFrom;
            boolean target = dragging && index == dragOver;
            drawSlot(g, s, x, y, w, h, source, hovered && !dragging);

            if (dragging && target) {
                // 落点提示：画在格子之上的白环（源格同时也被画暗，于是"从哪来、去哪"一目了然）
                PhoneShapes.fillRoundedRectFrame(g, x - 2, y - 2, w + 4, h + 4,
                        x, y, w, h, Math.max(4, w / 4) + 2, DROP_RING);
            }
        }
    };

    private final PhoneGrid<Integer> recycleGrid = new PhoneGrid<>() {
        @Override
        protected List<Integer> items() {
            return recycleItems;
        }

        @Override
        protected int columns() {
            return RECYCLE_COLS;
        }

        @Override
        protected int cellWidth() {
            return recycleCell();
        }

        @Override
        protected int cellHeight() {
            return recycleCell();
        }

        @Override
        protected int gapX() {
            return gap();
        }

        @Override
        protected int gapY() {
            return gap();
        }

        @Override
        protected int padLeft() {
            return recycleX0();
        }

        @Override
        protected void renderCell(GuiGraphics g, int index, Integer item,
                                  int x, int y, int w, int h, boolean hovered) {
            MainPage.DesktopSlot s = index < removed.size() ? removed.get(index) : null;
            if (s == null) {
                return;
            }
            drawSlot(g, s, x, y, w, h, false, hovered);
        }
    };

    // ==================== 骨架钩子 ====================

    @Override
    protected String title() {
        return PhoneText.tr("gui.huhutalk.desktop.title");
    }

    /** 与图库/手册/传文件页一致：顶栏锁成品牌色，不随以后新增的配色方案漂移。 */
    @Override
    protected int topBarBackground() {
        return BRAND_TOPBAR_BG;
    }

    @Override
    protected int pageBackground() {
        return colors().pageBgList();
    }

    // contentHeight() 保持默认 0：本页整体不滚，只有底部回收栏在自己区域内滚。

    // ==================== 布局（全部内容坐标） ====================

    private int ln() {
        return Minecraft.getInstance().font.lineHeight;
    }

    /** 横栏高 = 一行字 */
    private int barH() {
        return Math.max(1, PhoneText.height(font(), PhoneText.scale(1.0f)));
    }

    private int margin() {
        return Math.max(2, width() / 40);
    }

    private int gap() {
        return Math.max(3, (int) (width() * 0.03));
    }

    private int hintTop() {
        return 0;
    }

    /** 网格顶（提示栏下） */
    private int gridTop() {
        return hintTop() + barH() + 1 + gap();
    }

    private int gridRows() {
        return (MainPage.SLOT_COUNT + COLS - 1) / COLS;
    }

    /**
     * 底部回收栏预留的一行高。
     * <p>
     * <b>刻意是个与 {@link #cell()} 无关的固定值</b>：如果让它反过来依赖 cell()，
     * 而 cell() 又要靠"回收栏顶"算可用高度，就成了循环依赖（会栈溢出）。
     */
    private int recycleReserve() {
        return Math.max(14, PhoneText.px(24));
    }

    /** 格子边长：先按宽度铺满，再用"网格与回收栏之间剩下的高度"压一压，保证 3 行都在屏内。 */
    private int cell() {
        int byW = (width() - margin() * 2 - gap() * (COLS - 1)) / COLS;
        int usedByBars = (hintTop() + barH() + 1 + gap())                       // 提示栏
                + (barH() + 1 + recycleReserve() + Math.max(2, ln() / 2));      // 回收栏
        int avail = contentViewHeight() - usedByBars - gap() * gridRows();
        int byH = avail / Math.max(1, gridRows());
        return Math.max(8, Math.min(byW, byH));
    }

    /** 网格总宽（用于居中） */
    private int gridTotal() {
        return COLS * cell() + (COLS - 1) * gap();
    }

    private int gridX0() {
        return Math.max(0, (width() - gridTotal()) / 2);
    }

    /** 回收栏横栏顶（从内容区底部反推） */
    private int recycleBarTop() {
        return contentViewHeight() - barH() - 1 - recycleReserve() - Math.max(2, ln() / 2);
    }

    private int recycleGridTop() {
        return recycleBarTop() + barH() + 1;
    }

    private int recycleCell() {
        int byW = (width() - margin() * 2 - gap() * (RECYCLE_COLS - 1)) / RECYCLE_COLS;
        return Math.max(8, Math.min(recycleReserve() - 2, byW));
    }

    private int recycleTotal() {
        int n = Math.min(RECYCLE_COLS, Math.max(1, removed.size()));
        return n * recycleCell() + (n - 1) * gap();
    }

    private int recycleX0() {
        return Math.max(0, (width() - recycleTotal()) / 2);
    }

    // ==================== 数据快照 ====================

    private void refresh() {
        slotCache = MainPage.desktopSlots();
        removed = MainPage.desktopRemoved();
        recycleItems.clear();
        for (int i = 0; i < removed.size(); i++) {
            recycleItems.add(i);
        }
    }

    private MainPage.DesktopSlot slotAt(int index) {
        return index >= 0 && index < slotCache.size() ? slotCache.get(index) : null;
    }

    /** 把两个网格的视口摆成与"刚渲染的那一帧"完全相同（渲染与命中共用）。 */
    private void prepareGrids() {
        slotGrid.setViewport(0, gridTop(), width(), Math.max(0, recycleBarTop() - gridTop()));
        recycleGrid.setViewport(0, recycleGridTop(), width(), recycleReserve());
    }

    /** 点是否落在底部「已移除」栏里（含横栏本身）。 */
    private boolean inRecycleArea(double localY) {
        return localY >= recycleBarTop();
    }

    // ==================== 渲染 ====================

    @Override
    protected void renderContent(GuiGraphics g, int ox, int oy, int w, int h, float partialTick) {
        refresh();

        drawBar(g, ox, oy + hintTop(), w, PhoneText.tr("gui.huhutalk.desktop.hint"));
        prepareGrids();
        slotGrid.render(g, ox, oy);

        String right = removed.isEmpty()
                ? PhoneText.tr("gui.huhutalk.desktop.removed_empty")
                : PhoneText.tr("gui.huhutalk.desktop.removed_hint");
        drawBar(g, ox, oy + recycleBarTop(), w, right);
        if (!removed.isEmpty()) {
            recycleGrid.render(g, ox, oy);
        }

        if (dragging && dragOverRecycle) {
            // 拖到回收栏上：栏顶画一条白线，明确"松手 = 移除"
            g.fill(ox, oy + recycleBarTop(), ox + w, oy + recycleBarTop() + 2, DROP_RING);
        }
    }

    /** 一条"一行字高"的分区横栏。 */
    private void drawBar(GuiGraphics g, int originX, int barY, int w, String left) {
        Font font = font();
        int barH = barH();
        ColorScheme c = colors();
        g.fill(originX, barY, originX + w, barY + barH, BAR_BG);
        PhoneText.drawLeftIn(g, font, left, originX, barY, w, barH, 4, c.textMuted(), PhoneText.scale(0.85f));
        g.fill(originX, barY + barH, originX + w, barY + barH + 1, c.divider());
    }

    /** 画一格：底色 + 图标 + （可移除时）右上角 ✕。 */
    private void drawSlot(GuiGraphics g, MainPage.DesktopSlot s, int x, int y, int w, int h,
                          boolean source, boolean hovered) {
        int r = Math.max(4, w / 4);
        if (s.key == null) {
            // 空格子：半透明浅底，一眼能看出"这格还没用"
            PhoneShapes.fillRoundedRect(g, x, y, w, h, r, SLOT_EMPTY_BG);
            return;
        }
        int base = s.color & 0x00FFFFFF;
        // 拖动中的源格画暗：不引入半透明贴图（那要动 shader 颜色），
        // 直接向页面底色混一半，视觉上就是"被拿起来了"
        int fill = source ? mix(base, pageBackground() & 0x00FFFFFF, 0.62f) : (base | 0xFF000000);
        PhoneShapes.fillRoundedRect(g, x, y, w, h, r, fill);

        if (s.iconTexture != null) {
            float f = s.iconFill <= 0f ? 1f : s.iconFill;
            int box = Math.max(1, Math.round(w * f));
            int dx = x + (w - box) / 2;
            int dy = y + (h - box) / 2;
            g.blit(s.iconTexture, dx, dy, box, box, 0f, 0f, s.iconTexWidth, s.iconTexHeight,
                    s.iconTexWidth, s.iconTexHeight);
        } else if (!s.center.isEmpty()) {
            PhoneText.drawCenteredIn(g, font(), s.center, x, y, w, h, s.centerColor,
                    PhoneText.scale(s.centerScale));
        }

        if (s.fixed) {
            // 主应用：右上角一枚小锁点，替代别处的 ✕ —— 明确"这格不给你动"
            int d = Math.max(3, w / 8);
            PhoneShapes.fillRoundedRect(g, x + w - d - 2, y + 2, d, d, d / 2, 0xCCFFFFFF);
            return;
        }
        if (MainPage.isDesktopRemovable(s.key)) {
            // 只有可移除的 App 才画 ✕。设置不可移除但可拖动，所以既不画 ✕ 也不画锁点。
            drawRemoveButton(g, x, y, w, hovered);
        }
    }

    /** 右上角 ✕：命中框比图形略大一圈，避免小格子上难点。 */
    private void drawRemoveButton(GuiGraphics g, int x, int y, int w, boolean hovered) {
        int d = removeBtnSize(w);
        int bx = removeBtnX(x, w);
        int by = removeBtnY(y);
        PhoneShapes.fillRoundedRect(g, bx, by, d, d, d / 2, hovered ? 0xFFE05252 : REMOVE_BTN_BG);
        // 手画一个 ✕（两条对角线），比赌字体里有 U+2715 可靠得多。
        // 用 t×t 的小方块沿对角线铺，缩放/字体倍率下都不会变成一串虚线点。
        int t = Math.max(1, d / 4);
        int pad = Math.max(1, d / 4);
        int len = d - pad * 2 - t;
        int step = Math.max(1, t - 1);
        for (int i = 0; i <= len; i += step) {
            int p = pad + i;
            g.fill(bx + p, by + p, bx + p + t, by + p + t, 0xFFFFFFFF);
            g.fill(bx + d - p - t, by + p, bx + d - p, by + p + t, 0xFFFFFFFF);
        }
    }

    private int removeBtnSize(int cell) {
        return Math.max(7, cell / 3);
    }

    private int removeBtnX(int x, int cell) {
        return x + cell - removeBtnSize(cell) - 1;
    }

    private int removeBtnY(int y) {
        return y + 1;
    }

    /** 两色线性混合（都把 alpha 当不透明处理，返回值 alpha=FF）。 */
    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // ==================== 交互：点击 ====================

    @Override
    protected boolean onContentClick(double localX, double localY, int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || action != GLFW.GLFW_PRESS) {
            return false;
        }
        if (localY < 0) {
            return false; // 顶栏区域（返回键）
        }
        refresh();
        prepareGrids();

        // ① 先判 ✕（它压在格子上，必须优先于"点格子"）
        int idx = slotGrid.indexAt(localX, localY);
        MainPage.DesktopSlot s = slotAt(idx);
        if (s != null && s.key != null && MainPage.isDesktopRemovable(s.key)) {
            int cx = slotGrid.cellX(idx);
            int cy = slotGrid.cellY(idx);
            int d = removeBtnSize(cell());
            if (hit(removeBtnX(cx, cell()), removeBtnY(cy), d, d, localX, localY)) {
                MainPage.removeDesktopApp(s.key);
                refresh();
                return true;
            }
        }

        // ② 底部「已移除」栏：点一下放回
        int ri = recycleGrid.indexAt(localX, localY);
        if (ri >= 0 && ri < removed.size()) {
            MainPage.restoreDesktopApp(removed.get(ri).key);
            refresh();
            return true;
        }
        return false;
    }

    @Override
    protected void onContentMoved(double localX, double localY) {
        prepareGrids();
        slotGrid.mouseMoved(localX, localY);
        recycleGrid.mouseMoved(localX, localY);
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        // 本页整体不滚（contentHeight=0），只有底部回收栏装不下时滚自己
        double localY = relY - contentTop();
        if (inRecycleArea(localY)) {
            prepareGrids();
            return recycleGrid.mouseScrolled(scrollDelta);
        }
        return false;
    }

    // ==================== 交互：拖放 ====================

    /**
     * 按下那一刻决定要不要接管这次拖动。
     * <p>只有"落在一个有图标、且不是主应用"的格子上才接管 —— 空槽/主应用上按住，
     * 手势照旧交给滚动体系（见类注释）。
     */
    @Override
    protected boolean onDragReorderStart(double localX, double localY) {
        if (localY < 0) {
            return false;
        }
        refresh();
        prepareGrids();
        int idx = slotGrid.indexAt(localX, localY);
        MainPage.DesktopSlot s = slotAt(idx);
        if (s == null || s.key == null || s.fixed) {
            return false;
        }
        // ⚠ 按在 ✕ 上不算拖动起点：接管了这次手势，松开时就<b>不会</b>再补普通点击，
        //    于是 ✕ 永远点不动（这条真的踩过）。把这一小块交还给点击通路。
        //    只有可移除的 App 才有 ✕；设置不可移除但可拖动，因此跳过这块检查。
        if (MainPage.isDesktopRemovable(s.key)) {
            int d = removeBtnSize(cell());
            if (hit(removeBtnX(slotGrid.cellX(idx), cell()), removeBtnY(slotGrid.cellY(idx)),
                    d, d, localX, localY)) {
                return false;
            }
        }
        dragging = true;
        dragFrom = idx;
        dragOver = idx;
        dragOverRecycle = false;
        return true;
    }

    @Override
    protected void onDragReorderMove(double localX, double localY) {
        if (!dragging) {
            return;
        }
        prepareGrids();
        int idx = slotGrid.indexAt(localX, localY);
        dragOver = idx;
        // 落到回收栏 = 松手就移除；落到空格 = 移入
        dragOverRecycle = idx < 0 && inRecycleArea(localY);
    }

    @Override
    protected void onDragReorderEnd(double localX, double localY) {
        if (!dragging) {
            return;
        }
        int from = dragFrom;
        dragging = false;
        dragFrom = -1;
        dragOver = -1;
        dragOverRecycle = false;

        MainPage.DesktopSlot src = slotAt(from);
        if (src == null || src.key == null) {
            return;
        }
        String key = src.key;

        if (inRecycleArea(localY)) {
            if (MainPage.isDesktopRemovable(key)) {
                MainPage.removeDesktopApp(key); // 拖进回收栏 = 移除（不可移除的 App 拖进去不生效）
            }
            refresh();
            return;
        }
        prepareGrids();
        int target = slotGrid.indexAt(localX, localY);
        if (target < 0 || target == from) {
            return; // 没落在有效格子上：什么都不做（不会误移）
        }
        MainPage.moveDesktopApp(key, target);
        refresh();
    }

    @Override
    protected void onDragReorderCancel() {
        dragging = false;
        dragFrom = -1;
        dragOver = -1;
        dragOverRecycle = false;
    }
}
