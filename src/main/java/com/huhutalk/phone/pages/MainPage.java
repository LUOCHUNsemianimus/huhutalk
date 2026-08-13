package com.huhutalk.phone.pages;

import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 手机主界面。
 * 根据 {@link PhoneOverlay#getGridColumns()} 动态调整每行图标数量，
 * 图标大小和间距由手机宽度自适应。
 */
public class MainPage implements IPhonePage {
    private final List<AppInfo> apps = new ArrayList<>();

    public MainPage() {
        // 添加应用：label、颜色、是否已实现、页面工厂（未实现则 null）
        apps.add(new AppInfo("TALK", 0xFFC0CB, true, () -> new MaidListPage())); // 粉色
        apps.add(new AppInfo("30分", 0x6495ED, false, null)); // 蓝色
        apps.add(new AppInfo("打车", 0xFFFF00, false, null)); // 黄色
        apps.add(new AppInfo("打人", 0xFF0000, false, null)); // 红色
        // 可以继续添加...
    }

    @Override
    public void render(GuiGraphics graphics, int baseX, int baseY, float partialTick) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();

        // 顶部状态栏（7% 高度）
        int navH = (int)(h * 0.08);
        graphics.fill(baseX, baseY, baseX + w, baseY + navH, 0xFF333333);

        // 关闭按钮 (右上角)
        graphics.drawString(Minecraft.getInstance().font, "X",
                baseX + w - 12, baseY + 3, 0xFFFF0000);

        // 图标区域起始位置
        int margin = (int)(w * 0.08);          // 边距
        int cols = PhoneOverlay.getGridColumns();
        int iconW = (w - margin * (cols + 1)) / cols;   // 图标宽
        int iconH = (int)(iconW * 0.8);                 // 图标高（略矮）
        int startY = baseY + navH + (int)(h * 0.05);    // 距离导航栏下方

        for (int i = 0; i < apps.size(); i++) {
            int row = i / cols;
            int col = i % cols;
            int x = baseX + margin + col * (iconW + margin);
            int y = startY + row * (iconH + margin);

            AppInfo app = apps.get(i);
            // 绘制图标背景（纯色）
            graphics.fill(x, y, x + iconW, y + iconH, app.color | 0xFF000000);
            // 文字居中
            int textX = x + iconW / 2 - Minecraft.getInstance().font.width(app.label) / 2;
            int textY = y + iconH / 2 - 4;
            graphics.drawString(Minecraft.getInstance().font, app.label, textX, textY, 0xFFFFFFFF);
        }
    }

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (action != 1) return false; // 只处理按下

        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int navH = (int)(h * 0.08);

        // 关闭按钮区域
        if (relX >= w - 15 && relX <= w && relY >= 0 && relY <= navH) {
            PhoneOverlay.toggle(); // 关闭手机
            return true;
        }

        // 计算图标布局（与 render 完全相同）
        int margin = (int)(w * 0.08);
        int cols = PhoneOverlay.getGridColumns();
        int iconW = (w - margin * (cols + 1)) / cols;
        int iconH = (int)(iconW * 0.8);
        int startY = navH + (int)(h * 0.05);

        for (int i = 0; i < apps.size(); i++) {
            int row = i / cols;
            int col = i % cols;
            int x = margin + col * (iconW + margin);
            int y = startY + row * (iconH + margin);

            if (relX >= x && relX <= x + iconW && relY >= y && relY <= y + iconH) {
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

    // 应用信息内部类
    public static class AppInfo {
        final String label;
        final int color;          // 背景色（不带 alpha）
        final boolean implemented;
        final Supplier<IPhonePage> pageFactory;

        public AppInfo(String label, int color, boolean implemented, Supplier<IPhonePage> factory) {
            this.label = label;
            this.color = color;
            this.implemented = implemented;
            this.pageFactory = factory;
        }
    }
}