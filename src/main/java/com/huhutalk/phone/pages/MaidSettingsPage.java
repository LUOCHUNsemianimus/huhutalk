package com.huhutalk.phone.pages;

import com.huhutalk.client.MaidStatusCache;
import com.huhutalk.client.SosBindCache;
import com.huhutalk.core.contact.SosBindData;
import com.huhutalk.network.C2SRequestMaidStatusPacket;
import com.huhutalk.network.C2SToggleSosBindPacket;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 女仆设置页（从聊天页右上角 ⚙ 进入）。
 * <p>目前只放针对该女仆的<b>SOS 绑定开关</b>与当前状态。后续可在此扩展移动/工作模式等操作。
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

        String hint = "已绑定 " + SosBindCache.count() + "/" + SosBindData.MAX_BOUND + " 位女仆作为 SOS 护卫";
        g.drawString(font, hint, baseX + 10, y, 0xFF888888);
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
        return false;
    }
}
