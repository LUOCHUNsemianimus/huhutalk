package com.huhutalk.phone.pages;

import com.huhutalk.client.NotificationManager;
import com.huhutalk.network.C2SSOSPacket;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 手机主界面。
 * - APP 图标网格（按 gridColumns 动态调整）
 * - TALK 图标右上角未读红点
 * - ✅ 顶部状态栏端口：左时间 / 中信号 / 右电量 + 关闭按钮
 * - SOS 紧急呼叫按钮（召来全部已佩戴手机的女仆并进入战斗保护）
 */
public class MainPage implements IPhonePage {
    private final List<AppInfo> apps = new ArrayList<>();
    /** SOS 点击后的短暂提示（毫秒时间戳） */
    private long sosFlashUntil = 0;
    private String sosMessage = "";

    public MainPage() {
        apps.add(new AppInfo("TALK", 0xFFC0CB, true, () -> new MaidListPage()));
        apps.add(new AppInfo("SOS", 0xFF0000, true, null));
        apps.add(new AppInfo("30分", 0x6495ED, false, null));
        apps.add(new AppInfo("打车", 0xFFFF00, false, null));
        apps.add(new AppInfo("打人", 0xFF8000, false, null));
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

        // 统一状态栏已由 PhoneOverlay 渲染，这里只留顶部小留白
        int topPad = (int)(h * 0.03);

        // ============ APP 图标网格 ============
        int margin = (int)(w * 0.08);
        int cols = PhoneOverlay.getGridColumns();
        int iconW = (w - margin * (cols + 1)) / cols;
        int iconH = (int)(iconW * 0.8);
        int startY = topPad + (int)(h * 0.05);

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

            // TALK 红点
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

        // SOS 点击后的短暂提示
        if (System.currentTimeMillis() < sosFlashUntil && !sosMessage.isEmpty()) {
            int msgW = font.width(sosMessage);
            int msgY = startY - (int)(h * 0.02);
            graphics.fill(baseX + 4, msgY - 2, baseX + w - 4, msgY + font.lineHeight + 2, 0xAA000000);
            graphics.drawString(font, sosMessage, baseX + (w - msgW) / 2, msgY, 0xFFFF6666);
        }
    }

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (action != GLFW.GLFW_PRESS) return false;

        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        // 与 render 顶部留白一致
        int topPad = (int)(h * 0.03);

        int margin = (int)(w * 0.08);
        int cols = PhoneOverlay.getGridColumns();
        int iconW = (w - margin * (cols + 1)) / cols;
        int iconH = (int)(iconW * 0.8);
        int startY = topPad + (int)(h * 0.05);

        for (int i = 0; i < apps.size(); i++) {
            int row = i / cols;
            int col = i % cols;
            int x = margin + col * (iconW + margin);
            int y = startY + row * (iconH + margin);

            if (relX >= x && relX <= x + iconW && relY >= y && relY <= y + iconH) {
                AppInfo app = apps.get(i);
                // SOS：不发页，直接发紧急呼叫包并显示提示
                if ("SOS".equals(app.label)) {
                    sendSos();
                    return true;
                }
                // TALK：进入 HuhuTalk App 内容区（消息页 + 底部导航）
                if ("TALK".equals(app.label)) {
                    PhoneOverlay.openChatApp();
                    return true;
                }
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

    /** 发送 SOS 紧急呼叫包，并显示短暂提示 */
    private void sendSos() {
        PacketDistributor.sendToServer(new C2SSOSPacket());
        int bound = com.huhutalk.client.SosBindCache.count();
        sosMessage = "🚨 已呼叫 " + bound + " 位 SOS 女仆赶来保护!";
        sosFlashUntil = System.currentTimeMillis() + 3000;
    }

    /** 红点坐标计算（预留未来动画使用） */
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