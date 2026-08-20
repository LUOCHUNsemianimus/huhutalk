package com.huhutalk.phone.pages;

import com.huhutalk.client.ChatHistoryStorage;
import com.huhutalk.client.MaidListCache;
import com.huhutalk.client.MaidStatusCache;
import com.huhutalk.client.NotificationManager;
import com.huhutalk.client.SosBindCache;
import com.huhutalk.core.contact.SosBindData;
import com.huhutalk.network.C2SRequestMaidListPacket;
import com.huhutalk.network.C2SRequestMaidStatusPacket;
import com.huhutalk.network.C2SToggleSosBindPacket;
import com.huhutalk.network.S2CMaidListPacket;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

public class MaidListPage implements IPhonePage {
    private List<S2CMaidListPacket.MaidDisplayInfo> maidList = List.of();
    private int scrollOffset = 0;
    private static final int ITEM_HEIGHT = 48;
    /** 右侧 SOS 绑定按钮的尺寸 */
    private static final int BIND_BTN_W = 28;
    private static final int BIND_BTN_H = 16;
    /** 本次页面打开是否已向服务端按需请求过状态（列表异步到达，需去重） */
    private boolean statusRequested = false;

    public MaidListPage() {
        PacketDistributor.sendToServer(new C2SRequestMaidListPacket());
    }

    @Override
    public void onOpen() {
        statusRequested = false;
        refreshList();
    }

    private void refreshList() {
        maidList = MaidListCache.getList();
    }

    /** 列表到达后按需请求一次所有女仆的状态（打开页面/列表变化时立刻刷新状态小字） */
    private void requestStatusOnce() {
        if (statusRequested || maidList.isEmpty()) {
            return;
        }
        statusRequested = true;
        List<String> ids = new ArrayList<>();
        for (S2CMaidListPacket.MaidDisplayInfo info : maidList) {
            ids.add(info.stableId());
        }
        PacketDistributor.sendToServer(new C2SRequestMaidStatusPacket(ids));
    }

    @Override
    public void render(GuiGraphics graphics, int baseX, int baseY, float partialTick) {
        refreshList();
        requestStatusOnce();
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int navH = (int)(h * 0.08);
        var font = Minecraft.getInstance().font;

        graphics.fill(baseX, baseY, baseX + w, baseY + navH, 0xFF333333);
        graphics.drawString(font, "< 返回", baseX + 4, baseY + 3, 0xFFFFFFFF);
        String title = "我的女仆";
        int titleW = font.width(title);
        graphics.drawString(font, title, baseX + (w - titleW) / 2, baseY + 3, 0xFFFFFFFF);
        // 右上角 SOS 绑定计数
        String sosCount = "SOS " + SosBindCache.count() + "/" + SosBindData.MAX_BOUND;
        graphics.drawString(font, sosCount, baseX + w - font.width(sosCount) - 4, baseY + 3, 0xFFFF6666);

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
                graphics.drawString(font, info.name(), baseX + 45, itemY + 6, 0xFFFFFFFF);

                // 未送达红"!"（入离线队列、尚未送达）
                if (ChatHistoryStorage.hasPendingMessages(info.stableId())) {
                    int nameW = font.width(info.name());
                    graphics.drawString(font, "!", baseX + 45 + nameW + 3, itemY + 6, 0xFFFF3333);
                }

                // 状态小字：女仆当前工作状态（如"种地中…"），按需请求 + 低频推送更新
                String actionStatus = MaidStatusCache.get(info.stableId());
                if (!actionStatus.isEmpty()) {
                    graphics.drawString(font, actionStatus, baseX + 45, itemY + 20, 0xFFAAAAAA);
                }

                String statusText;
                int statusColor;
                switch (info.status()) {
                    case ONLINE   -> { statusText = "在线"; statusColor = 0xFF00FF00; }
                    case DEAD     -> { statusText = "死亡"; statusColor = 0xFFFF0000; }
                    case SHUTDOWN -> { statusText = "已关机"; statusColor = 0xFF888888; }
                    default       -> { statusText = "离线"; statusColor = 0xFFAAAAAA; }
                }
                graphics.drawString(font, statusText, baseX + w - 34, itemY + 6, statusColor);

                int unread = NotificationManager.getUnread(info.stableId());
                if (unread > 0) {
                    int dotX = baseX + w - 46;
                    int dotY = itemY + 30;
                    graphics.fill(dotX, dotY, dotX + 12, dotY + 12, 0xFFFF0000);
                    String num = unread > 99 ? "99+" : String.valueOf(unread);
                    graphics.drawString(font, num, dotX + 3, dotY + 2, 0xFFFFFFFF);
                }

                // 右侧 SOS 绑定按钮：点击切换绑定（已绑定高亮红，未绑定灰）
                boolean sosBound = SosBindCache.isBound(info.stableId());
                int btnLeft = baseX + w - 32;
                int btnTop = itemY + 19;
                int btnFill = sosBound ? 0xFFE53935 : 0xFF3A3A3A;
                graphics.fill(btnLeft, btnTop, btnLeft + BIND_BTN_W, btnTop + BIND_BTN_H, btnFill);
                graphics.drawString(font, "SOS",
                        btnLeft + (BIND_BTN_W - font.width("SOS")) / 2,
                        btnTop + (BIND_BTN_H - font.lineHeight) / 2 + 1,
                        sosBound ? 0xFFFFFFFF : 0xFFAAAAAA);
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
                // SOS 绑定按钮：命中则切换绑定，不进入聊天
                int btnLeft = w - 32;
                int btnTop = itemY + 19;
                if (relX >= btnLeft && relX <= btnLeft + BIND_BTN_W
                        && relY >= btnTop && relY <= btnTop + BIND_BTN_H) {
                    PacketDistributor.sendToServer(new C2SToggleSosBindPacket(info.stableId()));
                    return true;
                }
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