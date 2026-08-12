package com.huhutalk.phone.pages;

import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * 手机主界面，显示应用图标网格。
 */
public class MainPage implements IPhonePage {
    private final List<AppInfo> apps = new ArrayList<>();

    public MainPage() {
        apps.add(new AppInfo("TALK", 0xFFC0CB, true, () -> new MaidListPage()));          // 粉底
        apps.add(new AppInfo("30分", 0x6495ED, false, null));   // 蓝色
        apps.add(new AppInfo("打车", 0xFFFF00, false, null));   // 黄色
        apps.add(new AppInfo("打人", 0xFF0000, false, null));   // 红色
        // 可追加更多 App...
    }

    @Override
    public void render(GuiGraphics graphics, int baseX, int baseY, float partialTick) {
        // 绘制关闭按钮
        graphics.drawString(Minecraft.getInstance().font, "X", baseX + 202, baseY + 8, 0xFFFF0000);

        // 绘制四个图标
        int cols = 2;
        int iconSize = 80;
        int gap = 15;
        int startX = baseX + 20;
        int startY = baseY + 40;

        for (int i = 0; i < apps.size(); i++) {
            AppInfo app = apps.get(i);
            int row = i / cols;
            int col = i % cols;
            int x = startX + col * (iconSize + gap);
            int y = startY + row * (iconSize + gap);

            // 图标背景色
            graphics.fill(x, y, x + iconSize, y + iconSize, app.color | 0xFF000000);
            // 图标文字
            graphics.drawCenteredString(Minecraft.getInstance().font, app.label, x + iconSize/2, y + iconSize/2 - 4, 0xFFFFFFFF);
        }
    }

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (action != 1) return false;

        // 关闭按钮
        if (relX >= 200 && relX <= 215 && relY >= 5 && relY <= 20) {
            PhoneOverlay.toggle();
            return true;
        }

        // 检查图标点击
        int cols = 2;
        int iconSize = 80;
        int gap = 15;
        int startX = 20;
        int startY = 40;

        for (int i = 0; i < apps.size(); i++) {
            int row = i / cols;
            int col = i % cols;
            int x = startX + col * (iconSize + gap);
            int y = startY + row * (iconSize + gap);

            if (relX >= x && relX < x + iconSize && relY >= y && relY < y + iconSize) {
                AppInfo app = apps.get(i);
                if (app.implemented && app.pageFactory != null) {
                    PhoneOverlay.pushPage(app.pageFactory.get());
                } else {
                    PhoneOverlay.pushPage(new ComingSoonPage());
                }
                return true;
            }
        }
        return false;
    }

    // 内部 App 信息类
    public static class AppInfo {
        String label;
        int color;
        boolean implemented;
        java.util.function.Supplier<IPhonePage> pageFactory;

        public AppInfo(String label, int color, boolean implemented, java.util.function.Supplier<IPhonePage> pageFactory) {
            this.label = label;
            this.color = color;
            this.implemented = implemented;
            this.pageFactory = pageFactory;
        }
    }
}