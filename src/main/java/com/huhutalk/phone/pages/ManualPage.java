package com.huhutalk.phone.pages;

import com.huhutalk.client.PhotoLibrary;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.phone.UiFont;
import com.huhutalk.phone.UiShapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 手机内置手册 / 设置页：条目列表 → 点开条目查看详细内容（可滚动文本 + 可选图片）。
 * <p>
 * 内容以 {@link ManualEntry} 定义在代码里；图片 PNG 放 {@code gameDir/huhutalk/manual/}，
 * 条目通过文件名（{@code imageKey}）引用。图片由 {@link PhotoLibrary} 加载渲染。
 */
public class ManualPage implements IPhonePage {

    /** 一条手册条目：标题 + 正文（支持 \n 换行、按宽度自动折行）+ 可选图片文件名 */
    public record ManualEntry(String title, String body, String imageKey) {}

    private final List<ManualEntry> entries = new ArrayList<>();
    /** 当前打开的条目；null = 处于列表视图 */
    private ManualEntry current = null;
    private float scroll = 0f;

    public ManualPage() {
        entries.add(new ManualEntry("关于 HuhuTalk",
                "HuhuTalk（狐狐通讯）是狐狐女仆的专属手机通讯模组，灵感来自碧蓝档案的桃信（MomoTalk）。\n"
                        + "\n本机内置说明手册，由狐狐随身携带（笑）。这里是占位说明，正式内容稍后补全。",
                null));
        entries.add(new ManualEntry("如何使用手机",
                "• 右键 / 按键打开手机\n"
                        + "• 拖动顶部状态栏可移动手机\n"
                        + "• 点击右上角 X 关闭手机\n"
                        + "\n更多帮助稍后补充。",
                null));
        entries.add(new ManualEntry("带图片的条目",
                "这个条目演示如何在说明里插入一张图片。把 PNG 放到 run/huhutalk/manual/ 目录，"
                        + "并把文件名填到该条目的 imageKey 即可。",
                "demo.png"));
    }

    @Override
    public void render(GuiGraphics g, int baseX, int baseY, float partialTick) {
        if (current == null) {
            renderList(g, baseX, baseY);
        } else {
            renderDetail(g, baseX, baseY);
        }
    }

    // ==================== 列表视图 ====================

    private void renderList(GuiGraphics g, int baseX, int baseY) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        Font font = Minecraft.getInstance().font;

        int headerH = UiFont.sh(22);
        g.fill(baseX, baseY, baseX + w, baseY + headerH, 0xFFF06292);
        // 左键返回（回桌面）+ 标题
        drawScaledText(g, font, "‹ 返回", baseX + UiFont.sh(8), baseY + (headerH - lineH(font)) / 2,
                0xFFFFFFFF, UiFont.sc(1.0f));
        int backW = (int) (font.width("‹ 返回") * UiFont.sc(1.0f));
        drawScaledText(g, font, "设置", baseX + UiFont.sh(8) + backW + UiFont.sh(10),
                baseY + (headerH - lineH(font)) / 2, 0xFFFFFFFF, UiFont.sc(1.0f));

        int pad = UiFont.sh(8);
        int top = baseY + headerH + UiFont.sh(6);
        int rowH = UiFont.sh(34);
        int gap = UiFont.sh(4);
        int cy = top;
        for (ManualEntry e : entries) {
            if (cy + rowH > baseY + h - UiFont.sh(6)) break;
            UiShapes.fillRoundedRect(g, baseX + pad, cy, w - pad * 2, rowH, UiFont.sh(8), 0xFFFFFFFF);
            drawScaledText(g, font, "›  " + e.title(), baseX + pad + UiFont.sh(10),
                    cy + (rowH - lineH(font)) / 2, 0xFF333333, UiFont.sc(0.9f));
            cy += rowH + gap;
        }
    }

    // ==================== 详情视图 ====================

    private void renderDetail(GuiGraphics g, int baseX, int baseY) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        Font font = Minecraft.getInstance().font;

        // 顶栏：返回 + 标题
        int headerH = UiFont.sh(22);
        g.fill(baseX, baseY, baseX + w, baseY + headerH, 0xFFF06292);
        drawScaledText(g, font, "‹ 返回", baseX + UiFont.sh(8), baseY + (headerH - lineH(font)) / 2,
                0xFFFFFFFF, UiFont.sc(1.0f));
        int backW = (int) (font.width("‹ 返回") * UiFont.sc(1.0f));
        drawScaledText(g, font, current.title(), baseX + UiFont.sh(8) + backW + UiFont.sh(10),
                baseY + (headerH - lineH(font)) / 2, 0xFFFFFFFF, UiFont.sc(0.85f));

        int pad = UiFont.sh(8);
        int contentTop = baseY + headerH + UiFont.sh(4);
        int contentBottom = baseY + h - UiFont.sh(6);

        // 图片：若存在，固定在底部，等比缩放适配宽度
        int imgH = 0;
        if (current.imageKey() != null) {
            ResourceLocation imgTex = PhotoLibrary.getOrLoadTexture(current.imageKey());
            int[] dims = PhotoLibrary.getTextureSize(current.imageKey());
            if (imgTex != null) {
                int availW = w - pad * 2;
                int sw = Math.min(availW, dims[0]);
                int imgW = sw;
                imgH = Math.max(1, (int) (dims[1] * (sw / (float) Math.max(1, dims[0]))));
                g.blit(imgTex, baseX + pad, contentBottom - imgH, imgW, imgH,
                        0.0f, 0.0f, dims[0], dims[1], dims[0], dims[1]);
            }
        }

        // 正文区域（可滚动，文字位于图片上方）
        int bodyBottom = contentBottom - (imgH > 0 ? imgH + UiFont.sh(4) : 0);
        int bodyAreaH = bodyBottom - contentTop;

        List<FormattedCharSequence> lines = wrapBody(font, w - pad * 2);
        int lh = font.lineHeight;
        int totalH = lines.size() * lh;
        float maxScroll = Math.max(0, totalH - bodyAreaH);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        int textX = baseX + pad;
        int textY = contentTop - (int) scroll;
        for (FormattedCharSequence line : lines) {
            if (textY + lh >= contentTop && textY < bodyBottom) {
                g.drawString(font, line, textX, textY, 0xFF333333, false);
            }
            textY += lh;
        }
    }

    /** 换行：先按 \n 分段，再按宽度自动折行 */
    private List<FormattedCharSequence> wrapBody(Font font, int maxWidth) {
        List<FormattedCharSequence> out = new ArrayList<>();
        for (String para : current.body().split("\n")) {
            if (para.isBlank()) {
                out.add(Component.literal(" ").getVisualOrderText());
                continue;
            }
            out.addAll(font.split(Component.literal(para), maxWidth));
        }
        return out;
    }

    // ==================== 交互 ====================

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (action != GLFW.GLFW_PRESS) return false;

        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int headerH = UiFont.sh(22);

        if (current != null) {
            // 详情：点顶栏"返回"区域回到列表
            int backW = UiFont.sh(8) + (int) (Minecraft.getInstance().font.width("‹ 返回") * UiFont.sc(1.0f)) + UiFont.sh(8);
            if (relY <= headerH && relX <= backW) {
                current = null;
                scroll = 0;
                return true;
            }
            return false;
        }

        // 列表：先判断顶栏"返回"（弹回桌面），再判断条目
        int pad = UiFont.sh(8);
        int backW = UiFont.sh(8) + (int) (Minecraft.getInstance().font.width("‹ 返回") * UiFont.sc(1.0f)) + UiFont.sh(8);
        if (relY <= headerH && relX <= backW) {
            PhoneOverlay.popPage();
            return true;
        }
        int top = headerH + UiFont.sh(6);
        int rowH = UiFont.sh(34);
        int gap = UiFont.sh(4);
        int cy = top;
        for (ManualEntry e : entries) {
            if (cy + rowH > h - UiFont.sh(6)) break;
            if (relX >= pad && relX <= w - pad && relY >= cy && relY <= cy + rowH) {
                current = e;
                scroll = 0;
                return true;
            }
            cy += rowH + gap;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        if (current == null) return false;
        scroll += (float) (scrollDelta * UiFont.sh(10));
        return true; // 详情页滚轮调整滚动，越界由 clamp 处理
    }

    // ==================== 工具 ====================

    private static int lineH(Font font) {
        return font.lineHeight;
    }

    /** 以指定倍率绘制文本（无阴影） */
    private static void drawScaledText(GuiGraphics g, Font font, String text, float x, float y, int color, float scale) {
        float inv = 1.0f / scale;
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(font, text, x * inv, y * inv, color, false);
        g.pose().popPose();
    }
}
