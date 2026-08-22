package com.huhutalk.phone.pages;

import com.huhutalk.client.ChatHistoryStorage;
import com.huhutalk.client.MaidListCache;
import com.huhutalk.client.NotificationManager;
import com.huhutalk.client.theme.ColorScheme;
import com.huhutalk.client.theme.ThemeManager;
import com.huhutalk.network.C2SRequestMaidListPacket;
import com.huhutalk.network.S2CMaidListPacket;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.network.PacketDistributor;
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
 * 每个条目显示：头像（右下角在线状态圆点 + 右上角未读红点）、女仆名、
 * 最后一条消息摘要（超宽截断加"…"，未送达时右侧标红"!"）。
 * 点击条目：清未读后进入 {@link ChatPage}（与该女仆的聊天界面）。
 * <p>
 * 列表支持鼠标滚轮滚动；空列表时内容区居中提示「暂无消息会话」。
 */
public class MessageListPage implements IPhonePage {
    /** 会话条目行高度 */
    private static final int ITEM_HEIGHT = 44;
    /** 头像占位色块边长 */
    private static final int AVATAR_SIZE = 28;
    /** 头像角上状态/未读圆点半径（当前圆半径 7 的 2/3） */
    private static final float CORNER_DOT_R = 7f * 2f / 3f;
    /** 常规文字深色 */
    private static final int TEXT_COLOR = 0xFF333333;
    /** 次要文字灰色（摘要/提示） */
    private static final int TEXT_SUB = 0xFF888888;
    /** 未读红点背景 / 未送达红"!" */
    private static final int UNREAD_BG = 0xFFE53935;
    /** 在线状态圆点颜色：在线绿 / 其余灰 */
    private static final int STATUS_ONLINE = 0xFF4CAF50;
    private static final int STATUS_OFFLINE = 0xFF9E9E9E;

    /** 列表滚动偏移（像素） */
    private int scrollOffset = 0;

    public MessageListPage() {
        // 进入消息页即向服务端请求最新女仆列表（用于名字映射、检测新增会话）
        PacketDistributor.sendToServer(new C2SRequestMaidListPacket());
    }

    // ==================== 数据 ====================

    /** 女仆 stableId -> 显示名（MaidListCache 查不到时回退 stableId 本身） */
    private static Map<String, String> buildNameMap() {
        Map<String, String> map = new HashMap<>();
        for (S2CMaidListPacket.MaidDisplayInfo info : MaidListCache.getList()) {
            String name = info.name();
            map.put(info.stableId(), name == null || name.isBlank() ? info.stableId() : name);
        }
        return map;
    }

    /** 文本超宽截断：保留最长前缀并追加"…" */
    private static String truncate(Font font, String text, int maxWidth) {
        if (text == null || text.isEmpty() || font.width(text) <= maxWidth) {
            return text;
        }
        String cut = font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…")));
        return cut + "…";
    }

    /** 从 MaidListCache 查询女仆在线状态；查不到（离线/未加载）返回 null */
    private static com.huhutalk.common.MaidStatus getMaidStatus(String stableId) {
        for (S2CMaidListPacket.MaidDisplayInfo info : MaidListCache.getList()) {
            if (stableId.equals(info.stableId())) {
                return info.status();
            }
        }
        return null;
    }

    /**
     * 以任意倍率绘制文本（无阴影）。
     * <p>MC 字体为位图字体，当「合成后的屏幕缩放」为整数时最清晰（如 GUI 缩放 6×0.5＝3）。
     * 0.75 倍时 6×0.75＝4.5 非整数，清晰度待实测。坐标与截断宽度按 1/scale 换算，
     * 保证缩放后文本落在原位置且占满原宽度。浅底上必须 shadow=false，避免描边粘连。
     */
    private static void drawScaledText(GuiGraphics g, Font font, String text, float x, float y, int color, float scale) {
        float inv = 1.0f / scale;
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(font, text, x * inv, y * inv, color, false);
        g.pose().popPose();
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics graphics, int baseX, int baseY, float partialTick) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int bottomNavH = PhoneOverlay.getBottomNavHeight();
        ColorScheme scheme = ThemeManager.getActive();
        Font font = Minecraft.getInstance().font;
        // 标题栏高度随字体缩放（跟随 GUI 字体大小），而非手机高度百分比
        int navH = navH();

        // 页面底色（浅珍珠粉）
        graphics.fill(baseX, baseY, baseX + w, baseY + h, scheme.pageBg());

        // 顶部标题栏：主题粉底 + 白字标题「消息」
        graphics.fill(baseX, baseY, baseX + w, baseY + navH, scheme.topBarBg());
        String title = "消息";
        graphics.drawString(font, title, baseX + (w - font.width(title)) / 2,
                baseY + (navH - font.lineHeight) / 2 + 1, scheme.topBarText());

        // 内容区：标题栏下方起，底部预留底部导航高度
        int contentTop = baseY + navH;
        int contentBottom = baseY + h - bottomNavH;
        int viewH = Math.max(0, contentBottom - contentTop);

        // 会话数据每次渲染重新读取：删除会话后条目自动消失
        List<String> ids = ChatHistoryStorage.getConversationIds();
        Map<String, String> names = buildNameMap();

        // 列表变短时把滚动钳制回合法区间，避免出现空白
        int maxScroll = Math.max(0, ids.size() * ITEM_HEIGHT - viewH);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        if (ids.isEmpty()) {
            // 空列表：内容区居中提示
            String tip = "暂无消息会话";
            graphics.drawString(font, tip, baseX + (w - font.width(tip)) / 2,
                    (contentTop + contentBottom) / 2 - font.lineHeight / 2, TEXT_SUB, false);
        } else {
            // 裁剪到内容区，防止滚动时溢出到标题栏/底部导航
            graphics.enableScissor(baseX, contentTop, baseX + w, contentBottom);
            for (int i = 0; i < ids.size(); i++) {
                String stableId = ids.get(i);
                int itemY = contentTop + i * ITEM_HEIGHT - scrollOffset;
                if (itemY + ITEM_HEIGHT < contentTop || itemY > contentBottom) {
                    continue;
                }
                renderEntry(graphics, font, scheme, baseX, w, itemY,
                        stableId, names.getOrDefault(stableId, stableId));
            }
            graphics.disableScissor();
        }
    }

    /** 渲染单个会话条目：头像、名字、摘要、状态圆点、未读红点、未送达"!"、分隔线 */
    private void renderEntry(GuiGraphics g, Font font, ColorScheme scheme, int baseX, int w,
                             int itemY, String stableId, String name) {
        // 头像占位色块（条目左侧，柔粉底 + 名字首字）
        int avatarX = baseX + 8;
        int avatarY = itemY + (ITEM_HEIGHT - AVATAR_SIZE) / 2;
        g.fill(avatarX, avatarY, avatarX + AVATAR_SIZE, avatarY + AVATAR_SIZE, scheme.accentSoft());
        String initial = name.isEmpty() ? "?" : name.substring(0, 1);
        g.drawString(font, initial, avatarX + (AVATAR_SIZE - font.width(initial)) / 2,
                avatarY + (AVATAR_SIZE - font.lineHeight) / 2, TEXT_COLOR, false);

        // 在线状态圆点：圆心在头像右下角，半入半出形成"气泡"感；在线绿 / 其余灰
        com.huhutalk.common.MaidStatus maidStatus = getMaidStatus(stableId);
        boolean online = maidStatus == com.huhutalk.common.MaidStatus.ONLINE;
        fillCircle(g, avatarX + AVATAR_SIZE, avatarY + AVATAR_SIZE, CORNER_DOT_R,
                online ? STATUS_ONLINE : STATUS_OFFLINE);

        // 未读红色小圆点：圆心在头像右上角，数字缩到很小
        int unread = NotificationManager.getUnread(stableId);
        if (unread > 0) {
            float cx = avatarX + AVATAR_SIZE;
            float cy = avatarY;
            fillCircle(g, cx, cy, CORNER_DOT_R, UNREAD_BG);
            String num = unread > 99 ? "99+" : String.valueOf(unread);
            float s = 0.4f;
            drawScaledText(g, font, num, cx - font.width(num) * s / 2f, cy - font.lineHeight * s / 2f, 0xFFFFFFFF, s);
        }

        // 右侧文字区起点（头像右侧；右侧无删除按钮，可多用宽度）
        int textX = baseX + 44;
        int textMaxW = w - 44 - 12;

        // 女仆名（深色，超宽截断；浅底上用无阴影，避免 MC 默认描边粘连）
        g.drawString(font, truncate(font, name, textMaxW), textX, itemY + 6, TEXT_COLOR, false);

        // 最后一条消息摘要（灰色，超宽截断加"…"，小字号）
        String last = ChatHistoryStorage.getLastMessage(stableId);
        if (last == null || last.isEmpty()) {
            last = "（无消息内容）";
        }
        final float summaryScale = 0.75f;
        String preview = truncate(font, last, (int)(textMaxW / summaryScale));
        int previewY = itemY + 6 + font.lineHeight + 2;
        drawScaledText(g, font, preview, textX, previewY, TEXT_SUB, summaryScale);

        // 未送达红"!"：标在预览右侧
        if (ChatHistoryStorage.hasPendingMessages(stableId)) {
            drawScaledText(g, font, "!", textX + font.width(preview) * summaryScale + 3, previewY, UNREAD_BG, summaryScale);
        }

        // 底部分隔线
        g.fill(baseX + 8, itemY + ITEM_HEIGHT - 1, baseX + w - 8, itemY + ITEM_HEIGHT, 0x22000000);
    }

    // ==================== 鼠标事件 ====================

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (action != GLFW.GLFW_PRESS) return false;
        int h = PhoneOverlay.getPhoneHeight();
        int navH = navH();
        int bottomNavH = PhoneOverlay.getBottomNavHeight();

        // 标题栏 / 底部导航区域不响应条目点击
        if (relY < navH || relY >= h - bottomNavH) {
            return false;
        }

        List<String> ids = ChatHistoryStorage.getConversationIds();
        Map<String, String> names = buildNameMap();
        for (int i = 0; i < ids.size(); i++) {
            int itemY = navH + i * ITEM_HEIGHT - scrollOffset;
            if (relY < itemY || relY >= itemY + ITEM_HEIGHT) {
                continue;
            }
            String stableId = ids.get(i);
            // 点击会话条目：清未读 → 直接进入与该女仆的聊天界面
            NotificationManager.clear(stableId);
            PhoneOverlay.pushPage(new ChatPage(stableId, names.getOrDefault(stableId, stableId)));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        int h = PhoneOverlay.getPhoneHeight();
        int navH = navH();
        int bottomNavH = PhoneOverlay.getBottomNavHeight();
        int viewH = Math.max(0, h - navH - bottomNavH);

        List<String> ids = ChatHistoryStorage.getConversationIds();
        int maxScroll = Math.max(0, ids.size() * ITEM_HEIGHT - viewH);
        scrollOffset = Math.max(0, Math.min(scrollOffset - (int)(scrollDelta * 10), maxScroll));
        return true;
    }

    /** 顶部标题栏高度：随字体缩放（跟随 GUI 字体大小），保证与消息条目一致 */
    private static int navH() {
        return Minecraft.getInstance().font.lineHeight + 8;
    }

    // ==================== 绘图辅助 ====================

    /** 绘制实心圆（中心 cx,cy，半径 r）。用逐行 fill 填充，保证铺满完整圆形 */
    private static void fillCircle(GuiGraphics g, float cx, float cy, float r, int color) {
        int minY = (int)Math.floor(cy - r);
        int maxY = (int)Math.ceil(cy + r);
        float r2 = r * r;
        for (int y = minY; y <= maxY; y++) {
            float dy = y - cy;
            float dx = (float)Math.sqrt(Math.max(0f, r2 - dy * dy));
            int x0 = (int)Math.floor(cx - dx);
            int x1 = (int)Math.ceil(cx + dx);
            g.fill(x0, y, x1, y + 1, color);
        }
    }
}
