package com.huhutalk.phone.pages;

import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.phone.UiShapes;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * 「传文件」物品选择页（P→M 发送队列编辑器）。
 * <p>
 * 这里不直接发送：单击（按住 alt）上部我的背包里的物品，会把它放进下部发送队列；
 * 再单击队列里的物品可移回我的背包。放好后返回聊天页，点发送按钮会把文字与这些物品一并发给女仆
 * （与图片附件同一套逻辑）。底部第5个"+"图标在队列非空时显示小粉点。
 * <p>
 * 本页是"队列选择界面"，自身没有发送按钮。
 */
public class FileTransferPage implements IPhonePage {
    private final ChatPage owner;

    public FileTransferPage(ChatPage owner) {
        this.owner = owner;
    }

    // ==================== 布局 ====================
    private int W() { return PhoneOverlay.getPhoneWidth(); }
    private int H() { return PhoneOverlay.getPhoneHeight(); }
    private Font font() { return Minecraft.getInstance().font; }

    /** 一个物品/槽位的方框边长：两倍默认字体行高（随字体缩放）。 */
    private int slot() {
        return Math.max(12, 2 * Minecraft.getInstance().font.lineHeight);
    }

    private int navH() { return slot(); }

    /** 队列面板（含标题 + 2 行队列）所占高度 */
    private int queuePanelHeight() {
        int s = slot();
        int title = slot();
        return title + 2 * s + Math.max(2, slot() / 3); // 标题行 + 两行格 + 少量余白
    }

    private int queuePanelTop() { return H() - queuePanelHeight(); }

    /** 上部背包可显示区域 */
    private int backpackTop() { return navH(); }
    private int backpackBottom() { return queuePanelTop(); }

    private int cols() { return 5; }
    private int margin() { return Math.max(4, slot() / 2); }
    private int gap() { return Math.max(2, slot() / 4); }

    /** 每格尺寸 = 槽高（两倍字体高）；若 5 列放不下则缩到能放下（保持方形）。 */
    private int cell() {
        int s = slot();
        int avail = W() - margin() * 2 - gap() * (cols() - 1);
        return Math.min(s, avail / cols());
    }

    private int bpRows() { return (36 + cols() - 1) / cols(); }
    private int maxScrollRows() { return Math.max(0, bpRows() - 4); }
    private int backpackScrollRows = 0;

    // ==================== 渲染 ====================
    @Override
    public void render(GuiGraphics g, int baseX, int baseY, float partialTick) {
        int w = W();
        int s = cell();
        int gap = gap();
        int margin = margin();
        int nav = navH();
        Font font = font();
        int totalW = cols() * s + (cols() - 1) * gap;     // 网格总宽
        int gridX = baseX + Math.max(margin, (w - totalW) / 2); // 网格水平居中 → 左右对称

        // 底色与顶栏
        g.fill(baseX, baseY + nav, baseX + w, baseY + H(), 0xFFF0E4EA);
        g.fill(baseX, baseY, baseX + w, baseY + nav, 0xFFDE7E7E);
        g.drawString(font, "< 返回", baseX + 4, baseY + 3, 0xFFFFFFFF);
        String title = "传文件";
        g.drawString(font, title, baseX + (w - font.width(title)) / 2, baseY + 3, 0xFFFFFFFF);

        int listTop = baseY + backpackTop();
        int listBottom = baseY + backpackBottom();

        // ===== 上部：我的背包 =====
        int backTitleY = listTop;
        g.drawString(font, "我的背包", gridX, backTitleY, 0xFF6A5A5A);
        int gridTop = listTop + s; // 标题用一行槽高占位，保持紧凑
        g.enableScissor(baseX, gridTop, baseX + w, listBottom);
        int viewRows = Math.max(1, (listBottom - gridTop) / (s + gap));
        for (int i = 0; i < 36; i++) {
            int row = i / cols() - backpackScrollRows;
            if (row < 0 || row >= viewRows) continue;
            int col = i % cols();
            int x = gridX + col * (s + gap);
            int y = gridTop + row * (s + gap);
            drawSlot(g, x, y, s, playerStack(i), false, false);
        }
        g.disableScissor();

        // ===== 下部：发送队列（2 行 × 5） =====
        int qTop = baseY + queuePanelTop();
        g.fill(baseX, qTop - 1, baseX + w, qTop, 0xFFD9D2D6);
        // 标题行：标题 + 右侧小字"x个文件待发送"
        String qTitle = "发送给女仆";
        int count = owner.transferCount();
        String qCount = count + "个文件待发送";
        g.drawString(font, qTitle, gridX, qTop, 0xFF6A5A5A);
        g.drawString(font, qCount, gridX + totalW - font.width(qCount), qTop, 0xFF8A6A6A);

        int qGridTop = qTop + s;
        for (int i = 0; i < owner.TRANSFER_CELLS; i++) {
            int row = i / 5;
            int col = i % 5;
            int x = gridX + col * (s + gap);
            int y = qGridTop + row * (s + gap);
            int src = owner.transferSlot(i);
            ItemStack st = src >= 0 ? playerStack(src) : ItemStack.EMPTY;
            drawSlot(g, x, y, s, st, src >= 0, true);
        }
    }

    /** 画一个圆角方框槽位：空槽浅色、有物品着色；背包来源格用细边区分。 */
    private void drawSlot(GuiGraphics g, int x, int y, int size, ItemStack st,
                          boolean queued, boolean isQueueCell) {
        int bg = queued ? 0xFF3A6A86 : (isQueueCell ? 0xFFEADEE6 : 0xFFE4D5DC);
        UiShapes.fillRoundedRect(g, x, y, size, size, Math.max(2, size / 6), bg);
        if (queued) {
            // 队列里已放物品：白细边提示"这是待发送格"
            UiShapes.fillRoundedRect(g, x, y, size, 2, 1, 0xFFFFFFFF);
        }
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

    // ==================== 交互：单击加入 / 移出 ====================
    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (button != 0 || action != 0) return false; // 仅左键按下

        if (relX <= 70 && relY <= navH()) {
            PhoneOverlay.popPage();
            return true;
        }

        // 上部我的背包：单击一个非空物品 → 加入发送队列（移到下一个空格）
        if (relY > backpackTop() && relY < backpackBottom()) {
            int idx = backpackIndexAt(relX, relY);
            if (idx >= 0 && !playerStack(idx).isEmpty() && !owner.transferSourceUsed(idx)) {
                owner.transferAdd(idx);
                return true;
            }
            return true; // 点在背包格子上就消费，避免误触其它
        }

        // 下部发送队列：单击已放的格 → 移回我的背包
        Integer cell = queueCellAt(relX, relY);
        if (cell != null && owner.transferSlot(cell) >= 0) {
            owner.transferRemove(cell);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        if (relY >= backpackTop() && relY < backpackBottom()) {
            backpackScrollRows = Math.max(0,
                    Math.min(maxScrollRows(), backpackScrollRows - (int) scrollDelta));
            return true;
        }
        return false;
    }

    /** 命中我的背包网格主槽；未命中返回 -1。 */
    private int backpackIndexAt(double relX, double relY) {
        int s = cell();
        int gap = gap();
        int totalW = cols() * s + (cols() - 1) * gap;
        int gridX = Math.max(margin(), (W() - totalW) / 2);
        int col = (int) ((relX - gridX) / (s + gap));
        int row = (int) ((relY - (backpackTop() + s)) / (s + gap)) + backpackScrollRows;
        if (col < 0 || col >= cols()) return -1;
        int idx = row * cols() + col;
        return idx < 36 ? idx : -1;
    }

    /** 命中发送队列格（0..9）；未命中返回 null。 */
    private Integer queueCellAt(double relX, double relY) {
        int s = cell();
        int gap = gap();
        int totalW = cols() * s + (cols() - 1) * gap;
        int gridX = Math.max(margin(), (W() - totalW) / 2);
        int col = (int) ((relX - gridX) / (s + gap));
        int row = (int) ((relY - (queuePanelTop() + s)) / (s + gap));
        if (col < 0 || col >= 5 || row < 0 || row >= 2) return null;
        int idx = row * 5 + col;
        return idx < owner.TRANSFER_CELLS ? idx : null;
    }
}
