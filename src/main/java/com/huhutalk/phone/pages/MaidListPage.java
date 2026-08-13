package com.huhutalk.phone.pages;

import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 女仆列表页面。
 * 数据源暂时使用假列表，可替换为你的 MaidManager 获取逻辑。
 */
public class MaidListPage implements IPhonePage {
    private final List<MaidEntry> maids = new ArrayList<>();
    private int scrollOffset = 0;
    private static final int ITEM_HEIGHT = 40; // 暂时固定，未来可动态

    public MaidListPage() {
        // 假数据
        maids.add(new MaidEntry(UUID.randomUUID(), "女仆A", true));
        maids.add(new MaidEntry(UUID.randomUUID(), "女仆B", false));
        maids.add(new MaidEntry(UUID.randomUUID(), "女仆C", true));
        maids.add(new MaidEntry(UUID.randomUUID(), "女仆D", true));
        maids.add(new MaidEntry(UUID.randomUUID(), "女仆E", false));
    }

    @Override
    public void render(GuiGraphics graphics, int baseX, int baseY, float partialTick) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int navH = (int)(h * 0.08);          // 导航栏高度

        // 导航栏
        graphics.fill(baseX, baseY, baseX + w, baseY + navH, 0xFF333333);
        // 返回按钮
        graphics.drawString(Minecraft.getInstance().font, "< 返回",
                baseX + 4, baseY + 3, 0xFFFFFFFF);
        // 标题
        String title = "我的女仆";
        int titleWidth = Minecraft.getInstance().font.width(title);
        graphics.drawString(Minecraft.getInstance().font, title,
                baseX + (w - titleWidth) / 2, baseY + 3, 0xFFFFFFFF);

        // 列表区域（从 navH 开始，到窗口底部）
        int listTop = baseY + navH;
        int listBottom = baseY + h;
        // 裁剪区域
        graphics.enableScissor(baseX, listTop, baseX + w, listBottom);

        // 临时变量 index 需要在循环中声明
        int itemY;
        for (int i = 0; i < maids.size(); i++) {
            itemY = listTop + i * ITEM_HEIGHT - scrollOffset;
            if (itemY + ITEM_HEIGHT < listTop || itemY > listBottom) continue; // 完全不可见
            MaidEntry entry = maids.get(i);
            // 头像框
            graphics.fill(baseX + 5, itemY + 5, baseX + 35, itemY + 35, 0xFF888888);
            // 名字
            graphics.drawString(Minecraft.getInstance().font, entry.name(),
                    baseX + 45, itemY + 8, 0xFFFFFFFF);
            // 在线状态
            String status = entry.online() ? "在线" : "离线";
            graphics.drawString(Minecraft.getInstance().font, status,
                    baseX + w - 30, itemY + 8, 0xFFAAAAAA);
        }

        graphics.disableScissor();
    }

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (action != 1) return false;
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int navH = (int)(h * 0.08);

        // 返回按钮
        if (relX >= 0 && relX <= 60 && relY >= 0 && relY <= navH) {
            PhoneOverlay.popPage();
            return true;
        }

        int listTop = navH;
        for (int i = 0; i < maids.size(); i++) {
            int itemY = listTop + i * ITEM_HEIGHT - scrollOffset;
            if (relY >= itemY && relY < itemY + ITEM_HEIGHT) {
                MaidEntry maid = maids.get(i);
                PhoneOverlay.pushPage(new ChatPage(maid));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        int totalHeight = maids.size() * ITEM_HEIGHT;
        int visibleHeight = PhoneOverlay.getPhoneHeight() - (int)(PhoneOverlay.getPhoneHeight() * 0.08);
        scrollOffset -= (int)(scrollDelta * 10);
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, totalHeight - visibleHeight)));
        return true;
    }
}