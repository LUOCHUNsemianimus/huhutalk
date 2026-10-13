package com.huhutalk.phone;

import com.huhutalk.api.phone.ui.PhoneShapes;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * 手机 UI 共享绘制工具 —— <b>已迁移</b>，本类只是兼容转发壳。
 *
 * @deprecated 实现已经搬到公开包 {@link PhoneShapes}（{@code com.huhutalk.api.phone.ui}）。
 *         原因：整条公开工具链（{@code PhoneRow} / {@code PhoneDropdown} / {@code PhoneSwitch} /
 *         {@code PhoneSlider} / {@code PhoneTextInput}）都在用它，但"只用 {@code api/phone/ui}
 *         写页面"这条标准在圆角矩形上做不到 —— 必须去 import 一个非公开类。
 *         <p>
 *         本壳保留是为了让既有页面<b>一行都不用改</b>；新代码请直接用 {@link PhoneShapes}，
 *         老页面可以之后再慢慢迁（迁移是纯改名：{@code UiShapes} → {@code PhoneShapes}）。
 */
@Deprecated
public final class UiShapes {

    private UiShapes() {
    }

    /** 转发到 {@link PhoneShapes#fillRoundedRect}。 */
    public static void fillRoundedRect(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        PhoneShapes.fillRoundedRect(g, x, y, w, h, r, color);
    }

    /** 转发到 {@link PhoneShapes#fillRoundedRectFrame}。 */
    public static void fillRoundedRectFrame(GuiGraphics g, int x, int y, int w, int h,
                                            int holeX, int holeY, int holeW, int holeH,
                                            int r, int color) {
        PhoneShapes.fillRoundedRectFrame(g, x, y, w, h, holeX, holeY, holeW, holeH, r, color);
    }

    /** 转发到 {@link PhoneShapes#fillRoundedRectFrameTiled}。 */
    public static void fillRoundedRectFrameTiled(GuiGraphics g, TextureAtlasSprite sprite,
                                                 int x, int y, int w, int h,
                                                 int holeX, int holeY, int holeW, int holeH,
                                                 int r) {
        PhoneShapes.fillRoundedRectFrameTiled(g, sprite, x, y, w, h, holeX, holeY, holeW, holeH, r);
    }
}
