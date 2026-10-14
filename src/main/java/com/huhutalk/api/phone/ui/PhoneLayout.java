package com.huhutalk.api.phone.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 手机页面的<b>公开布局小工具</b>——矩形命中与细阴影分隔。
 *
 * <h2>为什么单独开一个类</h2>
 * {@link PhonePage} 已经提供了 {@code hit(...)}，但它是 {@code protected static}：
 * <b>只有继承 {@code PhonePage} 的页面用得到</b>。而这几类页面必须自绘、拿不到骨架：
 * <ul>
 *   <li>{@code ChatPage} —— 相机宿主，取景时要透明，不能继承 {@code PhonePage}（其 {@code render} 是
 *       final 且必定铺满不透明背景）；</li>
 *   <li>{@code MainPage} —— 桌面启动器，无顶栏、有自己的槽位命中；</li>
 *   <li>实现 {@code PhoneCameraHost} 的相机页。</li>
 * </ul>
 * 这些页面各自抄了一份一模一样的 {@code hit(...)} 与 {@code drawTopShadow(...)}，
 * 于是"渲染与命中必须用同一份计算"这条纪律在每个副本里都要重新靠自觉维持。这里收成一处。
 *
 * <h2>参数顺序（⚠ 与 {@link PhonePage#hit} 不同）</h2>
 * <b>本类是「先点、后矩形」</b>：{@code hit(px, py, x, y, w, h)} —— 与既有页面里
 * {@code ChatPage} / {@code MaidInfoPage} / {@code SettingsPage} 的私有副本参数顺序一致，
 * 所以迁到本类时<b>调用点一个字都不用改</b>。
 * 而 {@link PhonePage#hit} 是「先矩形、后点」的 {@code hit(x, y, w, h, px, py)}（它只是本类的转发壳）。
 * 两者刻意都保留：前者已有一批现存调用点，后者是骨架内的历史签名。
 *
 * <pre>{@code
 * // 内容坐标下的一个条目
 * if (PhoneLayout.hit(localX, localY, cardX, rowY, cardW, ROW_H)) { ... }
 * }</pre>
 */
public final class PhoneLayout {

    private PhoneLayout() {
    }

    /**
     * 矩形命中测试：点 {@code (px, py)} 是否落在矩形 {@code (x, y, w, h)} 内。
     * <p>
     * 半开区间（左闭右开）：{@code x <= px < x + w}、{@code y <= py < y + h}。
     * 与相邻矩形拼在一起时不会出现"边界同时命中两个"或"边界两边都不中"。
     * <p>
     * 坐标可以是内容坐标也可以是屏幕坐标——只要点和矩形用<b>同一套</b>。
     */
    public static boolean hit(double px, double py, int x, int y, int w, int h) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }

    /**
     * 在 {@code y} 处画一条细水平阴影（2 层递减的半透明深色），用于分区之间的视觉分隔。
     * <p>
     * 既有 {@code ChatPage.drawTopShadow} 的等价物：压一层 {@code 0x16000000} 再压一层
     * {@code 0x0C000000}，比单条实心线更像"投影"而不是"边框"。
     */
    public static void topShadow(GuiGraphics g, int x, int y, int w) {
        horizontalShadow(g, x, y, w, 2);
    }

    /**
     * {@link #topShadow} 的可调层数版：层数越多、阴影越长越柔。
     *
     * @param layers 层数；{@code <= 0} 时什么都不画。透明度从 {@code 0x16} 起步、每层递减 {@code 0x0A}
     */
    public static void horizontalShadow(GuiGraphics g, int x, int y, int w, int layers) {
        if (w <= 0 || layers <= 0) {
            return;
        }
        for (int i = 0; i < layers; i++) {
            int alpha = 0x16 - i * 0x0A;
            if (alpha <= 0) {
                break;
            }
            g.fill(x, y + i, x + w, y + i + 1, alpha << 24);
        }
    }
}
