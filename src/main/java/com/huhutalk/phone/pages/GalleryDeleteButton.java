package com.huhutalk.phone.pages;

import com.huhutalk.api.phone.ui.PhoneText;
import com.huhutalk.client.theme.ThemeManager;
import com.huhutalk.phone.UiFont;
import com.huhutalk.phone.UiShapes;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

/**
 * 图库页面右上角那颗「小小的删除」。
 * <p>
 * 抽出来是因为它有<b>两个使用点</b>（网格页的批量删除、阅览页的单张删除），
 * 而"渲染位置"与"命中位置"只要各写一遍就一定会脱节 —— 本类的
 * {@link #render} 与 {@link #hit} 共用同一个 {@link #rect}，那是唯一的位置真相。
 *
 * <h2>为什么是"暗红小胶囊"而不是"红色文字"</h2>
 * 顶栏用的是 HuhuTalk 品牌粉 {@code #DE7E7E}，亮红文字压在上面只有约 <b>1.7:1</b> 的对比度，
 * 基本看不清。改成暗红底 {@code dangerBg}({@code #B71C1C}) + 白字后是约 <b>6.6:1</b>，
 * 同时"实心胶囊"天然说明它是可点的按钮，也跟手机里其它按钮的语言一致。
 * 底色走主题的 {@code dangerBg}，所以以后加配色方案会自动跟着换。
 */
final class GalleryDeleteButton {

    /** 文案 */
    static final String LABEL = "删除";
    /** 文字色：白（压在暗红胶囊上） */
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private GalleryDeleteButton() {
    }

    /** 文字相对倍率：小一号，不抢标题 */
    private static float scale() {
        return UiFont.sc(0.75f);
    }

    /** 胶囊底色：主题的危险色（暗红） */
    private static int bg() {
        return ThemeManager.getActive().dangerBg();
    }

    /**
     * 按钮矩形（顶栏相对坐标）。
     *
     * @return {x, y, w, h, textInsetX, textInsetY}
     */
    private static int[] rect(Font font, int barW, int barH) {
        int tw = PhoneText.width(font, LABEL, scale());
        int th = PhoneText.height(font, scale());
        int padX = UiFont.sh(5);
        int padY = UiFont.sh(3);
        int w = tw + padX * 2;
        int h = th + padY * 2;
        int x = barW - UiFont.sh(6) - w;
        int y = (barH - h) / 2;
        return new int[]{x, y, w, h, padX, padY};
    }

    /** 画在顶栏右侧。 */
    static void render(GuiGraphics g, Font font, int baseX, int baseY, int barW, int barH) {
        int[] r = rect(font, barW, barH);
        UiShapes.fillRoundedRect(g, baseX + r[0], baseY + r[1], r[2], r[3], r[3] / 2, bg());
        PhoneText.draw(g, font, LABEL,
                baseX + r[0] + r[4], baseY + r[1] + r[5], TEXT_COLOR, scale());
    }

    /**
     * 是否点在这颗按钮上（坐标与 {@code IPhonePage.mouseClicked} 一致：手机内容区相对坐标）。
     * <p>
     * 命中区比文字略大（含内边距），因为它是"小小的删除"——文字本身太细，不给点余量会很难戳中。
     */
    static boolean hit(Font font, int barW, int barH, double relX, double relY, int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || action != GLFW.GLFW_PRESS) {
            return false;
        }
        int[] r = rect(font, barW, barH);
        return relX >= r[0] && relX <= r[0] + r[2] && relY >= r[1] && relY <= r[1] + r[3];
    }
}
