package com.huhutalk.phone.pages;

import com.huhutalk.client.ChatHistoryStorage;
import com.huhutalk.client.MaidAvatarManager;
import com.huhutalk.client.MaidGroupHelper;
import com.huhutalk.client.MaidListCache;
import com.huhutalk.client.MaidStatusCache;
import com.huhutalk.client.SosBindCache;
import com.huhutalk.client.SosTaskCache;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.client.theme.ColorScheme;
import com.huhutalk.client.theme.ThemeManager;
import com.huhutalk.common.MaidStatus;
import com.huhutalk.network.C2SDeleteContactPacket;
import com.huhutalk.network.C2SRequestMaidStatusPacket;
import com.huhutalk.network.C2SRequestSosTaskListPacket;
import com.huhutalk.network.C2SSetSosModePacket;
import com.huhutalk.network.C2SToggleSosBindPacket;
import com.huhutalk.network.S2CMaidListPacket;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneGroupNameInputScreen;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.phone.UiShapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 联系人设置页（消息列表页 / 联系人列表页点击女仆统一进入）。
 * <p>
 * UI：顶栏 #de7e7e；头部大头像（女仆照片）+ 名称/状态（1.75/1.25 倍字号）；页面底 #f0e4ea；
 * 全部设置项为圆角卡片 #fff5f8（圆角 8）。分三组：
 * <ul>
 *   <li><b>管理</b>：发消息 / 删除记录 / 删除联系人 / 更新头像。</li>
 *   <li><b>SOS呼叫</b>：SOS呼叫（绑定开关）/ SOS工作模式（点击展开原下拉）。</li>
 *   <li><b>分组设置</b>：现有分组选项（默认 + 自定义）+ 底部「+ 新建分组」。</li>
 * </ul>
 * 新建分组把新组名写入持久化列表并分当前女仆到该组，联系人页据此同步展示（含空组）。
 * 逻辑（删除/绑定/模式/分组/头像）均沿用原有实现。
 */
public class MaidInfoPage implements IPhonePage {
    private static final int CARD_H = 34;
    private static final int CARD_GAP = 6;
    private static final int CARD_R = 8;
    private static final int GROUP_TITLE_SPACE = 16;
    private static final int DROPDOWN_ROW_H = 16;
    private static final int DROPDOWN_VISIBLE_ROWS = 5;
    private static final int TEXT_COLOR = 0xFF333333;
    private static final int TEXT_SUB = 0xFF888888;
    private static final int PAGE_BG = 0xFFF0E4EA;
    private static final int TOPBAR_BG = 0xFFDE7E7E;
    private static final int CARD_BG = 0xFFFFF5F8;
    private static final int CARD_ACTIVE_BG = 0xFFFFE4EC; // 当前分组高亮
    private static final int GROUP_TITLE_COLOR = 0xFF9A8A90;
    private static final int DANGER = 0xFFC62828;

    private final String stableId;
    private final String maidName;

    /** 页面滚动偏移（像素） */
    private int scrollOffset = 0;
    /** SOS 工作模式下拉是否展开 + 高亮光标下标 */
    private boolean modeDropdownOpen = false;
    private int modeCursor = 0;
    /** 是否处于「删除联系人」二次确认状态（弹确认框） */
    private boolean confirmDeletePending = false;
    /** 短暂提示（toast）：文本 + 到期时间戳 */
    private String flashText = "";
    private long flashUntil = 0;

    public MaidInfoPage(String stableId, String maidName) {
        this.stableId = stableId;
        this.maidName = maidName;
        PacketDistributor.sendToServer(new C2SRequestMaidStatusPacket(List.of(stableId)));
        if (SosTaskCache.isEmpty()) {
            PacketDistributor.sendToServer(new C2SRequestSosTaskListPacket());
        }
    }

    // ==================== 数据 ====================

    public String getStableId() { return stableId; }

    /** 从 MaidListCache 查该女仆的 MaidStatus（查不到返回 null，按离线默认分组） */
    private MaidStatus currentStatus() {
        for (S2CMaidListPacket.MaidDisplayInfo info : MaidListCache.getList()) {
            if (stableId.equals(info.stableId())) {
                return info.status();
            }
        }
        return null;
    }

    /** 当前分组：优先手动分组，未设置则按状态默认分组 */
    private String currentGroup() {
        return MaidGroupHelper.resolveGroup(stableId, currentStatus());
    }

    /** 分组可选列表：默认分组 + 玩家自定义分组（保持顺序） */
    private List<String> groupOptions() {
        List<String> list = new ArrayList<>();
        list.add(MaidGroupHelper.GROUP_ONLINE);
        list.add(MaidGroupHelper.GROUP_OFFLINE);
        list.add(MaidGroupHelper.GROUP_SHUTDOWN);
        list.add(MaidGroupHelper.GROUP_DEAD);
        for (String g : HuhuTalkClientSettings.getCustomGroupNames()) {
            if (!list.contains(g)) list.add(g);
        }
        return list;
    }

    private void showFlash(String text) {
        flashText = text;
        flashUntil = System.currentTimeMillis() + 2000;
    }

    /** 点击"更新头像"：对在场女仆拍照生成头像 */
    private void updateAvatar() {
        int result = MaidAvatarManager.captureIfLoaded(stableId);
        switch (result) {
            case 1 -> showFlash("📷 头像已更新");
            case 2 -> showFlash("⚠ 拍照失败，请查看日志");
            default -> showFlash("⚠ 女仆不在场，无法拍照");
        }
    }

    /** 新建分组成功后由输入屏回调：提示（页面随下一帧重渲染即同步到分组列表） */
    public void onGroupCreated() {
        showFlash("✅ 已新建分组");
    }

    /** 把 taskUid 转为可读名称 */
    private static String formatTaskName(String uid) {
        try {
            String key = "task." + uid.replace(':', '.');
            Component c = Component.translatable(key);
            String localized = c.getString();
            if (localized == null || localized.isEmpty() || localized.equals(key)) {
                return uid.substring(uid.indexOf(':') + 1);
            }
            return localized;
        } catch (RuntimeException e) {
            return uid;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static boolean hit(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x <= rx + rw && y >= ry && y <= ry + rh;
    }

    // ==================== 布局 ====================

    /** 分组设置里的一个槽位：分组选项 或 "+ 新建分组"按钮 */
    private static final class GroupSlot {
        final String label;
        final int y;
        final int h;
        final boolean isNewGroup;
        GroupSlot(String label, int y, int h, boolean isNewGroup) {
            this.label = label;
            this.y = y;
            this.h = h;
            this.isNewGroup = isNewGroup;
        }
    }

    private static final class Layout {
        int navH;
        int headerY, headerH, avatarX, avatarY, avatarH, avatarSize;
        int nameX, nameTop, statusTop;
        int manageTitleY;
        int[] cardY = new int[4]; // 管理 4 项
        int sosTitleY, bindCardY, modeCardY, modeListY, modeListH;
        int groupTitleY;
        List<GroupSlot> groupSlots = new ArrayList<>();
        int contentH;
    }

    private Layout computeLayout() {
        int h = PhoneOverlay.getPhoneHeight();
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight;
        Layout L = new Layout();
        L.navH = lineH + 8;

        int y = L.navH + 8;

        // 头部：大头像高度 = 名称(1.75x) + 状态(1.25x) 文字高度之和
        int nameH = (int) (lineH * 1.75f);
        int statusH = (int) (lineH * 1.25f);
        L.avatarH = nameH + statusH;
        L.avatarSize = Math.max(24, L.avatarH);
        L.headerH = L.avatarSize + 16;
        L.headerY = y;
        L.avatarY = L.headerY + (L.headerH - L.avatarSize) / 2;
        L.avatarX = 12;
        L.nameX = L.avatarX + L.avatarSize + 12;
        L.nameTop = L.avatarY + (L.avatarSize - (nameH + statusH)) / 2;
        L.statusTop = L.nameTop + nameH + 2;
        y += L.headerH + 4;

        // 管理 分组
        L.manageTitleY = y;
        y += GROUP_TITLE_SPACE;
        for (int i = 0; i < 4; i++) {
            L.cardY[i] = y;
            y += CARD_H + CARD_GAP;
        }
        y += 6;

        // SOS 分组
        L.sosTitleY = y;
        y += GROUP_TITLE_SPACE;
        L.bindCardY = y;
        y += CARD_H + CARD_GAP;
        L.modeCardY = -1;
        L.modeListY = -1;
        L.modeListH = 0;
        L.modeCardY = y;
        y += CARD_H + CARD_GAP;
        if (modeDropdownOpen && SosBindCache.isBound(stableId)) {
            L.modeListY = L.modeCardY + CARD_H + CARD_GAP;
            L.modeListH = Math.min(DROPDOWN_VISIBLE_ROWS, SosTaskCache.getAll().size()) * DROPDOWN_ROW_H;
            y = L.modeListY + L.modeListH + 4;
        }
        y += 6;

        // 分组设置 分组
        L.groupTitleY = y;
        y += GROUP_TITLE_SPACE;
        for (String g : groupOptions()) {
            L.groupSlots.add(new GroupSlot(g, y, CARD_H, false));
            y += CARD_H + CARD_GAP;
        }
        L.groupSlots.add(new GroupSlot("+ 新建分组", y, CARD_H, true));
        y += CARD_H + CARD_GAP;
        y += 4;

        L.contentH = y - (L.navH + 8) + 4;
        return L;
    }

    private int ly(Layout L, int baseY, int logicalY) {
        return baseY + logicalY - scrollOffset;
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics g, int baseX, int baseY, float partialTick) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        Font font = Minecraft.getInstance().font;
        Layout L = computeLayout();

        // 页面底色
        g.fill(baseX, baseY, baseX + w, baseY + h, PAGE_BG);

        // 顶部标题栏 #de7e7e：返回箭头 + 居中名字
        g.fill(baseX, baseY, baseX + w, baseY + L.navH, TOPBAR_BG);
        int navCenterY = baseY + L.navH / 2;
        g.drawString(font, "←", baseX + 6, navCenterY - font.lineHeight / 2, 0xFFFFFFFF);
        int titleW = font.width(maidName);
        g.drawString(font, maidName, baseX + (w - titleW) / 2, navCenterY - font.lineHeight / 2, 0xFFFFFFFF);

        // 钳制滚动
        int viewH = Math.max(0, h - L.navH);
        int maxScroll = Math.max(0, L.contentH - viewH);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        g.enableScissor(baseX, baseY + L.navH, baseX + w, baseY + h);

        renderHeader(g, font, L, baseX, baseY);
        renderManageGroup(g, font, L, baseX, baseY);
        renderSosGroup(g, font, L, baseX, baseY);
        renderGroupSection(g, font, L, baseX, baseY);

        g.disableScissor();

        // 短暂提示
        if (System.currentTimeMillis() < flashUntil && !flashText.isEmpty()) {
            int msgW = font.width(flashText);
            int msgY = baseY + h - 30;
            UiShapes.fillRoundedRect(g, baseX + 4, msgY - 3, w - 8, font.lineHeight + 6, 8, 0xCC000000);
            g.drawString(font, flashText, baseX + (w - msgW) / 2, msgY, 0xFFFFFFFF);
        }

        if (confirmDeletePending) {
            renderConfirmDialog(g, font, L, baseX, w, baseY);
        }
    }

    /** 头部信息区：左侧大头像（女仆照片），右侧名称 + 状态，整体垂直居中 */
    private void renderHeader(GuiGraphics g, Font font, Layout L, int baseX, int baseY) {
        int avX = baseX + L.avatarX;
        int avY = ly(L, baseY, L.avatarY);
        int size = L.avatarSize;
        ResourceLocation tex = MaidAvatarManager.getOrLoadTexture(stableId);
        if (tex != null) {
            int[] d = MaidAvatarManager.getTextureSize(stableId);
            int tw = d != null ? d[0] : size;
            int th = d != null ? d[1] : size;
            g.blit(tex, avX, avY, size, size, 0.0f, 0.0f, tw, th, tw, th);
        } else {
            UiShapes.fillRoundedRect(g, avX, avY, size, size, 8, 0xFFB0BEC5);
        }

        String status = MaidStatusCache.get(stableId);
        if (status.isEmpty()) status = "…";
        drawScaledText(g, font, maidName, baseX + L.nameX, ly(L, baseY, L.nameTop), TEXT_COLOR, 1.75f);
        drawScaledText(g, font, status, baseX + L.nameX, ly(L, baseY, L.statusTop), TEXT_SUB, 1.25f);
    }

    private void renderManageGroup(GuiGraphics g, Font font, Layout L, int baseX, int baseY) {
        int cardX = baseX + 10;
        int cardW = PhoneOverlay.getPhoneWidth() - 20;
        g.drawString(font, "管理", baseX + 12, ly(L, baseY, L.manageTitleY), GROUP_TITLE_COLOR);
        String[] labels = {"💬 发消息", "🗑 删除记录", "☠ 删除联系人", "📷 更新头像"};
        int[] colors = {TEXT_COLOR, TEXT_COLOR, DANGER, 0xFF2E7D32};
        for (int i = 0; i < 4; i++) {
            int y = ly(L, baseY, L.cardY[i]);
            UiShapes.fillRoundedRect(g, cardX, y, cardW, CARD_H, CARD_R, CARD_BG);
            g.drawString(font, labels[i], cardX + 10, y + (CARD_H - font.lineHeight) / 2 + 1, colors[i], false);
            g.drawString(font, "›", cardX + cardW - 16, y + (CARD_H - font.lineHeight) / 2 + 1, 0xFFB9AAB0, false);
        }
    }

    private void renderSosGroup(GuiGraphics g, Font font, Layout L, int baseX, int baseY) {
        int cardX = baseX + 10;
        int cardW = PhoneOverlay.getPhoneWidth() - 20;
        g.drawString(font, "SOS 呼叫", baseX + 12, ly(L, baseY, L.sosTitleY), GROUP_TITLE_COLOR);

        // SOS 呼叫（绑定开关）卡片
        boolean bound = SosBindCache.isBound(stableId);
        int bindY = ly(L, baseY, L.bindCardY);
        UiShapes.fillRoundedRect(g, cardX, bindY, cardW, CARD_H, CARD_R, CARD_BG);
        g.drawString(font, "🚨 SOS 呼叫", cardX + 10, bindY + (CARD_H - font.lineHeight) / 2 + 1,
                bound ? DANGER : TEXT_COLOR, false);
        String bindState = bound ? "已绑定" : "未绑定";
        g.drawString(font, bindState, cardX + cardW - font.width(bindState) - 14,
                bindY + (CARD_H - font.lineHeight) / 2 + 1, bound ? DANGER : TEXT_SUB, false);

        // SOS 工作模式卡片（未绑定时置灰，点击展开原下拉）
        int modeY = ly(L, baseY, L.modeCardY);
        UiShapes.fillRoundedRect(g, cardX, modeY, cardW, CARD_H, CARD_R, CARD_BG);
        int modeColor = bound ? TEXT_COLOR : TEXT_SUB;
        g.drawString(font, "🎯 SOS 工作模式", cardX + 10, modeY + (CARD_H - font.lineHeight) / 2 + 1, modeColor, false);
        String modeName = bound ? formatTaskName(SosBindCache.getTaskUid(stableId)) : "需先绑定";
        g.drawString(font, modeName, cardX + cardW - font.width(modeName) - 26,
                modeY + (CARD_H - font.lineHeight) / 2 + 1, modeColor, false);
        g.drawString(font, modeDropdownOpen ? "▲" : "▼",
                cardX + cardW - 16, modeY + (CARD_H - font.lineHeight) / 2 + 1, 0xFFB9AAB0, false);

        if (bound && modeDropdownOpen && L.modeListY >= 0) {
            renderModeList(g, font, L, baseY, cardX, cardW);
        }
    }

    private void renderModeList(GuiGraphics g, Font font, Layout L, int baseY, int listX, int listW) {
        List<String> tasks = SosTaskCache.getAll();
        int listY = ly(L, baseY, L.modeListY);
        int listH = L.modeListH;
        UiShapes.fillRoundedRect(g, listX, listY, listW, listH, 8, 0xFFFFFFFF);
        int windowStart = clamp(modeCursor - DROPDOWN_VISIBLE_ROWS / 2,
                0, Math.max(0, tasks.size() - DROPDOWN_VISIBLE_ROWS));
        for (int i = 0; i < DROPDOWN_VISIBLE_ROWS && windowStart + i < tasks.size(); i++) {
            int idx = windowStart + i;
            int rowY = listY + i * DROPDOWN_ROW_H;
            if (idx == modeCursor) {
                UiShapes.fillRoundedRect(g, listX + 2, rowY, listW - 4, DROPDOWN_ROW_H, 6, 0xFF4A6FB5);
                g.drawString(font, formatTaskName(tasks.get(idx)), listX + 8, rowY + 3, 0xFFFFFFFF, false);
            } else {
                g.drawString(font, formatTaskName(tasks.get(idx)), listX + 8, rowY + 3, TEXT_COLOR, false);
            }
        }
    }

    private void renderGroupSection(GuiGraphics g, Font font, Layout L, int baseX, int baseY) {
        int cardX = baseX + 10;
        int cardW = PhoneOverlay.getPhoneWidth() - 20;
        g.drawString(font, "分组设置", baseX + 12, ly(L, baseY, L.groupTitleY), GROUP_TITLE_COLOR);

        String current = currentGroup();
        for (GroupSlot slot : L.groupSlots) {
            int y = ly(L, baseY, slot.y);
            if (slot.isNewGroup) {
                // 新建分组按钮：虚线感 → 用淡色描边卡片
                UiShapes.fillRoundedRect(g, cardX, y, cardW, slot.h, CARD_R, 0xFFFFF5F8);
                g.drawString(font, "＋ 新建分组", cardX + 10, y + (slot.h - font.lineHeight) / 2 + 1,
                        0xFFB06A6A, false);
                g.fill(cardX + 2, y + slot.h - 2, cardX + cardW - 2, y + slot.h - 1, 0x22B06A6A);
            } else {
                boolean isCurrent = slot.label.equals(current);
                UiShapes.fillRoundedRect(g, cardX, y, cardW, slot.h, CARD_R,
                        isCurrent ? CARD_ACTIVE_BG : CARD_BG);
                g.drawString(font, slot.label, cardX + 10, y + (slot.h - font.lineHeight) / 2 + 1,
                        isCurrent ? 0xFFB06A6A : TEXT_COLOR, false);
                if (isCurrent) {
                    g.drawString(font, "✓", cardX + cardW - 18, y + (slot.h - font.lineHeight) / 2 + 1,
                            0xFFB06A6A, false);
                }
            }
        }
    }

    /** 渲染删除联系人二次确认弹窗 */
    private void renderConfirmDialog(GuiGraphics g, Font font, Layout L, int baseX, int w, int baseY) {
        int h = PhoneOverlay.getPhoneHeight();
        g.fill(baseX, baseY + L.navH, baseX + w, baseY + h, 0x66000000);
        int dlgW = w - 40;
        int dlgH = 80;
        int dlgX = baseX + (w - dlgW) / 2;
        int dlgY = baseY + (L.navH + h) / 2 - dlgH / 2;
        UiShapes.fillRoundedRect(g, dlgX, dlgY, dlgW, dlgH, 8, 0xFFFAFAFA);

        int textY = dlgY + 12;
        g.drawString(font, "确认删除？此操作无法恢复", dlgX + 10, textY, TEXT_COLOR);
        textY += font.lineHeight + 2;
        g.drawString(font, "将同时清除聊天记录与联系人", dlgX + 10, textY, TEXT_SUB);

        int btnW = (dlgW - 30) / 2;
        int btnH = 22;
        int btnY = dlgY + dlgH - btnH - 8;
        int cancelX = dlgX + 10;
        int delX = dlgX + dlgW - 10 - btnW;
        UiShapes.fillRoundedRect(g, cancelX, btnY, btnW, btnH, btnH / 2, 0xFFE0E0E0);
        g.drawString(font, "取消", cancelX + (btnW - font.width("取消")) / 2, btnY + 6, TEXT_COLOR);
        UiShapes.fillRoundedRect(g, delX, btnY, btnW, btnH, btnH / 2, 0xFFE53935);
        g.drawString(font, "确认", delX + (btnW - font.width("确认")) / 2, btnY + 6, 0xFFFFFFFF);
    }

    // ==================== 鼠标事件 ====================

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (action != GLFW.GLFW_PRESS) return false;
        int w = PhoneOverlay.getPhoneWidth();
        Layout L = computeLayout();

        if (confirmDeletePending) {
            return handleConfirmClick(relX, relY, w, L);
        }

        if (relX >= 0 && relX <= 60 && relY >= 0 && relY <= L.navH) {
            PhoneOverlay.popPage();
            return true;
        }
        if (relY < L.navH) return false;

        double ly = relY + scrollOffset;
        int cardX = 10;
        int cardW = w - 20;

        // 管理 4 项
        if (hit(relX, ly, cardX, L.cardY[0], cardW, CARD_H)) {
            PhoneOverlay.pushPage(new ChatPage(stableId, maidName));
            return true;
        }
        if (hit(relX, ly, cardX, L.cardY[1], cardW, CARD_H)) {
            if (ChatHistoryStorage.hasConversation(stableId)) {
                ChatHistoryStorage.deleteConversation(stableId);
                showFlash("🗑 已删除聊天记录");
            } else {
                showFlash("暂无聊天记录");
            }
            return true;
        }
        if (hit(relX, ly, cardX, L.cardY[2], cardW, CARD_H)) {
            confirmDeletePending = true;
            return true;
        }
        if (hit(relX, ly, cardX, L.cardY[3], cardW, CARD_H)) {
            updateAvatar();
            return true;
        }

        // SOS 呼叫（绑定开关）
        if (hit(relX, ly, cardX, L.bindCardY, cardW, CARD_H)) {
            PacketDistributor.sendToServer(new C2SToggleSosBindPacket(stableId));
            return true;
        }

        // SOS 工作模式：先处理下拉列表项，再处理模式卡片展开
        boolean bound = SosBindCache.isBound(stableId);
        if (bound && modeDropdownOpen && L.modeListY >= 0
                && hit(relX, ly, cardX, L.modeListY, cardW, L.modeListH)) {
            List<String> tasks = SosTaskCache.getAll();
            int windowStart = clamp(modeCursor - DROPDOWN_VISIBLE_ROWS / 2,
                    0, Math.max(0, tasks.size() - DROPDOWN_VISIBLE_ROWS));
            int row = (int) ((ly - L.modeListY) / DROPDOWN_ROW_H);
            int idx = windowStart + row;
            if (idx >= 0 && idx < tasks.size()) {
                PacketDistributor.sendToServer(new C2SSetSosModePacket(stableId, tasks.get(idx)));
                modeDropdownOpen = false;
            }
            return true;
        }
        if (hit(relX, ly, cardX, L.modeCardY, cardW, CARD_H)) {
            if (bound) {
                modeDropdownOpen = !modeDropdownOpen;
                if (modeDropdownOpen) {
                    int idx = SosTaskCache.getAll().indexOf(SosBindCache.getTaskUid(stableId));
                    modeCursor = idx < 0 ? 0 : idx;
                }
            } else {
                showFlash("请先绑定 SOS 呼叫");
            }
            return true;
        }

        // 分组设置：分组选项 / 新建分组
        for (GroupSlot slot : L.groupSlots) {
            if (!hit(relX, ly, cardX, slot.y, cardW, slot.h)) continue;
            if (slot.isNewGroup) {
                Minecraft.getInstance().setScreen(new PhoneGroupNameInputScreen(this));
            } else {
                HuhuTalkClientSettings.setMaidGroup(stableId, slot.label);
                showFlash("已分到「" + slot.label + "」");
            }
            return true;
        }
        return false;
    }

    private boolean handleConfirmClick(double relX, double relY, int w, Layout L) {
        int h = PhoneOverlay.getPhoneHeight();
        int dlgW = w - 40;
        int dlgH = 80;
        int dlgX = (w - dlgW) / 2;
        int dlgY = (L.navH + h) / 2 - dlgH / 2;
        int btnW = (dlgW - 30) / 2;
        int btnH = 22;
        int btnY = dlgY + dlgH - btnH - 8;
        int cancelX = dlgX + 10;
        int delX = dlgX + dlgW - 10 - btnW;

        if (relX >= delX && relX <= delX + btnW && relY >= btnY && relY <= btnY + btnH) {
            PacketDistributor.sendToServer(new C2SDeleteContactPacket(stableId));
            ChatHistoryStorage.deleteConversation(stableId);
            HuhuTalkClientSettings.setMaidGroup(stableId, "");
            confirmDeletePending = false;
            showFlash("☠ 已删除联系人");
            return true;
        }
        boolean onCancel = relX >= cancelX && relX <= cancelX + btnW
                && relY >= btnY && relY <= btnY + btnH;
        boolean insideDlg = relX >= dlgX && relX <= dlgX + dlgW
                && relY >= dlgY && relY <= dlgY + dlgH;
        if (onCancel || !insideDlg) {
            confirmDeletePending = false;
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        if (confirmDeletePending) return true;
        int w = PhoneOverlay.getPhoneWidth();
        Layout L = computeLayout();
        int cardX = 10;
        int cardW = w - 20;

        if (SosBindCache.isBound(stableId) && modeDropdownOpen && L.modeListY >= 0) {
            int listTop = L.modeListY - scrollOffset;
            if (relX >= cardX && relX <= cardX + cardW
                    && relY >= listTop && relY <= listTop + L.modeListH) {
                List<String> tasks = SosTaskCache.getAll();
                if (!tasks.isEmpty()) {
                    int step = scrollDelta > 0 ? -1 : 1;
                    modeCursor = clamp(modeCursor + step, 0, tasks.size() - 1);
                }
                return true;
            }
        }

        int viewH = Math.max(0, PhoneOverlay.getPhoneHeight() - L.navH);
        int maxScroll = Math.max(0, L.contentH - viewH);
        scrollOffset = Math.max(0, Math.min(scrollOffset - (int) (scrollDelta * 10), maxScroll));
        return true;
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
}
