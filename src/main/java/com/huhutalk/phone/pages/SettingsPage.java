package com.huhutalk.phone.pages;

import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.client.theme.ColorScheme;
import com.huhutalk.client.theme.ThemeManager;
import com.huhutalk.network.C2SSOSPacket;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 全局设置页（底部导航「设置」tab）。
 * <p>
 * 包含：
 * <ul>
 *   <li>消息免打扰开关</li>
 *   <li>消息断句动画开关</li>
 *   <li>延时参数（每字耗时 / 单句上限）</li>
 *   <li>配色切换（一键换主题）</li>
 *   <li>手机壳配置入口（选方块）</li>
 *   <li>SOS 紧急呼叫入口（预留自动 SOS 扩展）</li>
 * </ul>
 * 使用滚动列表 + 简单开关/按钮交互。
 */
public class SettingsPage implements IPhonePage {
    private static final int ROW_H = 30;
    /** 可选屏幕比例（宽:高） */
    private static final List<String> ASPECT_OPTIONS = List.of("20:9", "9:16", "4:3");

    /** 滚动偏移（像素） */
    private int scrollOffset = 0;
    private int contentHeight = 0;
    /** 是否正在拖动屏幕大小滑块 */
    private boolean sizeDragging = false;

    /** 顶部标题栏高度：随字体缩放（跟随 GUI 字体大小） */
    private static int navH() {
        return Minecraft.getInstance().font.lineHeight + 8;
    }

    public SettingsPage() {
    }

    @Override
    public void render(GuiGraphics g, int baseX, int baseY, float partialTick) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        Font font = Minecraft.getInstance().font;
        ColorScheme scheme = ThemeManager.getActive();
        // 标题栏高度随字体缩放（跟随 GUI 字体大小），而非手机高度百分比
        int navH = navH();

        // 页面底色
        g.fill(baseX, baseY, baseX + w, baseY + h, scheme.pageBg());

        // 标题栏
        g.fill(baseX, baseY, baseX + w, baseY + navH, scheme.topBarBg());
        int titleW = font.width("设置");
        g.drawString(font, "设置", baseX + (w - titleW) / 2, baseY + 3, scheme.topBarText());

        // 内容区（可滚动）
        int topY = baseY + navH + 4;
        int bottomY = baseY + h - PhoneOverlay.getBottomNavHeight() - 4;
        g.enableScissor(baseX, topY, baseX + w, bottomY);

        int y = topY - scrollOffset;
        contentHeight = 0;

        y = renderToggleRow(g, font, baseX, y, w, "消息免打扰",
                HuhuTalkClientSettings.isDndEnabled(),
                v -> HuhuTalkClientSettings.setDndEnabled(v));
        y = renderToggleRow(g, font, baseX, y, w, "消息动画",
                HuhuTalkClientSettings.isMessageAnimationEnabled(),
                v -> HuhuTalkClientSettings.setMessageAnimation(v));
        y = renderStepperRow(g, font, baseX, y, w, "每字耗时(ms)",
                HuhuTalkClientSettings.getCharTypingMs(),
                v -> HuhuTalkClientSettings.setCharTypingMs(v));
        y = renderStepperRow(g, font, baseX, y, w, "单句上限(ms)",
                HuhuTalkClientSettings.getMaxTypingMs(),
                v -> HuhuTalkClientSettings.setMaxTypingMs(v));

        // 配色切换
        y = renderThemeRow(g, font, baseX, y, w, scheme);
        // SOS 紧急呼叫
        y = renderSosRow(g, font, baseX, y, w);
        // 手机壳
        y = renderCaseRow(g, font, baseX, y, w);
        // 屏幕比例
        y = renderAspectRow(g, font, baseX, y, w);
        // 屏幕大小（拖动滑块）
        y = renderSizeRow(g, font, baseX, y, w);

        contentHeight = y - (topY - scrollOffset) + scrollOffset;

        g.disableScissor();
    }

    private int renderToggleRow(GuiGraphics g, Font font, int baseX, int y, int w,
                                String label, boolean value, java.util.function.Consumer<Boolean> setter) {
        ColorScheme scheme = ThemeManager.getActive();
        g.fill(baseX + 6, y, baseX + w - 6, y + ROW_H, 0x44FFFFFF);
        g.drawString(font, label, baseX + 12, y + (ROW_H - font.lineHeight) / 2 + 1, 0xFF333333, false);
        // 开关
        int swX = baseX + w - 56, swY = y + 6;
        int swW = 44, swH = ROW_H - 12;
        g.fill(swX, swY, swX + swW, swY + swH, value ? scheme.accentSoftActive() : 0xFF9E9E9E);
        int knobX = value ? swX + swW - 18 : swX + 2;
        g.fill(knobX, swY + 2, knobX + 16, swY + swH - 2, 0xFFFFFFFF);
        return y + ROW_H;
    }

    private int renderStepperRow(GuiGraphics g, Font font, int baseX, int y, int w,
                                 String label, long value, java.util.function.Consumer<Long> setter) {
        ColorScheme scheme = ThemeManager.getActive();
        g.fill(baseX + 6, y, baseX + w - 6, y + ROW_H, 0x44FFFFFF);
        g.drawString(font, label + "：" + value, baseX + 12, y + (ROW_H - font.lineHeight) / 2 + 1, 0xFF333333, false);
        // - 和 +
        int btnY = y + 5, btnH = ROW_H - 10, btnW = 26;
        int minusX = baseX + w - 66, plusX = baseX + w - 34;
        g.fill(minusX, btnY, minusX + btnW, btnY + btnH, 0xFF888888);
        g.drawCenteredString(font, "−", minusX + btnW / 2, btnY + (btnH - font.lineHeight) / 2, 0xFFFFFFFF);
        g.fill(plusX, btnY, plusX + btnW, btnY + btnH, 0xFF888888);
        g.drawCenteredString(font, "＋", plusX + btnW / 2, btnY + (btnH - font.lineHeight) / 2, 0xFFFFFFFF);
        // 存当前行信息供点击处理（用简单字段模拟：因渲染时不直接处理点击，这里只记录区间）
        return y + ROW_H;
    }

    private int renderThemeRow(GuiGraphics g, Font font, int baseX, int y, int w, ColorScheme scheme) {
        g.fill(baseX + 6, y, baseX + w - 6, y + ROW_H, 0x44FFFFFF);
        g.drawString(font, "配色方案：" + scheme.displayName(), baseX + 12, y + (ROW_H - font.lineHeight) / 2 + 1, 0xFF333333, false);
        // 切换按钮
        int btnW = 44, btnX = baseX + w - btnW - 10, btnY = y + 5, btnH = ROW_H - 10;
        g.fill(btnX, btnY, btnX + btnW, btnY + btnH, scheme.accentSoftActive());
        g.drawCenteredString(font, "切换", btnX + btnW / 2, btnY + (btnH - font.lineHeight) / 2, 0xFFFFFFFF);
        return y + ROW_H;
    }

    private int renderSosRow(GuiGraphics g, Font font, int baseX, int y, int w) {
        ColorScheme scheme = ThemeManager.getActive();
        g.fill(baseX + 6, y, baseX + w - 6, y + ROW_H, scheme.sosBg());
        g.drawString(font, "🚨 SOS 紧急呼叫", baseX + 12, y + (ROW_H - font.lineHeight) / 2 + 1, scheme.sosText());
        return y + ROW_H;
    }

    private int renderCaseRow(GuiGraphics g, Font font, int baseX, int y, int w) {
        ColorScheme scheme = ThemeManager.getActive();
        String current = HuhuTalkClientSettings.getPhoneCaseBlock();
        String display = current.isBlank() ? "手机壳：默认" : "手机壳：" + current.substring(current.indexOf(':') + 1);
        g.fill(baseX + 6, y, baseX + w - 6, y + ROW_H, 0x44FFFFFF);
        g.drawString(font, display, baseX + 12, y + (ROW_H - font.lineHeight) / 2 + 1, 0xFF333333, false);
        int btnW = 44, btnX = baseX + w - btnW - 10, btnY = y + 5, btnH = ROW_H - 10;
        g.fill(btnX, btnY, btnX + btnW, btnY + btnH, scheme.accentSoftActive());
        g.drawCenteredString(font, "选壳", btnX + btnW / 2, btnY + (btnH - font.lineHeight) / 2, 0xFFFFFFFF);
        return y + ROW_H;
    }

    /** 屏幕比例行：显示当前比例，点"切换"在 20:9 / 9:16 / 4:3 间循环 */
    private int renderAspectRow(GuiGraphics g, Font font, int baseX, int y, int w) {
        ColorScheme scheme = ThemeManager.getActive();
        g.fill(baseX + 6, y, baseX + w - 6, y + ROW_H, 0x44FFFFFF);
        g.drawString(font, "屏幕比例：" + HuhuTalkClientSettings.getPhoneAspect(),
                baseX + 12, y + (ROW_H - font.lineHeight) / 2 + 1, 0xFF333333, false);
        int btnW = 44, btnX = baseX + w - btnW - 10, btnY = y + 5, btnH = ROW_H - 10;
        g.fill(btnX, btnY, btnX + btnW, btnY + btnH, scheme.accentSoftActive());
        g.drawCenteredString(font, "切换", btnX + btnW / 2, btnY + (btnH - font.lineHeight) / 2, 0xFFFFFFFF);
        return y + ROW_H;
    }

    /** 屏幕大小行：底部横向滑块，可拖动调整高度比例（0.5~1.0）；拖动中实时更新 */
    private int renderSizeRow(GuiGraphics g, Font font, int baseX, int y, int w) {
        ColorScheme scheme = ThemeManager.getActive();
        g.fill(baseX + 6, y, baseX + w - 6, y + ROW_H, 0x44FFFFFF);
        float size = HuhuTalkClientSettings.getPhoneSize();
        g.drawString(font, "屏幕大小：" + Math.round(size * 100) + "%",
                baseX + 12, y + 4, 0xFF333333, false);

        int trackLeft = baseX + 12;
        int trackRight = baseX + w - 12;
        int trackY = y + ROW_H - 12;
        g.fill(trackLeft, trackY, trackRight, trackY + 3, 0xFFAAAAAA);
        int handleX = trackLeft + (int)((size - 0.5f) / 0.5f * (trackRight - trackLeft));
        g.fill(handleX - 4, trackY - 4, handleX + 4, trackY + 7, scheme.accentSoftActive());

        // 拖动中：轮询鼠标位置实时更新（不落盘，松开时再 save）
        if (sizeDragging) {
            Minecraft mc = Minecraft.getInstance();
            double mx = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
            updateSizeFromRelX(mx - baseX, w);
        }
        return y + ROW_H;
    }

    /** 根据手机相对横坐标设置高度比例（钳制 0.5~1.0，不落盘） */
    private void updateSizeFromRelX(double relX, int w) {
        float f = 0.5f + (float)((relX - 12) / (double)(w - 24)) * 0.5f;
        HuhuTalkClientSettings.setPhoneSizeRaw(f);
    }

    /** 循环切换到下一种屏幕比例 */
    private void cycleAspect() {
        String cur = HuhuTalkClientSettings.getPhoneAspect();
        int idx = ASPECT_OPTIONS.indexOf(cur);
        int next = (idx + 1) % ASPECT_OPTIONS.size();
        HuhuTalkClientSettings.setPhoneAspect(ASPECT_OPTIONS.get(idx < 0 ? 0 : next));
    }

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        // 松开：结束滑块拖动并落盘
        if (action == GLFW.GLFW_RELEASE) {
            if (sizeDragging) {
                sizeDragging = false;
                HuhuTalkClientSettings.save();
            }
            return true;
        }
        if (action != GLFW.GLFW_PRESS) return false;
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int navH = navH();

        int topY = navH + 4;
        int rowIndex = (int)((relY - topY + scrollOffset) / ROW_H);
        int colX = (int)relX;
        int rowY = topY - scrollOffset + rowIndex * ROW_H;

        // 第 0 行：免打扰
        if (rowIndex == 0 && relY >= rowY && relY < rowY + ROW_H) {
            HuhuTalkClientSettings.setDndEnabled(!HuhuTalkClientSettings.isDndEnabled());
            return true;
        }
        // 第 1 行：消息动画
        if (rowIndex == 1 && relY >= rowY && relY < rowY + ROW_H) {
            HuhuTalkClientSettings.setMessageAnimation(!HuhuTalkClientSettings.isMessageAnimationEnabled());
            return true;
        }
        // 第 2 行：每字耗时 stepper
        if (rowIndex == 2 && relY >= rowY && relY < rowY + ROW_H) {
            long v = HuhuTalkClientSettings.getCharTypingMs();
            if (colX >= w - 66 && colX <= w - 40) {
                HuhuTalkClientSettings.setCharTypingMs(Math.max(50, v - 50));
            } else if (colX >= w - 34 && colX <= w - 8) {
                HuhuTalkClientSettings.setCharTypingMs(v + 50);
            }
            return true;
        }
        // 第 3 行：单句上限 stepper
        if (rowIndex == 3 && relY >= rowY && relY < rowY + ROW_H) {
            long v = HuhuTalkClientSettings.getMaxTypingMs();
            if (colX >= w - 66 && colX <= w - 40) {
                HuhuTalkClientSettings.setMaxTypingMs(Math.max(100, v - 100));
            } else if (colX >= w - 34 && colX <= w - 8) {
                HuhuTalkClientSettings.setMaxTypingMs(v + 100);
            }
            return true;
        }
        // 第 4 行：配色切换
        if (rowIndex == 4 && relY >= rowY && relY < rowY + ROW_H) {
            if (colX >= w - 60 && colX <= w - 10) {
                ThemeManager.cycleNext();
            }
            return true;
        }
        // 第 5 行：SOS 紧急呼叫
        if (rowIndex == 5 && relY >= rowY && relY < rowY + ROW_H) {
            PacketDistributor.sendToServer(new C2SSOSPacket());
            return true;
        }
        // 第 6 行：手机壳
        if (rowIndex == 6 && relY >= rowY && relY < rowY + ROW_H) {
            if (colX >= w - 60 && colX <= w - 10) {
                PhoneOverlay.pushPage(new PhoneCasePickerPage());
            }
            return true;
        }
        // 第 7 行：屏幕比例切换
        if (rowIndex == 7 && relY >= rowY && relY < rowY + ROW_H) {
            if (colX >= w - 60 && colX <= w - 10) {
                cycleAspect();
            }
            return true;
        }
        // 第 8 行：屏幕大小滑块（按下开始拖动）
        if (rowIndex == 8 && relY >= rowY && relY < rowY + ROW_H) {
            sizeDragging = true;
            updateSizeFromRelX(relX, w);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        int maxScroll = Math.max(0, contentHeight - (int)(PhoneOverlay.getPhoneHeight() * 0.9));
        scrollOffset = Math.max(0, Math.min(scrollOffset - (int)(scrollDelta * 20), maxScroll));
        return true;
    }
}
