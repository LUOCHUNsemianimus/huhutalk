package com.huhutalk.phone.pages;

import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.api.phone.ui.PhoneShapes;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * 「传文件」物品选择页（P→M 发送队列编辑器）。
 * <p>
 * 不在本页发送：单击（按住 alt）上部我的背包里的物品 → 放入下部队列；再单击队列里的格 → 移回背包。
 * 放好后返回聊天页，点发送按钮会把文字与这些物品一起发给女仆（与图片附件同一套逻辑）。
 * <p>
 * 版式约定：两个分区各有一条"一倍字体高"的标题横栏（背景 #f5ebee，细阴影横线隔断），
 * 标题靠左垂直居中、无阴影；右侧计数字体 0.75 倍默认字。格位按手机屏宽自适应（缩小左右留白、放大格与间距）。
 */
public class FileTransferPage implements IPhonePage {
    private final ChatPage owner;

    public FileTransferPage(ChatPage owner) {
        this.owner = owner;
    }

    // ==================== 基础几何 ====================
    private int W() { return PhoneOverlay.getPhoneWidth(); }
    private int H() { return PhoneOverlay.getPhoneHeight(); }
    private Font font() { return Minecraft.getInstance().font; }
    /** 默认字行高（gui 坐标）。 */
    private int ln() { return Minecraft.getInstance().font.lineHeight; }

    /** 顶栏（含返回/标题）高 = 一行字 + 一点上下留白 */
    private int navH() { return ln() + 6; }
    /** 分区标题横栏高 = 一倍字体高 */
    private int barH() { return ln(); }

    private int cols() { return 5; }
    /** 左右留白：约为屏宽的很小一部分，配合放大格子 */
    private int margin() { return Math.max(2, W() / 40); }
    /** 格间距：略随屏宽，便于撑满宽度 */
    private int gap() { return Math.max(3, (int) (W() * 0.03)); }

    /** 格子边长（方形）：由宽度反推，5 列尽量撑满内容区（减去少量留白与间距）。 */
    private int cell() {
        int c = (W() - margin() * 2 - gap() * (cols() - 1)) / cols();
        return Math.max(8, c);
    }

    /** 网格总宽（用于水平居中时左右对称）。 */
    private int gridTotal() { return cols() * cell() + (cols() - 1) * gap(); }
    /** 网格左起点（水平居中）。 */
    private int gridX0() { return (W() - gridTotal()) / 2; }

    // ==================== 分区纵向排布 ====================
    /** 顶部背包区标题横栏顶部（位于顶栏之下）。 */
    private int topBarTop() { return navH(); }
    /** 顶部标题横栏底（其下为背包网格）。 */
    private int topBarBottom() { return topBarTop() + barH(); }
    /** 背包网格顶（横栏 + 细分隔线下）。 */
    private int backpackGridTop() { return topBarBottom() + 1; }

    /** 底部队列区标题横栏顶部（从页面底部反推）。 */
    private int queueBarTop() {
        // 预留：底部队列横栏 + 2 行格子 + 底部留白
        return H() - (barH() + 1 + 2 * cell() + gap() + Math.max(2, ln() / 2));
    }
    private int queueBarBottom() { return queueBarTop() + barH(); }
    /** 队列格网顶。 */
    private int queueGridTop() { return queueBarBottom() + 1; }

    /** 背包可显示的最大行数（含滚动窗口）。 */
    private int visibleRows() {
        int area = queueBarTop() - backpackGridTop();
        int rowH = cell() + gap();
        return Math.max(1, area / rowH);
    }
    private int totalRows() { return (36 + cols() - 1) / cols(); }
    private int maxScrollRows() { return Math.max(0, totalRows() - visibleRows()); }
    private int backpackScrollRows = 0;

    // ==================== 渲染 ====================
    @Override
    public void render(GuiGraphics g, int baseX, int baseY, float partialTick) {
        int w = W();
        int x0 = gridX0();
        int nav = navH();
        int cell = cell();
        int gap = gap();
        Font font = font();

        // 页面底色 + 顶栏（粉）
        g.fill(baseX, baseY + nav, baseX + w, baseY + H(), 0xFFF0E4EA);
        g.fill(baseX, baseY, baseX + w, baseY + nav, 0xFFDE7E7E);
        g.drawString(font, "< 返回", baseX + 4, baseY + (nav - ln()) / 2, 0xFFFFFFFF);
        String title = "传文件";
        g.drawString(font, title, baseX + (w - font.width(title)) / 2, baseY + (nav - ln()) / 2, 0xFFFFFFFF);

        // 顶部标题横栏：我的背包
        drawHeaderBar(g, baseX, baseY + topBarTop(), w, "我的背包", null, 1f);

        // 我的背包网格
        int gridTop = backpackGridTop();
        int rowH = cell + gap;
        g.enableScissor(baseX, baseY + gridTop, baseX + w, baseY + queueBarTop());
        int vRows = visibleRows();
        for (int i = 0; i < 36; i++) {
            int row = i / cols() - backpackScrollRows;
            if (row < 0 || row >= vRows) continue;
            int col = i % cols();
            int x = baseX + x0 + col * rowH;
            int y = baseY + gridTop + row * rowH;
            drawSlot(g, x, y, cell, playerStack(i), false);
        }
        g.disableScissor();

        // 底部标题横栏：发送给女仆 + 右侧计数字(0.75×)
        String countText = owner.transferCount() + "个文件待发送";
        drawHeaderBar(g, baseX, baseY + queueBarTop(), w, "发送给女仆", countText, 0.75f);

        // 队列网格（2 行 × 5）
        int qTop = queueGridTop();
        for (int i = 0; i < owner.TRANSFER_CELLS; i++) {
            int row = i / 5;
            int col = i % 5;
            int x = baseX + x0 + col * rowH;
            int y = baseY + qTop + row * rowH;
            int src = owner.transferSlot(i);
            ItemStack st = src >= 0 ? playerStack(src) : ItemStack.EMPTY;
            drawSlot(g, x, y, cell, st, src >= 0);
        }
    }

    /**
     * 画一条"一倍字体高"的分区标题横栏（barY 为屏幕绝对 y）：底色 #f5ebee、细阴影分隔线、
     * 左标题无阴影；可选右端文字（按 scale 缩放，无阴影、右端水平居中）。
     */
    private void drawHeaderBar(GuiGraphics g, int baseX, int barY, int w,
                               String left, String right, float rightScale) {
        Font font = font();
        // 底色 #f5ebee（比页面略深/略粉，做分区感）
        g.fill(baseX, barY, baseX + w, barY + barH(), 0xFFF5EBEE);
        // 标题左对齐垂直居中（无阴影）
        g.drawString(font, left, baseX + 4, barY + (barH() - ln()) / 2, 0xFF6A5A5A, false);
        if (right != null) {
            // 右端文字：整体缩放 rightScale，靠右并水平居中
            int leftW = font.width(left) + 4;
            int avail = w - leftW - 4;
            float scaledW = font.width(right) * rightScale;
            int rx = baseX + leftW + (int) ((avail - scaledW) / 2f); // 在右侧区水平居中
            int ry = barY + (barH() - ln()) / 2;
            PoseStack pose = g.pose();
            pose.pushPose();
            pose.translate(rx, ry, 0);
            pose.scale(rightScale, rightScale, 1f);
            g.drawString(font, right, 0, 0, 0xFF8A6A6A, false);
            pose.popPose();
        }
        // 细阴影分隔线（与页面其它分隔线一致 #d9d2d6）
        g.fill(baseX, barY + barH(), baseX + w, barY + barH() + 1, 0xFFD9D2D6);
    }

    /** 画一个圆角方框槽：空槽浅色、有物品深蓝着色。 */
    private void drawSlot(GuiGraphics g, int x, int y, int size, ItemStack st, boolean queued) {
        int bg = queued ? 0xFF3A6A86 : 0xFFE4D5DC;
        PhoneShapes.fillRoundedRect(g, x, y, size, size, Math.max(2, size / 6), bg);
        if (st.isEmpty()) return;
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
            Font font = font();
            String cnt = String.valueOf(st.getCount());
            g.drawString(font, cnt, x + size - font.width(cnt) - 2, y + size - 9, 0xFFFFFFFF);
        }
    }

    /** 读取我的背包主槽（玩家未就绪/越界返回 EMPTY）。 */
    private ItemStack playerStack(int slot) {
        var player = Minecraft.getInstance().player;
        if (player == null) return ItemStack.EMPTY;
        var inv = player.getInventory();
        if (slot < 0 || slot >= inv.items.size()) return ItemStack.EMPTY;
        ItemStack s = inv.getItem(slot);
        return s == null ? ItemStack.EMPTY : s;
    }

    // ==================== 交互 ====================
    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (button != 0 || action != 0) return false;

        if (relX <= 70 && relY <= navH()) {
            PhoneOverlay.popPage();
            return true;
        }

        // 我的背包：单击非空物品 → 加入发送队列
        if (relY > backpackGridTop() && relY < queueBarTop()) {
            int idx = backpackIndexAt(relX, relY);
            if (idx >= 0 && !playerStack(idx).isEmpty() && !owner.transferSourceUsed(idx)) {
                owner.transferAdd(idx);
                return true;
            }
            return true;
        }

        // 发送队列：单击已放格 → 移回背包
        Integer cellHit = queueIndexAt(relX, relY);
        if (cellHit != null && owner.transferSlot(cellHit) >= 0) {
            owner.transferRemove(cellHit);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        if (relY > backpackGridTop() && relY < queueBarTop()) {
            backpackScrollRows = Math.max(0,
                    Math.min(maxScrollRows(), backpackScrollRows - (int) scrollDelta));
            return true;
        }
        return false;
    }

    /** 命中我的背包网格主槽；未命中返回 -1。 */
    private int backpackIndexAt(double relX, double relY) {
        int rowH = cell() + gap();
        int col = (int) ((relX - gridX0()) / rowH);
        int row = (int) ((relY - backpackGridTop()) / rowH) + backpackScrollRows;
        if (col < 0 || col >= cols()) return -1;
        if (row < backpackScrollRows || row >= backpackScrollRows + visibleRows()) return -1; // 只在可见行生效
        int idx = row * cols() + col;
        return idx < 36 ? idx : -1;
    }

    /** 命中发送队列格（0..9）；未命中返回 null。 */
    private Integer queueIndexAt(double relX, double relY) {
        int rowH = cell() + gap();
        int col = (int) ((relX - gridX0()) / rowH);
        int row = (int) ((relY - queueGridTop()) / rowH);
        if (col < 0 || col >= 5 || row < 0 || row >= 2) return null;
        int idx = row * 5 + col;
        return idx < owner.TRANSFER_CELLS ? idx : null;
    }
}
