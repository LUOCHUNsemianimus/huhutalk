package com.huhutalk.phone.pages;

import com.huhutalk.api.phone.ui.PhoneImages;
import com.huhutalk.api.phone.ui.PhonePage;
import com.huhutalk.api.phone.ui.PhoneText;
import com.huhutalk.client.PhotoLibrary;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 图库的「阅览页」：一张大图 + 右上角那颗小小的删除。
 *
 * <h2>为什么单独开一页而不是在网格页里切状态</h2>
 * <ul>
 *   <li>右键返回是现成的（{@code PhonePage} 的返回键 + {@code PhoneOverlay.popPage}），
 *       返回时下层网格页会收到 {@code onOpen()}，<b>自动刷新列表</b>——
 *       在阅览页删掉照片，退回网格看到的就已经是删过的了，不用手写通知；</li>
 *   <li>阅览页的顶栏、返回键、裁剪全部由 {@code PhonePage} 提供，这里只写"画图 + 删除"。</li>
 * </ul>
 *
 * <h2>删除后为什么直接返回</h2>
 * 删掉的就是当前这张，留在阅览页没有东西可看。所以删完 {@code popPage()} 回网格。
 */
public class GalleryViewerPage extends PhonePage {

    /** 阅览区四周留白（逻辑像素） */
    private static final int PAD = 6;

    /** 要看的照片文件名（来自 {@link PhotoLibrary#listGallery()}） */
    private final String fileName;

    public GalleryViewerPage(String fileName) {
        this.fileName = fileName;
    }

    @Override
    protected String title() {
        return PhoneText.tr("gui.huhutalk.gallery.viewer_title");
    }

    /** 阅览页用深底：照片是主角，浅色底会把它压灰 */
    @Override
    protected int pageBackground() {
        return 0xFF101010;
    }

    /** 顶栏与网格页同一个品牌粉（深底内容 + 品牌顶栏，和内置页面观感一致） */
    @Override
    protected int topBarBackground() {
        return BRAND_TOPBAR_BG;
    }

    @Override
    protected void renderContent(GuiGraphics g, int ox, int oy, int w, int h, float partialTick) {
        ResourceLocation tex = PhotoLibrary.getOrLoadTexture(fileName);
        if (tex == null) {
            PhoneText.drawLeftIn(g, font(), PhoneText.tr("gui.huhutalk.gallery.gone"),
                    ox, oy + PhoneText.px(10), w, PhoneText.px(16), PhoneText.px(10),
                    0xFFB0BEC5, PhoneText.scale(0.9f));
            return;
        }
        // getTextureSize 永不返回 null（取不到时兜底 24×24）
        int[] d = PhotoLibrary.getTextureSize(fileName);
        int pad = PhoneText.px(PAD);
        // drawFit（contain）：整张图完整可见、四周留白，不裁也不拉伸 ——
        // 阅览就是要"看全"，这与网格里用 cover 的取舍正好相反。
        PhoneImages.drawFit(g, tex, ox + pad, oy + pad, w - pad * 2, h - pad * 2, d[0], d[1]);
    }

    @Override
    protected void renderTopBarExtras(GuiGraphics graphics, int baseX, int baseY, int w, int barH, int textY) {
        GalleryDeleteButton.render(graphics, font(), baseX, baseY, w, barH);
    }

    @Override
    protected boolean onTopBarClick(double relX, double relY, int button, int action) {
        if (GalleryDeleteButton.hit(font(), width(), topBarHeight(), relX, relY, button, action)) {
            PhotoLibrary.deleteGallery(fileName);
            PhoneOverlay.showBubble(PhoneText.tr("gui.huhutalk.gallery.deleted_one"));
            PhoneOverlay.popPage();   // 删的就是当前这张，没有东西可看了
            return true;
        }
        return false;
    }
}
