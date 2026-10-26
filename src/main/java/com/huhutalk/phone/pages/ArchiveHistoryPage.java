package com.huhutalk.phone.pages;

import com.huhutalk.api.phone.ui.PhoneLayout;
import com.huhutalk.api.phone.ui.PhonePage;
import com.huhutalk.api.phone.ui.PhoneShapes;
import com.huhutalk.api.phone.ui.PhoneText;
import com.huhutalk.client.ArchiveAssetClient;
import com.huhutalk.client.ArchiveHistoryClient;
import com.huhutalk.client.theme.ColorScheme;
import com.huhutalk.core.archive.ArchiveEvent;
import com.huhutalk.core.archive.ArchiveEventKind;
import com.huhutalk.core.archive.ArchiveHistoryGeometry;
import com.huhutalk.core.archive.ArchiveHistoryRecord;
import com.huhutalk.core.archive.ArchiveHistoryStatus;
import com.huhutalk.core.archive.ArchiveItemSummary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 女仆资料页进入的独立聊天档案页（只读）。
 *
 * <p>档案是服务端长期保存的在线消息与结构化事件，和本机 {@code ChatHistoryStorage}
 * 是两套存储：本页不写本地历史、不合并实时消息、不提供删除/恢复/领取按钮。分页由
 * 服务端按 sequence 固定上界返回。图片只在用户点击“查看图片”时按需请求，内容区
 * 内联缩放显示，关闭图片或页面会释放纹理并作废 pending；图片加载状态不会重排长文字。</p>
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
    private static final int IMAGE_BUTTON_H = 20;
    private static final int VIEW_HEADER_H = 22;
    private static final int VIEW_FOOTER_H = 32;

    private final String stableId;
    private final String maidName;

    private Layout cachedLayout;
    private int cachedWidth = -1;
    private List<ArchiveHistoryRecord> cachedRecords;
    private ArchiveHistoryStatus cachedStatus;
    private boolean cachedLoading;
    private String cachedLanguage = "";
    private float cachedScale = -1f;

    /** 非空 = 正在内容区内联查看该 hash 的图片（不 push 子页，避免父页 onClose 清分页）。 */
    private String viewingHash;
    private int savedScrollOffset;

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
        viewingHash = null;
        ArchiveHistoryClient.open(stableId);
    }

    @Override
    public void onClose() {
        // 先清图片 pending/纹理，再关闭历史分页。
        ArchiveAssetClient.close();
        viewingHash = null;
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
        return viewingHash != null ? contentViewHeight() : layout().contentH;
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        if (viewingHash != null) {
            // 单图查看不滚页，避免图片透点触发分页。
            return false;
        }
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
        final String imageHash;

        Block(int y, int h, String label, int labelColor, int textColor,
              List<FormattedCharSequence> lines, boolean card, String imageHash) {
            this.y = y;
            this.h = h;
            this.label = label;
            this.labelColor = labelColor;
            this.textColor = textColor;
            this.lines = lines;
            this.card = card;
            this.imageHash = imageHash;
        }
    }

    private record ImageButton(String hash, int y, int h) {
    }

    private static final class Layout {
        final List<Block> blocks;
        final List<ImageButton> imageButtons;
        final int contentH;

        Layout(List<Block> blocks, List<ImageButton> imageButtons, int contentH) {
            this.blocks = blocks;
            this.imageButtons = imageButtons;
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
        List<ImageButton> imageButtons = new ArrayList<>();

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
                String label = roleLabel(record.role());
                int labelColor = roleColor(record.role());
                List<FormattedCharSequence> lines = new ArrayList<>();
                if (!record.text().isEmpty()) {
                    lines.addAll(font.split(Component.literal(record.text()), wrapPx));
                }
                for (String eventLine : eventLines(record.event())) {
                    if (!eventLine.isEmpty()) {
                        lines.addAll(font.split(Component.literal(eventLine), wrapPx));
                    }
                }
                String imageHash = record.hasImage() ? record.attachmentRefs().get(0) : null;
                int before = blocks.size();
                y = addWrapped(blocks, y, label, labelColor, lines, colors().textPrimary(),
                        true, lineH, imageHash != null);
                Block block = blocks.get(before);
                if (imageHash != null) {
                    // 图片按钮放在卡片底部，渲染与命中共用同一逻辑 y。
                    int buttonH = PhoneText.px(IMAGE_BUTTON_H);
                    int buttonY = block.y + block.h - PhoneText.px(CARD_PAD) - buttonH;
                    imageButtons.add(new ImageButton(imageHash, buttonY, buttonH));
                }
            }
        }

        int contentH = y + footerH() + PhoneText.px(8);
        Layout layout = new Layout(List.copyOf(blocks), List.copyOf(imageButtons), contentH);
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
        return addWrapped(blocks, y, label, labelColor, lines, textColor, card, lineH, false);
    }

    private static int addWrapped(List<Block> blocks, int y, String label, int labelColor,
                                  List<FormattedCharSequence> lines, int textColor,
                                  boolean card, int lineH, boolean imageButton) {
        List<FormattedCharSequence> safeLines = lines == null ? List.of() : lines;
        if (label == null && safeLines.isEmpty()) {
            return y;
        }
        int top = card ? PhoneText.px(CARD_PAD) : 0;
        int bottom = card ? PhoneText.px(CARD_PAD) : 0;
        int labelH = label == null ? 0 : lineH + PhoneText.px(2);
        int bodyH = safeLines.size() * lineH;
        int actionH = imageButton ? PhoneText.px(IMAGE_BUTTON_H) + PhoneText.px(4) : 0;
        int h = top + labelH + bodyH + actionH + bottom;
        blocks.add(new Block(y, h, label, labelColor, textColor, safeLines, card,
                imageButton ? "" : null));
        return y + h + PhoneText.px(BLOCK_GAP);
    }

    private static String roleLabel(String role) {
        return switch (role) {
            case "PLAYER" -> PhoneText.tr("gui.huhutalk.archive.role.player");
            case "MAID" -> PhoneText.tr("gui.huhutalk.archive.role.maid");
            case "OBSERVATION" -> PhoneText.tr("gui.huhutalk.archive.role.observation");
            case "SYSTEM" -> PhoneText.tr("gui.huhutalk.archive.role.system");
            default -> PhoneText.tr("gui.huhutalk.archive.role.tool");
        };
    }

    private static int roleColor(String role) {
        return switch (role) {
            case "PLAYER" -> 0xFF1E88E5;
            case "MAID" -> 0xFF00796B;
            case "OBSERVATION" -> 0xFF6A1B9A;
            case "SYSTEM" -> 0xFFC62828;
            default -> 0xFF5D4037;
        };
    }

    /** 结构化事件生成稳定事实行；物品只按 registryId/显示名/真实数量展示。 */
    private static List<String> eventLines(ArchiveEvent event) {
        if (event == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        ArchiveEventKind kind = event.kind();
        if (kind == ArchiveEventKind.IMAGE_SENT) {
            lines.add(PhoneText.tr("gui.huhutalk.archive.event.image_sent"));
        } else if (kind == ArchiveEventKind.VISION_OBSERVATION) {
            lines.add(PhoneText.tr("gui.huhutalk.archive.event.observation",
                    nullToEmpty(event.visualSite()), nullToEmpty(event.visualModel())));
        } else if (kind == ArchiveEventKind.VISION_FAILED) {
            lines.add(PhoneText.tr("gui.huhutalk.archive.event.vision_failed"));
        } else if (kind == ArchiveEventKind.EMOTE) {
            lines.add(PhoneText.tr("gui.huhutalk.archive.event.emote",
                    nullToEmpty(event.emoteResource())));
        } else {
            if (kind == ArchiveEventKind.ITEMS_SENT) {
                lines.add(PhoneText.tr("gui.huhutalk.archive.event.items_sent"));
            } else if (kind == ArchiveEventKind.ITEMS_CLAIMED) {
                lines.add(PhoneText.tr("gui.huhutalk.archive.event.items_claimed"));
            } else if (kind == ArchiveEventKind.ITEMS_DROPPED) {
                lines.add(PhoneText.tr("gui.huhutalk.archive.event.items_dropped"));
            }
            if (event.outcome() == com.huhutalk.core.archive.ArchiveTransferOutcome.QUEUED) {
                lines.add(PhoneText.tr("gui.huhutalk.archive.event.items_queued_note"));
            }
            for (ArchiveItemSummary item : event.items()) {
                lines.add(PhoneText.tr("gui.huhutalk.archive.event.item",
                        PhoneText.tr(directionKey(event.direction())),
                        PhoneText.tr(outcomeKey(event.outcome())),
                        item.displayName(), item.count(), item.itemId()));
            }
        }
        return lines;
    }

    private static String directionKey(com.huhutalk.core.archive.ArchiveTransferDirection direction) {
        if (direction == null) {
            return "gui.huhutalk.archive.direction.unknown";
        }
        return switch (direction) {
            case PLAYER_TO_MAID -> "gui.huhutalk.archive.direction.player_to_maid";
            case MAID_TO_PLAYER -> "gui.huhutalk.archive.direction.maid_to_player";
            case MAID_TO_WORLD -> "gui.huhutalk.archive.direction.maid_to_world";
            case PLAYER_TO_WORLD -> "gui.huhutalk.archive.direction.player_to_world";
        };
    }

    private static String outcomeKey(com.huhutalk.core.archive.ArchiveTransferOutcome outcome) {
        if (outcome == null) {
            return "gui.huhutalk.archive.outcome.unknown";
        }
        return switch (outcome) {
            case SUCCESS -> "gui.huhutalk.archive.outcome.success";
            case PARTIAL -> "gui.huhutalk.archive.outcome.partial";
            case FAILED -> "gui.huhutalk.archive.outcome.failed";
            case QUEUED -> "gui.huhutalk.archive.outcome.queued";
        };
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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
        if (viewingHash != null) {
            renderViewer(g, originX, originY, w, h);
            return;
        }
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
        for (ImageButton button : layout.imageButtons) {
            drawImageButton(g, font, scale, originX, originY, w, button);
        }

        renderFooter(g, font, scale, originX, originY, w, h, lineH);
    }

    private void drawImageButton(GuiGraphics g, Font font, float scale, int originX, int originY,
                                 int w, ImageButton button) {
        int x = originX + PhoneText.px(PAGE_PAD + CARD_PAD);
        int y = originY + scrollY(button.y);
        int bw = w - PhoneText.px((PAGE_PAD + CARD_PAD) * 2);
        PhoneShapes.fillRoundedRect(g, x, y, bw, button.h, button.h / 2, colors().accentSoft());
        String label = PhoneText.truncate(font,
                PhoneText.tr("gui.huhutalk.archive.button.view_image"),
                Math.max(0, bw - PhoneText.px(8)), scale);
        PhoneText.drawCenteredIn(g, font, label, x, y, bw, button.h,
                colors().textPrimary(), scale);
    }

    private void renderViewer(GuiGraphics g, int originX, int originY, int w, int h) {
        Font font = font();
        float scale = PhoneText.scale(1.0f);
        int margin = PhoneText.px(PAGE_PAD);
        int lineH = Math.max(1, PhoneText.height(font, scale));
        PhoneText.draw(g, font, PhoneText.tr("gui.huhutalk.archive.viewer.title"),
                originX + margin, originY + PhoneText.px(6), colors().textPrimary(), scale);

        ResourceLocation texture = ArchiveAssetClient.texture();
        if (texture != null && ArchiveAssetClient.textureWidth() > 0
                && ArchiveAssetClient.textureHeight() > 0) {
            int tw = ArchiveAssetClient.textureWidth();
            int th = ArchiveAssetClient.textureHeight();
            int maxW = Math.max(1, w - margin * 2);
            int maxH = Math.max(1, h - PhoneText.px(VIEW_HEADER_H + VIEW_FOOTER_H + 8));
            float fit = Math.min(maxW / (float) tw, maxH / (float) th);
            int dw = Math.max(1, Math.round(tw * fit));
            int dh = Math.max(1, Math.round(th * fit));
            int dx = originX + (w - dw) / 2;
            int dy = originY + PhoneText.px(VIEW_HEADER_H) + Math.max(0, (maxH - dh) / 2);
            g.blit(texture, dx, dy, dw, dh, 0.0f, 0.0f, tw, th, tw, th);
        } else {
            int messageColor = ArchiveAssetClient.state() == ArchiveAssetClient.State.ERROR
                    ? 0xFFC62828 : ThemeText.secondary();
            String message = ArchiveAssetClient.state() == ArchiveAssetClient.State.ERROR
                    ? viewerErrorText() : PhoneText.tr("gui.huhutalk.archive.viewer.loading");
            PhoneText.drawCenteredIn(g, font, message, originX, originY + lineH * 2, w,
                    PhoneText.px(VIEW_HEADER_H), messageColor, scale);
        }

        int bw = Math.max(1, (w - margin * 2 - PhoneText.px(BUTTON_GAP)) / 2);
        int bh = PhoneText.px(24);
        int by = originY + h - PhoneText.px(VIEW_FOOTER_H) + PhoneText.px(2);
        drawButton(g, font, scale, originX + margin, by, bw, bh, lineH,
                PhoneText.tr("gui.huhutalk.archive.viewer.back"), true, true);
        if (ArchiveAssetClient.canRetry()) {
            drawButton(g, font, scale, originX + margin + bw + PhoneText.px(BUTTON_GAP), by,
                    bw, bh, lineH, PhoneText.tr("gui.huhutalk.archive.viewer.retry"),
                    true, false);
        }
    }

    private static String viewerErrorText() {
        return switch (ArchiveAssetClient.lastError() == null
                ? com.huhutalk.core.archive.ArchiveAssetStatus.ERROR
                : ArchiveAssetClient.lastError()) {
            case MISSING -> PhoneText.tr("gui.huhutalk.archive.viewer.missing");
            case NO_PERMISSION -> PhoneText.tr("gui.huhutalk.archive.viewer.no_permission");
            case RATE_LIMITED -> PhoneText.tr("gui.huhutalk.archive.viewer.busy");
            default -> PhoneText.tr("gui.huhutalk.archive.viewer.failed");
        };
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
        if (viewingHash != null) {
            return clickViewer(localX, localY, viewH);
        }
        int footerTop = viewH - footerH();
        if (localY >= footerTop) {
            return clickFooter(localX, localY, viewH);
        }

        int logicalY = logicalY(localY);
        int bw = width() - PhoneText.px((PAGE_PAD + CARD_PAD) * 2);
        int bx = PhoneText.px(PAGE_PAD + CARD_PAD);
        for (ImageButton imageButton : layout().imageButtons) {
            if (hit(bx, imageButton.y, bw, imageButton.h, localX, logicalY)) {
                openViewer(imageButton.hash);
                return true;
            }
        }
        return false;
    }

    private boolean clickFooter(double localX, double localY, int viewH) {
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

    private boolean clickViewer(double localX, double localY, int viewH) {
        int w = width();
        int margin = PhoneText.px(PAGE_PAD);
        int bw = Math.max(1, (w - margin * 2 - PhoneText.px(BUTTON_GAP)) / 2);
        int bh = PhoneText.px(24);
        int by = viewH - PhoneText.px(VIEW_FOOTER_H) + PhoneText.px(2);
        if (hit(margin, by, bw, bh, localX, localY)) {
            closeViewer();
            return true;
        }
        if (ArchiveAssetClient.canRetry()
                && hit(margin + bw + PhoneText.px(BUTTON_GAP), by, bw, bh, localX, localY)) {
            ArchiveAssetClient.retry();
            return true;
        }
        return true;
    }

    private void openViewer(String hash) {
        savedScrollOffset = scrollOffset();
        viewingHash = hash;
        setScrollOffset(0);
        ArchiveAssetClient.request(stableId, hash);
    }

    private void closeViewer() {
        ArchiveAssetClient.close();
        viewingHash = null;
        setScrollOffset(savedScrollOffset);
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
