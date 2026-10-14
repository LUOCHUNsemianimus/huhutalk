package com.huhutalk.phone.pages;

import com.huhutalk.api.phone.ui.PhoneImages;
import com.huhutalk.api.phone.ui.PhonePage;
import com.huhutalk.api.phone.ui.PhoneText;
import com.huhutalk.client.PhotoLibrary;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.api.phone.ui.PhoneShapes;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 桌面「图库」App：每行 3 张的网格相册。
 *
 * <h2>两套手势</h2>
 * <table border="1">
 *   <tr><th>手势</th><th>普通态</th><th>选中态</th></tr>
 *   <tr><td>单击图片</td><td>进入<b>阅览页</b>（大图）</td><td><b>多选</b>：切换这张的选中状态</td></tr>
 *   <tr><td>长按 1 秒且未拖动</td><td>进入选中态并选中这张</td><td>—（已经是选中态）</td></tr>
 *   <tr><td>右上角「删除」</td><td>不显示</td><td>批量删除已选中的照片</td></tr>
 *   <tr><td>右键</td><td>返回桌面</td><td>退出选中态（再来一次才返回桌面）</td></tr>
 * </table>
 * <p>
 * 长按走的是 {@link com.huhutalk.phone.IPhonePage#onLongPress} —— <b>在松开那一刻</b>判定按住时长。
 * 之所以不做"按住到 1 秒即时反馈"：那需要每 tick 的计时器 + 松开时的抑制逻辑，
 * 而当前输入链路本来就在松开时才把点击交给页面（见那个方法的说明）。
 *
 * <h2>选中态的视觉</h2>
 * 图片缩到 <b>0.8 倍</b>并居中（"变小"比"加描边"一眼可辨），右上角补一个粉色小方块。
 * 配色与聊天内图库页一致：选中粉 {@code #DE7E7E}、未选半透明黑 {@code 0x40000000}、
 * 删除红 {@code #E53935}。
 *
 * <h2>列表什么时候刷新</h2>
 * {@link PhotoLibrary#listGallery()} 每次都会<b>读一遍相册目录</b>，所以不能每帧调。
 * 这里只在两个时机刷新：{@link #onOpen()}（每次进入/从阅览页返回）与删除之后。
 */
public class GalleryPage extends PhonePage {

    /** 每行几张 */
    private static final int COLS = 3;
    /** 选中态的图片缩放 */
    private static final float SELECT_SCALE = 0.8f;
    /** 选中粉（与聊天内图库页同一档） */
    private static final int ACCENT = 0xFFDE7E7E;
    /** 未选中的空方块（与聊天内图库页同一档） */
    private static final int DOT_OFF = 0x40000000;
    /** 加载不出纹理时的占位灰 */
    private static final int PLACEHOLDER = 0xFFB0BEC5;

    /** 当前照片列表（最新在前），只在 onOpen / 删除后刷新 */
    private List<String> items = List.of();
    /** 是否处于选中（多选）态 */
    private boolean selecting = false;
    /** 已选中的照片文件名；用 LinkedHashSet 保证"删除 N 张"的计数确定 */
    private final Set<String> selected = new LinkedHashSet<>();

    @Override
    protected String title() {
        return "图库";
    }

    /** 列表页底色用略深一档，与设置类页面一致 */
    @Override
    protected int pageBackground() {
        return colors().pageBgList();
    }

    /** 顶栏用 HuhuTalk 品牌粉（与聊天页/消息页/设置页一致），不跟主题走 */
    @Override
    protected int topBarBackground() {
        return BRAND_TOPBAR_BG;
    }

    @Override
    public void onOpen() {
        refresh();
    }

    /**
     * 重新读取相册目录。
     * <p>
     * 从阅览页返回时也会走到这里（{@code PhoneOverlay.popPage} 会给下层页调 {@code onOpen}），
     * 所以在阅览页里删掉照片后，退回网格看到的就是删过的列表。
     */
    private void refresh() {
        items = PhotoLibrary.listGallery();
        // 已被删掉的文件不可能再留在选中集合里（例如在阅览页删了某张又退回来）
        selected.removeIf(key -> !items.contains(key));
        if (selected.isEmpty()) {
            selecting = false;
        }
    }

    // ==================== 布局（渲染与命中共用） ====================

    private int margin() {
        return PhoneText.px(8);
    }

    private int gap() {
        return PhoneText.px(6);
    }

    /** 单格边长：把手机宽度按 3 列等分（含外边距与列间距） */
    private int cellSize() {
        return Math.max(8, (width() - margin() * 2 - gap() * (COLS - 1)) / COLS);
    }

    private int step() {
        return cellSize() + gap();
    }

    private int rowCount() {
        return (items.size() + COLS - 1) / COLS;
    }

    /** 第 index 格左缘（内容坐标） */
    private int cellX(int index) {
        return margin() + (index % COLS) * step();
    }

    /** 第 index 格上缘（<b>逻辑</b>坐标，渲染时经 {@link #scrollY} 换到内容坐标） */
    private int cellY(int index) {
        return PhoneText.px(8) + (index / COLS) * step();
    }

    @Override
    protected int contentHeight() {
        if (rowCount() == 0) {
            return 0;
        }
        return PhoneText.px(8) * 2 + rowCount() * step() - gap();
    }

    // ==================== 渲染 ====================

    @Override
    protected void renderContent(GuiGraphics g, int ox, int oy, int w, int h, float partialTick) {
        if (items.isEmpty()) {
            PhoneText.drawLeftIn(g, font(), "暂无照片，先用 📷 拍一张吧",
                    ox, oy + PhoneText.px(10), w, PhoneText.px(16), PhoneText.px(10),
                    0xFF8A7C82, PhoneText.scale(0.9f));
            return;
        }
        int cell = cellSize();
        for (int i = 0; i < items.size(); i++) {
            int x = ox + cellX(i);
            int y = oy + scrollY(cellY(i));
            // 视口外的整行直接跳过（相册可能有上百张，别去算它的纹理）
            if (y + cell < oy || y > oy + h) {
                continue;
            }
            drawCell(g, i, x, y, cell);
        }
    }

    /** 画一格：底色/描边 + 照片 + （选中态）右上角小方块 */
    private void drawCell(GuiGraphics g, int index, int x, int y, int cell) {
        String key = items.get(index);
        boolean on = selected.contains(key);

        // 选中 → 整格缩到 0.8 倍并居中。
        // "变小"比"加一圈描边"更能一眼看出选中：周围会多出一圈空隙，即使在缩略图墙里也跳出来。
        int box = on ? Math.round(cell * SELECT_SCALE) : cell;
        int bx = x + (cell - box) / 2;
        int by = y + (cell - box) / 2;

        PhoneShapes.fillRoundedRect(g, bx, by, box, box, PhoneText.px(4), on ? ACCENT : 0xFFFFFFFF);

        int inset = PhoneText.px(2);
        ResourceLocation tex = PhotoLibrary.getOrLoadTexture(key);
        if (tex != null) {
            // getTextureSize 永不返回 null（取不到时兜底 24×24）
            int[] d = PhotoLibrary.getTextureSize(key);
            // cover：任意屏幕比例的截图贴进方格里都不变形
            PhoneImages.drawCover(g, tex, bx + inset, by + inset, box - inset * 2, box - inset * 2, d[0], d[1]);
        } else {
            g.fill(bx + inset, by + inset, bx + box - inset, by + box - inset, PLACEHOLDER);
        }

        // 选中态：右上角小方块（边长 = 格子的 1/4，与聊天内图库页同比例）
        if (selecting) {
            int dot = Math.max(2, Math.round(box / 4f));
            int dx = bx + box - dot - PhoneText.px(2);
            int dy = by + PhoneText.px(2);
            g.fill(dx, dy, dx + dot, dy + dot, on ? ACCENT : DOT_OFF);
        }
    }

    @Override
    protected void renderTopBarExtras(GuiGraphics graphics, int baseX, int baseY, int w, int barH, int textY) {
        // 只有选中态才出现「删除」——它在普通态没有意义（那时点图片是进阅览）
        if (selecting) {
            GalleryDeleteButton.render(graphics, font(), baseX, baseY, w, barH);
        }
    }

    // ==================== 输入 ====================

    @Override
    protected boolean onContentClick(double localX, double localY, int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || action != GLFW.GLFW_PRESS) {
            return false;
        }
        int index = indexAt(localX, localY);
        if (index < 0) {
            return false;
        }
        String key = items.get(index);
        if (selecting) {
            // 选中态：点击 = 多选（再点一次取消）
            if (!selected.remove(key)) {
                selected.add(key);
            }
            if (selected.isEmpty()) {
                selecting = false;   // 全取消了就自动退出选中态，不用玩家再右键一次
            }
        } else {
            // 普通态：单击进阅览
            PhoneOverlay.pushPage(new GalleryViewerPage(key));
        }
        return true;
    }

    /**
     * 长按进入选中态并选中这一张。
     * <p>
     * 返回 true = 已消费，于是这次按住不会再补一次"单击进阅览"——
     * 否则长按之后会立刻弹进阅览页。
     */
    @Override
    public boolean onLongPress(double relX, double relY) {
        // ⚠ 坐标换算：onLongPress 是 IPhonePage 级的方法，收到的是**手机内容区**坐标
        //   （原点在手机内容区左上角，顶栏也在里面）；而 indexAt 与 onContentClick 一样
        //   吃的是 PhonePage 的**内容坐标**（原点在顶栏下方）。少减这一次 contentTop()，
        //   长按就会选中下面一格的图片。
        int index = indexAt(relX, relY - contentTop());
        if (index < 0) {
            return false;
        }
        selecting = true;
        selected.clear();
        selected.add(items.get(index));
        return true;
    }

    /** 右键：选中态先退出选中，再按一次才返回桌面 */
    @Override
    public boolean onBackPressed() {
        if (selecting) {
            selecting = false;
            selected.clear();
            return true;
        }
        return false;
    }

    @Override
    protected boolean onTopBarClick(double relX, double relY, int button, int action) {
        if (selecting && GalleryDeleteButton.hit(font(), width(), topBarHeight(), relX, relY, button, action)) {
            deleteSelected();
            return true;
        }
        return false;
    }

    /** 批量删除已选中的照片 */
    private void deleteSelected() {
        int n = selected.size();
        if (n == 0) {
            return;
        }
        // 先复制一份再遍历：deleteGallery 会动纹理缓存，别在遍历 selected 的同时改它
        List<String> doomed = new ArrayList<>(selected);
        for (String key : doomed) {
            PhotoLibrary.deleteGallery(key);
        }
        selected.clear();
        selecting = false;
        refresh();
        PhoneOverlay.showBubble("已删除 " + n + " 张");
    }

    /** 内容坐标 → 第几格（视口外返回 -1） */
    private int indexAt(double localX, double localY) {
        int logical = logicalY(localY);   // 逆运算：内容坐标 → 逻辑坐标（已含滚动）
        int cell = cellSize();
        for (int i = 0; i < items.size(); i++) {
            int x = cellX(i);
            int y = cellY(i);
            if (localX >= x && localX < x + cell && logical >= y && logical < y + cell) {
                return i;
            }
        }
        return -1;
    }
}
