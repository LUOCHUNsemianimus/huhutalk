package com.huhutalk.phone;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * 手机 UI 共享绘制工具（纯渲染，不影响逻辑）。
 * <p>
 * 提供圆角矩形/胶囊的平滑填充：圆角矩形是凸多边形，用"中心点 + 外轮廓"三角扇形
 * （TRIANGLE_FAN）一次交给 GPU 栅格化，边缘平滑、四角对称，与气泡/卡片高度无关。
 */
public final class UiShapes {

    private UiShapes() {
    }

    /** 填充圆角矩形方框。圆角半径 r 会钳制到不超过宽/高的 1/2。 */
    public static void fillRoundedRect(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        // 顶点必须用当前 pose 矩阵变换（含手机页面缩放/平移），与 g.fill 一致。
        Matrix4f pose = g.pose().last().pose();
        BufferBuilder bb = Tesselator.getInstance().begin(
                VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(pose, x + w / 2f, y + h / 2f, 0.0f).setColor(color);
        addArc(bb, pose, x + r, y + r, r, 180f, 270f, color);      // 左上圆弧
        bb.addVertex(pose, x + w - r, y, 0.0f).setColor(color);     // 上边
        addArc(bb, pose, x + w - r, y + r, r, 270f, 360f, color);   // 右上圆弧
        bb.addVertex(pose, x + w, y + h - r, 0.0f).setColor(color); // 右边
        addArc(bb, pose, x + w - r, y + h - r, r, 0f, 90f, color);  // 右下圆弧
        bb.addVertex(pose, x + r, y + h, 0.0f).setColor(color);     // 下边
        addArc(bb, pose, x + r, y + h - r, r, 90f, 180f, color);    // 左下圆弧
        bb.addVertex(pose, x, y + r, 0.0f).setColor(color);         // 左边（闭合）
        BufferUploader.drawWithShader(bb.buildOrThrow());
        RenderSystem.disableBlend();
    }

    /** 向扇形 buffer 追加一段圆弧（0°=右，90°=下，180°=左，270°=上，含首尾端点） */
    private static void addArc(BufferBuilder bb, Matrix4f pose, float cxx, float cyy, int r,
                               float aStart, float aEnd, int color) {
        final int SEGMENTS = 8;
        for (int i = 0; i <= SEGMENTS; i++) {
            float a = aStart + (aEnd - aStart) * i / SEGMENTS;
            float rad = (float) Math.toRadians(a);
            bb.addVertex(pose, cxx + r * (float) Math.cos(rad),
                    cyy + r * (float) Math.sin(rad), 0.0f).setColor(color);
        }
    }
}
