package com.huhutalk.phone.pages;

import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 女仆列表页面，点击某女仆进入聊天。
 * 数据源：目前使用静态假列表，请替换为你的真实女仆获取逻辑。
 */
public class MaidListPage implements IPhonePage {
    private final List<MaidEntry> maids = new ArrayList<>();
    private int scrollOffset = 0;
    private final int ITEM_HEIGHT = 40;

    public MaidListPage() {
        // 假数据：请替换为你的 MaidManager 接口
        maids.add(new MaidEntry(UUID.randomUUID(), "女仆A", true));
        maids.add(new MaidEntry(UUID.randomUUID(), "女仆B", false));
        maids.add(new MaidEntry(UUID.randomUUID(), "女仆C", true));
    }

    @Override
    public void render(GuiGraphics graphics, int baseX, int baseY, float partialTick) {
        // 顶部导航栏
        drawNavBar(graphics, baseX, baseY);
        // 女仆列表
        int listStartY = baseY + 30;
        for (int i = 0; i < maids.size(); i++) {
            int y = listStartY + i * ITEM_HEIGHT - scrollOffset;
            if (y + ITEM_HEIGHT < listStartY || y > baseY + 340) continue; // 裁剪

            MaidEntry entry = maids.get(i);
            graphics.fill(baseX + 5, y + 5, baseX + 35, y + 35, 0xFF888888); // 头像框
            graphics.drawString(Minecraft.getInstance().font, entry.name(), baseX + 45, y + 10, 0xFFFFFFFF);
            String status = entry.online() ? "在线" : "离线";
            graphics.drawString(Minecraft.getInstance().font, status, baseX + 180, y + 10, 0xFFAAAAAA);
        }
    }

    private void drawNavBar(GuiGraphics graphics, int baseX, int baseY) {
        graphics.fill(baseX, baseY, baseX + 220, baseY + 25, 0xFF333333);
        // 返回按钮
        graphics.drawString(Minecraft.getInstance().font, "< 返回", baseX + 5, baseY + 5, 0xFFFFFFFF);
        graphics.drawString(Minecraft.getInstance().font, "我的女仆", baseX + 70, baseY + 5, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (action != 1) return false; // 只处理按下事件
        // 检查返回按钮
        if (relX >= 0 && relX <= 60 && relY >= 0 && relY <= 25) {
            PhoneOverlay.popPage();
            return true;
        }
        // 检查女仆列表项目
        int listStartY = 30;
        for (int i = 0; i < maids.size(); i++) {
            int y = listStartY + i * ITEM_HEIGHT - scrollOffset;
            if (relY >= y && relY < y + ITEM_HEIGHT) {
                MaidEntry maid = maids.get(i);
                PhoneOverlay.pushPage(new ChatPage(maid));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        int listHeight = maids.size() * ITEM_HEIGHT;
        int visibleHeight = 340 - 30; // 手机内可用高度
        scrollOffset -= (int) (scrollDelta * 10);
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, listHeight - visibleHeight)));
        return true;
    }
}