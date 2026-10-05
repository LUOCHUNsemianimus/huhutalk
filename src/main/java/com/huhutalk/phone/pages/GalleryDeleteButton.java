package com.huhutalk.phone.pages;

import com.huhutalk.api.phone.ui.PhoneText;
import com.huhutalk.phone.UiFont;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

/**
 * 图库页面右上角那颗「小小的删除」。
 * <p>
 * 抽出来是因为它有<b>两个使用点</b>（网格页的批量删除、阅览页的单张删除），
 * 而"渲染位置"与"命中位置"只要各写一遍就一定会脱节 —— 本类的
 * {@link #render} 与 {@link #hit} 共用同一个 {@link #rect}，那是唯一的位置真相。
 * <p>
 * 配色沿用聊天内图库页的删除红（{@code #E53935}，见 {@code ChatPage} 的删除小叉）。
 */
final class GalleryDeleteButton {

    /** 文案 */
    static final String LABEL = "删除";
    /** 删除红（与聊天内图库页的删除小叉同色） */
    static final int COLOR = 0xFFE53935;

    private GalleryDeleteButton() {
    }

    /** 文字相对倍率：小一号，不抢标题 */
    private static float scale() {
        return UiFont.sc(0.75f);
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
        PhoneText.draw(g, font, LABEL,
                baseX + r[0] + r[4], baseY + r[1] + r[5], COLOR, scale());
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
