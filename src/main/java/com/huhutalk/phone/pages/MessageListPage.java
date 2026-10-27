package com.huhutalk.phone.pages;

import com.huhutalk.api.phone.ui.PhoneImages;
import com.huhutalk.api.phone.ui.PhoneList;
import com.huhutalk.api.phone.ui.PhonePage;
import com.huhutalk.api.phone.ui.PhoneShapes;
import com.huhutalk.api.phone.ui.PhoneText;
import com.huhutalk.client.ChatHistoryStorage;
import com.huhutalk.client.MaidAvatarManager;
import com.huhutalk.client.MaidListCache;
import com.huhutalk.client.NotificationManager;
import com.huhutalk.client.theme.ColorScheme;
import com.huhutalk.common.MaidStatus;
import com.huhutalk.network.C2SRequestMaidListPacket;
import com.huhutalk.network.S2CMaidListPacket;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import com.huhutalk.network.transport.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息列表页（底部导航「消息」tab 顶层页）。
 * <p>
 * 仅展示"产生过消息交互的女仆会话"，逻辑对齐手机 QQ 会话列表：
 * 数据源为 {@link ChatHistoryStorage#getConversationIds()}（有历史记录的女仆 stableId），
 * 名字经 {@link MaidListCache} 查询，找不到时回退显示 stableId。
 * <p>
 * 每个条目显示：头像（右下角在线状态方块 + 右上角未读红点）、女仆名、
 * 最后一条消息摘要（超宽截断加"…"，未送达时右侧标红"!"）。
 * 点击条目：清未读后进入 {@link ChatPage}（与该女仆的聊天界面）。
 *
 * <h2>改用公开骨架（批次 3）</h2>
 * 旧实现 368 行，其中列表滚动、裁剪、条目命中、顶栏高度、文字缩放、截断都各抄了一遍：
 * 手绘顶栏与玩家头颅、手写 {@code navH()} / {@code itemH()} / {@code avatarSize()}、
 * 手写 {@code drawScaledText} / {@code truncate} / {@code fillCircle}、
 * 手写 {@code enableScissor} 配对，以及最要命的 ——
 * <b>「第 i 行画在哪」在 {@code render} 里算一遍、在 {@code mouseClicked} 里再算一遍</b>
 * （{@code contentTop + i * itemH - scrollOffset} vs {@code navH + i * itemH - scrollOffset}），
 * 改一处忘另一处就是「点不准」。
 * <p>
 * 现在：顶栏 / 底色 / 裁剪 → {@link PhonePage}；列表的行位置、滚动、命中、可见性剔除 →
 * {@link PhoneList}（{@link PhoneList#itemY(int)} 与 {@link PhoneList#indexAt(double, double)}
 * 是唯一一对互逆映射，渲染与命中在结构上不可能不同步）。
 *
 * <h2>刻意保留的两处</h2>
 * <ul>
 *   <li>头像仍用 {@link PhoneImages#draw}（拉伸）。旧实现就是拉伸，
 *       换成 {@code drawCover}（不变形）会改变观感，留给作者决定；</li>
 *   <li>条目<b>没有悬停高亮</b>（旧实现也没有）。{@code renderItem} 收得到 {@code hovered}，
 *       想加就是一行 {@code g.fill(...)}。</li>
 * </ul>
 */
public class MessageListPage extends PhonePage {

    /** 未读红点背景 / 未送达红"!" */
    private static final int UNREAD_BG = 0xFFE53935;
    /** 在线状态小方块颜色：在线绿 / 其余灰 */
    private static final int STATUS_ONLINE = 0xFF6FD444;
    private static final int STATUS_OFFLINE = 0xFF9E9E9E;
    /** 头像占位色（纹理还没加载出来时） */
    private static final int AVATAR_PLACEHOLDER = 0xFFB0BEC5;
    /** 条目分隔线 */
    private static final int SEPARATOR = 0x22000000;
    /** 滚轮下滑底部余量：让被悬浮导航胶囊盖住的内容能滑出来 */
    private static final int SCROLL_PAD_PX = 30;

    /** 上行：联系人名称缩放（1.25 倍默认大小） */
    private static final float NAME_SCALE = 1.25f;
    /** 下行：最后一条消息预览缩放（0.75 倍默认大小） */
    private static final float SUMMARY_SCALE = 0.75f;
    /** 未读数字缩放 */
    private static final float UNREAD_SCALE = 0.3f;

    /**
     * 本帧的会话列表与名字表。
     * <p>
     * ⚠ 必须缓存、不能每帧直接问 {@link ChatHistoryStorage#getConversationIds()}：
     * 那个方法<b>每次调用都新建一份 List</b>，而 {@link PhoneList#mouseClicked} 会先调
     * {@code indexAt(...)}（读一次 items）再调 {@code items().get(index)}（再读一次）——
     * 两次之间若列表变了就会越界或点错行。缓存到一帧一份即稳。
     * <p>
     * 每帧刷新（与旧实现"删除会话后条目自动消失"一致），点击前也刷新一次。
     */
    private List<String> conversationIds = List.of();
    private Map<String, String> nameMap = Map.of();

    private final PhoneList<String> list = new PhoneList<>() {
        @Override
        protected List<String> items() {
            return conversationIds;
        }

        @Override
        protected int itemHeight() {
            return itemH();
        }

        @Override
        protected void renderItem(GuiGraphics g, int index, String stableId,
                                  int x, int y, int w, int h, boolean hovered) {
            renderEntry(g, x, y, w, stableId);
        }

        @Override
        protected void onItemClick(int index, String stableId) {
            // 点击会话条目：清未读 → 直接进入与该女仆的聊天界面
            NotificationManager.clear(stableId);
            PhoneOverlay.pushPage(new ChatPage(stableId, nameMap.getOrDefault(stableId, stableId)));
        }
    };

    public MessageListPage() {
        // 进入消息页即向服务端请求最新女仆列表（用于名字映射、检测新增会话）
        PacketDistributor.sendToServer(new C2SRequestMaidListPacket());
        // 打开时对已加载且未缓存头像的女仆触发拍照（先验证离屏渲染）
        for (String stableId : ChatHistoryStorage.getConversationIds()) {
            triggerCaptureIfNeeded(stableId);
        }
    }

    @Override
    public void onOpen() {
        refreshConversations();
        for (String stableId : conversationIds) {
            triggerCaptureIfNeeded(stableId);
        }
    }

    // ==================== 数据 ====================

    /** 重新读取会话列表与名字表（渲染每帧一次；点击前再一次）。 */
    private void refreshConversations() {
        conversationIds = ChatHistoryStorage.getConversationIds();
        Map<String, String> map = new HashMap<>();
        for (S2CMaidListPacket.MaidDisplayInfo info : MaidListCache.getList()) {
            String name = info.name();
            map.put(info.stableId(), name == null || name.isBlank() ? info.stableId() : name);
        }
        nameMap = map;
    }

    /** 从 MaidListCache 查询女仆完整条目（含 status / modelId）；查不到返回 null */
    private static S2CMaidListPacket.MaidDisplayInfo getMaidInfo(String stableId) {
        for (S2CMaidListPacket.MaidDisplayInfo info : MaidListCache.getList()) {
            if (stableId.equals(info.stableId())) {
                return info;
            }
        }
        return null;
    }

    /** 触发未缓存头像的女仆拍照（女仆必须在场且未在拍照中） */
    private static void triggerCaptureIfNeeded(String stableId) {
        if (MaidAvatarManager.hasAvatar(stableId) || MaidAvatarManager.isCapturing(stableId)) {
            return;
        }
        MaidAvatarManager.captureIfLoaded(stableId);
    }

    /** 头像边长 = 上行名称文字高度(1.25x) + 下行预览文字高度(0.75x) */
    private static int avatarSize(Font font) {
        return (int) (font.lineHeight * (PhoneText.scale(NAME_SCALE) + PhoneText.scale(SUMMARY_SCALE)));
    }

    /** 会话条目行高度 = 头像高度 + 上下留白 */
    private static int itemH() {
        return avatarSize(Minecraft.getInstance().font) + PhoneText.px(12);
    }

    // ==================== 骨架钩子 ====================

    /** 顶栏左上是「玩家头颅 + ID」，不是居中标题，所以标题留空。 */
    @Override
    protected String title() {
        return "";
    }

    /** 顶层 tab 页没有「返回上一页」这回事（切 tab 由底部导航负责）。 */
    @Override
    protected boolean showBackButton() {
        return false;
    }

    /** 顶栏显式锁成品牌色，与聊天 / 联系人 / 设置是同一台手机。 */
    @Override
    protected int topBarBackground() {
        return BRAND_TOPBAR_BG;
    }

    /** 底部导航是悬浮层，内容滚到底时最后一行会被盖住 —— 留一段可滚动余量。 */
    @Override
    protected int bottomInset() {
        return PhoneOverlay.getBottomNavHeight() + PhoneText.px(SCROLL_PAD_PX);
    }

    // ==================== 顶栏：玩家头颅 + ID ====================

    @Override
    protected void renderTopBarExtras(GuiGraphics g, int baseX, int baseY, int w, int barH, int textY) {
        PlayerHeader.render(g, baseX, baseY, barH, textY, colors().topBarText());
    }

    // ==================== 列表 ====================

    @Override
    protected void renderContent(GuiGraphics g, int originX, int originY, int w, int h, float partialTick) {
        refreshConversations();

        if (conversationIds.isEmpty()) {
            // 空列表：内容区居中提示
            PhoneText.drawCenteredIn(g, font(), PhoneText.tr("gui.huhutalk.messages.empty"), originX, originY, w, h,
                    colors().textSecondary(), PhoneText.scale(1.0f));
            return;
        }
        list.setViewport(0, 0, w, h);
        list.render(g, originX, originY);
    }

    @Override
    protected boolean onContentClick(double localX, double localY, int button, int action) {
        if (action == GLFW.GLFW_PRESS) {
            refreshConversations(); // 命中用的列表必须与渲染同一份
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

    /** 渲染单个会话条目。{@code (x, y)} 是条目左上角的<b>屏幕</b>坐标，{@code w} 是列宽。 */
    private void renderEntry(GuiGraphics g, int x, int y, int w, String stableId) {
        Font font = font();
        ColorScheme scheme = colors();
        int entryH = itemH();
        int avatar = avatarSize(font);
        int avatarX = x + PhoneText.px(6);
        int top = y + (entryH - avatar) / 2;

        // 头像：使用缓存的头像纹理，否则占位色块
        S2CMaidListPacket.MaidDisplayInfo info = getMaidInfo(stableId);
        ResourceLocation tex = MaidAvatarManager.getOrLoadTexture(stableId);
        if (tex != null) {
            int[] d = MaidAvatarManager.getTextureSize(stableId);
            PhoneImages.draw(g, tex, avatarX, top, avatar, avatar,
                    d != null ? d[0] : avatar, d != null ? d[1] : avatar);
        } else {
            g.fill(avatarX, top, avatarX + avatar, top + avatar, AVATAR_PLACEHOLDER);
        }

        // 在线状态小方块：边长 = 原圆点直径的一半，居中于头像右下角；在线绿 / 其余灰
        MaidStatus maidStatus = info == null ? null : info.status();
        boolean online = maidStatus == MaidStatus.ONLINE;
        float dotR = Math.max(2.5f, avatar * 0.22f);
        int sq = Math.max(3, Math.round(dotR));
        int sqX = avatarX + avatar - sq / 2;
        int sqY = top + avatar - sq / 2;
        g.fill(sqX, sqY, sqX + sq, sqY + sq, online ? STATUS_ONLINE : STATUS_OFFLINE);

        // 未读红色小圆点：圆心在头像右上角（用公开的圆角矩形画圆：w = h = 2r、圆角 = r）
        int unread = NotificationManager.getUnread(stableId);
        if (unread > 0) {
            int dotD = Math.max(2, Math.round(dotR * 2));
            int dotX = avatarX + avatar - dotD / 2;
            int dotY = top - dotD / 2;
            PhoneShapes.fillRoundedRect(g, dotX, dotY, dotD, dotD, dotD / 2, UNREAD_BG);
            String num = unread > 99 ? "99+" : String.valueOf(unread);
            PhoneText.drawCenteredIn(g, font, num, dotX, dotY, dotD, dotD,
                    0xFFFFFFFF, PhoneText.scale(UNREAD_SCALE));
        }

        // 右侧文字区（头像右侧）。textMaxW 是屏幕像素，截断交给 PhoneText
        int textX = avatarX + avatar + PhoneText.px(6);
        int textMaxW = Math.max(1, w - avatar - PhoneText.px(14));
        int nameH = PhoneText.height(font, PhoneText.scale(NAME_SCALE));

        PhoneText.drawLeftIn(g, font, nameMap.getOrDefault(stableId, stableId),
                textX, top, textMaxW, nameH, 0, scheme.textPrimary(), PhoneText.scale(NAME_SCALE));

        // 下行：最后一条消息预览（0.75 倍）
        String last = ChatHistoryStorage.getLastMessagePreview(stableId);
        if (last == null || last.isEmpty()) {
            last = PhoneText.tr("gui.huhutalk.messages.no_content");
        }
        String preview = PhoneText.truncate(font, last, textMaxW, PhoneText.scale(SUMMARY_SCALE));
        int previewY = top + nameH;
        PhoneText.draw(g, font, preview, textX, previewY, scheme.textSecondary(),
                PhoneText.scale(SUMMARY_SCALE));

        // 未送达红"!"：标在预览右侧
        if (ChatHistoryStorage.hasPendingMessages(stableId)) {
            PhoneText.draw(g, font, "!",
                    textX + PhoneText.width(font, preview, PhoneText.scale(SUMMARY_SCALE)) + PhoneText.px(3),
                    previewY, UNREAD_BG, PhoneText.scale(SUMMARY_SCALE));
        }

        // 底部分隔线
        g.fill(x + PhoneText.px(6), y + entryH - 1, x + w - PhoneText.px(6), y + entryH, SEPARATOR);
    }
}
