package com.huhutalk.phone.pages;

import com.huhutalk.client.MaidListCache;
import com.huhutalk.client.NotificationManager;
import com.huhutalk.network.C2SRequestMaidListPacket;
import com.huhutalk.network.S2CMaidListPacket;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

public class MaidListPage implements IPhonePage {
    private List<S2CMaidListPacket.MaidDisplayInfo> maidList = List.of();
    private int scrollOffset = 0;
    private static final int ITEM_HEIGHT = 40;

    public MaidListPage() {
        PacketDistributor.sendToServer(new C2SRequestMaidListPacket());
    }

    @Override
    public void onOpen() {
        refreshList();
    }

    private void refreshList() {
        maidList = MaidListCache.getList();
    }

    @Override
    public void render(GuiGraphics graphics, int baseX, int baseY, float partialTick) {
        refreshList();
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int navH = (int)(h * 0.08);
        var font = Minecraft.getInstance().font;

        graphics.fill(baseX, baseY, baseX + w, baseY + navH, 0xFF333333);
        graphics.drawString(font, "< 返回", baseX + 4, baseY + 3, 0xFFFFFFFF);
        String title = "我的女仆";
        int titleW = font.width(title);
        graphics.drawString(font, title, baseX + (w - titleW) / 2, baseY + 3, 0xFFFFFFFF);

        int listTop = baseY + navH;
        int listBottom = baseY + h;
        graphics.enableScissor(baseX, listTop, baseX + w, listBottom);

        if (maidList.isEmpty()) {
            graphics.drawString(font, "正在加载或没有女仆...", baseX + 10, listTop + 10, 0xFFAAAAAA);
        } else {
            for (int i = 0; i < maidList.size(); i++) {
                int itemY = listTop + i * ITEM_HEIGHT - scrollOffset;
                if (itemY + ITEM_HEIGHT < listTop || itemY > listBottom) continue;
                S2CMaidListPacket.MaidDisplayInfo info = maidList.get(i);

                graphics.fill(baseX + 5, itemY + 5, baseX + 35, itemY + 35, 0xFF888888);
                graphics.drawString(font, info.name(), baseX + 45, itemY + 8, 0xFFFFFFFF);

                String statusText;
                int statusColor;
                switch (info.status()) {
                    case ONLINE   -> { statusText = "在线"; statusColor = 0xFF00FF00; }
                    case DEAD     -> { statusText = "死亡"; statusColor = 0xFFFF0000; }
                    case SHUTDOWN -> { statusText = "已关机"; statusColor = 0xFF888888; }
                    default       -> { statusText = "离线"; statusColor = 0xFFAAAAAA; }
                }
                graphics.drawString(font, statusText, baseX + w - 34, itemY + 8, statusColor);

                int unread = NotificationManager.getUnread(info.stableId());
                if (unread > 0) {
                    int dotX = baseX + w - 46;
                    int dotY = itemY + 6;
                    graphics.fill(dotX, dotY, dotX + 12, dotY + 12, 0xFFFF0000);
                    String num = unread > 99 ? "99+" : String.valueOf(unread);
                    graphics.drawString(font, num, dotX + 3, dotY + 2, 0xFFFFFFFF);
                }
            }
        }
        graphics.disableScissor();
    }

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (action != 1) return false;
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int navH = (int)(h * 0.08);

        if (relX >= 0 && relX <= 60 && relY >= 0 && relY <= navH) {
            PhoneOverlay.popPage();
            return true;
        }

        int listTop = navH;
        for (int i = 0; i < maidList.size(); i++) {
            int itemY = listTop + i * ITEM_HEIGHT - scrollOffset;
            if (relY >= itemY && relY < itemY + ITEM_HEIGHT) {
                S2CMaidListPacket.MaidDisplayInfo info = maidList.get(i);
                NotificationManager.clear(info.stableId());
                PhoneOverlay.pushPage(new ChatPage(info.stableId(), info.name()));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        int maxScroll = Math.max(0, maidList.size() * ITEM_HEIGHT - (int)(PhoneOverlay.getPhoneHeight() * 0.92));
        scrollOffset = Math.max(0, Math.min(scrollOffset - (int)(scrollDelta * 10), maxScroll));
        return true;
    }
}