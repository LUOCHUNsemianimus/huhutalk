package com.huhutalk.api.phone.ui;

import com.huhutalk.client.theme.ThemeManager;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 上下增减控件（stepper）：竖排 ▲▼ 两个箭头，▲ 增大、▼ 减小。
 * 形态与内置「设置」页的「每字耗时 / 单句上限 / 字体倍率」一致。
 *
 * <h2>离散档位</h2>
 * 取值不是连续区间，而是<b>一组档位</b>（例如字体倍率的 0.5 / 0.75 / 1.0 / 1.5 / 2.0）。
 * {@link #setValue(double)} 会吸附到最近的档位，所以外部设置值不必自己对齐。
 *
 * <pre>{@code
 * PhoneStepper stepper = new PhoneStepper()
 *         .steps(0.5, 0.75, 1.0, 1.5, 2.0)
 *         .setValue(HuhuTalkClientSettings.getUiFontScale())
 *         .onChange(v -> HuhuTalkClientSettings.setUiFontScale((float) v));
 * PhoneRow row = PhoneRow.of(() -> "字体倍率：×" + stepper.display(), stepper).bounds(...);
 * }</pre>
 */
public final class PhoneStepper extends PhoneWidget {

    /** 方块高度占字体行高的比例（与 SettingsPage 的 contH 一致） */
    private static final float BOX_SCALE = 0.9f;
    /** 箭头文字的相对字号（SettingsPage 用 0.5 倍） */
    private static final float ARROW_SCALE = 0.5f;
    /** 两个箭头之间的间距（像素，随字体倍率缩放） */
    private static final int GAP_PX = 2;

    private double[] steps = {0.0, 1.0};
    private int index = 0;
    private Consumer<Double> onChanged;
    private Integer arrowColor;
    private Function<Double, String> formatter;

    /** 方块边长（也是控件宽度）。 */
    public static int boxSize(Font font) {
        return Math.max(1, (int) (font.lineHeight * PhoneText.scale(BOX_SCALE)));
    }

    /**
     * 设置档位表（必须非空）。内部会排序，所以调用处不必保证升序。
     * 设置后会把当前值吸附到最近的档位。
     */
    public PhoneStepper steps(double... values) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("PhoneStepper.steps 不能为空");
        }
        double[] copy = values.clone();
        java.util.Arrays.sort(copy);
        this.steps = copy;
        setValue(this.value());
        return this;
    }

    /** 设为最接近 {@code v} 的档位；<b>不</b>触发回调。 */
    public PhoneStepper setValue(double v) {
        int best = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < steps.length; i++) {
            double d = Math.abs(steps[i] - v);
            if (d < bestDiff) {
                bestDiff = d;
                best = i;
            }
        }
        this.index = best;
        return this;
    }

    public double value() {
        return steps[index];
    }

    /** 当前档位的显示文本（可用 {@link #format} 自定义）。 */
    public String display() {
        double v = value();
        return formatter != null ? formatter.apply(v) : defaultFormat(v);
    }

    public PhoneStepper onChange(Consumer<Double> listener) {
        this.onChanged = listener;
        return this;
    }

    /** 自定义箭头颜色；不设则取当前主题的 {@code accent}。 */
    public PhoneStepper arrowColor(int argb) {
        this.arrowColor = argb;
        return this;
    }

    /** 自定义 {@link #display()} 的格式。默认整数去小数点（{@code 1.0 → "1"}）。 */
    public PhoneStepper format(Function<Double, String> f) {
        this.formatter = f;
        return this;
    }

    @Override
    public int preferredWidth(Font font) {
        return boxSize(font);
    }

    @Override
    public int preferredHeight(Font font) {
        return boxSize(font) * 2 + PhoneText.px(GAP_PX);
    }

    /**
     * 点击 ▲/▼ 的命中：上半部分为 ▲（增），下半部分为 ▼（减）。
     * 供需要自行处理点击的调用方判断。
     *
     * @return 1 = ▲（增）、-1 = ▼（减）、0 = 未命中
     */
    public int hitArrow(double px, double py) {
        if (!hit(px, py)) {
            return 0;
        }
        return py < y() + height() / 2.0 ? 1 : -1;
    }

    @Override
    public void render(GuiGraphics g, int originX, int originY) {
        int box = boxSize(net.minecraft.client.Minecraft.getInstance().font);
        if (box <= 0) {
            return;
        }
        int x = originX + x();
        int y = originY + y();
        int gap = PhoneText.px(GAP_PX);
        int color = arrowColor != null ? arrowColor : ThemeManager.getActive().accent();
        Font font = net.minecraft.client.Minecraft.getInstance().font;
        PhoneText.drawCenteredIn(g, font, "▲", x, y, box, box, color, PhoneText.scale(ARROW_SCALE));
        PhoneText.drawCenteredIn(g, font, "▼", x, y + box + gap, box, box, color, PhoneText.scale(ARROW_SCALE));
    }

    @Override
    public boolean mousePressed(double px, double py, int button) {
        if (!enabled() || !isPrimaryPress(button)) {
            return false;
        }
        int dir = hitArrow(px, py);
        if (dir == 0) {
            return false;
        }
        int next = index + dir;
        if (next < 0 || next >= steps.length) {
            return true; // 到顶/到底：消费掉点击，避免穿透到下层
        }
        index = next;
        if (onChanged != null) {
            onChanged.accept(value());
        }
        return true;
    }

    /** 默认格式：整数省掉小数点。 */
    private static String defaultFormat(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }
}
