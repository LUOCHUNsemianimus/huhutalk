package com.huhutalk.phone.pages;

import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.client.theme.ColorScheme;
import com.huhutalk.client.theme.ThemeManager;
import com.huhutalk.network.C2SSOSPacket;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.phone.UiShapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 全局设置页（底部导航「设置」tab）。
 * <p>
 * 顶栏 #de7e7e、标题「HuhuTalk设置」左对齐；页面底 #f0e4ea；设置项为圆角卡片 #fff5f8。
 * SOS 紧急呼叫胶囊置于页面顶部首位。
 * 分组：消息 / 动画 / 配色 / 显示。所有开关与按钮统一为胶囊形。
 */
public class SettingsPage implements IPhonePage {
    private static final int PAGE_BG = 0xFFF0E4EA;
    private static final int TOPBAR_BG = 0xFFDE7E7E;
    private static final int CARD_BG = 0xFFFFF5F8;
    /** 分组标题颜色：深灰色 */
    private static final int GROUP_TITLE_COLOR = 0xFF555555;
    private static final int TEXT_COLOR = 0xFF333333;
    private static final int TEXT_SUB = 0xFF888888;
    private static final int DIVIDER = 0xFFE5DBE1;
    private static final int CARD_R = 8;
    private static final int RECT_PAD = 3;
    private static final int ROW_H = 24;
    private static final int GROUP_TITLE_TOP = 8;
    /** 分组标题底到卡片顶的间距（使 UI 更松弛，不戳进卡片） */
    private static final int CARD_GAP = 10;
    private static final int SOS_H = 26;
    /** 分组标题缩放（0.75 倍） */
    private static final float GROUP_SCALE = 0.75f;
    /** 所有按钮统一强调色 #de7e7e */
    private static final int BTN_COLOR = 0xFFDE7E7E;
    /** 按钮内文字缩放（相对默认字体） */
    private static final float BTN_TEXT_SCALE = 0.7f;
    /** 开关未激活时的淡灰色 */
    private static final int SWITCH_OFF = 0xFFB9AAB0;
    private static final List<String> ASPECT_OPTIONS = List.of("20:9", "9:16", "4:3");

    /** 滚动偏移（像素） */
    private int scrollOffset = 0;
    /** 是否正在拖动屏幕大小滑块 */
    private boolean sizeDragging = false;

    private static int navH() {
        return Minecraft.getInstance().font.lineHeight + 8;
    }

    /** 滚轮下滑底部余量：略高于底部导航栏，让被导航栏盖住的内容能滑出来 */
    private static int scrollPad() {
        return PhoneOverlay.getBottomNavHeight() + 30;
    }

    public SettingsPage() {
    }

    // ==================== 布局 ====================

    private static final class Layout {
        int navH;
        int sosY, sosH;
        int msgTitleY, msgRectY, msgRectH;
        int[] msgRow = new int[1];
        int animTitleY, animRectY, animRectH;
        int[] animRow = new int[3];
        int colorTitleY, colorRectY, colorRectH;
        int[] colorRow = new int[2];
        int dispTitleY, dispRectY, dispRectH;
        int[] dispRow = new int[2];
        int contentH;
    }

    private Layout computeLayout() {
        Font font = Minecraft.getInstance().font;
        Layout L = new Layout();
        L.navH = font.lineHeight + 8;
        int y = L.navH + 8;
        // 分组标题按 0.75 倍缩放后的文字高度
        int titleH = (int) (font.lineHeight * GROUP_SCALE);

        // SOS 胶囊（页面顶部首位）
        L.sosY = y;
        L.sosH = SOS_H;
        y += L.sosH + GROUP_TITLE_TOP;

        // 消息 分组（1 行）：标题在上，卡片在标题下方留出间距
        L.msgTitleY = y;
        y += titleH + CARD_GAP;
        L.msgRow[0] = y;
        y += ROW_H;
        L.msgRectY = L.msgRow[0] - RECT_PAD;
        L.msgRectH = (L.msgRow[0] + ROW_H) - L.msgRectY + RECT_PAD;
        y = L.msgRow[0] + ROW_H + RECT_PAD;
        y += GROUP_TITLE_TOP;

        // 动画 分组（3 行）
        L.animTitleY = y;
        y += titleH + CARD_GAP;
        for (int i = 0; i < 3; i++) {
            L.animRow[i] = y;
            y += ROW_H;
        }
        L.animRectY = L.animRow[0] - RECT_PAD;
        L.animRectH = (L.animRow[2] + ROW_H) - L.animRectY + RECT_PAD;
        y = L.animRow[2] + ROW_H + RECT_PAD;
        y += GROUP_TITLE_TOP;

        // 配色 分组（2 行）
        L.colorTitleY = y;
        y += titleH + CARD_GAP;
        for (int i = 0; i < 2; i++) {
            L.colorRow[i] = y;
            y += ROW_H;
        }
        L.colorRectY = L.colorRow[0] - RECT_PAD;
        L.colorRectH = (L.colorRow[1] + ROW_H) - L.colorRectY + RECT_PAD;
        y = L.colorRow[1] + ROW_H + RECT_PAD;
        y += GROUP_TITLE_TOP;

        // 显示 分组（2 行）
        L.dispTitleY = y;
        y += titleH + CARD_GAP;
        for (int i = 0; i < 2; i++) {
            L.dispRow[i] = y;
            y += ROW_H;
        }
        L.dispRectY = L.dispRow[0] - RECT_PAD;
        L.dispRectH = (L.dispRow[1] + ROW_H) - L.dispRectY + RECT_PAD;
        y = L.dispRow[1] + ROW_H + RECT_PAD;
        y += 4;

        L.contentH = y - (L.navH + 8) + 4;
        return L;
    }

    private int ly(Layout L, int baseY, int logicalY) {
        return baseY + logicalY - scrollOffset;
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics g, int baseX, int baseY, float partialTick) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        Font font = Minecraft.getInstance().font;
        Layout L = computeLayout();

        g.fill(baseX, baseY, baseX + w, baseY + h, PAGE_BG);

        // 顶栏 #de7e7e：标题「HuhuTalk设置」左对齐、垂直居中
        g.fill(baseX, baseY, baseX + w, baseY + L.navH, TOPBAR_BG);
        g.drawString(font, "HuhuTalk设置", baseX + 10, baseY + (L.navH - font.lineHeight) / 2 + 1,
                0xFFFFFFFF, false);

        int topY = baseY + L.navH + 4;
        int bottomY = baseY + h - PhoneOverlay.getBottomNavHeight() - 4;
        int viewH = Math.max(0, bottomY - topY);
        int maxScroll = Math.max(0, L.contentH + scrollPad() - viewH);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        g.enableScissor(baseX, topY, baseX + w, bottomY);

        renderSosButton(g, font, L, baseX, baseY);
        renderMsgGroup(g, font, L, baseX, baseY);
        renderAnimGroup(g, font, L, baseX, baseY);
        renderColorGroup(g, font, L, baseX, baseY);
        renderDisplayGroup(g, font, L, baseX, baseY);

        g.disableScissor();
    }

    /** SOS 紧急呼叫胶囊（页面顶部首位，红底白字） */
    private void renderSosButton(GuiGraphics g, Font font, Layout L, int baseX, int baseY) {
        ColorScheme scheme = ThemeManager.getActive();
        int x = baseX + 10;
        int y = ly(L, baseY, L.sosY);
        int w = PhoneOverlay.getPhoneWidth() - 20;
        UiShapes.fillRoundedRect(g, x, y, w, L.sosH, L.sosH / 2, scheme.sosBg());
        g.drawString(font, "🚨 SOS 紧急呼叫", x + 12, y + (L.sosH - font.lineHeight) / 2 + 1, scheme.sosText(), false);
    }

    private void renderMsgGroup(GuiGraphics g, Font font, Layout L, int baseX, int baseY) {
        int rectX = baseX + 10;
        int rectW = PhoneOverlay.getPhoneWidth() - 20;
        drawScaledText(g, font, "消息", baseX + 12, ly(L, baseY, L.msgTitleY), GROUP_TITLE_COLOR, GROUP_SCALE);
        UiShapes.fillRoundedRect(g, rectX, ly(L, baseY, L.msgRectY), rectW, L.msgRectH, CARD_R, CARD_BG);
        // 消息免打扰
        int rowY = ly(L, baseY, L.msgRow[0]);
        drawToggleRow(g, font, rectX, rectW, rowY, "消息免打扰", HuhuTalkClientSettings.isDndEnabled());
    }

    private void renderAnimGroup(GuiGraphics g, Font font, Layout L, int baseX, int baseY) {
        int rectX = baseX + 10;
        int rectW = PhoneOverlay.getPhoneWidth() - 20;
        drawScaledText(g, font, "动画", baseX + 12, ly(L, baseY, L.animTitleY), GROUP_TITLE_COLOR, GROUP_SCALE);
        UiShapes.fillRoundedRect(g, rectX, ly(L, baseY, L.animRectY), rectW, L.animRectH, CARD_R, CARD_BG);
        // 消息动画
        int y0 = ly(L, baseY, L.animRow[0]);
        drawToggleRow(g, font, rectX, rectW, y0, "消息动画", HuhuTalkClientSettings.isMessageAnimationEnabled());
        drawDivider(g, rectX, rectW, y0 + ROW_H);
        // 每字耗时
        int y1 = ly(L, baseY, L.animRow[1]);
        drawStepperRow(g, font, rectX, rectW, y1, "每次耗时(ms)", HuhuTalkClientSettings.getCharTypingMs());
        drawDivider(g, rectX, rectW, y1 + ROW_H);
        // 单句上限
        int y2 = ly(L, baseY, L.animRow[2]);
        drawStepperRow(g, font, rectX, rectW, y2, "单句上限(ms)", HuhuTalkClientSettings.getMaxTypingMs());
    }

    private void renderColorGroup(GuiGraphics g, Font font, Layout L, int baseX, int baseY) {
        int rectX = baseX + 10;
        int rectW = PhoneOverlay.getPhoneWidth() - 20;
        drawScaledText(g, font, "配色", baseX + 12, ly(L, baseY, L.colorTitleY), GROUP_TITLE_COLOR, GROUP_SCALE);
        UiShapes.fillRoundedRect(g, rectX, ly(L, baseY, L.colorRectY), rectW, L.colorRectH, CARD_R, CARD_BG);
        // 配色方案
        int y0 = ly(L, baseY, L.colorRow[0]);
        drawButtonRow(g, font, rectX, rectW, y0, "配色方案：" + ThemeManager.getActive().displayName(), "切换");
        drawDivider(g, rectX, rectW, y0 + ROW_H);
        // 手机壳
        int y1 = ly(L, baseY, L.colorRow[1]);
        String caseText = HuhuTalkClientSettings.getPhoneCaseBlock();
        String caseDisplay = caseText.isBlank() ? "手机壳：默认"
                : "手机壳：" + caseText.substring(caseText.indexOf(':') + 1);
        drawButtonRow(g, font, rectX, rectW, y1, caseDisplay, "选壳");
    }

    private void renderDisplayGroup(GuiGraphics g, Font font, Layout L, int baseX, int baseY) {
        int rectX = baseX + 10;
        int rectW = PhoneOverlay.getPhoneWidth() - 20;
        drawScaledText(g, font, "显示", baseX + 12, ly(L, baseY, L.dispTitleY), GROUP_TITLE_COLOR, GROUP_SCALE);
        UiShapes.fillRoundedRect(g, rectX, ly(L, baseY, L.dispRectY), rectW, L.dispRectH, CARD_R, CARD_BG);
        // 屏幕比例
        int y0 = ly(L, baseY, L.dispRow[0]);
        drawButtonRow(g, font, rectX, rectW, y0, "屏幕比例：" + HuhuTalkClientSettings.getPhoneAspect(),
                "切换");
        drawDivider(g, rectX, rectW, y0 + ROW_H);
        // 屏幕大小（滑块）
        int y1 = ly(L, baseY, L.dispRow[1]);
        drawSizeRow(g, font, rectX, rectW, y1);
        if (sizeDragging) {
            Minecraft mc = Minecraft.getInstance();
            double mx = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
            updateSizeFromRelX(mx - baseX, rectW);
        }
    }

    // ==================== 控件绘制 ====================

    private void drawToggleRow(GuiGraphics g, Font font, int rectX, int rectW, int y,
                               String label, boolean on) {
        g.drawString(font, label, rectX + 12, y + (ROW_H - font.lineHeight) / 2 + 1, TEXT_COLOR, false);
        // 胶囊开关（右对齐）：激活 #de7e7e，未激活淡灰
        int swH = contH(font);
        int swW = swH * 2;
        int swX = rectX + rectW - swW - 10;
        int swY = y + (ROW_H - swH) / 2;
        UiShapes.fillRoundedRect(g, swX, swY, swW, swH, swH / 2, on ? BTN_COLOR : SWITCH_OFF);
        int knob = on ? swX + swW - swH + 1 : swX + 1;
        UiShapes.fillRoundedRect(g, knob, swY + 1, swH - 2, swH - 2, (swH - 2) / 2, 0xFFFFFFFF);
    }

    private void drawStepperRow(GuiGraphics g, Font font, int rectX, int rectW, int y,
                                String label, long value) {
        g.drawString(font, label + "：" + value, rectX + 12, y + (ROW_H - font.lineHeight) / 2 + 1, TEXT_COLOR, false);
        // 竖排上下箭头（▲ 增 / ▼ 减），每个 0.5 倍字体大小，#de7e7e
        int btn = contH(font);
        int gap = 2;
        int right = rectX + rectW - btn - 10;
        int topY = y + (ROW_H - (btn * 2 + gap)) / 2;
        arrowBtn(g, font, right, topY, btn, "▲");
        arrowBtn(g, font, right, topY + btn + gap, btn, "▼");
    }

    private void drawButtonRow(GuiGraphics g, Font font, int rectX, int rectW, int y,
                               String label, String btnText) {
        g.drawString(font, label, rectX + 12, y + (ROW_H - font.lineHeight) / 2 + 1, TEXT_COLOR, false);
        int btnH = contH(font);
        int bw = btnW(font, btnText);
        int btnX = rectX + rectW - bw - 10;
        int btnY = y + (ROW_H - btnH) / 2;
        capsuleBtn(g, font, btnX, btnY, bw, btnH, btnText, BTN_COLOR);
    }

    private void drawSizeRow(GuiGraphics g, Font font, int rectX, int rectW, int y) {
        float size = HuhuTalkClientSettings.getPhoneSize();
        g.drawString(font, "屏幕大小：" + Math.round(size * 100) + "%",
                rectX + 12, y + 6, TEXT_COLOR, false);
        // 横向滑块轨道
        int trackLeft = rectX + 12;
        int trackRight = rectX + rectW - 12;
        int trackY = y + ROW_H - 12;
        UiShapes.fillRoundedRect(g, trackLeft, trackY, trackRight - trackLeft, 3, 1, 0xFFAAAAAA);
        int handleX = trackLeft + (int) ((size - 0.5f) / 0.5f * (trackRight - trackLeft));
        UiShapes.fillRoundedRect(g, handleX - 5, trackY - 3, 10, 9, 4, 0xFFDE7E7E);
    }

    /** 按钮高度 = 0.9 倍默认字体大小 */
    private static int contH(Font font) {
        return Math.max(1, (int) (font.lineHeight * 0.9f));
    }

    /** 按钮宽度：正好包裹文字（文字缩放后宽度 + 两侧留白） */
    private static int btnW(Font font, String text) {
        return (int) (font.width(text) * BTN_TEXT_SCALE) + 10;
    }

    /** 竖排箭头（每个 0.5 倍字体大小，只保留箭头本身、去掉背景包裹，取色 #de7e7e） */
    private void arrowBtn(GuiGraphics g, Font font, int x, int y, int size, String text) {
        float s = 0.5f;
        drawScaledText(g, font, text, x + (size - font.width(text) * s) / 2f,
                y + (size - font.lineHeight * s) / 2f, BTN_COLOR, s);
    }

    private void capsuleBtn(GuiGraphics g, Font font, int x, int y, int w, int h, String text, int bg) {
        UiShapes.fillRoundedRect(g, x, y, w, h, h / 2, bg);
        float s = BTN_TEXT_SCALE;
        drawScaledText(g, font, text, x + (w - font.width(text) * s) / 2f,
                y + (h - font.lineHeight * s) / 2f, 0xFFFFFFFF, s);
    }

    /** 以任意倍率绘制文本（无阴影） */
    private static void drawScaledText(GuiGraphics g, Font font, String text, float x, float y,
                                       int color, float scale) {
        float inv = 1.0f / scale;
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(font, text, x * inv, y * inv, color, false);
        g.pose().popPose();
    }

    private static void drawDivider(GuiGraphics g, int rectX, int rectW, int y) {
        g.fill(rectX + 8, y, rectX + rectW - 8, y + 1, DIVIDER);
    }

    // ==================== 交互 ====================

    private void cycleAspect() {
        String cur = HuhuTalkClientSettings.getPhoneAspect();
        int idx = ASPECT_OPTIONS.indexOf(cur);
        int next = (idx + 1) % ASPECT_OPTIONS.size();
        HuhuTalkClientSettings.setPhoneAspect(ASPECT_OPTIONS.get(idx < 0 ? 0 : next));
    }

    private void updateSizeFromRelX(double relX, int w) {
        float f = 0.5f + (float) ((relX - 12) / (double) (w - 24)) * 0.5f;
        HuhuTalkClientSettings.setPhoneSizeRaw(f);
    }

    private static boolean hit(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x <= rx + rw && y >= ry && y <= ry + rh;
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
        int rectX = 10;
        int rectW = w - 20;
        Layout L = computeLayout();
        double ly = relY + scrollOffset;

        // SOS 紧急呼叫（页面顶部首位）
        if (relY >= L.sosY && relY <= L.sosY + L.sosH) {
            PacketDistributor.sendToServer(new C2SSOSPacket());
            return true;
        }

        // 消息：免打扰
        if (hit(relX, ly, rectX, L.msgRow[0], rectW, ROW_H)) {
            HuhuTalkClientSettings.setDndEnabled(!HuhuTalkClientSettings.isDndEnabled());
            return true;
        }

        // 动画：消息动画开关
        if (hit(relX, ly, rectX, L.animRow[0], rectW, ROW_H)) {
            HuhuTalkClientSettings.setMessageAnimation(!HuhuTalkClientSettings.isMessageAnimationEnabled());
            return true;
        }
        // 动画：每字耗时 stepper（▲ 增 / ▼ 减）
        int charStep = hitStepper(relX, ly, L.animRow[1], rectW);
        if (charStep != 0) {
            long v = HuhuTalkClientSettings.getCharTypingMs();
            if (charStep == 1) {
                HuhuTalkClientSettings.setCharTypingMs(v + 50);
            } else {
                HuhuTalkClientSettings.setCharTypingMs(Math.max(50, v - 50));
            }
            return true;
        }
        // 动画：单句上限 stepper（▲ 增 / ▼ 减）
        int maxStep = hitStepper(relX, ly, L.animRow[2], rectW);
        if (maxStep != 0) {
            long v = HuhuTalkClientSettings.getMaxTypingMs();
            if (maxStep == 1) {
                HuhuTalkClientSettings.setMaxTypingMs(v + 100);
            } else {
                HuhuTalkClientSettings.setMaxTypingMs(Math.max(100, v - 100));
            }
            return true;
        }

        // 配色：配色方案切换
        if (hitButton(relX, ly, L.colorRow[0], rectW, "切换")) {
            ThemeManager.cycleNext();
            return true;
        }
        // 配色：手机壳
        if (hitButton(relX, ly, L.colorRow[1], rectW, "选壳")) {
            PhoneOverlay.pushPage(new PhoneCasePickerPage());
            return true;
        }

        // 显示：屏幕比例
        if (hitButton(relX, ly, L.dispRow[0], rectW, "切换")) {
            cycleAspect();
            return true;
        }
        // 显示：屏幕大小滑块（按下开始拖动）
        if (hit(relX, ly, rectX, L.dispRow[1], rectW, ROW_H)) {
            sizeDragging = true;
            updateSizeFromRelX(relX, rectW);
            return true;
        }
        return false;
    }

    /** 命中 stepper 行竖排箭头：返回 1=▲增、2=▼减、0=未命中 */
    private int hitStepper(double x, double y, int rowY, int rectW) {
        Font font = Minecraft.getInstance().font;
        int btn = contH(font);
        int gap = 2;
        int right = 10 + rectW - btn - 10;
        int topY = rowY + (ROW_H - (btn * 2 + gap)) / 2;
        if (hit(x, y, right, topY, btn, btn)) return 1;
        if (hit(x, y, right, topY + btn + gap, btn, btn)) return 2;
        return 0;
    }

    /** 命中单按钮行（切换/选壳）的按钮区域 */
    private boolean hitButton(double x, double y, int rowY, int rectW, String btnText) {
        Font font = Minecraft.getInstance().font;
        int btnH = contH(font);
        int bw = btnW(font, btnText);
        int btnX = 10 + rectW - bw - 10;
        int btnY = rowY + (ROW_H - btnH) / 2;
        return hit(x, y, btnX, btnY, bw, btnH);
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        Layout L = computeLayout();
        int viewH = Math.max(0, PhoneOverlay.getPhoneHeight() - L.navH);
        int maxScroll = Math.max(0, L.contentH + scrollPad() - viewH);
        scrollOffset = Math.max(0, Math.min(scrollOffset - (int) (scrollDelta * 20), maxScroll));
        return true;
    }
}
