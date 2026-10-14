package com.huhutalk.phone.pages;

import com.huhutalk.api.phone.ui.PhonePage;
import com.huhutalk.api.phone.ui.PhoneText;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

/**
 * 占位页「🚧 敬请期待」——桌面槽位还没有 {@code pageFactory} 时用它。
 *
 * <h2>改用公开骨架（批次 1）</h2>
 * 旧实现是 22 行裸 {@code IPhonePage}：<b>连页面底色都没画</b>（桌面底色会直接透过来），
 * 文案用 {@code drawCenteredString} 按 1 倍字号钉在硬编码的 {@code (110, 160)}，
 * 加上一个把命中框写成 {@code 220×340} 魔数的「点哪都返回」。
 * <p>
 * 现在继承 {@link PhonePage}：底色、顶栏、返回键、裁剪都由骨架负责；
 * 文案用 {@link PhoneText#drawCenteredIn} 在内容区真正的水平/垂直居中，
 * 并跟随玩家的「字体倍率」设置缩放。硬编码坐标全部消失。
 */
public class ComingSoonPage extends PhonePage {

    private static final String TEXT = "🚧 敬请期待";

    /** 文案相对倍率（大一点才像占位页，而不是一行普通正文）。 */
    private static final float TEXT_SCALE = 1.5f;

    /**
     * 顶栏不写标题：本页正文就是那句居中文案，顶栏再写一遍会重复。
     * 返回键仍然保留（左上角），与其它子页面一致。
     */
    @Override
    protected String title() {
        return "";
    }

    @Override
    protected void renderContent(GuiGraphics g, int originX, int originY, int w, int h, float partialTick) {
        Font font = font();
        PhoneText.drawCenteredIn(g, font, TEXT, originX, originY, w, h,
                colors().textPrimary(), PhoneText.scale(TEXT_SCALE));
    }

    /**
     * 保留旧实现的「点哪都返回」手感——玩家不该在一个占位页里找不到出口。
     * <p>
     * ⚠ 只在 {@link GLFW#GLFW_PRESS} 时处理：{@code PhoneOverlay} 会把按下与松开都转发给页面，
     * 不区分 action 就会连弹两页。
     */
    @Override
    protected boolean onContentClick(double localX, double localY, int button, int action) {
        if (action == GLFW.GLFW_PRESS) {
            onBack();
            return true;
        }
        return false;
    }
}
