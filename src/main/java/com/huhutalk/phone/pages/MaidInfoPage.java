package com.huhutalk.phone.pages;

import com.huhutalk.client.ChatHistoryStorage;
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
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 女仆信息设置页（消息列表页 / 联系人列表页点击女仆统一进入）。
 * <p>本页是单个女仆所有操作的聚合点，从上到下布局：
 * <ul>
 *   <li><b>顶部标题栏</b>：主题粉底 + 女仆名，左上角「&lt; 返回」。</li>
 *   <li><b>状态小字</b>：女仆当前状态（{@link MaidStatusCache}）。</li>
 *   <li><b>三个核心操作按钮</b>：💬 发消息（进入聊天页）/ 🗑 删除聊天记录 /
 *       ☠ 删除联系人（危险操作，先弹二次确认）。</li>
 *   <li><b>SOS 区域</b>：绑定开关（已绑定红底/未绑定灰底）+ 已绑定时的工作模式下拉。</li>
 *   <li><b>分组设置</b>：显示当前分组（手动优先，未设置按状态默认），下拉切换 在线/离线/已关机/死亡。</li>
 * </ul>
 * 整个内容区可滚动（鼠标滚轮），下拉展开时滚轮优先用于选择（需鼠标位于下拉区域内）。
 */
public class MaidInfoPage implements IPhonePage {
    /** 下拉列表单行高度 */
    private static final int DROPDOWN_ROW_H = 16;
    /** 下拉列表最大可见行数 */
    private static final int DROPDOWN_VISIBLE_ROWS = 5;
    /** 下拉模式框高度 */
    private static final int DROPDOWN_BOX_H = 20;
    /** SOS 绑定开关按钮高度 */
    private static final int BIND_BTN_H = 28;
    /** 核心操作按钮高度 */
    private static final int ACTION_BTN_H = 30;
    /** 按钮纵向间距 */
    private static final int BTN_GAP = 6;
    /** 常规文字深色 */
    private static final int TEXT_COLOR = 0xFF333333;
    /** 次要文字灰色 */
    private static final int TEXT_SUB = 0xFF888888;
    /** SOS 已绑定底（红） / 未绑定底（灰），与 MaidSettingsPage 一致 */
    private static final int SOS_BOUND_BG = 0xFFE53935;
    private static final int SOS_UNBOUND_BG = 0xFF3A3A3A;
    /** 工作模式下拉：光标高亮（蓝）与当前选中行底，与 MaidSettingsPage 一致 */
    private static final int MODE_HIGHLIGHT = 0xFF4A6FB5;
    private static final int MODE_CURRENT_BG = 0xFF3A3A3A;

    /** 分组下拉可选的固定分组（顺序与 MaidGroupHelper 排序权重一致） */
    private static final List<String> GROUP_OPTIONS = List.of(
            MaidGroupHelper.GROUP_ONLINE,
            MaidGroupHelper.GROUP_OFFLINE,
            MaidGroupHelper.GROUP_SHUTDOWN,
            MaidGroupHelper.GROUP_DEAD
    );

    private final String stableId;
    private final String maidName;

    /** 页面滚动偏移（像素） */
    private int scrollOffset = 0;
    /** SOS 工作模式下拉是否展开 + 高亮光标下标 */
    private boolean modeDropdownOpen = false;
    private int modeCursor = 0;
    /** 分组下拉是否展开 + 高亮光标下标 */
    private boolean groupDropdownOpen = false;
    private int groupCursor = 0;
    /** 是否处于「删除联系人」二次确认状态（弹确认框） */
    private boolean confirmDeletePending = false;
    /** 短暂提示（toast）：文本 + 到期时间戳 */
    private String flashText = "";
    private long flashUntil = 0;

    public MaidInfoPage(String stableId, String maidName) {
        this.stableId = stableId;
        this.maidName = maidName;
        // 打开时按需请求一次该女仆最新状态（刷新状态小字 / 默认分组）
        PacketDistributor.sendToServer(new C2SRequestMaidStatusPacket(List.of(stableId)));
        // 若可用任务列表未加载，则向服务端请求一次（SOS 工作模式下拉用）
        if (SosTaskCache.isEmpty()) {
            PacketDistributor.sendToServer(new C2SRequestSosTaskListPacket());
        }
    }

    // ==================== 数据 ====================

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

    /** 显示短暂提示（toast），2 秒后消失 */
    private void showFlash(String text) {
        flashText = text;
        flashUntil = System.currentTimeMillis() + 2000;
    }

    /** 把 taskUid 转为可读名称：优先用语言键 task.namespace.path，缺失时回退显示路径 */
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

    /** 命中判定：点 (x,y) 是否落在矩形 (rx,ry,rw,rh) 内 */
    private static boolean hit(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x <= rx + rw && y >= ry && y <= ry + rh;
    }

    // ==================== 布局 ====================

    /**
     * 布局快照：所有控件矩形均为<b>未滚动</b>的逻辑坐标（相对页面左上角，含标题栏）。
     * 渲染时统一减去 scrollOffset；命中判定时统一加上 scrollOffset，保证二者一致。
     * mode* 仅在已绑定 SOS 时有效（未绑定时为 -1）。
     */
    private static final class Layout {
        int navH;                 // 标题栏高度
        int statusY;              // 状态小字顶 y
        int btn1Y, btn2Y, btn3Y;  // 三个核心操作按钮顶 y
        int sosLabelY;            // SOS 区域标题
        int bindBtnY;             // SOS 绑定开关顶 y
        int modeLabelY, modeBoxY, modeListY, modeListH; // 工作模式下拉（未绑定全为 -1/0）
        int groupLabelY, groupBoxY, groupListY, groupListH; // 分组下拉
        int contentH;             // 内容总高（从标题栏底到内容末尾）
    }

    /** 依据当前状态（绑定、下拉展开等）计算完整布局 */
    private Layout computeLayout() {
        int h = PhoneOverlay.getPhoneHeight();
        Layout L = new Layout();
        L.navH = Minecraft.getInstance().font.lineHeight + 8; // 标题栏随字体缩放
        int y = L.navH + 8;
        L.statusY = y;
        y += 18;

        // 三个核心操作按钮
        L.btn1Y = y;
        L.btn2Y = L.btn1Y + ACTION_BTN_H + BTN_GAP;
        L.btn3Y = L.btn2Y + ACTION_BTN_H + BTN_GAP;
        y = L.btn3Y + ACTION_BTN_H + 12;

        // SOS 区域：标题 + 绑定开关
        L.sosLabelY = y;
        y += 12;
        L.bindBtnY = y;
        y += BIND_BTN_H + 10;

        // 已绑定时：SOS 工作模式下拉
        L.modeLabelY = -1;
        L.modeBoxY = -1;
        L.modeListY = -1;
        L.modeListH = 0;
        if (SosBindCache.isBound(stableId)) {
            L.modeLabelY = y;
            y += 12;
            L.modeBoxY = y;
            y += DROPDOWN_BOX_H;
            if (modeDropdownOpen) {
                L.modeListY = L.modeBoxY + DROPDOWN_BOX_H;
                L.modeListH = Math.min(DROPDOWN_VISIBLE_ROWS, SosTaskCache.getAll().size()) * DROPDOWN_ROW_H;
                y += L.modeListH + 8;
            } else {
                y += 10;
            }
        }

        // 分组设置：标题 + 下拉框（展开时列表计入高度）
        L.groupLabelY = y;
        y += 12;
        L.groupBoxY = y;
        y += DROPDOWN_BOX_H;
        L.groupListY = -1;
        L.groupListH = 0;
        if (groupDropdownOpen) {
            L.groupListY = L.groupBoxY + DROPDOWN_BOX_H;
            L.groupListH = GROUP_OPTIONS.size() * DROPDOWN_ROW_H;
            y += L.groupListH + 8;
        } else {
            y += 10;
        }

        L.contentH = y - (L.navH + 8) + 4; // 末尾留白
        return L;
    }

    // ==================== 渲染 ====================

    /** 逻辑 y（未滚动，相对页面左上）→ 屏幕 y */
    private int ly(Layout L, int baseY, int logicalY) {
        return baseY + logicalY - scrollOffset;
    }

    @Override
    public void render(GuiGraphics g, int baseX, int baseY, float partialTick) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        ColorScheme scheme = ThemeManager.getActive();
        Font font = Minecraft.getInstance().font;
        Layout L = computeLayout();

        // 页面底色
        g.fill(baseX, baseY, baseX + w, baseY + h, scheme.pageBg());

        // 顶部标题栏：主题粉底 + 白字女仆名 + 左上角返回
        g.fill(baseX, baseY, baseX + w, baseY + L.navH, scheme.topBarBg());
        g.drawString(font, "< 返回", baseX + 4, baseY + (L.navH - font.lineHeight) / 2 + 1, scheme.topBarText());
        int titleW = font.width(maidName);
        g.drawString(font, maidName, baseX + (w - titleW) / 2,
                baseY + (L.navH - font.lineHeight) / 2 + 1, scheme.topBarText());

        // 钳制滚动偏移（内容变短时避免出现空白）
        int viewH = Math.max(0, h - L.navH);
        int maxScroll = Math.max(0, L.contentH - viewH);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        // 内容区裁剪，防止滚动溢出到标题栏
        g.enableScissor(baseX, baseY + L.navH, baseX + w, baseY + h);

        // 状态小字
        String status = MaidStatusCache.get(stableId);
        if (status.isEmpty()) {
            status = "…";
        }
        g.drawString(font, "状态：" + status, baseX + 10, ly(L, baseY, L.statusY), TEXT_SUB);

        // 三个核心操作按钮
        renderActionButtons(g, font, scheme, baseX, w, L, baseY);
        // SOS 区域（含已绑定时的模式下拉）
        renderSosSection(g, font, baseX, w, L, baseY);
        // 分组设置
        renderGroupSection(g, font, scheme, baseX, w, L, baseY);

        g.disableScissor();

        // 短暂提示（toast，置于内容之上）
        if (System.currentTimeMillis() < flashUntil && !flashText.isEmpty()) {
            int msgW = font.width(flashText);
            int msgY = baseY + h - 30;
            g.fill(baseX + 4, msgY - 2, baseX + w - 4, msgY + font.lineHeight + 2, 0xAA000000);
            g.drawString(font, flashText, baseX + (w - msgW) / 2, msgY, 0xFFFFFFFF);
        }

        // 删除联系人二次确认弹窗（置于最上层）
        if (confirmDeletePending) {
            renderConfirmDialog(g, font, scheme, baseX, w, L, baseY);
        }
    }

    /** 渲染三个核心操作按钮（💬 发消息 / 🗑 删除聊天记录 / ☠ 删除联系人） */
    private void renderActionButtons(GuiGraphics g, Font font, ColorScheme scheme,
                                     int baseX, int w, Layout L, int baseY) {
        int btnX = baseX + 10;
        int btnW = w - 20;
        // 💬 发消息：中度柔粉（选中高亮色），点击进入聊天页
        g.fill(btnX, ly(L, baseY, L.btn1Y), btnX + btnW, ly(L, baseY, L.btn1Y) + ACTION_BTN_H,
                scheme.accentSoftActive());
        g.drawString(font, "💬 发消息", btnX + 8,
                ly(L, baseY, L.btn1Y) + (ACTION_BTN_H - font.lineHeight) / 2 + 1, scheme.topBarText());
        // 🗑 删除聊天记录：中性灰
        g.fill(btnX, ly(L, baseY, L.btn2Y), btnX + btnW, ly(L, baseY, L.btn2Y) + ACTION_BTN_H,
                scheme.neutralBg());
        g.drawString(font, "🗑 删除聊天记录", btnX + 8,
                ly(L, baseY, L.btn2Y) + (ACTION_BTN_H - font.lineHeight) / 2 + 1, scheme.neutralText());
        // ☠ 删除联系人：危险暗红（危险操作，点击后先弹确认）
        g.fill(btnX, ly(L, baseY, L.btn3Y), btnX + btnW, ly(L, baseY, L.btn3Y) + ACTION_BTN_H,
                scheme.dangerBg());
        g.drawString(font, "☠ 删除联系人", btnX + 8,
                ly(L, baseY, L.btn3Y) + (ACTION_BTN_H - font.lineHeight) / 2 + 1, scheme.dangerText());
    }

    /** 渲染 SOS 区域：标题 + 绑定开关 +（已绑定时）工作模式下拉 */
    private void renderSosSection(GuiGraphics g, Font font, int baseX, int w, Layout L, int baseY) {
        // 区域标题
        g.drawString(font, "SOS 呼叫", baseX + 10, ly(L, baseY, L.sosLabelY), TEXT_SUB);

        // 绑定开关：已绑定红底 / 未绑定灰底
        boolean bound = SosBindCache.isBound(stableId);
        int btnX = baseX + 10;
        int btnW = w - 20;
        g.fill(btnX, ly(L, baseY, L.bindBtnY), btnX + btnW, ly(L, baseY, L.bindBtnY) + BIND_BTN_H,
                bound ? SOS_BOUND_BG : SOS_UNBOUND_BG);
        String bindText = bound ? "SOS 呼叫：已绑定（点击解除）" : "SOS 呼叫：未绑定（点击绑定）";
        g.drawString(font, bindText, btnX + 8,
                ly(L, baseY, L.bindBtnY) + (BIND_BTN_H - font.lineHeight) / 2 + 1, 0xFFFFFFFF);

        // 已绑定时的工作模式下拉
        if (bound) {
            g.drawString(font, "SOS 工作模式：", baseX + 10, ly(L, baseY, L.modeLabelY), TEXT_SUB);
            int boxX = baseX + 10;
            int boxY = ly(L, baseY, L.modeBoxY);
            int boxW = w - 20;
            String currentUid = SosBindCache.getTaskUid(stableId);
            String modeName = formatTaskName(currentUid);
            g.fill(boxX, boxY, boxX + boxW, boxY + DROPDOWN_BOX_H,
                    modeDropdownOpen ? 0xFF444444 : 0xFF333333);
            g.drawString(font, modeName, boxX + 6, boxY + 4, 0xFFFFFFFF);
            g.drawString(font, modeDropdownOpen ? "▲" : "▼", boxX + boxW - 14, boxY + 4, 0xFFAAAAAA);

            if (modeDropdownOpen) {
                renderModeList(g, font, L, baseY, boxX, boxW, currentUid);
            }
        }
    }

    /** 渲染工作模式下拉列表（蓝底高亮光标，滚动窗口保证光标可见） */
    private void renderModeList(GuiGraphics g, Font font, Layout L, int baseY,
                                int boxX, int boxW, String currentUid) {
        List<String> tasks = SosTaskCache.getAll();
        int listX = boxX;
        int listY = ly(L, baseY, L.modeListY);
        int listW = boxW;
        int listH = L.modeListH;
        g.fill(listX, listY, listX + listW, listY + listH, 0xFF222222);
        g.fill(listX, listY, listX + listW, listY + 1, 0xFF555555);
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        int windowStart = clamp(modeCursor - DROPDOWN_VISIBLE_ROWS / 2,
                0, Math.max(0, tasks.size() - DROPDOWN_VISIBLE_ROWS));
        for (int i = 0; i < DROPDOWN_VISIBLE_ROWS && windowStart + i < tasks.size(); i++) {
            int idx = windowStart + i;
            int rowY = listY + i * DROPDOWN_ROW_H;
            if (idx == modeCursor) {
                g.fill(listX, rowY, listX + listW, rowY + DROPDOWN_ROW_H, MODE_HIGHLIGHT);
            } else if (idx == tasks.indexOf(currentUid)) {
                g.fill(listX, rowY, listX + listW, rowY + DROPDOWN_ROW_H, MODE_CURRENT_BG);
            }
            g.drawString(font, formatTaskName(tasks.get(idx)), listX + 6, rowY + 3, 0xFFFFFFFF);
        }
        g.disableScissor();
    }

    /** 渲染分组设置：标题 + 当前分组下拉（柔粉主题色） */
    private void renderGroupSection(GuiGraphics g, Font font, ColorScheme scheme,
                                    int baseX, int w, Layout L, int baseY) {
        g.drawString(font, "分组设置", baseX + 10, ly(L, baseY, L.groupLabelY), TEXT_SUB);

        int boxX = baseX + 10;
        int boxY = ly(L, baseY, L.groupBoxY);
        int boxW = w - 20;
        String groupName = currentGroup();
        String manualMark = MaidGroupHelper.hasManualGroup(stableId) ? "（手动）" : "";
        // 当前分组框：柔粉底 + 深色文字
        g.fill(boxX, boxY, boxX + boxW, boxY + DROPDOWN_BOX_H, scheme.accentSoft());
        g.drawString(font, groupName + manualMark, boxX + 6, boxY + 4, TEXT_COLOR);
        g.drawString(font, groupDropdownOpen ? "▲" : "▼", boxX + boxW - 14, boxY + 4, 0xFF666666);

        if (groupDropdownOpen) {
            int listX = boxX;
            int listY = ly(L, baseY, L.groupListY);
            int listW = boxW;
            int listH = L.groupListH;
            // 白底列表 + 顶边框
            g.fill(listX, listY, listX + listW, listY + listH, 0xFFFFFFFF);
            g.fill(listX, listY, listX + listW, listY + 1, 0x22000000);
            for (int i = 0; i < GROUP_OPTIONS.size(); i++) {
                int rowY = listY + i * DROPDOWN_ROW_H;
                String option = GROUP_OPTIONS.get(i);
                if (i == groupCursor) {
                    // 光标行：柔粉高亮 + 白字
                    g.fill(listX, rowY, listX + listW, rowY + DROPDOWN_ROW_H, scheme.accentSoftActive());
                    g.drawString(font, option, listX + 6, rowY + 3, scheme.topBarText());
                } else if (option.equals(groupName)) {
                    // 当前生效分组（非光标）：淡柔粉底 + 深色字
                    g.fill(listX, rowY, listX + listW, rowY + DROPDOWN_ROW_H, scheme.accentSoft());
                    g.drawString(font, option, listX + 6, rowY + 3, TEXT_COLOR);
                } else {
                    g.drawString(font, option, listX + 6, rowY + 3, TEXT_COLOR);
                }
            }
        }
    }

    /** 渲染删除联系人二次确认弹窗：半透明遮罩 + 白底对话框 + 取消/确认按钮 */
    private void renderConfirmDialog(GuiGraphics g, Font font, ColorScheme scheme,
                                     int baseX, int w, Layout L, int baseY) {
        int h = PhoneOverlay.getPhoneHeight();
        // 内容区半透明遮罩
        g.fill(baseX, baseY + L.navH, baseX + w, baseY + h, 0x66000000);

        int dlgW = w - 40;
        int dlgH = 80;
        int dlgX = baseX + (w - dlgW) / 2;
        int dlgY = baseY + (L.navH + h) / 2 - dlgH / 2;
        g.fill(dlgX, dlgY, dlgX + dlgW, dlgY + dlgH, 0xFFFAFAFA);

        // 弹窗文案（两行）
        int textY = dlgY + 12;
        g.drawString(font, "确认删除？此操作无法恢复", dlgX + 10, textY, TEXT_COLOR);
        textY += font.lineHeight + 2;
        g.drawString(font, "将同时清除聊天记录与联系人", dlgX + 10, textY, TEXT_SUB);

        // 底部两个按钮：取消（中性灰）/ 确认（危险红）
        int btnW = (dlgW - 30) / 2;
        int btnH = 22;
        int btnY = dlgY + dlgH - btnH - 8;
        int cancelX = dlgX + 10;
        int delX = dlgX + dlgW - 10 - btnW;
        g.fill(cancelX, btnY, cancelX + btnW, btnY + btnH, 0xFFE0E0E0);
        g.drawString(font, "取消", cancelX + (btnW - font.width("取消")) / 2, btnY + 6, TEXT_COLOR);
        g.fill(delX, btnY, delX + btnW, btnY + btnH, scheme.dangerBg());
        g.drawString(font, "确认", delX + (btnW - font.width("确认")) / 2, btnY + 6, scheme.dangerText());
    }

    // ==================== 鼠标事件 ====================

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (action != GLFW.GLFW_PRESS) return false;
        int w = PhoneOverlay.getPhoneWidth();
        Layout L = computeLayout();

        // 删除确认弹窗打开时：只处理弹窗交互
        if (confirmDeletePending) {
            return handleConfirmClick(relX, relY, w, L);
        }

        // 左上角「< 返回」
        if (relX >= 0 && relX <= 60 && relY >= 0 && relY <= L.navH) {
            PhoneOverlay.popPage();
            return true;
        }

        // 内容区点击：逻辑坐标 = 相对坐标 + 滚动偏移
        if (relY < L.navH) return false;
        double ly = relY + scrollOffset;
        int boxX = 10;
        int boxW = w - 20;

        // 三个核心操作按钮
        if (hit(relX, ly, boxX, L.btn1Y, boxW, ACTION_BTN_H)) {
            PhoneOverlay.pushPage(new ChatPage(stableId, maidName));
            return true;
        }
        if (hit(relX, ly, boxX, L.btn2Y, boxW, ACTION_BTN_H)) {
            // 删除聊天记录（含草稿）：有记录才删，并短暂提示
            if (ChatHistoryStorage.hasConversation(stableId)) {
                ChatHistoryStorage.deleteConversation(stableId);
                showFlash("🗑 已删除聊天记录");
            } else {
                showFlash("暂无聊天记录");
            }
            return true;
        }
        if (hit(relX, ly, boxX, L.btn3Y, boxW, ACTION_BTN_H)) {
            // 危险操作：先进入二次确认状态
            confirmDeletePending = true;
            return true;
        }

        // SOS 绑定开关
        if (hit(relX, ly, boxX, L.bindBtnY, boxW, BIND_BTN_H)) {
            PacketDistributor.sendToServer(new C2SToggleSosBindPacket(stableId));
            return true;
        }

        // SOS 工作模式下拉
        if (SosBindCache.isBound(stableId)) {
            // 若下拉已展开，先处理列表项点击（区域覆盖在模式框下方）
            if (modeDropdownOpen && L.modeListY >= 0
                    && hit(relX, ly, boxX, L.modeListY, boxW, L.modeListH)) {
                List<String> tasks = SosTaskCache.getAll();
                int windowStart = clamp(modeCursor - DROPDOWN_VISIBLE_ROWS / 2,
                        0, Math.max(0, tasks.size() - DROPDOWN_VISIBLE_ROWS));
                int row = (int)((ly - L.modeListY) / DROPDOWN_ROW_H);
                int idx = windowStart + row;
                if (idx >= 0 && idx < tasks.size()) {
                    PacketDistributor.sendToServer(new C2SSetSosModePacket(stableId, tasks.get(idx)));
                    modeDropdownOpen = false;
                }
                return true;
            }
            // 点击模式框本身 → 展开/收起；展开时把光标定位到当前模式
            if (hit(relX, ly, boxX, L.modeBoxY, boxW, DROPDOWN_BOX_H)) {
                modeDropdownOpen = !modeDropdownOpen;
                if (modeDropdownOpen) {
                    int idx = SosTaskCache.getAll().indexOf(SosBindCache.getTaskUid(stableId));
                    modeCursor = idx < 0 ? 0 : idx;
                }
                return true;
            }
        }

        // 分组下拉
        if (groupDropdownOpen && L.groupListY >= 0
                && hit(relX, ly, boxX, L.groupListY, boxW, L.groupListH)) {
            int row = (int)((ly - L.groupListY) / DROPDOWN_ROW_H);
            if (row >= 0 && row < GROUP_OPTIONS.size()) {
                HuhuTalkClientSettings.setMaidGroup(stableId, GROUP_OPTIONS.get(row));
                groupDropdownOpen = false;
            }
            return true;
        }
        // 点击分组框本身 → 展开/收起；展开时把光标定位到当前分组
        if (hit(relX, ly, boxX, L.groupBoxY, boxW, DROPDOWN_BOX_H)) {
            groupDropdownOpen = !groupDropdownOpen;
            if (groupDropdownOpen) {
                int idx = GROUP_OPTIONS.indexOf(currentGroup());
                groupCursor = idx < 0 ? 0 : idx;
            }
            return true;
        }
        return false;
    }

    /** 删除确认弹窗的点击处理：确认删除 / 取消 / 点击外部取消 */
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

        // 确认删除：发网络包 + 清本地聊天记录 + 清手动分组
        if (relX >= delX && relX <= delX + btnW && relY >= btnY && relY <= btnY + btnH) {
            PacketDistributor.sendToServer(new C2SDeleteContactPacket(stableId));
            ChatHistoryStorage.deleteConversation(stableId);
            HuhuTalkClientSettings.setMaidGroup(stableId, "");
            confirmDeletePending = false;
            showFlash("☠ 已删除联系人");
            return true;
        }
        // 取消按钮 / 点击弹窗外部 → 取消确认
        boolean onCancel = relX >= cancelX && relX <= cancelX + btnW
                && relY >= btnY && relY <= btnY + btnH;
        boolean insideDlg = relX >= dlgX && relX <= dlgX + dlgW
                && relY >= dlgY && relY <= dlgY + dlgH;
        if (onCancel || !insideDlg) {
            confirmDeletePending = false;
            return true;
        }
        // 点击弹窗空白处：保持确认状态
        return true;
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        // 确认弹窗打开期间不滚动
        if (confirmDeletePending) {
            return true;
        }
        int w = PhoneOverlay.getPhoneWidth();
        Layout L = computeLayout();
        int boxX = 10;
        int boxW = w - 20;

        // 工作模式下拉已展开且鼠标在下拉区域内 → 滚动移动高亮光标
        if (SosBindCache.isBound(stableId) && modeDropdownOpen && L.modeListY >= 0) {
            int listTop = L.modeListY - scrollOffset;
            if (relX >= boxX && relX <= boxX + boxW
                    && relY >= listTop && relY <= listTop + L.modeListH) {
                List<String> tasks = SosTaskCache.getAll();
                if (!tasks.isEmpty()) {
                    int step = scrollDelta > 0 ? -1 : 1; // 正滚向上
                    modeCursor = clamp(modeCursor + step, 0, tasks.size() - 1);
                }
                return true;
            }
        }
        // 分组下拉已展开且鼠标在下拉区域内 → 滚动移动高亮光标
        if (groupDropdownOpen && L.groupListY >= 0) {
            int listTop = L.groupListY - scrollOffset;
            if (relX >= boxX && relX <= boxX + boxW
                    && relY >= listTop && relY <= listTop + L.groupListH) {
                int step = scrollDelta > 0 ? -1 : 1;
                groupCursor = clamp(groupCursor + step, 0, GROUP_OPTIONS.size() - 1);
                return true;
            }
        }

        // 其余情况：滚动整个页面
        int viewH = Math.max(0, PhoneOverlay.getPhoneHeight() - L.navH);
        int maxScroll = Math.max(0, L.contentH - viewH);
        scrollOffset = Math.max(0, Math.min(scrollOffset - (int)(scrollDelta * 10), maxScroll));
        return true;
    }
}
