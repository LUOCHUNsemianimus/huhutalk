package com.huhutalk.phone.pages;

import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.client.theme.ColorScheme;
import com.huhutalk.client.theme.PhoneCaseRenderer;
import com.huhutalk.client.theme.ThemeManager;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.phone.UiFont;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 手机壳方块选择页（从设置页进入）。
 * <p>
 * 列出玩家背包里的所有<b>完整方块</b>（非完整方块不允许，用 {@link PhoneCaseRenderer#isFullBlockItem} 过滤），
 * 点击某个方块即设置为手机壳贴图（存 {@link HuhuTalkClientSettings#setPhoneCaseBlock}），实时生效。
 * 纯视觉，不影响内部布局。
 */
public class PhoneCasePickerPage implements IPhonePage {
    private static final int ITEM_H = 34;
    /** 每行方块数 */
    private static final int COLS = 4;

    /** 顶部标题栏高度：随字体缩放 */
    private static int navH() {
        return (int) (Minecraft.getInstance().font.lineHeight * UiFont.uiScale()) + UiFont.sh(8);
    }

    /** 缩放后默认字体的行高（用于文字垂直居中） */
    private static int textH(Font font) {
        return Math.max(1, (int) (font.lineHeight * UiFont.sc(1.0f)));
    }

    /** 方块格高度：随字体倍率缩放（格内含短名文字） */
    private static int itemH() {
        return UiFont.sh(ITEM_H);
    }

    private final List<String> blocks = new ArrayList<>();
    private int scrollOffset = 0;

    public PhoneCasePickerPage() {
        refreshFromInventory();
    }

    /** 从背包收集完整方块的 block id（去重，保持出现顺序） */
    private void refreshFromInventory() {
        blocks.clear();
        Set<String> seen = new HashSet<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            for (ItemStack stack : mc.player.getInventory().items) {
                if (!PhoneCaseRenderer.isFullBlockItem(stack)) {
                    continue;
                }
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (seen.add(id)) {
                    blocks.add(id);
                }
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int baseX, int baseY, float partialTick) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        Font font = Minecraft.getInstance().font;
        ColorScheme scheme = ThemeManager.getActive();
        // 标题栏高度随字体缩放
        int navH = navH();

        // 页面底色 + 标题栏
        g.fill(baseX, baseY, baseX + w, baseY + h, scheme.pageBg());
        g.fill(baseX, baseY, baseX + w, baseY + navH, scheme.topBarBg());
        drawScaledText(g, font, "< 返回", baseX + UiFont.sh(4), baseY + (navH - textH(font)) / 2 + 1,
                scheme.topBarText(), UiFont.sc(1.0f));
        float titleW = font.width("选择手机壳方块") * UiFont.sc(1.0f);
        drawScaledText(g, font, "选择手机壳方块", baseX + (w - titleW) / 2f, baseY + (navH - textH(font)) / 2 + 1,
                scheme.topBarText(), UiFont.sc(1.0f));
        drawScaledText(g, font, "当前：" + currentDisplay(), baseX + UiFont.sh(4), baseY + navH + UiFont.sh(4),
                0xFF333333, UiFont.sc(1.0f));

        int gridTop = baseY + navH + UiFont.sh(16);
        int gridBottom = baseY + h - UiFont.sh(4);
        g.enableScissor(baseX, gridTop, baseX + w, gridBottom);

        int margin = UiFont.sh(6);
        int cellW = (w - margin * (COLS + 1)) / COLS;
        int startY = gridTop - scrollOffset;
        for (int i = 0; i < blocks.size(); i++) {
            int row = i / COLS;
            int col = i % COLS;
            int x = baseX + margin + col * (cellW + margin);
            int y = startY + row * (itemH() + margin);
            String id = blocks.get(i);
            // 当前选中高亮
            boolean selected = id.equals(HuhuTalkClientSettings.getPhoneCaseBlock());
            g.fill(x, y, x + cellW, y + itemH(), selected ? scheme.accentSoftActive() : 0x33FFFFFF);
            // 渲染方块物品图标
            net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(id));
            if (item != null) {
                g.renderItem(new ItemStack(item), x + 3, y + 3);
            }
            // 短名
            String shortName = shortName(id);
            drawScaledText(g, font, shortName, x + UiFont.sh(22), y + (itemH() - textH(font)) / 2 + 1,
                    0xFF333333, UiFont.sc(1.0f));
        }
        g.disableScissor();
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

    private String currentDisplay() {
        String id = HuhuTalkClientSettings.getPhoneCaseBlock();
        return id.isBlank() ? "默认" : shortName(id);
    }

    private String shortName(String id) {
        int idx = id.indexOf(':');
        return idx >= 0 ? id.substring(idx + 1) : id;
    }

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (action != GLFW.GLFW_PRESS) return false;
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int navH = navH();

        if (relX >= 0 && relX <= UiFont.sh(60) && relY >= 0 && relY <= navH) {
            PhoneOverlay.popPage();
            return true;
        }

        // 点击方块网格
        int gridTop = navH + UiFont.sh(16);
        int margin = UiFont.sh(6);
        int cellW = (w - margin * (COLS + 1)) / COLS;
        int row = (int)((relY - gridTop + scrollOffset) / (itemH() + margin));
        int col = (int)((relX - margin) / (cellW + margin));
        if (relX < margin || relX > w - margin) {
            return false;
        }
        int idx = row * COLS + col;
        if (idx >= 0 && idx < blocks.size()) {
            String id = blocks.get(idx);
            if (HuhuTalkClientSettings.getPhoneCaseBlock().equals(id)) {
                HuhuTalkClientSettings.setPhoneCaseBlock(""); // 点已选中的 → 恢复默认
            } else {
                HuhuTalkClientSettings.setPhoneCaseBlock(id);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        int totalRows = (blocks.size() + COLS - 1) / COLS;
        int visibleRows = Math.max(1, (int)(PhoneOverlay.getPhoneHeight() / (itemH() + margin())));
        int maxScroll = Math.max(0, totalRows * (itemH() + margin()) - visibleRows * (itemH() + margin()));
        scrollOffset = Math.max(0, Math.min(scrollOffset + (int)(scrollDelta * (itemH() + margin())), maxScroll));
        return true;
    }

    private int margin() {
        return UiFont.sh(6);
    }
}
