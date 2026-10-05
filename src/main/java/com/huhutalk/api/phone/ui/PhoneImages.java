package com.huhutalk.api.phone.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 手机页面里画图片的公开工具——头像、照片、图标、贴图等。
 *
 * <h2>统一用 {@link ResourceLocation} 作图片句柄</h2>
 * 本模组里所有"运行期产生的图"已经统一成这个形式：
 * <ul>
 *   <li>女仆头像：{@code MaidAvatarManager.getOrLoadTexture(stableId)} / {@code getTextureSize(stableId)}</li>
 *   <li>图库照片：{@code PhotoLibrary.getOrLoadTexture(key)} / {@code getTextureSize(key)}</li>
 *   <li>玩家皮肤：{@code ((AbstractClientPlayer) player).getSkin().texture()}，头颅正面是 8×8 子区域</li>
 *   <li>资源包里的图：{@code ResourceLocation.fromNamespaceAndPath("mymod", "textures/icon/x.png")}</li>
 * </ul>
 * 所以这里只提供「贴图 → 矩形」的四种画法，不引入第二套图片类型。
 *
 * <h2>四种画法怎么选</h2>
 * <table border="1">
 *   <tr><td>{@link #draw}</td><td>拉伸铺满矩形（会变形）</td></tr>
 *   <tr><td>{@link #drawFit}</td><td>按宽高比缩放到<b>完整可见</b>，四周留白（contain）</td></tr>
 *   <tr><td>{@link #drawCover}</td><td>按宽高比铺满矩形并<b>居中裁切</b>（cover）——非正方形照片做方头像用这个，不变形</td></tr>
 *   <tr><td>{@link #drawRegion}</td><td>只画贴图的一个子区域（皮肤头颅、雪碧图、九宫格）</td></tr>
 * </table>
 *
 * <h2>明确没做</h2>
 * <b>圆角图片</b>。手机 UI 到处是圆角，但现有 {@code UiShapes} 只能填纯色矩形/圆角矩形，
 * 圆角贴图需要遮罩或自定义着色器，是另一件事。需要时单独提。
 *
 * <pre>{@code
 * // 女仆头像画成 30×30 圆形位置的方块（cover 不变形）
 * ResourceLocation tex = MaidAvatarManager.getOrLoadTexture(stableId);
 * int[] size = MaidAvatarManager.getTextureSize(stableId);
 * if (tex != null && size != null) {
 *     PhoneImages.drawCover(g, tex, x, y, 30, 30, size[0], size[1]);
 * }
 *
 * // 玩家皮肤的头颅正面（64×64 贴图里的 8×8 区域）
 * PhoneImages.drawRegion(g, skin, x, y, 30, 30, 8, 8, 8, 8, 64, 64);
 * }</pre>
 */
public final class PhoneImages {

    private PhoneImages() {
    }

    /**
     * 把整张贴图<b>拉伸</b>铺满矩形（宽高比不一致时会变形）。
     * 确定尺寸天生匹配时用它最省事，否则优先 {@link #drawFit} 或 {@link #drawCover}。
     */
    public static void draw(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h,
                            int srcW, int srcH) {
        drawRegion(g, tex, x, y, w, h, 0, 0, srcW, srcH, srcW, srcH);
    }

    /**
     * 按宽高比缩放，保证整张图<b>完整可见</b>，多余方向居中留白（contain）。
     * 适合"内容不能被裁掉"的图，例如照片预览、说明插图。
     */
    public static void drawFit(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h,
                               int srcW, int srcH) {
        if (invalid(tex, w, h, srcW, srcH)) {
            return;
        }
        float scale = Math.min((float) w / srcW, (float) h / srcH);
        int dw = Math.max(1, Math.round(srcW * scale));
        int dh = Math.max(1, Math.round(srcH * scale));
        drawRegion(g, tex, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, 0, 0, srcW, srcH, srcW, srcH);
    }

    /**
     * 按宽高比铺满矩形，超出的部分<b>居中裁切</b>（cover）。
     * <p>
     * 头像的标准画法：正方形头像框 + 任意比例的照片，用它会自动裁掉多余部分而<b>不拉伸变形</b>。
     */
    public static void drawCover(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h,
                                 int srcW, int srcH) {
        if (invalid(tex, w, h, srcW, srcH)) {
            return;
        }
        float destAspect = (float) w / h;
        float srcAspect = (float) srcW / srcH;
        int u = 0, v = 0, uW = srcW, vH = srcH;
        if (srcAspect > destAspect) {
            // 源更宽 → 左右各裁掉一部分
            uW = Math.max(1, Math.round(srcH * destAspect));
            u = (srcW - uW) / 2;
        } else if (srcAspect < destAspect) {
            // 源更高 → 上下各裁掉一部分
            vH = Math.max(1, Math.round(srcW / destAspect));
            v = (srcH - vH) / 2;
        }
        drawRegion(g, tex, x, y, w, h, u, v, uW, vH, srcW, srcH);
    }

    /**
     * 画贴图的一个子区域，缩放铺满目标矩形。
     *
     * @param u,v    子区域在贴图里的左上角（像素）
     * @param uW,vH  子区域尺寸（像素）
     * @param texW,texH 整张贴图的尺寸（像素）
     */
    public static void drawRegion(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h,
                                  int u, int v, int uW, int vH, int texW, int texH) {
        if (invalid(tex, w, h, uW, vH) || texW <= 0 || texH <= 0) {
            return;
        }
        g.blit(tex, x, y, w, h, (float) u, (float) v, uW, vH, texW, texH);
    }

    /** 参数是否非法：贴图为空、或目标矩形/源区域尺寸非正。 */
    private static boolean invalid(ResourceLocation tex, int w, int h, int srcW, int srcH) {
        return tex == null || w <= 0 || h <= 0 || srcW <= 0 || srcH <= 0;
    }
}
