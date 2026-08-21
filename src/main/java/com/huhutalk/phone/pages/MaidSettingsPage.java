package com.huhutalk.phone.pages;

import com.huhutalk.client.MaidStatusCache;
import com.huhutalk.client.SosBindCache;
import com.huhutalk.client.SosTaskCache;
import com.huhutalk.core.contact.SosBindData;
import com.huhutalk.network.C2SRequestMaidStatusPacket;
import com.huhutalk.network.C2SRequestSosTaskListPacket;
import com.huhutalk.network.C2SSetSosModePacket;
import com.huhutalk.network.C2SToggleSosBindPacket;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 女仆设置页（从聊天页右上角 ⚙ 进入）。
 * <p>包含针对该女仆的<b>SOS 绑定开关</b>与当前状态，以及（已绑定时）<b>SOS 工作模式</b>下拉选择。
 * 工作模式枚举所有可用任务（兼容新增攻击模式的 mod）。点击模式框展开下拉列表，鼠标滚动选择。
 */
public class MaidSettingsPage implements IPhonePage {
    private static final int NAV_H_RATIO = 8;
    /** 下拉列表单行高度 */
    private static final int DROPDOWN_ROW_H = 16;
    /** 下拉列表最大可见行数 */
    private static final int DROPDOWN_VISIBLE_ROWS = 5;

    private final String stableId;
    private final String maidName;
    /** 工作模式下拉是否展开 */
    private boolean modeDropdownOpen = false;
    /** 下拉滚动选择的高亮下标（在全部任务列表中的索引） */
    private int modeCursor = 0;

    public MaidSettingsPage(String stableId, String maidName) {
        this.stableId = stableId;
        this.maidName = maidName;
        // 打开时按需请求一次该女仆最新状态
        PacketDistributor.sendToServer(new C2SRequestMaidStatusPacket(List.of(stableId)));
        // 若可用任务列表未加载，则向服务端请求一次
        if (SosTaskCache.isEmpty()) {
            PacketDistributor.sendToServer(new C2SRequestSosTaskListPacket());
        }
    }

    @Override
    public void render(GuiGraphics g, int baseX, int baseY, float partialTick) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int navH = (int)(h * NAV_H_RATIO / 100.0);
        var font = Minecraft.getInstance().font;

        // 导航栏
        g.fill(baseX, baseY, baseX + w, baseY + navH, 0xFF333333);
        g.drawString(font, "< 返回", baseX + 4, baseY + 3, 0xFFFFFFFF);
        int titleW = font.width(maidName);
        g.drawString(font, maidName, baseX + (w - titleW) / 2, baseY + 3, 0xFFFFFFFF);
        g.drawString(font, "设置", baseX + w - 20, baseY + 3, 0xFFFFAAAA);

        int y = baseY + navH + 10;

        // 当前状态
        String status = MaidStatusCache.get(stableId);
        if (status.isEmpty()) {
            status = "…";
        }
        g.drawString(font, "状态：" + status, baseX + 10, y, 0xFFAAAAAA);
        y += 24;

        // SOS 绑定开关
        boolean bound = SosBindCache.isBound(stableId);
        int btnX = baseX + 10;
        int btnW = w - 20;
        int btnH = 28;
        g.fill(btnX, y, btnX + btnW, y + btnH, bound ? 0xFFE53935 : 0xFF3A3A3A);
        String btnText = bound ? "SOS 呼叫：已绑定（点击解除）" : "SOS 呼叫：未绑定（点击绑定）";
        g.drawString(font, btnText, btnX + 8, y + (btnH - font.lineHeight) / 2 + 1, 0xFFFFFFFF);
        y += btnH + 8;

        // 已绑定时的 SOS 工作模式下拉选择器
        if (bound) {
            y = renderModeSelector(g, font, baseX, baseY, w, y);
        }

        String hint = "已绑定 " + SosBindCache.count() + "/" + SosBindData.MAX_BOUND + " 位女仆作为 SOS 护卫";
        g.drawString(font, hint, baseX + 10, y, 0xFF888888);
    }

    /** 渲染工作模式下拉选择器，返回下一控件起始 y */
    private int renderModeSelector(GuiGraphics g, Font font, int baseX, int baseY, int w, int y) {
        g.drawString(font, "SOS 工作模式：", baseX + 10, y, 0xFF888888);
        int boxY = y + 14;
        int boxX = baseX + 10;
        int boxW = w - 20;
        int boxH = 20;
        // 当前模式框
        String currentUid = SosBindCache.getTaskUid(stableId);
        String modeName = formatTaskName(currentUid);
        g.fill(boxX, boxY, boxX + boxW, boxY + boxH, modeDropdownOpen ? 0xFF444444 : 0xFF333333);
        g.drawString(font, modeName, boxX + 6, boxY + 4, 0xFFFFFFFF);
        // 右侧下拉箭头
        g.drawString(font, modeDropdownOpen ? "▲" : "▼",
                boxX + boxW - 14, boxY + 4, 0xFFAAAAAA);

        // 下拉展开：列出所有任务，高亮当前光标，滚动可选
        if (modeDropdownOpen) {
            List<String> tasks = SosTaskCache.getAll();
            int listX = boxX;
            int listY = boxY + boxH;
            int listW = boxW;
            int visible = Math.min(DROPDOWN_VISIBLE_ROWS, tasks.size());
            int listH = visible * DROPDOWN_ROW_H;
            // 背景 + 边框
            g.fill(listX, listY, listX + listW, listY + listH, 0xFF222222);
            g.fill(listX, listY, listX + listW, listY + 1, 0xFF555555);
            g.enableScissor(listX, listY, listX + listW, listY + listH);
            // 计算滚动窗口起点：让光标可见
            int windowStart = clamp(modeCursor - visible / 2, 0, Math.max(0, tasks.size() - visible));
            for (int i = 0; i < visible && windowStart + i < tasks.size(); i++) {
                int idx = windowStart + i;
                int rowY = listY + i * DROPDOWN_ROW_H;
                if (idx == modeCursor) {
                    g.fill(listX, rowY, listX + listW, rowY + DROPDOWN_ROW_H, 0xFF4A6FB5);
                } else if (idx == tasks.indexOf(currentUid)) {
                    g.fill(listX, rowY, listX + listW, rowY + DROPDOWN_ROW_H, 0xFF3A3A3A);
                }
                g.drawString(font, formatTaskName(tasks.get(idx)), listX + 6, rowY + 3, 0xFFFFFFFF);
            }
            g.disableScissor();
        }
        int dropdownH = modeDropdownOpen
                ? Math.min(DROPDOWN_VISIBLE_ROWS, SosTaskCache.getAll().size()) * DROPDOWN_ROW_H
                : 0;
        return boxY + boxH + dropdownH + 10;
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

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (action != 1) {
            return false;
        }
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int navH = (int)(h * NAV_H_RATIO / 100.0);

        if (relX >= 0 && relX <= 60 && relY >= 0 && relY <= navH) {
            PhoneOverlay.popPage();
            return true;
        }

        // SOS 绑定开关
        int btnX = 10;
        int btnW = w - 20;
        int btnH = 28;
        int y = navH + 10 + 24;
        if (relX >= btnX && relX <= btnX + btnW && relY >= y && relY <= y + btnH) {
            PacketDistributor.sendToServer(new C2SToggleSosBindPacket(stableId));
            return true;
        }

        // SOS 工作模式下拉
        if (SosBindCache.isBound(stableId)) {
            int boxY = y + btnH + 8 + 14;
            int boxX = 10;
            int boxW = w - 20;
            int boxH = 20;

            // 若下拉已展开，先处理列表项点击（区域覆盖在模式框下方）
            if (modeDropdownOpen) {
                List<String> tasks = SosTaskCache.getAll();
                int visible = Math.min(DROPDOWN_VISIBLE_ROWS, tasks.size());
                int listX = boxX;
                int listY = boxY + boxH;
                int listW = boxW;
                int listH = visible * DROPDOWN_ROW_H;
                int windowStart = clamp(modeCursor - visible / 2, 0, Math.max(0, tasks.size() - visible));
                // 点击某行 → 选择并发送
                if (relX >= listX && relX <= listX + listW && relY >= listY && relY <= listY + listH) {
                    int row = (int)((relY - listY) / DROPDOWN_ROW_H);
                    int idx = windowStart + row;
                    if (idx >= 0 && idx < tasks.size()) {
                        PacketDistributor.sendToServer(new C2SSetSosModePacket(stableId, tasks.get(idx)));
                        modeDropdownOpen = false;
                    }
                    return true;
                }
            }

            // 点击模式框本身 → 展开/收起
            if (relX >= boxX && relX <= boxX + boxW && relY >= boxY && relY <= boxY + boxH) {
                modeDropdownOpen = !modeDropdownOpen;
                // 展开时把光标定位到当前模式
                if (modeDropdownOpen) {
                    int idx = SosTaskCache.getAll().indexOf(SosBindCache.getTaskUid(stableId));
                    modeCursor = idx < 0 ? 0 : idx;
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        // 仅当下拉展开且鼠标在下拉区域内时，滚动移动高亮光标
        if (!SosBindCache.isBound(stableId) || !modeDropdownOpen) {
            return false;
        }
        int w = PhoneOverlay.getPhoneWidth();
        int navH = (int)(PhoneOverlay.getPhoneHeight() * NAV_H_RATIO / 100.0);
        int boxY = navH + 10 + 24 + 28 + 8 + 14; // 与渲染一致的模式框 y
        int boxX = 10;
        int boxW = w - 20;
        int boxH = 20;
        int listY = boxY + boxH;
        int listH = Math.min(DROPDOWN_VISIBLE_ROWS, SosTaskCache.getAll().size()) * DROPDOWN_ROW_H;
        if (relX < boxX || relX > boxX + boxW || relY < listY || relY > listY + listH) {
            return false;
        }
        List<String> tasks = SosTaskCache.getAll();
        if (tasks.isEmpty()) {
            return true;
        }
        int step = scrollDelta > 0 ? -1 : 1; // 正滚向上
        modeCursor = clamp(modeCursor + step, 0, tasks.size() - 1);
        return true;
    }
}
