package com.huhutalk.phone.pages;

import com.huhutalk.api.phone.ui.PhoneGrid;
import com.huhutalk.api.phone.ui.PhonePage;
import com.huhutalk.api.phone.ui.PhoneShapes;
import com.huhutalk.api.phone.ui.PhoneText;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 传文件页（聊天页「＋」→「传文件」进入）。
 * <p>
 * 版式约定：两个分区各有一条"一倍字体高"的标题横栏（细线隔断），
 * 标题靠左垂直居中、无阴影；右侧计数字体 0.75 倍默认字。
 * 上半是「我的背包」格网（5 列 × 36 格，可在自己区域内滚动），
 * 下半是「发送给女仆」队列（5 列 × 2 行，固定在底部）。
 * <p>
 * 单击背包非空格 → 加入队列；单击队列已放格 → 移回背包。
 *
 * <h2>改用公开骨架（批次 4）</h2>
 * 旧实现 255 行里有一整套自己写的网格几何：{@code cols/margin/gap/cell/gridTotal/gridX0}
 * 七个几何方法，加 {@code backpackIndexAt} / {@code queueIndexAt} 两个<b>命中</b>方法 ——
 * 而渲染那边又用 {@code row/col → x/y} 把同一套算术各写了一遍
 * （{@code x = baseX + x0 + col * rowH} 出现在渲染里，{@code col = (relX - gridX0()) / rowH}
 * 出现在命中里）。改一处忘一处就是"点不准"。
 * <p>
 * 现在两个网格都用 {@link PhoneGrid}：{@link PhoneGrid#cellX(int)} / {@link PhoneGrid#cellY(int)}
 * 与 {@link PhoneGrid#indexAt(double, double)} 是唯一一对互逆映射，
 * 渲染与命中在结构上不可能不同步；滚动、裁剪、可见性剔除也一并交给它。
 *
 * <h2>与旧实现的差异</h2>
 * <ul>
 *   <li>背包装不下时的滚动从<b>按整行吸附</b>（{@code backpackScrollRows}）变成
 *       {@link PhoneGrid} 的像素滚动（10px/单位）—— 好处是可以露出半行、手感与别的列表页一致；</li>
 *   <li>顶栏高度从 {@code lineHeight + 6} 变成骨架统一的 {@code lineHeight + 8}（本批全局统一）；</li>
 *   <li>顶栏 / 标题横栏的文字改走 {@link PhoneText}，会跟随玩家的「字体倍率」设置缩放
 *       （旧实现是固定 1 倍）。<b>例外</b>：格子内右下角的物品数量角标刻意保持 1 倍 ——
 *       它压在固定 16px 的物品图标上，跟着放大就会溢出格子。</li>
 * </ul>
 */
public class FileTransferPage extends PhonePage {

    /** 每行格数 */
    private static final int COLS = 5;
    /** 背包主槽总数 */
    private static final int BACKPACK_SLOTS_COUNT = 36;

    /** 背包全部槽位下标（0..35）——数据源恒定，建一次即可 */
    private static final List<Integer> BACKPACK_SLOTS =
            IntStream.range(0, BACKPACK_SLOTS_COUNT).boxed().toList();
    /** 队列格下标（0..TRANSFER_CELLS-1） */
    private static final List<Integer> QUEUE_CELLS =
            IntStream.range(0, ChatPage.TRANSFER_CELLS).boxed().toList();

    /** 分区标题横栏底色（比页面略深/略粉，做分区感） */
    private static final int SECTION_BAR_BG = 0xFFF5EBEE;
    /** 分区标题文字色 */
    private static final int SECTION_TITLE = 0xFF6A5A5A;
    /** 分区右侧计数色 */
    private static final int SECTION_COUNT = 0xFF8A6A6A;
    /** 横栏下的细分隔线 */
    private static final int SECTION_LINE = 0xFFD9D2D6;
    /** 空槽底色 */
    private static final int SLOT_EMPTY = 0xFFE4D5DC;
    /** 已入队格底色 */
    private static final int SLOT_QUEUED = 0xFF3A6A86;

    private final ChatPage owner;

    private final PhoneGrid<Integer> backpackGrid = new PhoneGrid<>() {
        @Override
        protected List<Integer> items() {
            return BACKPACK_SLOTS;
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
            drawSlot(g, x, y, w, playerStack(slot), false);
        }

        @Override
        protected void onCellClick(int index, Integer slot) {
            if (!playerStack(slot).isEmpty() && !owner.transferSourceUsed(slot)) {
                owner.transferAdd(slot);
            }
        }
    };

    private final PhoneGrid<Integer> queueGrid = new PhoneGrid<>() {
        @Override
        protected List<Integer> items() {
            return QUEUE_CELLS;
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
        protected void renderCell(GuiGraphics g, int index, Integer cell,
                                  int x, int y, int w, int h, boolean hovered) {
            int src = owner.transferSlot(cell);
            drawSlot(g, x, y, w, src >= 0 ? playerStack(src) : ItemStack.EMPTY, src >= 0);
        }

        @Override
        protected void onCellClick(int index, Integer cell) {
            if (owner.transferSlot(cell) >= 0) {
                owner.transferRemove(cell);
            }
        }
    };

    public FileTransferPage(ChatPage owner) {
        this.owner = owner;
    }

    // ==================== 基础几何（全部是内容坐标） ====================

    private int ln() {
        return Minecraft.getInstance().font.lineHeight;
    }

    /** 顶栏下那条分区横栏的高 = 一行字 */
    private int barH() {
        return ln();
    }

    /** 分区横栏顶（内容坐标 0 起） */
    private int sectionBarTop() {
        return 0;
    }

    /** 背包网格顶（横栏 + 细分隔线下） */
    private int backpackGridTop() {
        return sectionBarTop() + barH() + 1;
    }

    /** 底部队列区横栏顶（从内容区底部反推） */
    private int queueBarTop() {
        int margin = Math.max(2, ln() / 2);
        return contentViewHeight() - (barH() + 1 + 2 * cell() + gap() + margin);
    }

    /** 队列格网顶 */
    private int queueGridTop() {
        return queueBarTop() + barH() + 1;
    }

    /** 左右留白：约为屏宽的很小一部分，配合放大格子 */
    private int margin() {
        return Math.max(2, width() / 40);
    }

    /** 格间距：略随屏宽，便于撑满宽度 */
    private int gap() {
        return Math.max(3, (int) (width() * 0.03));
    }

    /** 格子边长（方形）：由宽度反推，5 列尽量撑满内容区 */
    private int cell() {
        return Math.max(8, (width() - margin() * 2 - gap() * (COLS - 1)) / COLS);
    }

    /** 网格总宽（用于水平居中时左右对称） */
    private int gridTotal() {
        return COLS * cell() + (COLS - 1) * gap();
    }

    /** 网格左起点（水平居中），交给 {@code PhoneGrid#padLeft()} */
    private int gridX0() {
        return (width() - gridTotal()) / 2;
    }

    // ==================== 骨架钩子 ====================

    @Override
    protected String title() {
        return PhoneText.tr("gui.huhutalk.file_transfer.title");
    }

    @Override
    protected int topBarBackground() {
        return BRAND_TOPBAR_BG;
    }

    // contentHeight() 保持默认 0：本页整体不滚，只有背包网格在自己区域内滚。

    // ==================== 渲染 ====================

    @Override
    protected void renderContent(GuiGraphics g, int originX, int originY, int w, int h, float partialTick) {
        // 顶部横栏：我的背包
        drawHeaderBar(g, originX, originY + sectionBarTop(), w,
                PhoneText.tr("gui.huhutalk.file_transfer.backpack"), null, 1.0f);

        backpackGrid.setViewport(0, backpackGridTop(), w,
                Math.max(0, queueBarTop() - backpackGridTop()));
        backpackGrid.render(g, originX, originY);

        // 底部横栏：发送给女仆 + 右侧计数
        drawHeaderBar(g, originX, originY + queueBarTop(), w,
                PhoneText.tr("gui.huhutalk.file_transfer.to_maid"),
                PhoneText.tr("gui.huhutalk.file_transfer.pending", owner.transferCount()), 0.75f);

        queueGrid.setViewport(0, queueGridTop(), w, 2 * cell() + gap());
        queueGrid.render(g, originX, originY);
    }

    /** 画一条"一倍字体高"的分区标题横栏。{@code barY} 是内容坐标 y。 */
    private void drawHeaderBar(GuiGraphics g, int originX, int barY, int w,
                               String left, String right, float rightScale) {
        Font font = font();
        int barH = barH();

        g.fill(originX, barY, originX + w, barY + barH, SECTION_BAR_BG);

        float scale = PhoneText.scale(1.0f);
        // 右侧计数先按上限截断，再把左侧标题收紧到不与它重叠（英文比中文长，直接画会压字）
        float rs = right != null ? PhoneText.scale(rightScale) : 0f;
        String rightShown = right != null
                ? PhoneText.truncate(font, right, Math.max(0, w - PhoneText.px(8) - PhoneText.px(40)), rs)
                : null;
        int rw = rightShown != null ? PhoneText.width(font, rightShown, rs) : 0;
        int leftMax = Math.max(0, w - PhoneText.px(8) - (rightShown != null ? rw + PhoneText.px(6) : 0));
        String leftShown = PhoneText.truncate(font, left, leftMax, scale);
        PhoneText.draw(g, font, leftShown, originX + 4,
                barY + (barH - PhoneText.height(font, scale)) / 2f, SECTION_TITLE, scale);

        if (rightShown != null) {
            int leftW = PhoneText.width(font, leftShown, scale) + 4;
            int avail = Math.max(0, w - leftW - 4);
            PhoneText.draw(g, font, rightShown, originX + leftW + (avail - rw) / 2f,
                    barY + (barH - PhoneText.height(font, rs)) / 2f, SECTION_COUNT, rs);
        }
        g.fill(originX, barY + barH, originX + w, barY + barH + 1, SECTION_LINE);
    }

    /** 画一个圆角方框槽：空槽浅色、有物品深蓝着色。 */
    private static void drawSlot(GuiGraphics g, int x, int y, int size, ItemStack st, boolean queued) {
        PhoneShapes.fillRoundedRect(g, x, y, size, size, Math.max(2, size / 6),
                queued ? SLOT_QUEUED : SLOT_EMPTY);
        if (st.isEmpty()) {
            return;
        }
        int inset = Math.max(2, size / 6);
        int iconBox = size - inset * 2;
        float scale = iconBox / 16f;
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x + inset, y + inset, 0);
        pose.scale(scale, scale, 1f);
        g.renderItem(st, 0, 0);
        pose.popPose();

        if (st.getCount() > 1) {
            // ⚠ 刻意保持 1 倍字号：这个角标压在固定 16px 的物品图标上，
            //    跟随「字体倍率」放大就会溢出格子。
            //
            // ⚠ 同时必须抬到 z=200：物品本体是 GuiGraphics.renderItem 画在 z=150 的，
            //    而 drawString 默认 z=0 —— 深度测试是 LEQUAL，z=0 的文字会被 z=150 的
            //    图标判掉，角标就"看不见"了（原版 renderItemDecorations 用 200 也是这个理由）。
            Font font = Minecraft.getInstance().font;
            String cnt = String.valueOf(st.getCount());
            g.pose().pushPose();
            g.pose().translate(0f, 0f, 200f);
            g.drawString(font, cnt, x + size - font.width(cnt) - 2, y + size - 9, 0xFFFFFFFF);
            g.pose().popPose();
        }
    }

    /** 读取我的背包主槽（玩家未就绪/越界返回 EMPTY）。 */
    private static ItemStack playerStack(int slot) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return ItemStack.EMPTY;
        }
        var inv = player.getInventory();
        if (slot < 0 || slot >= inv.items.size()) {
            return ItemStack.EMPTY;
        }
        return inv.getItem(slot);
    }

    // ==================== 交互 ====================

    /**
     * ⚠ 这里判的是 {@code action == GLFW_PRESS}。
     *
     * <h2>曾经判 RELEASE，结果"点不动东西"</h2>
     * 旧实现自己手写命中（{@code backpackIndexAt} / {@code queueIndexAt}），判的是
     * {@code action == GLFW_RELEASE}；批次 4 迁移时为了"不改既有手感"照抄了这个判据，
     * <b>但命中的活已经交给了 {@link PhoneGrid}</b> —— 而
     * {@link PhoneGrid#mouseClicked} 的契约是<b>按下即触发</b>
     * （{@code if (action != GLFW_PRESS ...) return false;}）。
     * 于是 RELEASE 一路传到网格后被直接吞掉，{@code onCellClick} 永远不触发：
     * 整页的格子全部点不动（顶栏返回键照常能用，因为它走 {@code PhonePage} 的 PRESS 分支）。
     *
     * <h2>为什么改成 PRESS 在手感上"零变化"</h2>
     * {@code PhoneOverlay.onPhoneSideButton} 在轻点结束时是
     * <b>连着补发 PRESS + RELEASE 两个事件</b>（同一个 tick，中间没有任何渲染）：
     * <pre>
     * handlePhoneLocalMouse(relX, relY, LEFT, GLFW_PRESS);
     * handlePhoneLocalMouse(relX, relY, LEFT, GLFW_RELEASE);
     * </pre>
     * 真实鼠标左右键不会走到页面（只有玩家绑定的手机侧键才进这条通路），
     * 所以"按下触发"与"松开触发"的实机时机完全相同 —— 差别只在于
     * <b>PRESS 能真正走到 {@link PhoneGrid}</b>。
     *
     * <h2>通用教训</h2>
     * 判据要与被委托方的契约一致。把"哪一刻算点击"留在页面里、却把命中交给一个
     * 只认 PRESS 的控件，等于两边各说各话 —— 表现就是"渲染正常但点不动"。
     */
    @Override
    protected boolean onContentClick(double localX, double localY, int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || action != GLFW.GLFW_PRESS) {
            return false;
        }
        if (localY < 0) {
            return false; // 顶栏区域（返回键按下时会走到这里，松开不该命中格子）
        }
        // 命中前重新摆一遍，与刚渲染的是同一份布局
        backpackGrid.setViewport(0, backpackGridTop(), width(),
                Math.max(0, queueBarTop() - backpackGridTop()));
        queueGrid.setViewport(0, queueGridTop(), width(), 2 * cell() + gap());

        if (localY > backpackGridTop() && localY < queueBarTop()) {
            backpackGrid.mouseClicked(localX, localY, button, action);
            return true; // 背包区域内一律消费（与旧实现一致）
        }
        queueGrid.mouseClicked(localX, localY, button, action);
        return false;
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        double localY = relY - contentTop();
        if (localY > backpackGridTop() && localY < queueBarTop()) {
            return backpackGrid.mouseScrolled(scrollDelta);
        }
        return false;
    }
}
