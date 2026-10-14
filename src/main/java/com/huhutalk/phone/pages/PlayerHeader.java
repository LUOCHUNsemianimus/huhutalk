package com.huhutalk.phone.pages;

import com.huhutalk.api.phone.ui.PhoneImages;
import com.huhutalk.api.phone.ui.PhoneText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

/**
 * 顶栏左侧的「玩家头颅正面 + 玩家 ID」——消息页与联系人页共用的那一块。
 *
 * <h2>为什么单独开一个文件</h2>
 * 这两页的顶栏都不是骨架默认的「居中标题」，而是「左对齐：玩家头像 + ID（联系人页再多一个"联系人"）」。
 * 迁移时这段逻辑先在两个页面里各写了一遍 —— 19 行逐字相同，
 * 正是这一轮要消灭的东西（IDE 的「重复代码段」检查当场就报了）。
 * <p>
 * 只放在 {@code pages} 包内、包私有：目前只有这两个调用点，
 * 不急着提升为公开 API（等第三个调用点出现再说）。
 */
final class PlayerHeader {

    private PlayerHeader() {
    }

    /**
     * 在顶栏左侧画玩家头颅正面与玩家 ID。
     *
     * @param barH  顶栏高度（来自 {@code PhonePage.renderTopBarExtras}）
     * @param textY 顶栏文字基线 y（同上）
     * @param color 文字颜色（通常是 {@code colors().topBarText()}）
     * @return ID 文本右侧的 x，调用方可以从这里接着画自己的标题
     */
    static int render(GuiGraphics g, int baseX, int baseY, int barH, int textY, int color) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int headSize = Math.max(1, barH - PhoneText.px(4));
        int headX = baseX + PhoneText.px(6);
        int headY = baseY + (barH - headSize) / 2;

        String id = "Player";
        if (mc.player != null) {
            String name = mc.player.getGameProfile().getName();
            if (name != null && !name.isEmpty()) {
                id = name;
            }
            // 只渲染皮肤纹理里的「头颅正面」8×8 区域（不是立体模型）
            if (mc.player instanceof AbstractClientPlayer acp) {
                ResourceLocation skin = acp.getSkin().texture();
                PhoneImages.drawRegion(g, skin, headX, headY, headSize, headSize, 8, 8, 8, 8, 64, 64);
            } else {
                g.fill(headX, headY, headX + headSize, headY + headSize, 0xFFFFFFFF);
            }
        } else {
            g.fill(headX, headY, headX + headSize, headY + headSize, 0xFFFFFFFF);
        }

        float scale = PhoneText.scale(1.0f);
        int x = headX + headSize + PhoneText.px(6);
        PhoneText.draw(g, font, id, x, textY, color, scale);
        return x + PhoneText.width(font, id, scale);
    }
}
