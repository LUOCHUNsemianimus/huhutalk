package com.huhutalk.phone.pages;

import com.huhutalk.api.phone.ui.PhoneLayout;
import com.huhutalk.api.phone.ui.PhonePage;
import com.huhutalk.api.phone.ui.PhoneShapes;
import com.huhutalk.api.phone.ui.PhoneText;
import com.huhutalk.client.ArchiveHistoryClient;
import com.huhutalk.client.theme.ColorScheme;
import com.huhutalk.core.archive.ArchiveHistoryGeometry;
import com.huhutalk.core.archive.ArchiveHistoryRecord;
import com.huhutalk.core.archive.ArchiveHistoryStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 女仆资料页进入的独立聊天档案页（只读）。
 *
 * <p>档案是服务端长期保存的在线文字原话，和本机 {@code ChatHistoryStorage} 是两套存储：
 * 本页不写本地历史、不合并实时消息、不提供删除/恢复按钮。分页由服务端按 sequence
 * 固定上界返回，本页只缓存当前页与已访问 cursor；文字按可用宽度完整换行并随页面滚动，
 * 底部刷新/较近/较早按钮固定在内容区底部。</p>
 */
public final class ArchiveHistoryPage extends PhonePage {
    private static final int PAGE_PAD = 10;
    private static final int CARD_PAD = 7;
    private static final int CARD_RADIUS = 8;
    private static final int BLOCK_GAP = 6;
    private static final int FOOTER_H = 34;
    private static final int BUTTON_H = 22;
    private static final int BUTTON_GAP = 6;
    private static final int BUTTON_MARGIN = 10;

    private final String stableId;
    private final String maidName;

    private Layout cachedLayout;
    private int cachedWidth = -1;
    private List<ArchiveHistoryRecord> cachedRecords;
    private ArchiveHistoryStatus cachedStatus;
    private boolean cachedLoading;
    private String cachedLanguage = "";
    private float cachedScale = -1f;

    public ArchiveHistoryPage(String stableId, String maidName) {
        this.stableId = stableId;
        this.maidName = maidName;
    }

    public String getStableId() {
        return stableId;
    }

    @Override
    public void onOpen() {
        // 每次打开都从“最新一页”重新读取；断线重连后同样能重新读取。
        setScrollOffset(0);
        ArchiveHistoryClient.open(stableId);
    }

    @Override
    public void onClose() {
        ArchiveHistoryClient.close(stableId);
    }

    @Override
    protected String title() {
        return PhoneText.tr("gui.huhutalk.archive.title", maidName);
    }

    @Override
    protected int pageBackground() {
        return colors().pageBgList();
    }

    @Override
    protected int topBarBackground() {
        return BRAND_TOPBAR_BG;
    }

    @Override
    protected int contentHeight() {
        return layout().contentH;
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        // 档案正文可能很长：比默认列表略快，仍走骨架的滚动钳制。
        if (relY < contentTop() || maxScroll() <= 0) {
            return false;
        }
        setScrollOffset(scrollOffset() - (int) (scrollDelta * 18));
        return true;
    }

    // ==================== 布局 ====================

    private static final class Block {
        final int y;
        final int h;
        final String label;
        final int labelColor;
        final int textColor;
        final List<FormattedCharSequence> lines;
        final boolean card;

        Block(int y, int h, String label, int labelColor, int textColor,
              List<FormattedCharSequence> lines, boolean card) {
            this.y = y;
            this.h = h;
            this.label = label;
            this.labelColor = labelColor;
            this.textColor = textColor;
            this.lines = lines;
            this.card = card;
        }
    }

    private static final class Layout {
        final List<Block> blocks;
        final int contentH;

        Layout(List<Block> blocks, int contentH) {
            this.blocks = blocks;
            this.contentH = contentH;
        }
    }

    private Layout layout() {
        int w = width();
        List<ArchiveHistoryRecord> records = ArchiveHistoryClient.records();
        ArchiveHistoryStatus status = ArchiveHistoryClient.status();
        boolean loading = ArchiveHistoryClient.isLoading();
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        float scale = PhoneText.scale(1.0f);
        if (cachedLayout != null && cachedWidth == w && cachedRecords == records
                && cachedStatus == status && cachedLoading == loading
                && Objects.equals(cachedLanguage, language)
                && Float.compare(cachedScale, scale) == 0) {
            return cachedLayout;
        }

        Font font = font();
        int lineH = Math.max(1, PhoneText.height(font, scale));
        int wrapPx = Math.max(1, (int) ((w - PhoneText.px(PAGE_PAD) * 2
                - PhoneText.px(CARD_PAD) * 2) / scale));
        int y = PhoneText.px(6);
        List<Block> blocks = new ArrayList<>();

        y = addWrapped(blocks, y, null, 0,
                font.split(Component.literal(PhoneText.tr("gui.huhutalk.archive.note.coverage")), wrapPx),
                colors().textMuted(), false, lineH);
        y = addWrapped(blocks, y, null, 0,
                font.split(Component.literal(PhoneText.tr("gui.huhutalk.archive.note.independent")), wrapPx),
                colors().textMuted(), false, lineH);

        String statusMessage = statusMessage(records, status, loading);
        if (!statusMessage.isEmpty()) {
            int statusColor = statusColor(status);
            y = addWrapped(blocks, y, null, 0,
                    font.split(Component.literal(statusMessage), wrapPx),
                    statusColor, false, lineH);
        }

        if (status == ArchiveHistoryStatus.OK && !loading) {
            for (ArchiveHistoryRecord record : records) {
                String label = record.isPlayer()
                        ? PhoneText.tr("gui.huhutalk.archive.role.player")
                        : PhoneText.tr("gui.huhutalk.archive.role.maid");
                int labelColor = record.isPlayer() ? 0xFF1E88E5 : 0xFF00796B;
                y = addWrapped(blocks, y, label, labelColor,
                        font.split(Component.literal(record.text()), wrapPx),
                        colors().textPrimary(), true, lineH);
            }
        }

        int contentH = y + footerH() + PhoneText.px(8);
        Layout layout = new Layout(List.copyOf(blocks), contentH);
        cachedLayout = layout;
        cachedWidth = w;
        cachedRecords = records;
        cachedStatus = status;
        cachedLoading = loading;
        cachedLanguage = language;
        cachedScale = scale;
        return layout;
    }

    private static int addWrapped(List<Block> blocks, int y, String label, int labelColor,
                                  List<FormattedCharSequence> lines, int textColor,
                                  boolean card, int lineH) {
        List<FormattedCharSequence> safeLines = lines == null ? List.of() : lines;
        if (label == null && safeLines.isEmpty()) {
            return y;
        }
        int top = card ? PhoneText.px(CARD_PAD) : 0;
        int bottom = card ? PhoneText.px(CARD_PAD) : 0;
        int labelH = label == null ? 0 : lineH + PhoneText.px(2);
        int bodyH = safeLines.size() * lineH;
        int h = top + labelH + bodyH + bottom;
        blocks.add(new Block(y, h, label, labelColor, textColor, safeLines, card));
        return y + h + PhoneText.px(BLOCK_GAP);
    }

    private static String statusMessage(List<ArchiveHistoryRecord> records,
                                        ArchiveHistoryStatus status, boolean loading) {
        if (loading) {
            return PhoneText.tr("gui.huhutalk.archive.status.loading");
        }
        if (status == null) {
            return PhoneText.tr("gui.huhutalk.archive.status.loading");
        }
        return switch (status) {
            case NO_ARCHIVE -> PhoneText.tr("gui.huhutalk.archive.status.no_archive");
            case EMPTY -> PhoneText.tr("gui.huhutalk.archive.status.empty");
            case NO_PERMISSION -> PhoneText.tr("gui.huhutalk.archive.status.no_permission");
            case TOO_LARGE -> PhoneText.tr("gui.huhutalk.archive.status.too_large");
            case RATE_LIMITED -> PhoneText.tr("gui.huhutalk.archive.status.busy");
            case INVALID_REQUEST, STALE_WORLD, ERROR ->
                    PhoneText.tr("gui.huhutalk.archive.status.failed");
            case OK -> records.isEmpty()
                    ? PhoneText.tr("gui.huhutalk.archive.status.empty_page") : "";
        };
    }

    private static int statusColor(ArchiveHistoryStatus status) {
        if (status == ArchiveHistoryStatus.NO_PERMISSION
                || status == ArchiveHistoryStatus.TOO_LARGE
                || status == ArchiveHistoryStatus.INVALID_REQUEST
                || status == ArchiveHistoryStatus.STALE_WORLD
                || status == ArchiveHistoryStatus.ERROR) {
            return 0xFFC62828;
        }
        return ThemeText.secondary();
    }

    // ==================== 渲染 ====================

    @Override
    protected void renderContent(GuiGraphics g, int originX, int originY, int w, int h,
                                 float partialTick) {
        Font font = font();
        float scale = PhoneText.scale(1.0f);
        int lineH = Math.max(1, PhoneText.height(font, scale));
        Layout layout = layout();

        for (Block block : layout.blocks) {
            int by = originY + scrollY(block.y);
            int x = originX + PhoneText.px(PAGE_PAD);
            int textY = by;
            if (block.card) {
                PhoneShapes.fillRoundedRect(g, x, by, w - PhoneText.px(PAGE_PAD) * 2,
                        block.h, CARD_RADIUS, colors().cardBg());
                x += PhoneText.px(CARD_PAD);
                textY += PhoneText.px(CARD_PAD);
            }
            if (block.label != null) {
                PhoneText.draw(g, font, block.label, x, textY, block.labelColor, scale);
                textY += lineH + PhoneText.px(2);
            }
            for (FormattedCharSequence line : block.lines) {
                PhoneText.draw(g, font, line, x, textY, block.textColor, scale);
                textY += lineH;
            }
        }

        renderFooter(g, font, scale, originX, originY, w, h, lineH);
    }

    private void renderFooter(GuiGraphics g, Font font, float scale, int originX, int originY,
                              int w, int h, int lineH) {
        ColorScheme c = colors();
        int footerTop = originY + h - footerH();
        g.fill(originX, footerTop, originX + w, originY + h, c.pageBgList());
        PhoneLayout.topShadow(g, originX, footerTop, w);

        boolean loading = ArchiveHistoryClient.isLoading();
        boolean older = ArchiveHistoryClient.hasOlder() && !loading;
        boolean newer = ArchiveHistoryClient.hasNewer() && !loading;
        ArchiveHistoryGeometry geometry = geometry();
        int bw = geometry.buttonWidth();
        int bh = geometry.buttonHeight();
        int by = geometry.screenY(originY, h);

        drawButton(g, font, scale,
                geometry.screenX(originX, ArchiveHistoryGeometry.NEWER_BUTTON), by, bw, bh, lineH,
                PhoneText.tr("gui.huhutalk.archive.button.newer"), newer, false);
        drawButton(g, font, scale,
                geometry.screenX(originX, ArchiveHistoryGeometry.REFRESH_BUTTON), by, bw, bh, lineH,
                PhoneText.tr("gui.huhutalk.archive.button.refresh"), true, true);
        drawButton(g, font, scale,
                geometry.screenX(originX, ArchiveHistoryGeometry.OLDER_BUTTON), by, bw, bh, lineH,
                PhoneText.tr("gui.huhutalk.archive.button.older"), older, false);
    }

    private void drawButton(GuiGraphics g, Font font, float scale, int x, int y, int w, int h,
                            int lineH, String label, boolean enabled, boolean primary) {
        ColorScheme c = colors();
        int bg = primary ? c.accent() : (enabled ? c.accentSoft() : c.neutralBg());
        int fg = primary ? 0xFFFFFFFF : (enabled ? c.textPrimary() : c.textMuted());
        PhoneShapes.fillRoundedRect(g, x, y, w, h, h / 2, bg);
        String shown = PhoneText.truncate(font, label, Math.max(0, w - PhoneText.px(8)), scale);
        PhoneText.drawCenteredIn(g, font, shown, x, y, w, h, fg, scale);
    }

    private static int footerH() {
        return PhoneText.px(FOOTER_H);
    }

    private static int buttonH() {
        return PhoneText.px(BUTTON_H);
    }

    /** 底栏几何：渲染与命中共用同一份本地矩形，渲染时再按内容原点平移。 */
    private ArchiveHistoryGeometry geometry() {
        return new ArchiveHistoryGeometry(width(), PhoneText.px(BUTTON_MARGIN),
                PhoneText.px(BUTTON_GAP), buttonH(), footerH());
    }

    // ==================== 命中 ====================

    @Override
    protected boolean onContentClick(double localX, double localY, int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || action != GLFW.GLFW_PRESS) {
            return false;
        }
        int viewH = contentViewHeight();
        int footerTop = viewH - footerH();
        if (localY < footerTop) {
            return false;
        }
        ArchiveHistoryGeometry geometry = geometry();

        // 固定底栏的命中矩形与渲染共用同一份几何；坐标已是 PhonePage 内容坐标。
        if (geometry.hit(localX, localY, viewH, ArchiveHistoryGeometry.OLDER_BUTTON)) {
            if (ArchiveHistoryClient.hasOlder() && !ArchiveHistoryClient.isLoading()) {
                setScrollOffset(0);
                ArchiveHistoryClient.requestOlder();
            }
            return true;
        }
        if (geometry.hit(localX, localY, viewH, ArchiveHistoryGeometry.REFRESH_BUTTON)) {
            setScrollOffset(0);
            ArchiveHistoryClient.refresh();
            return true;
        }
        if (geometry.hit(localX, localY, viewH, ArchiveHistoryGeometry.NEWER_BUTTON)) {
            if (ArchiveHistoryClient.hasNewer() && !ArchiveHistoryClient.isLoading()) {
                setScrollOffset(0);
                ArchiveHistoryClient.requestNewer();
            }
            return true;
        }
        return true;
    }

    /** 包一层主题文字色，避免在静态 statusColor 里依赖实例。 */
    private static final class ThemeText {
        private ThemeText() {
        }

        static int secondary() {
            return com.huhutalk.client.theme.ThemeManager.getActive().textSecondary();
        }
    }
}
