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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 女仆设置页（从聊天页右上角 ⚙ 进入）。
 * <p>包含针对该女仆的<b>SOS 绑定开关</b>与当前状态，以及（已绑定时）<b>SOS 工作模式</b>选择。
 * 工作模式枚举所有可用任务（兼容新增攻击模式的 mod）。
 */
public class MaidSettingsPage implements IPhonePage {
    private static final int NAV_H_RATIO = 8;

    private final String stableId;
    private final String maidName;

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

        // 已绑定时的 SOS 工作模式选择器
        if (bound) {
            int modeY = y;
            g.drawString(font, "SOS 工作模式：", baseX + 10, modeY, 0xFF888888);
            modeY += 14;
            // 左侧 ◀ 按钮
            g.fill(btnX, modeY, btnX + 24, modeY + 20, 0xFF444444);
            g.drawCenteredString(font, "◀", btnX + 12, modeY + 4, 0xFFFFFFFF);
            // 中间显示当前模式（用语言键本地化；任务 uid: namespace:path → task.namespace.path）
            String currentUid = SosBindCache.getTaskUid(stableId);
            String modeName = formatTaskName(currentUid);
            int midX = baseX + 10 + 30;
            int midW = w - 20 - 30 - 28;
            g.fill(midX, modeY, midX + midW, modeY + 20, 0xFF333333);
            g.drawString(font, modeName, midX + 6, modeY + 4, 0xFFFFFFFF);
            // 右侧 ▶ 按钮
            g.fill(midX + midW, modeY, midX + midW + 24, modeY + 20, 0xFF444444);
            g.drawCenteredString(font, "▶", midX + midW + 12, modeY + 4, 0xFFFFFFFF);
            y = modeY + 28;
        }

        String hint = "已绑定 " + SosBindCache.count() + "/" + SosBindData.MAX_BOUND + " 位女仆作为 SOS 护卫";
        g.drawString(font, hint, baseX + 10, y, 0xFF888888);
    }

    /** 把 taskUid 转为可读名称：优先用语言键 task.namespace.path，缺失时回退显示原始 uid */
    private static String formatTaskName(String uid) {
        try {
            Component c = Component.translatable("task." + uid.replace(':', '.'));
            String localized = c.getString();
            if (localized == null || localized.isEmpty() || localized.equals("task." + uid.replace(':', '.'))) {
                return uid.substring(uid.indexOf(':') + 1);
            }
            return localized;
        } catch (RuntimeException e) {
            return uid;
        }
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

        // SOS 工作模式选择器（仅已绑定时）
        if (SosBindCache.isBound(stableId)) {
            int modeY = y + btnH + 8 + 14;
            List<String> tasks = SosTaskCache.getAll();
            String currentUid = SosBindCache.getTaskUid(stableId);
            int idx = tasks.indexOf(currentUid);
            if (idx < 0) {
                idx = 0;
            }
            // 左侧 ◀
            if (relX >= btnX && relX <= btnX + 24 && relY >= modeY && relY <= modeY + 20) {
                if (!tasks.isEmpty()) {
                    String prev = tasks.get((idx - 1 + tasks.size()) % tasks.size());
                    PacketDistributor.sendToServer(new C2SSetSosModePacket(stableId, prev));
                }
                return true;
            }
            // 右侧 ▶
            int midX = btnX + 30;
            int midW = w - 20 - 30 - 28;
            if (relX >= midX + midW && relX <= midX + midW + 24 && relY >= modeY && relY <= modeY + 20) {
                if (!tasks.isEmpty()) {
                    String next = tasks.get((idx + 1) % tasks.size());
                    PacketDistributor.sendToServer(new C2SSetSosModePacket(stableId, next));
                }
                return true;
            }
        }
        return false;
    }
}
