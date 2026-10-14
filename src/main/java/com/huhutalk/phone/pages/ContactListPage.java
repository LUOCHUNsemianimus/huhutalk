package com.huhutalk.phone.pages;

import com.huhutalk.api.phone.ui.PhoneImages;
import com.huhutalk.api.phone.ui.PhoneList;
import com.huhutalk.api.phone.ui.PhonePage;
import com.huhutalk.api.phone.ui.PhoneText;
import com.huhutalk.client.MaidAvatarManager;
import com.huhutalk.client.MaidGroupHelper;
import com.huhutalk.client.MaidListCache;
import com.huhutalk.client.MaidStatusCache;
import com.huhutalk.client.SosBindCache;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.common.MaidStatus;
import com.huhutalk.network.C2SRequestMaidListPacket;
import com.huhutalk.network.S2CMaidListPacket;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

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
 * 顶栏 #de7e7e，左侧依次：玩家头颅 + 玩家ID + 「联系人」。女仆条目按分组聚合；
 * 条目左为头像（女仆照片）、右为两行（名称 1.2x / 在线+工作状态 0.7x）；
 * 分组标题栏背景与页面一致、标题灰字 0.75x、右侧三角箭头；点击标题栏展开/收起（默认全展开）。
 *
 * <h2>改用公开骨架（批次 3）</h2>
 * 旧实现 381 行，重复的样板与消息列表页同源（手绘顶栏、{@code navH()} / {@code textH} /
 * {@code truncateScaled} / {@code drawScaledText}、手写 scissor 配对），
 * 另外还多一层：<b>它是「分组标题 + 条目」的混排行高</b>，
 * 于是"第 i 行画在哪"的算术在 {@code render} 与 {@code mouseClicked} 里各写一遍
 * （各自把 {@code rowHeight(row)} 累加一遍求 y），任何一行高度改动都会同时影响两处。
 * <p>
 * 现在用 {@link PhoneList} 的<b>可变行高</b>（{@code PhoneList#itemHeight(int)}）：
 * 各行的行高累成一份前缀和，渲染与命中都从同一份前缀和走 ——
 * {@link PhoneList#itemY(int)} 读它、{@link PhoneList#indexAt(double, double)} 对它二分。
 * 折叠/展开分组后调一次 {@link PhoneList#invalidateLayout()} 即可。
 *
 * <h2>刻意保留的细节</h2>
 * <ul>
 *   <li>条目分隔线是<b>左右各内缩 10px</b> 的短线，所以 {@code separatorColor()} 返回 0、
 *       由条目自己画（{@link PhoneList} 的分隔线是整行宽）；</li>
 *   <li>分隔线颜色仍是 {@code 0x22000000}（主题的 {@code divider} 在粉底上反而更抢眼）；</li>
 *   <li>头像仍是拉伸绘制（与旧实现逐像素一致）；</li>
 *   <li>分组标题行不画分隔线 —— 这正是 {@code separatorColor(int)} 存在的理由。</li>
 * </ul>
 */
public class ContactListPage extends PhonePage {

    private static final int GROUP_TITLE_COLOR = 0xFF9A8A90;
    private static final int TEXT_COLOR = 0xFF333333;
    private static final int TEXT_SUB = 0xFF888888;
    private static final int AVATAR_COLOR = 0xFFB0BEC5;
    private static final int SOS_BADGE = 0xFFE53935;
    /** 条目分隔线（左右内缩 10px） */
    private static final int SEPARATOR = 0x22000000;

    /** 分组标题行高（基准像素） */
    private static final int GROUP_HEADER_H = 18;
    /** 分组标题缩放 */
    private static final float GROUP_SCALE = 0.75f;
    /** 条目名称字号 */
    private static final float NAME_SCALE = 1.2f;
    /** 条目状态字号 */
    private static final float STATUS_SCALE = 0.7f;
    /** 好感度等级数字字号 */
    private static final float FAV_SCALE = 0.75f;
    /** 好感度心形背景相对字号（比数字醒目、仍在条目高度内） */
    private static final float FAV_BOX_SCALE = 1.6f;
    /** SOS 角标边长占头像的比例 */
    private static final float SOS_BADGE_RATIO = 0.5f;
    /** 底部滚动余量：让被悬浮导航胶囊盖住的内容能滑出来 */
    private static final int SCROLL_PAD_PX = 30;
    /** 心形贴图（64x64 ARGB，像素画，为清晰放大 2 倍） */
    private static final ResourceLocation HEART_TEX =
            ResourceLocation.fromNamespaceAndPath("huhutalk", "textures/heart.png");
    /** 心形贴图原始尺寸 */
    private static final int HEART_TEX_SIZE = 64;

    /** 一行：分组标题 或 女仆条目 */
    private record Row(boolean header, String group, S2CMaidListPacket.MaidDisplayInfo maid) {
    }

    /**
     * 本帧的行序列。
     * <p>
     * ⚠ 必须缓存：{@link #buildRows()} 每次都重建一份（还要遍历全部女仆 + 排序），
     * 而 {@link PhoneList} 一帧里会问 {@code items()} 好几次；更要紧的是
     * {@code mouseClicked} 先 {@code indexAt(...)} 再 {@code items().get(index)}，
     * 两次之间列表若变了就会越界。
     */
    private List<Row> rows = List.of();
    /** 已收起的分组（其余默认展开） */
    private final Set<String> collapsedGroups = new HashSet<>();

    private final PhoneList<Row> list = new PhoneList<>() {
        @Override
        protected List<Row> items() {
            return rows;
        }

        @Override
        protected int itemHeight() {
            return itemH();
        }

        /** 混排行高：分组标题一行、女仆条目一行。 */
        @Override
        protected int itemHeight(int index) {
            return isHeader(index) ? PhoneText.px(GROUP_HEADER_H) : itemH();
        }

        /** 分隔线由条目自己画（左右内缩），这里一律不画。 */
        @Override
        protected int separatorColor() {
            return 0;
        }

        @Override
        protected void renderItem(GuiGraphics g, int index, Row row,
                                  int x, int y, int w, int h, boolean hovered) {
            if (row.header()) {
                renderGroupHeader(g, x, y, w, row.group());
            } else {
                renderMaidItem(g, x, y, w, row.maid());
            }
        }

        @Override
        protected void onItemClick(int index, Row row) {
            if (row.header()) {
                // 点击分组标题栏：展开/收起
                if (!collapsedGroups.remove(row.group())) {
                    collapsedGroups.add(row.group());
                }
                refreshRows(); // 行数变了，必须重建（并让行高前缀和失效）
                return;
            }
            PhoneOverlay.pushPage(new MaidInfoPage(row.maid().stableId(), row.maid().name()));
        }
    };

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

    private static void triggerCaptureIfNeeded(String stableId) {
        if (MaidAvatarManager.hasAvatar(stableId) || MaidAvatarManager.isCapturing(stableId)) {
            return;
        }
        MaidAvatarManager.captureIfLoaded(stableId);
    }

    // ==================== 骨架钩子 ====================

    /** 顶栏左上是「玩家头颅 + ID + 联系人」，不是居中标题，所以标题留空。 */
    @Override
    protected String title() {
        return "";
    }

    /** 顶层 tab 页没有「返回上一页」这回事（切 tab 由底部导航负责）。 */
    @Override
    protected boolean showBackButton() {
        return false;
    }

    /** 顶栏显式锁成品牌色，与聊天 / 消息 / 设置是同一台手机。 */
    @Override
    protected int topBarBackground() {
        return BRAND_TOPBAR_BG;
    }

    /** 底部导航是悬浮层，内容滚到底时最后一行会被盖住 —— 留一段可滚动余量。 */
    @Override
    protected int bottomInset() {
        return PhoneOverlay.getBottomNavHeight() + PhoneText.px(SCROLL_PAD_PX);
    }

    // ==================== 顶栏：玩家头颅 + ID + 联系人 ====================

    @Override
    protected void renderTopBarExtras(GuiGraphics g, int baseX, int baseY, int w, int barH, int textY) {
        int color = colors().topBarText();
        int x = PlayerHeader.render(g, baseX, baseY, barH, textY, color);
        PhoneText.draw(g, font(), "联系人", x + PhoneText.px(6), textY, color, PhoneText.scale(1.0f));
    }

    // ==================== 列表 ====================

    @Override
    protected void renderContent(GuiGraphics g, int originX, int originY, int w, int h, float partialTick) {
        refreshRows();

        if (rows.isEmpty()) {
            PhoneText.drawCenteredIn(g, font(), "暂无联系人", originX, originY, w, h,
                    colors().textSecondary(), PhoneText.scale(1.0f));
            return;
        }
        list.setViewport(0, 0, w, h);
        list.render(g, originX, originY);
    }

    @Override
    protected boolean onContentClick(double localX, double localY, int button, int action) {
        if (action == GLFW.GLFW_PRESS) {
            refreshRows(); // 命中用的行序列必须与渲染同一份
        }
        return list.mouseClicked(localX, localY, button, action);
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        if (relY < contentTop()) {
            return false; // 顶栏不滚
        }
        return list.mouseScrolled(scrollDelta);
    }

    // ==================== 行渲染 ====================

    private boolean isHeader(int index) {
        return index >= 0 && index < rows.size() && rows.get(index).header();
    }

    /** 分组标题行：底色与页面一致（不额外填充）、灰字 0.75x、右侧展开/收起三角箭头 */
    private void renderGroupHeader(GuiGraphics g, int x, int y, int w, String group) {
        Font font = font();
        int headerH = PhoneText.px(GROUP_HEADER_H);
        float groupScale = PhoneText.scale(GROUP_SCALE);
        PhoneText.draw(g, font, group,
                x + PhoneText.px(10),
                y + (headerH - PhoneText.height(font, groupScale)) / 2f,
                GROUP_TITLE_COLOR, groupScale);

        String arrow = collapsedGroups.contains(group) ? "▸" : "▾";
        float scale = PhoneText.scale(1.0f);
        PhoneText.draw(g, font, arrow,
                x + w - PhoneText.width(font, arrow, scale) - PhoneText.px(10),
                y + (headerH - PhoneText.height(font, scale)) / 2f + 1,
                GROUP_TITLE_COLOR, scale);
    }

    /** 女仆条目：左侧头像(照片)，右侧两行（名称 / 在线+工作状态），最右好感度心形 */
    private void renderMaidItem(GuiGraphics g, int x, int y, int w,
                                S2CMaidListPacket.MaidDisplayInfo info) {
        Font font = font();
        int entryH = itemH();
        int avatar = avatarSize();
        int avatarX = x + PhoneText.px(10);
        int avatarY = y + (entryH - avatar) / 2;

        ResourceLocation tex = MaidAvatarManager.getOrLoadTexture(info.stableId());
        if (tex != null) {
            int[] d = MaidAvatarManager.getTextureSize(info.stableId());
            PhoneImages.draw(g, tex, avatarX, avatarY, avatar, avatar,
                    d != null ? d[0] : avatar, d != null ? d[1] : avatar);
        } else {
            g.fill(avatarX, avatarY, avatarX + avatar, avatarY + avatar, AVATAR_COLOR);
        }

        // SOS 角标：头像右上角小方块
        if (SosBindCache.isBound(info.stableId())) {
            int badge = Math.max(2, Math.round(avatar * SOS_BADGE_RATIO));
            g.fill(avatarX + avatar - badge, avatarY, avatarX + avatar, avatarY + badge, SOS_BADGE);
        }

        // 右侧两行（整体垂直居中）
        int centerY = y + entryH / 2;
        float nameScale = PhoneText.scale(NAME_SCALE);
        float statusScale = PhoneText.scale(STATUS_SCALE);
        int nameH = PhoneText.height(font, nameScale);
        int statusH = PhoneText.height(font, statusScale);
        int nameY = centerY - (nameH + statusH) / 2;
        int statusY = nameY + nameH + 1;
        int nameX = avatarX + avatar + PhoneText.px(8);

        // 右侧好感度等级：heart.png 作背景、数字 0.75x 居中在心上
        int favSize = (int) (font.lineHeight * PhoneText.scale(FAV_BOX_SCALE));
        int favX = x + w - PhoneText.px(10) - favSize;
        int favY = centerY - favSize / 2;
        PhoneImages.draw(g, HEART_TEX, favX, favY, favSize, favSize, HEART_TEX_SIZE, HEART_TEX_SIZE);
        PhoneText.drawCenteredIn(g, font, String.valueOf(info.favorabilityLevel()),
                favX, favY, favSize, favSize, 0xFFFFFFFF, PhoneText.scale(FAV_SCALE));

        PhoneText.draw(g, font, info.name(), nameX, nameY, TEXT_COLOR, nameScale);

        // 下行：在线状态 + 工作状态，过长截断
        String statusText = statusText(info.status());
        String work = MaidStatusCache.get(info.stableId());
        String line2 = work.isEmpty() ? statusText : statusText + " · " + work;
        int rightReserve = favSize + PhoneText.px(10) + PhoneText.px(6);
        int line2MaxW = Math.max(1, w - (nameX - x) - rightReserve);
        PhoneText.draw(g, font, PhoneText.truncate(font, line2, line2MaxW, statusScale),
                nameX, statusY, TEXT_SUB, statusScale);

        // 底部分隔线（左右内缩 10px）
        g.fill(x + PhoneText.px(10), y + entryH - 1, x + w - PhoneText.px(10), y + entryH, SEPARATOR);
    }

    // ==================== 数据 ====================

    /** 头像边长 = 名称(1.2x) + 状态(0.7x) 文字高度之和（随倍率缩放） */
    private static int avatarSize() {
        Font font = Minecraft.getInstance().font;
        return (int) (font.lineHeight * (PhoneText.scale(NAME_SCALE) + PhoneText.scale(STATUS_SCALE)));
    }

    /** 女仆条目行高度 = 头像高度 + 上下留白 */
    private static int itemH() {
        return avatarSize() + PhoneText.px(12);
    }

    private void refreshRows() {
        rows = buildRows();
        list.invalidateLayout(); // 行高与行数都可能变了
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

        List<Row> out = new ArrayList<>();
        for (String group : names) {
            out.add(new Row(true, group, null));
            if (!collapsedGroups.contains(group)) {
                for (S2CMaidListPacket.MaidDisplayInfo info : groups.get(group)) {
                    out.add(new Row(false, null, info));
                }
            }
        }
        return out;
    }

    private static String statusText(MaidStatus status) {
        if (status == null) {
            return "离线";
        }
        return switch (status) {
            case ONLINE -> "在线";
            case OFFLINE -> "离线";
            case DEAD -> "死亡";
            case SHUTDOWN -> "已关机";
            case IN_CHARM -> "魂符内";
        };
    }
}
