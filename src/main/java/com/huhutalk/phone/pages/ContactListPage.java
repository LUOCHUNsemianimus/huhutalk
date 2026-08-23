package com.huhutalk.phone.pages;

import com.huhutalk.client.MaidAvatarManager;
import com.huhutalk.client.MaidGroupHelper;
import com.huhutalk.client.MaidListCache;
import com.huhutalk.client.MaidStatusCache;
import com.huhutalk.client.SosBindCache;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.common.MaidStatus;
import com.huhutalk.network.C2SRequestMaidListPacket;
import com.huhutalk.network.S2CMaidListPacket;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 联系人列表页（底部导航「联系人」tab 顶层页）。
 * <p>
 * 顶栏 #de7e7e，左侧依次：玩家头颅 + 玩家ID + 「联系人」。页面底 #f5ebee。
 * 女仆条目按分组聚合；条目左为头像（女仆照片）、右为两行（名称 1.2x / 在线+工作状态 0.7x）。
 * 分组标题栏背景与页面一致、标题灰字 0.75x、右侧三角箭头；点击标题栏展开/收起（默认全展开）。
 */
public class ContactListPage implements IPhonePage {
    /** 顶部标题栏高度：随字体缩放 */
    private static int navH() {
        return Minecraft.getInstance().font.lineHeight + 8;
    }

    /** 滚轮下滑底部余量：略高于底部导航栏，让被导航栏盖住的内容能滑出来 */
    private static int scrollPad() {
        return PhoneOverlay.getBottomNavHeight() + 30;
    }

    private static final int PAGE_BG = 0xFFF5EBEE;
    private static final int TOPBAR_BG = 0xFFDE7E7E;
    private static final int GROUP_TITLE_COLOR = 0xFF9A8A90;
    private static final int TEXT_COLOR = 0xFF333333;
    private static final int TEXT_SUB = 0xFF888888;
    private static final int AVATAR_COLOR = 0xFFB0BEC5;
    private static final int SOS_BADGE = 0xFFE53935;
    private static final int GROUP_HEADER_H = 18;
    /** 分组标题缩放 */
    private static final float GROUP_SCALE = 0.75f;
    /** 条目名称字号 */
    private static final float NAME_SCALE = 1.2f;
    /** 条目状态字号 */
    private static final float STATUS_SCALE = 0.7f;

    /** 头像边长 = 名称(1.2x) + 状态(0.7x) 文字高度之和 */
    private static int avatarSize() {
        return (int) (Minecraft.getInstance().font.lineHeight * (NAME_SCALE + STATUS_SCALE));
    }

    /** 女仆条目行高度 = 头像高度 + 上下留白 */
    private static int itemH() {
        return avatarSize() + 12;
    }

    private static final int ROW_GROUP = 0;
    private static final int ROW_MAID = 1;

    private record Row(int kind, String group, S2CMaidListPacket.MaidDisplayInfo maid) {}

    /** 列表滚动偏移（像素） */
    private int scrollOffset = 0;
    /** 已收起的分组（其余默认展开） */
    private final Set<String> collapsedGroups = new HashSet<>();

    public ContactListPage() {
        PacketDistributor.sendToServer(new C2SRequestMaidListPacket());
    }

    @Override
    public void onOpen() {
        PacketDistributor.sendToServer(new C2SRequestMaidListPacket());
        // 为未加载头像的女仆触发拍照，保证条目头像能显示（与消息列表页一致）
        for (S2CMaidListPacket.MaidDisplayInfo info : MaidListCache.getList()) {
            triggerCaptureIfNeeded(info.stableId());
        }
    }

    private void triggerCaptureIfNeeded(String stableId) {
        if (MaidAvatarManager.hasAvatar(stableId) || MaidAvatarManager.isCapturing(stableId)) {
            return;
        }
        MaidAvatarManager.captureIfLoaded(stableId);
    }

    @Override
    public void render(GuiGraphics g, int baseX, int baseY, float partialTick) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int bottomNavH = PhoneOverlay.getBottomNavHeight();
        Font font = Minecraft.getInstance().font;
        int navH = navH();

        // 页面底色 #f5ebee
        g.fill(baseX, baseY, baseX + w, baseY + h, PAGE_BG);

        // 顶部标题栏 #de7e7e：玩家头颅 + 玩家ID + 「联系人」（左对齐，固定间距）
        g.fill(baseX, baseY, baseX + w, baseY + navH, TOPBAR_BG);
        renderHeader(g, font, baseX, baseY, navH);

        int contentTop = baseY + navH;
        int contentBottom = baseY + h - bottomNavH;

        List<Row> rows = buildRows();
        int contentH = 0;
        for (Row row : rows) {
            contentH += rowHeight(row);
        }
        int viewH = Math.max(0, contentBottom - contentTop);
        int maxScroll = Math.max(0, contentH + scrollPad() - viewH);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        if (rows.isEmpty()) {
            String tip = "暂无联系人";
            g.drawString(font, tip, baseX + (w - font.width(tip)) / 2,
                    (contentTop + contentBottom) / 2 - font.lineHeight / 2, TEXT_SUB, false);
            return;
        }

        g.enableScissor(baseX, contentTop, baseX + w, contentBottom);
        int y = contentTop - scrollOffset;
        for (Row row : rows) {
            int rowH = rowHeight(row);
            if (y + rowH >= contentTop && y <= contentBottom) {
                if (row.kind() == ROW_GROUP) {
                    renderGroupHeader(g, font, baseX, w, y, row.group(),
                            collapsedGroups.contains(row.group()));
                } else {
                    renderMaidItem(g, font, baseX, w, y, row.maid());
                }
            }
            y += rowH;
        }
        g.disableScissor();
    }

    /** 顶部标题栏：玩家头颅(2D正面) + 玩家ID + 「联系人」，左对齐、固定间距 */
    private void renderHeader(GuiGraphics g, Font font, int baseX, int baseY, int navH) {
        Minecraft mc = Minecraft.getInstance();
        int headSize = navH - 4;
        int headX = baseX + 6;
        int headY = baseY + (navH - headSize) / 2;
        String id = "Player";
        if (mc.player != null) {
            id = mc.player.getGameProfile().getName();
            if (mc.player instanceof AbstractClientPlayer acp) {
                ResourceLocation skin = acp.getSkin().texture();
                g.blit(skin, headX, headY, headSize, headSize, 8.0f, 8.0f, 8, 8, 64, 64);
            } else {
                g.fill(headX, headY, headX + headSize, headY + headSize, 0xFFFFFFFF);
            }
        } else {
            g.fill(headX, headY, headX + headSize, headY + headSize, 0xFFFFFFFF);
        }
        int textY = baseY + (navH - font.lineHeight) / 2 + 1;
        int x = headX + headSize + 6;
        g.drawString(font, id, x, textY, 0xFFFFFFFF, false);
        x += font.width(id) + 6;
        g.drawString(font, "联系人", x, textY, 0xFFFFFFFF, false);
    }

    /** 分组标题行：底色与页面一致、灰字 0.75x、右侧展开/收起三角箭头 */
    private void renderGroupHeader(GuiGraphics g, Font font, int baseX, int w, int y, String group, boolean collapsed) {
        // 背景与页面底色一致，无需额外填充
        int titleH = (int) (font.lineHeight * GROUP_SCALE);
        drawScaledText(g, font, group, baseX + 10, y + (GROUP_HEADER_H - titleH) / 2, GROUP_TITLE_COLOR, GROUP_SCALE);
        String arrow = collapsed ? "▸" : "▾";
        g.drawString(font, arrow, baseX + w - font.width(arrow) - 10,
                y + (GROUP_HEADER_H - font.lineHeight) / 2 + 1, GROUP_TITLE_COLOR, false);
    }

    /** 女仆条目：左侧头像(照片)，右侧两行（名称 1.5x / 在线+工作状态 1.0x），整体垂直居中 */
    private void renderMaidItem(GuiGraphics g, Font font, int baseX, int w, int y,
                                S2CMaidListPacket.MaidDisplayInfo info) {
        int avatarSize = avatarSize();
        int avatarX = baseX + 10;
        int avatarY = y + (itemH() - avatarSize) / 2;
        ResourceLocation tex = MaidAvatarManager.getOrLoadTexture(info.stableId());
        if (tex != null) {
            int[] d = MaidAvatarManager.getTextureSize(info.stableId());
            int tw = d != null ? d[0] : avatarSize;
            int th = d != null ? d[1] : avatarSize;
            g.blit(tex, avatarX, avatarY, avatarSize, avatarSize, 0.0f, 0.0f, tw, th, tw, th);
        } else {
            g.fill(avatarX, avatarY, avatarX + avatarSize, avatarY + avatarSize, AVATAR_COLOR);
        }
        // SOS 角标：头像右上角小方块
        if (SosBindCache.isBound(info.stableId())) {
            g.fill(avatarX + avatarSize - 6, avatarY, avatarX + avatarSize, avatarY + 6, SOS_BADGE);
        }

        // 右侧两行（垂直居中）
        int nameX = avatarX + avatarSize + 8;
        int lineH = font.lineHeight;
        int nameH = (int) (lineH * NAME_SCALE);
        int statusH = (int) (lineH * STATUS_SCALE);
        int centerY = y + itemH() / 2;
        int nameY = centerY - (nameH + statusH) / 2;
        int statusY = nameY + nameH + 1;
        drawScaledText(g, font, info.name(), nameX, nameY, TEXT_COLOR, NAME_SCALE);

        // 下行：在线状态 + 工作状态（STATUS_SCALE），过长截断
        String statusText = statusText(info.status());
        String work = MaidStatusCache.get(info.stableId());
        String line2 = work.isEmpty() ? statusText : statusText + " · " + work;
        int line2MaxW = Math.max(1, w - (nameX - baseX) - 14);
        line2 = truncateScaled(font, line2, line2MaxW, STATUS_SCALE);
        drawScaledText(g, font, line2, nameX, statusY, TEXT_SUB, STATUS_SCALE);

        // 底部分隔线
        g.fill(baseX + 10, y + itemH() - 1, baseX + w - 10, y + itemH(), 0x22000000);
    }

    /** 将全量女仆列表按分组聚合成行序列；已收起的分组只保留标题行 */
    private List<Row> buildRows() {
        Map<String, List<S2CMaidListPacket.MaidDisplayInfo>> groups = new LinkedHashMap<>();
        for (String g : HuhuTalkClientSettings.getCustomGroupNames()) {
            groups.computeIfAbsent(g, k -> new ArrayList<>());
        }
        for (S2CMaidListPacket.MaidDisplayInfo info : MaidListCache.getList()) {
            String group = MaidGroupHelper.resolveGroup(info.stableId(), info.status());
            groups.computeIfAbsent(group, k -> new ArrayList<>()).add(info);
        }
        List<String> names = new ArrayList<>(groups.keySet());
        names.sort(Comparator.comparingInt(MaidGroupHelper::groupSortIndex));

        List<Row> rows = new ArrayList<>();
        for (String group : names) {
            rows.add(new Row(ROW_GROUP, group, null));
            if (!collapsedGroups.contains(group)) {
                for (S2CMaidListPacket.MaidDisplayInfo info : groups.get(group)) {
                    rows.add(new Row(ROW_MAID, null, info));
                }
            }
        }
        return rows;
    }

    private static int rowHeight(Row row) {
        return row.kind() == ROW_GROUP ? GROUP_HEADER_H : itemH();
    }

    private static String statusText(MaidStatus status) {
        if (status == null) return "离线";
        return switch (status) {
            case ONLINE -> "在线";
            case OFFLINE -> "离线";
            case DEAD -> "死亡";
            case SHUTDOWN -> "已关机";
        };
    }

    /** 按 0.75 倍缩放截断文本，过长补"…" */
    private static String truncateScaled(Font font, String text, int maxW, float scale) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        if (maxW <= 0 || font.width(text) * scale <= maxW) return text;
        int room = Math.max(1, (int) ((maxW - font.width("…") * scale) / scale));
        String cut = font.plainSubstrByWidth(text, room);
        return cut + "…";
    }

    /** 以任意倍率绘制文本（无阴影） */
    private static void drawScaledText(GuiGraphics g, Font font, String text,
                                       float x, float y, int color, float scale) {
        float inv = 1.0f / scale;
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(font, text, x * inv, y * inv, color, false);
        g.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (action != 1) return false;
        int h = PhoneOverlay.getPhoneHeight();
        int navH = navH();
        int bottomNavH = PhoneOverlay.getBottomNavHeight();
        if (relY < navH || relY >= h - bottomNavH) {
            return false;
        }

        List<Row> rows = buildRows();
        int contentH = 0;
        for (Row row : rows) {
            contentH += rowHeight(row);
        }
        int viewH = Math.max(0, h - navH - bottomNavH);
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, contentH + scrollPad() - viewH)));

        int y = navH - scrollOffset;
        for (Row row : rows) {
            int rowH = rowHeight(row);
            if (relY >= y && relY < y + rowH) {
                if (row.kind() == ROW_GROUP) {
                    // 点击分组标题栏：展开/收起
                    String group = row.group();
                    if (collapsedGroups.contains(group)) {
                        collapsedGroups.remove(group);
                    } else {
                        collapsedGroups.add(group);
                    }
                    return true;
                }
                S2CMaidListPacket.MaidDisplayInfo info = row.maid();
                PhoneOverlay.pushPage(new MaidInfoPage(info.stableId(), info.name()));
                return true;
            }
            y += rowH;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        int h = PhoneOverlay.getPhoneHeight();
        int navH = navH();
        int bottomNavH = PhoneOverlay.getBottomNavHeight();

        List<Row> rows = buildRows();
        int contentH = 0;
        for (Row row : rows) {
            contentH += rowHeight(row);
        }
        int viewH = Math.max(0, h - navH - bottomNavH);
        int maxScroll = Math.max(0, contentH + scrollPad() - viewH);
        scrollOffset = Math.max(0, Math.min(scrollOffset - (int) (scrollDelta * 10), maxScroll));
        return true;
    }
}
