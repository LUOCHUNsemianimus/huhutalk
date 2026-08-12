package com.huhutalk.phone.pages;

import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public class ComingSoonPage implements IPhonePage {
    @Override
    public void render(GuiGraphics graphics, int baseX, int baseY, float partialTick) {
        graphics.drawCenteredString(Minecraft.getInstance().font, "🚧 敬请期待", baseX + 110, baseY + 160, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (action == 1 && relX >= 0 && relX <= 220 && relY >= 0 && relY <= 340) {
            PhoneOverlay.popPage();
            return true;
        }
        return false;
    }
}