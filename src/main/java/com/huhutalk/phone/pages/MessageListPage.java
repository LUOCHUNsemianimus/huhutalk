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
 * 每个条目显示：女仆名、最后一条消息摘要（超宽截断加"…"）、未读红点数、
 * 右上角删除按钮（✕）。点击条目：清未读后进入 {@link ChatPage}（与该女仆的聊天界面）。
 * 点击删除按钮先弹二次确认，确认后调用 {@link ChatHistoryStorage#deleteConversation}
 * 真实删除会话（含其聊天历史）；因列表每次 render 重新读取，删除后条目自动消失。
 * <p>
 * 列表支持鼠标滚轮滚动；空列表时内容区居中提示「暂无消息会话」。
 */
public class MessageListPage implements IPhonePage {
    /** 顶部标题栏高度比例（h * 0.08） */
    private static final int NAV_H_RATIO = 8;
    /** 会话条目行高度 */
    private static final int ITEM_HEIGHT = 44;
    /** 头像占位色块边长 */
    private static final int AVATAR_SIZE = 28;
    /** 删除按钮（✕）边长 */
    private static final int DELETE_BTN_SIZE = 16;
    /** 未读红点最小宽度 */
    private static final int UNREAD_MIN_W = 16;
    /** 常规文字深色 */
    private static final int TEXT_COLOR = 0xFF333333;
    /** 次要文字灰色（摘要/提示） */
    private static final int TEXT_SUB = 0xFF888888;
    /** 未读红点背景 */
    private static final int UNREAD_BG = 0xFFFF0000;
    /** 删除按钮普通底色（浅灰）与文字色 */
    private static final int DELETE_BG = 0xFFDDDDDD;
    private static final int DELETE_TEXT = 0xFF666666;

    /** 列表滚动偏移（像素） */
    private int scrollOffset = 0;
    /** 待删除二次确认的女仆 stableId；null 表示未处于确认状态 */
    private String pendingDeleteStableId = null;
    /** 鼠标悬停的删除按钮行下标（仅视觉高亮），-1 表示无 */
    private int hoverDeleteIndex = -1;

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

    /**
     * 以 0.5 倍字号绘制文本（无阴影）。
     * <p>MC 字体为位图字体，仅当「合成后的屏幕缩放」为整数时最清晰：
     * 当前 GUI 缩放为 6，0.5 倍缩放 → 6×0.5＝3（整数），故半字号应仍清晰（待验证）。
     * 坐标 x/y 与截断宽度按 2 倍换算，保证半字号文本落在原位置且占满原宽度。
     * 浅底上必须传 shadow=false，否则 MC 默认文字阴影会和黑字粘连。
     */
    private static void drawHalfText(GuiGraphics g, Font font, String text, float x, float y, int color) {
        g.pose().pushPose();
        g.pose().scale(0.5f, 0.5f, 1.0f);
        g.drawString(font, text, x * 2f, y * 2f, color, false);
        g.pose().popPose();
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics graphics, int baseX, int baseY, float partialTick) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int navH = (int)(h * NAV_H_RATIO / 100.0);
        int bottomNavH = PhoneOverlay.getBottomNavHeight();
        ColorScheme scheme = ThemeManager.getActive();
        Font font = Minecraft.getInstance().font;

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
                renderEntry(graphics, font, scheme, baseX, w, i, itemY,
                        stableId, names.getOrDefault(stableId, stableId));
            }
            graphics.disableScissor();
        }

        // 删除二次确认弹窗（置于最上层）
        if (pendingDeleteStableId != null) {
            renderConfirmDialog(graphics, font, scheme, baseX, w, contentTop, contentBottom);
        }
    }

    /** 渲染单个会话条目：头像占位、名字、摘要、未读红点、删除按钮、分隔线 */
    private void renderEntry(GuiGraphics g, Font font, ColorScheme scheme, int baseX, int w, int index,
                             int itemY, String stableId, String name) {
        // 头像占位色块（条目左侧，柔粉底 + 名字首字）
        int avatarX = baseX + 8;
        int avatarY = itemY + (ITEM_HEIGHT - AVATAR_SIZE) / 2;
        g.fill(avatarX, avatarY, avatarX + AVATAR_SIZE, avatarY + AVATAR_SIZE, scheme.accentSoft());
        String initial = name.isEmpty() ? "?" : name.substring(0, 1);
        g.drawString(font, initial, avatarX + (AVATAR_SIZE - font.width(initial)) / 2,
                avatarY + (AVATAR_SIZE - font.lineHeight) / 2, TEXT_COLOR, false);

        // 右侧文字区起点
        int textX = baseX + 44;
        // 为右侧未读红点 + 删除按钮留出空间
        int textMaxW = w - 44 - 60;

        // 女仆名（深色，超宽截断；浅底上用无阴影，避免 MC 默认描边粘连）
        g.drawString(font, truncate(font, name, textMaxW), textX, itemY + 6, TEXT_COLOR, false);

        // 最后一条消息摘要（灰色，超宽截断加"…"，字号为女仆名的一半）
        String last = ChatHistoryStorage.getLastMessage(stableId);
        if (last == null || last.isEmpty()) {
            last = "（无消息内容）";
        }
        // 半字号：截断宽度翻倍（truncate 按默认字号测量），绘制时 0.5 缩放回原位
        drawHalfText(g, font, truncate(font, last, textMaxW * 2), textX,
                itemY + 6 + font.lineHeight + 2, TEXT_SUB);

        // 未读红点数（>0 才显示，红底白字，99+ 封顶）
        int unread = NotificationManager.getUnread(stableId);
        if (unread > 0) {
            String num = unread > 99 ? "99+" : String.valueOf(unread);
            int badgeW = Math.max(UNREAD_MIN_W, font.width(num) + 8);
            int badgeX = baseX + w - 44 - badgeW;
            int badgeY = itemY + (ITEM_HEIGHT - 14) / 2;
            g.fill(badgeX, badgeY, badgeX + badgeW, badgeY + 14, UNREAD_BG);
            g.drawString(font, num, badgeX + (badgeW - font.width(num)) / 2, badgeY + 2, 0xFFFFFFFF);
        }

        // 删除按钮（条目右上角 ✕）：悬停时切为危险红底白字
        int btnX = baseX + w - DELETE_BTN_SIZE - 6;
        int btnY = itemY + 5;
        boolean hovered = hoverDeleteIndex == index;
        g.fill(btnX, btnY, btnX + DELETE_BTN_SIZE, btnY + DELETE_BTN_SIZE,
                hovered ? scheme.dangerBg() : DELETE_BG);
        g.drawString(font, "×", btnX + (DELETE_BTN_SIZE - font.width("×")) / 2,
                btnY + (DELETE_BTN_SIZE - font.lineHeight) / 2,
                hovered ? scheme.dangerText() : DELETE_TEXT, false);

        // 底部分隔线
        g.fill(baseX + 8, itemY + ITEM_HEIGHT - 1, baseX + w - 8, itemY + ITEM_HEIGHT, 0x22000000);
    }

    /** 渲染删除二次确认弹窗：半透明遮罩 + 白底对话框 + 取消/删除按钮 */
    private void renderConfirmDialog(GuiGraphics g, Font font, ColorScheme scheme,
                                     int baseX, int w, int contentTop, int contentBottom) {
        // 内容区半透明遮罩
        g.fill(baseX, contentTop, baseX + w, contentBottom, 0x66000000);
        String name = buildNameMap().getOrDefault(pendingDeleteStableId, pendingDeleteStableId);
        String msg = "删除与「" + name + "」的会话？\n其聊天记录将被清除";
        String[] lines = msg.split("\n", -1);

        int dlgW = w - 40;
        int dlgH = 70;
        int dlgX = baseX + (w - dlgW) / 2;
        int dlgY = (contentTop + contentBottom) / 2 - dlgH / 2;
        g.fill(dlgX, dlgY, dlgX + dlgW, dlgY + dlgH, 0xFFFAFAFA);

        // 弹窗文案（可能两行）
        int textY = dlgY + 10;
        for (String line : lines) {
            g.drawString(font, line, dlgX + 10, textY, TEXT_COLOR, false);
            textY += font.lineHeight + 2;
        }

        // 底部两个按钮：取消（中性灰）/ 删除（危险红）
        int btnW = (dlgW - 30) / 2;
        int btnH = 22;
        int btnY = dlgY + dlgH - btnH - 8;
        int cancelX = dlgX + 10;
        int delX = dlgX + dlgW - 10 - btnW;
        g.fill(cancelX, btnY, cancelX + btnW, btnY + btnH, 0xFFE0E0E0);
        g.drawString(font, "取消", cancelX + (btnW - font.width("取消")) / 2, btnY + 6, TEXT_COLOR, false);
        g.fill(delX, btnY, delX + btnW, btnY + btnH, scheme.dangerBg());
        g.drawString(font, "删除", delX + (btnW - font.width("删除")) / 2, btnY + 6, scheme.dangerText(), false);
    }

    // ==================== 鼠标事件 ====================

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (action != GLFW.GLFW_PRESS) return false;
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int navH = (int)(h * NAV_H_RATIO / 100.0);
        int bottomNavH = PhoneOverlay.getBottomNavHeight();

        // 删除确认弹窗打开时：只处理弹窗交互
        if (pendingDeleteStableId != null) {
            return handleConfirmClick(relX, relY, w, navH, bottomNavH);
        }

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
            // 删除按钮命中 → 进入二次确认
            int btnX = w - DELETE_BTN_SIZE - 6;
            int btnY = itemY + 5;
            if (relX >= btnX && relX <= btnX + DELETE_BTN_SIZE
                    && relY >= btnY && relY <= btnY + DELETE_BTN_SIZE) {
                pendingDeleteStableId = stableId;
                return true;
            }
            // 点击会话条目：清未读 → 直接进入与该女仆的聊天界面
            NotificationManager.clear(stableId);
            PhoneOverlay.pushPage(new ChatPage(stableId, names.getOrDefault(stableId, stableId)));
            return true;
        }
        return false;
    }

    /** 删除确认弹窗的点击处理：删除 / 取消 / 点击外部取消 */
    private boolean handleConfirmClick(double relX, double relY, int w, int navH, int bottomNavH) {
        String stableId = pendingDeleteStableId;
        int h = PhoneOverlay.getPhoneHeight();
        int dlgW = w - 40;
        int dlgH = 70;
        int dlgX = (w - dlgW) / 2;
        int dlgY = (h - bottomNavH + navH) / 2 - dlgH / 2;
        int btnW = (dlgW - 30) / 2;
        int btnH = 22;
        int btnY = dlgY + dlgH - btnH - 8;
        int cancelX = dlgX + 10;
        int delX = dlgX + dlgW - 10 - btnW;

        // 删除按钮：真实删除会话（含聊天历史），并顺带清掉该女仆的未读数
        if (relX >= delX && relX <= delX + btnW && relY >= btnY && relY <= btnY + btnH) {
            ChatHistoryStorage.deleteConversation(stableId);
            NotificationManager.clear(stableId);
            pendingDeleteStableId = null;
            return true;
        }
        // 取消按钮 / 点击弹窗外部 → 取消确认
        boolean onCancel = relX >= cancelX && relX <= cancelX + btnW
                && relY >= btnY && relY <= btnY + btnH;
        boolean insideDlg = relX >= dlgX && relX <= dlgX + dlgW
                && relY >= dlgY && relY <= dlgY + dlgH;
        if (onCancel || !insideDlg) {
            pendingDeleteStableId = null;
            return true;
        }
        // 点击弹窗空白处：保持确认状态
        return true;
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        // 确认弹窗打开期间不滚动列表
        if (pendingDeleteStableId != null) {
            return true;
        }
        int h = PhoneOverlay.getPhoneHeight();
        int navH = (int)(h * NAV_H_RATIO / 100.0);
        int bottomNavH = PhoneOverlay.getBottomNavHeight();
        int viewH = Math.max(0, h - navH - bottomNavH);

        List<String> ids = ChatHistoryStorage.getConversationIds();
        int maxScroll = Math.max(0, ids.size() * ITEM_HEIGHT - viewH);
        scrollOffset = Math.max(0, Math.min(scrollOffset - (int)(scrollDelta * 10), maxScroll));
        return true;
    }

    @Override
    public void mouseMoved(double relX, double relY) {
        hoverDeleteIndex = -1;
        if (pendingDeleteStableId != null) {
            return;
        }
        int h = PhoneOverlay.getPhoneHeight();
        int navH = (int)(h * NAV_H_RATIO / 100.0);
        int bottomNavH = PhoneOverlay.getBottomNavHeight();
        if (relY < navH || relY >= h - bottomNavH) {
            return;
        }
        List<String> ids = ChatHistoryStorage.getConversationIds();
        for (int i = 0; i < ids.size(); i++) {
            int itemY = navH + i * ITEM_HEIGHT - scrollOffset;
            if (relY < itemY || relY >= itemY + ITEM_HEIGHT) {
                continue;
            }
            int btnX = PhoneOverlay.getPhoneWidth() - DELETE_BTN_SIZE - 6;
            int btnY = itemY + 5;
            if (relX >= btnX && relX <= btnX + DELETE_BTN_SIZE
                    && relY >= btnY && relY <= btnY + DELETE_BTN_SIZE) {
                hoverDeleteIndex = i;
            }
            return; // 已命中该行即可结束
        }
    }
}
