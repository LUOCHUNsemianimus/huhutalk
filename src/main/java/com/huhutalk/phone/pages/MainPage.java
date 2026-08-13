package com.huhutalk.phone.pages;

import com.huhutalk.client.NotificationManager;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 手机主界面。
 * 根据 PhoneOverlay.getGridColumns() 动态调整每行图标数量。
 * TALK 图标右上角会显示未读红点（所有女仆未读总和）。
 */
public class MainPage implements IPhonePage {
    private final List<AppInfo> apps = new ArrayList<>();

    public MainPage() {
        apps.add(new AppInfo("TALK", 0xFFC0CB, true, () -> new MaidListPage())); // 粉色
        apps.add(new AppInfo("30分", 0x6495ED, false, null)); // 蓝色
        apps.add(new AppInfo("打车", 0xFFFF00, false, null)); // 黄色
        apps.add(new AppInfo("打人", 0xFF0000, false, null)); // 红色
    }

    /** 计算 TALK 图标的红点位置，渲染与点击共用 */
    private boolean getTalkIconPos(int[] out) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int navH = (int)(h * 0.08);
        int margin = (int)(w * 0.08);
        int cols = PhoneOverlay.getGridColumns();
        int iconW = (w - margin * (cols + 1)) / cols;
        int iconH = (int)(iconW * 0.8);
        int startY = navH + (int)(h * 0.05);

        for (int i = 0; i < apps.size(); i++) {
            if ("TALK".equals(apps.get(i).label)) {
                int row = i / cols;
                int col = i % cols;
                out[0] = margin + col * (iconW + margin);
                out[1] = startY + row * (iconH + margin);
                out[2] = iconW;
                out[3] = iconH;
                return true;
            }
        }
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int baseX, int baseY, float partialTick) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        var font = Minecraft.getInstance().font;

        // 顶部状态栏
        int navH = (int)(h * 0.08);
        graphics.fill(baseX, baseY, baseX + w, baseY + navH, 0xFF333333);
        graphics.drawString(font, "X", baseX + w - 12, baseY + 3, 0xFFFF0000);

        // 图标布局（与 mouseClicked 完全一致）
        int margin = (int)(w * 0.08);
        int cols = PhoneOverlay.getGridColumns();
        int iconW = (w - margin * (cols + 1)) / cols;
        int iconH = (int)(iconW * 0.8);
        int startY = navH + (int)(h * 0.05);

        for (int i = 0; i < apps.size(); i++) {
            int row = i / cols;
            int col = i % cols;
            int x = baseX + margin + col * (iconW + margin);
            int y = baseY + startY + row * (iconH + margin);

            AppInfo app = apps.get(i);
            graphics.fill(x, y, x + iconW, y + iconH, app.color | 0xFF000000);
            int textX = x + iconW / 2 - font.width(app.label) / 2;
            int textY = y + iconH / 2 - 4;
            graphics.drawString(font, app.label, textX, textY, 0xFFFFFFFF);

            // ✅ TALK 图标红点
            if ("TALK".equals(app.label)) {
                int unread = NotificationManager.getTotalUnread();
                if (unread > 0) {
                    int dotX = x + iconW - 14;
                    int dotY = y - 6;
                    graphics.fill(dotX, dotY, dotX + 14, dotY + 14, 0xFFFF0000);
                    String num = unread > 99 ? "99+" : String.valueOf(unread);
                    graphics.drawString(font, num, dotX + 2, dotY + 3, 0xFFFFFFFF);
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (action != 1) return false;

        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int navH = (int)(h * 0.08);

        // 关闭按钮
        if (relX >= w - 15 && relX <= w && relY >= 0 && relY <= navH) {
            PhoneOverlay.toggle();
            return true;
        }

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

    /** 红点坐标计算（预留未来动画后使用） */
    public int[] getTalkIconPosition() {
        int[] pos = new int[4];
        if (getTalkIconPos(pos)) return pos;
        return null;
    }

    public static class AppInfo {
        final String label;
        final int color;
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