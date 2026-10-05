package com.huhutalk.phone.pages;

import com.huhutalk.api.phone.PhoneApp;
import com.huhutalk.api.phone.PhoneAppRegistry;
import com.huhutalk.client.NotificationManager;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.network.C2SSOSPacket;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.phone.UiFont;
import com.huhutalk.phone.UiShapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 手机主界面（桌面）。
 * <ul>
 *   <li>桌面背景取自当前主题的 {@code pageBg}（随「配色方案」切换整体变化）</li>
 *   <li>APP 图标：圆角正方形，每行 {@link #APP_COLS} 个，纵向向下延伸；行数容量随手机长宽比变化
 *       （20:9 → 5 行、9:16 → 4 行、4:3 → 3 行）</li>
 *   <li>每个图标中心显示文字或 emoji，图标下方标注其灰字名称（0.5 倍、带阴影描边）</li>
 *   <li>HuhuTalk 图标右上角未读红点</li>
 *   <li>「设置」图标打开手机内置手册（{@link ManualPage}）</li>
 *   <li>SOS 紧急呼叫按钮（召来全部已佩戴手机的女仆并进入战斗保护）</li>
 * </ul>
 */
public class MainPage implements IPhonePage {
    /**
     * 桌面背景色：取自当前主题的 {@code pageBg}（主要页面背景）。
     * <p>
     * 这里原本是硬编码的临时色 {@code #f0e4ea}（注释写着"后续替换为正式配色"）。
     * 改读主题之后，「设置」页里的换配色方案会连桌面一起换掉——否则换个主题只换一半，看着很别扭。
     */
    private static int desktopBg() {
        return com.huhutalk.client.theme.ThemeManager.getActive().pageBg();
    }
    /** 每行 app 图标个数 */
    private static final int APP_COLS = 3;
    /** 图标下方名称的灰字颜色 */
    private static final int LABEL_COLOR = 0xFF7A7A7A;
    /** 桌面中央表情边长 = 手机内容宽度 × 该比例 */
    private static final float STICKER_WIDTH_RATIO = 2.0f / 3.0f;
    /** 女仆表情资源命名空间与目录（与聊天页一致） */
    private static final String EMOJI_NAMESPACE = "touhou_little_maid";
    private static final String EMOJI_DIR = "textures/chat_bubble/maid_emoji";
    /** HuhuTalk APP 图标贴图（三档可选：高清 symbol2 / 低清 symbol / 狐狸 emoji） */
    private static final ResourceLocation ICON_SYMBOL2 =
            ResourceLocation.fromNamespaceAndPath("huhutalk", "textures/huhutalk_symbol.png");
    private static final int ICON_SYMBOL2_SIZE = 256;
    private static final ResourceLocation ICON_SYMBOL =
            ResourceLocation.fromNamespaceAndPath("huhutalk", "textures/huhutalk_symbol_legacy.png");
    private static final int ICON_SYMBOL_SIZE = 32;
    /** HuhuTalk 图标贴图在桌面方块内占据的比例（1.0 = 铺满，边正好对上 APP 方框的边）。想留边调小即可。 */
    private static final float ICON_FILL_RATIO = 1.0f;
    /** 随机池：全部女仆表情（emoji_01~20） */
    private static final String[] ALL_EMOJI = {
            "emoji_01.png", "emoji_02.png", "emoji_03.png", "emoji_04.png", "emoji_05.png",
            "emoji_06.png", "emoji_07.png", "emoji_08.png", "emoji_09.png", "emoji_10.png",
            "emoji_11.png", "emoji_12.png", "emoji_13.png", "emoji_14.png", "emoji_15.png",
            "emoji_16.png", "emoji_17.png", "emoji_18.png", "emoji_19.png", "emoji_20-24x24.png"
    };

    private static final Random RANDOM = new Random();

    /** APP 元数据（静态，类加载时由 {@link #buildApps()} 构建一次；供通用启动动画按名字查询） */
    private static final List<AppInfo> apps = buildApps();

    /**
     * 内置 App + 附属模组注册进来的 App 的<b>合并列表缓存</b>。
     * <p>
     * 为什么不直接把外部 App 塞进 {@link #apps}：{@code apps} 是 {@code static final}、
     * 在类加载时定型，而附属模组的注册时机不由我们控制。改成「按注册版本号懒合并」之后，
     * 附属模组<b>任何时候</b>注册都能在下一帧生效，也不会出现「注册成功但图标不出现」这种哑失败。
     * <p>
     * 位置稳定性：内置 App 恒定占据 0..N-1（网格布局按下标算），外部 App 一律追加在后面，
     * 所以装不装附属模组都不会改变既有图标的排布。
     */
    private static List<AppInfo> mergedApps = null;
    /** {@link #mergedApps} 对应的注册版本号；与 {@link PhoneAppRegistry#generation()} 不一致时重建 */
    private static int mergedGeneration = -1;

    /**
     * 桌面当前应该显示的全部 App = 内置 {@link #apps} + {@link PhoneAppRegistry} 里的外部 App。
     * <p>
     * 每帧调用是便宜的：只有注册版本号变化时才真正重建列表。
     * 首次调用会广播 {@link com.huhutalk.api.phone.PhoneAppRegisterEvent}（见 {@link PhoneAppRegistry#apps()}），
     * 于是附属模组不需要关心"什么时候注册才来得及"。
     */
    private static List<AppInfo> allApps() {
        // 顺序要紧：先取列表（这一步可能广播注册事件），再读版本号。
        // 反过来会在"广播期间刚好有人注册"时把版本号读成旧值，导致缓存永不更新。
        List<PhoneApp> external = PhoneAppRegistry.apps();
        int generation = PhoneAppRegistry.generation();
        if (mergedApps == null || mergedGeneration != generation) {
            List<AppInfo> merged = new ArrayList<>(apps);
            for (PhoneApp app : external) {
                merged.add(toAppInfo(app));
            }
            mergedApps = merged;
            mergedGeneration = generation;
        }
        return mergedApps;
    }

    /** 把外部注册的 {@link PhoneApp} 转成桌面内部使用的 {@link AppInfo}。 */
    private static AppInfo toAppInfo(PhoneApp app) {
        return new AppInfo(app.displayName(), app.iconColor(), app.centerText(), false,
                app.centerScale(), app.centerColor(),
                app.pageFactory() != null, false, app.pageFactory(),
                app.iconTexture(), app.iconTexWidth(), app.iconTexHeight(), app.iconFill());
    }

    /** 桌面中央表情贴图（每次进入桌面时选择一次） */
    private ResourceLocation stickerTex = null;
    private int stickerW = 24, stickerH = 24;
    private boolean stickerChosen = false;
    /** SOS 点击后的短暂提示（毫秒时间戳） */
    private long sosFlashUntil = 0;
    private String sosMessage = "";

    public MainPage() {
        // apps 由 buildApps() 静态构建一次，构造函数不再往里 add，避免反复 new 造成重复图标
    }

    /** 一次性构建全部 APP 元数据。 */
    private static List<AppInfo> buildApps() {
        List<AppInfo> list = new ArrayList<>();
        // HuhuTalk：狐狸 emoji 居中（2.25 倍），点击进入 App 内容区（消息页 + 底部导航）。底色用 #de6f8d
        list.add(new AppInfo("HuhuTalk", 0xFFDE6F8D, "🦊", true, 2.25f, 0xFFFFFFFF, true, false, null));
        // 狐狐打人 / 狐狐打车：中心保留两字文案
        list.add(new AppInfo("狐狐打人", 0xFF8000, "打人", false, 1.0f, 0xFFFFFFFF, false, false, null));
        list.add(new AppInfo("狐狐打车", 0xFFFF00, "打车", false, 1.0f, 0xFFFFFFFF, false, false, null));
        // 第二行：[SOS, 狐狐三十分(隐藏), 设置]。SOS 左置、隐藏的 30 分居中、设置靠右
        list.add(new AppInfo("SOS", 0xFF0000, "SOS", false, 1.0f, 0xFFFFFFFF, true, false, null));
        list.add(new AppInfo("狐狐三十分", 0x6495ED, "30分", false, 1.0f, 0xFFFFFFFF, false, true, null));
        // 设置：齿轮 emoji，2 倍字号、灰色，打开手机内置手册
        list.add(new AppInfo("设置", 0xFFEEEEEE, "⚙", true, 2.0f, 0xFF757575, true, false, ManualPage::new));
        return list;
    }

    /** 重新成为当前页（从子页返回桌面）时，下一帧重选中央表情 */
    @Override
    public void onOpen() {
        stickerChosen = false;
    }

    /** 选择桌面中央表情：优先按玩家状态，否则随机。仅进入桌面时调用一次。 */
    private void chooseSticker() {
        stickerChosen = true;
        Minecraft mc = Minecraft.getInstance();
        boolean lowHp = false, lowFood = false;
        if (mc.player != null) {
            lowHp = mc.player.getHealth() <= mc.player.getMaxHealth() * 0.4f; // 低血量：<=40% 最大血量
            lowFood = mc.player.getFoodData().getFoodLevel() <= 6;             // 低饱食：foodLevel <= 6
        }
        String file;
        if (lowHp && lowFood) {
            file = "emoji_11.png";   // 害怕
        } else if (lowHp) {
            file = "emoji_15.png";   // 没血
        } else if (lowFood) {
            file = "emoji_18.png";   // 饿
        } else {
            file = ALL_EMOJI[RANDOM.nextInt(ALL_EMOJI.length)];
        }
        stickerTex = ResourceLocation.fromNamespaceAndPath(EMOJI_NAMESPACE, EMOJI_DIR + "/" + file);
        int[] sz = parseEmojiSize(file);
        stickerW = sz[0];
        stickerH = sz[1];
    }

    /** 从文件名解析表情尺寸：`-24x24.png` → 24x24；无尺寸后缀默认 24x24。与 TLM 规则一致。 */
    private static int[] parseEmojiSize(String file) {
        Matcher m = Pattern.compile("^.*?-(\\d+)x(\\d+)\\.png$").matcher(file);
        if (m.matches()) {
            int w = Integer.parseInt(m.group(1));
            int h = Integer.parseInt(m.group(2));
            return new int[]{Math.max(8, Math.min(256, w)), Math.max(8, Math.min(256, h))};
        }
        return new int[]{24, 24};
    }

    /** 网格行数容量：随手机长宽比变化。20:9→5、9:16→4、4:3→3，未知回退 5。 */
    private static int gridRows() {
        return switch (HuhuTalkClientSettings.getPhoneAspect()) {
            case "4:3" -> 3;
            case "9:16" -> 4;
            default -> 5; // "20:9" 及其它
        };
    }

    /** 图标区顶部起始 Y（内容区相对坐标） */
    private static int gridStartY(int h) {
        int topPad = Math.max(4, (int) (h * 0.03));
        return topPad + Math.max(4, (int) (h * 0.04));
    }

    /**
     * 计算第 i 个 app 图标的矩形（内容区相对坐标，未含 baseX/baseY）。
     * 渲染（加 baseX/baseY）与点击命中（直接用 relX/relY）共用，保证一致。
     * 纵向步进含图标下方名称留白，避免名称与下一行重叠。
     * out = {x, y, iconW, iconH}。
     */
    private static void appRect(int w, int h, int i, int[] out) {
        Font font = Minecraft.getInstance().font;
        int margin = Math.max(4, (int) (w * 0.06));
        int iconW = Math.max(10, (w - margin * (APP_COLS + 1)) / APP_COLS);
        int iconH = iconW; // 圆角正方形
        int labelH = Math.max(3, Math.round(font.lineHeight * UiFont.sc(0.8f)));
        int rowGap = Math.max(3, (int) (h * 0.015));
        int cellStep = iconH + labelH + rowGap;
        int startY = gridStartY(h);
        int row = i / APP_COLS;
        int col = i % APP_COLS;
        out[0] = margin + col * (iconW + margin);
        out[1] = startY + row * cellStep;
        out[2] = iconW;
        out[3] = iconH;
    }

    /** 计算 HuhuTalk 图标的红点位置，渲染与点击共用 */
    private boolean getTalkIconPos(int[] out) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        List<AppInfo> list = allApps();
        for (int i = 0; i < list.size(); i++) {
            if ("HuhuTalk".equals(list.get(i).name)) {
                appRect(w, h, i, out);
                return true;
            }
        }
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int baseX, int baseY, float partialTick) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        var font = Minecraft.getInstance().font;

        // 桌面背景（取自主题，随「配色方案」切换整体变化）
        graphics.fill(baseX, baseY, baseX + w, baseY + h, desktopBg());

        // 桌面中央表情（每次进入桌面时选择；本帧需要时选择后绘制）
        if (!stickerChosen) {
            chooseSticker();
        }
        if (stickerTex != null) {
            int s = (int) (w * STICKER_WIDTH_RATIO);
            int sx = baseX + (w - s) / 2;
            int sy = baseY + (h - s) / 2;
            graphics.blit(stickerTex, sx, sy, s, s, 0.0f, 0.0f, stickerW, stickerH, stickerW, stickerH);
        }

        int[] rect = new int[4];
        List<AppInfo> list = allApps();
        for (int i = 0; i < list.size(); i++) {
            AppInfo app = list.get(i);
            if (app.hidden) continue; // 狐狐三十分等暂隐藏

            appRect(w, h, i, rect);
            int x = baseX + rect[0];
            int y = baseY + rect[1];
            int iconW = rect[2];
            int iconH = rect[3];

            // 圆角正方形图标
            int r = Math.max(4, iconW / 4);
            UiShapes.fillRoundedRect(graphics, x, y, iconW, iconH, r, app.color | 0xFF000000);

            // 图标中心内容：HuhuTalk 按玩家选择的风格（高清/低清贴图或狐狸 emoji），其它用文字/emoji
            if ("HuhuTalk".equals(app.name)) {
                String style = HuhuTalkClientSettings.getHuhutalkIconStyle();
                if ("fox".equals(style)) {
                    // 狐狸 emoji（与其它 app 一样用大字）
                    String fox = "🦊";
                    float cs = UiFont.sc(2.25f);
                    int cw = (int) (font.width(fox) * cs);
                    int ch = (int) (font.lineHeight * cs);
                    drawScaledText(graphics, font, fox,
                            x + (iconW - cw) / 2, y + (iconH - ch) / 2, 0xFFFFFFFF, cs);
                } else {
                    // 贴图：高清 symbol2 / 低清 symbol，占图标方块 ICON_FILL_RATIO（1.0=铺满，边对上方框）
                    boolean legacy = "symbol".equals(style);
                    ResourceLocation tex = legacy ? ICON_SYMBOL : ICON_SYMBOL2;
                    int srcSize = legacy ? ICON_SYMBOL_SIZE : ICON_SYMBOL2_SIZE;
                    int iconDraw = (int) (iconW * ICON_FILL_RATIO);
                    int inset = Math.max(0, (iconW - iconDraw) / 2);
                    graphics.blit(tex, x + inset, y + inset, iconDraw, iconDraw,
                            0.0f, 0.0f, srcSize, srcSize, srcSize, srcSize);
                }
            } else if (app.iconTexture != null) {
                // 附属模组注册的 App：贴图图标（内置 App 各有各的画法，走不到这里）。
                // 非正方形贴图按较长边贴合、短边留白，不拉伸；iconFill < 1 时四周整体留白。
                float fill = app.iconFill <= 0f ? 1.0f : app.iconFill;
                int box = Math.max(1, Math.round(iconW * fill));
                int drawW = box, drawH = box;
                if (app.iconTexWidth > app.iconTexHeight) {
                    drawH = Math.max(1, box * app.iconTexHeight / app.iconTexWidth);
                } else if (app.iconTexHeight > app.iconTexWidth) {
                    drawW = Math.max(1, box * app.iconTexWidth / app.iconTexHeight);
                }
                int dx = x + (iconW - drawW) / 2;
                int dy = y + (iconH - drawH) / 2;
                graphics.blit(app.iconTexture, dx, dy, drawW, drawH,
                        0.0f, 0.0f, app.iconTexWidth, app.iconTexHeight,
                        app.iconTexWidth, app.iconTexHeight);
            } else {
                float cs = UiFont.sc(app.centerScale);
                int cw = (int) (font.width(app.center) * cs);
                int ch = (int) (font.lineHeight * cs);
                int cx = x + (iconW - cw) / 2;
                int cy = y + (iconH - ch) / 2;
                drawScaledText(graphics, font, app.center, cx, cy, app.centerColor, cs);
            }

            // 图标下方名称（灰字，0.8 倍，带阴影描边）
            float ls = UiFont.sc(0.8f);
            int lw = (int) (font.width(app.name) * ls);
            int lx = x + (iconW - lw) / 2;
            int ly = y + iconH + 1;
            drawScaledTextShaded(graphics, font, app.name, lx, ly, LABEL_COLOR, ls);

            // HuhuTalk 未读红点
            if ("HuhuTalk".equals(app.name)) {
                int unread = NotificationManager.getTotalUnread();
                if (unread > 0) {
                    int dotX = x + iconW - 12;
                    int dotY = y - 6;
                    graphics.fill(dotX, dotY, dotX + 12, dotY + 12, 0xFFFF0000);
                    String num = unread > 99 ? "99+" : String.valueOf(unread);
                    drawScaledText(graphics, font, num, dotX + 2, dotY + 1, 0xFFFFFFFF, UiFont.sc(0.75f));
                }
            }
            // 设置（手册）首次未看：右上角小红点提示
            if ("设置".equals(app.name) && !HuhuTalkClientSettings.isManualSeen()) {
                int dotX = x + iconW - 8;
                int dotY = y - 5;
                graphics.fill(dotX, dotY, dotX + 9, dotY + 9, 0xFFFF0000);
            }
        }

        // SOS 点击后的短暂提示
        if (System.currentTimeMillis() < sosFlashUntil && !sosMessage.isEmpty()) {
            int msgW = (int) (font.width(sosMessage) * UiFont.sc(1.0f));
            int msgY = gridStartY(h) - (int) (h * 0.02);
            graphics.fill(baseX + 4, msgY - UiFont.sh(2), baseX + w - 4, msgY + (int) (font.lineHeight * UiFont.sc(1.0f)) + UiFont.sh(2), 0xAA000000);
            drawScaledText(graphics, font, sosMessage, baseX + (w - msgW) / 2, msgY, 0xFFFF6666, UiFont.sc(1.0f));
        }
    }

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (action != GLFW.GLFW_PRESS) return false;

        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int[] rect = new int[4];
        List<AppInfo> list = allApps();
        for (int i = 0; i < list.size(); i++) {
            AppInfo app = list.get(i);
            if (app.hidden) continue;

            appRect(w, h, i, rect);
            int x = rect[0], y = rect[1], iconW = rect[2], iconH = rect[3];

            if (relX >= x && relX <= x + iconW && relY >= y && relY <= y + iconH) {
                // SOS：不发页，直接发紧急呼叫包并显示提示
                if ("SOS".equals(app.name)) {
                    sendSos();
                    return true;
                }
                // HuhuTalk：进入 HuhuTalk App 内容区（消息页 + 底部导航）
                if ("HuhuTalk".equals(app.name)) {
                    PhoneOverlay.openChatApp();
                    return true;
                }
                // 设置（手册）：点击即标记已看，消除首次红点
                if ("设置".equals(app.name)) {
                    HuhuTalkClientSettings.setManualSeen(true);
                }
                if (app.implemented && app.pageFactory != null) {
                    PhoneOverlay.pushPage(app.pageFactory.get());
                } else {
                    PhoneOverlay.pushPage(new ComingSoonPage());
                }
                return true;
            }
        }
        return false;
    }

    /** 发送 SOS 紧急呼叫包，并显示短暂提示 */
    private void sendSos() {
        PacketDistributor.sendToServer(new C2SSOSPacket());
        int bound = com.huhutalk.client.SosBindCache.count();
        sosMessage = "🚨 已呼叫 " + bound + " 位 SOS 女仆赶来保护!";
        sosFlashUntil = System.currentTimeMillis() + 3000;
    }

    /** 红点坐标计算（预留未来动画使用） */
    public int[] getTalkIconPosition() {
        int[] pos = new int[4];
        if (getTalkIconPos(pos)) return pos;
        return null;
    }

    /**
     * 指定 App 图标中心（屏幕绝对坐标）+ 边长 + 底色。
     * 供通用启动动画（{@code PhoneOverlay.launchApp}）作为色块生成原点、初始尺寸与颜色。
     * 返回 {centerX, centerY, iconW, colorRGB}；未找到该 App 返回 null。
     */
    public static int[] appIconCenterScreen(String appName) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        List<AppInfo> list = allApps();
        for (int i = 0; i < list.size(); i++) {
            AppInfo a = list.get(i);
            if (!a.name.equals(appName)) continue;
            int[] rect = new int[4];
            appRect(w, h, i, rect);
            int cx = rect[0] + rect[2] / 2;
            int cy = rect[1] + rect[3] / 2;
            int sx = PhoneOverlay.getPhoneX() + cx;
            int sy = PhoneOverlay.getPhoneY() + PhoneOverlay.getStatusBarHeight() + cy;
            return new int[]{ sx, sy, rect[2], a.color & 0x00FFFFFF };
        }
        return null;
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

    /** 以任意倍率绘制文本（带阴影描边），用于桌面图标的灰字名称 */
    private static void drawScaledTextShaded(GuiGraphics g, Font font, String text, float x, float y,
                                             int color, float scale) {
        float inv = 1.0f / scale;
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(font, text, x * inv, y * inv, color, true);
        g.pose().popPose();
    }

    public static class AppInfo {
        final String name;            // 图标下方全名
        final int color;              // 图标底色
        final String center;          // 图标中心内容（文字或 emoji）
        final boolean emoji;          // 中心是否为 emoji（配合 centerScale 用大字号）
        final float centerScale;      // 中心内容字号倍率
        final int centerColor;        // 中心内容颜色
        final boolean implemented;    // 是否已实现（否则点开 ComingSoon）
        final boolean hidden;         // 暂隐藏（逻辑保留）
        final Supplier<IPhonePage> pageFactory;
        /**
         * 图标中心的贴图（附属模组注册的 App 用）；为 null 表示走「文字 / emoji」画法。
         * 内置 App 全部为 null —— HuhuTalk 自己的贴图图标走的是它专用的特殊分支，不经过这里。
         */
        final ResourceLocation iconTexture;
        final int iconTexWidth;       // 贴图源宽（像素）
        final int iconTexHeight;      // 贴图源高（像素）
        final float iconFill;         // 贴图在图标方块内的占比（1.0 = 撑满）

        /** 内置 App 用的构造器（无贴图图标）。 */
        AppInfo(String name, int color, String center, boolean emoji, float centerScale, int centerColor,
                boolean implemented, boolean hidden, Supplier<IPhonePage> pageFactory) {
            this(name, color, center, emoji, centerScale, centerColor,
                    implemented, hidden, pageFactory, null, 0, 0, 1.0f);
        }

        /** 完整构造器：多出「贴图图标」一组字段，供附属模组注册的 App 使用。 */
        AppInfo(String name, int color, String center, boolean emoji, float centerScale, int centerColor,
                boolean implemented, boolean hidden, Supplier<IPhonePage> pageFactory,
                ResourceLocation iconTexture, int iconTexWidth, int iconTexHeight, float iconFill) {
            this.name = name;
            this.color = color;
            this.center = center;
            this.emoji = emoji;
            this.centerScale = centerScale;
            this.centerColor = centerColor;
            this.implemented = implemented;
            this.hidden = hidden;
            this.pageFactory = pageFactory;
            this.iconTexture = iconTexture;
            this.iconTexWidth = iconTexWidth;
            this.iconTexHeight = iconTexHeight;
            this.iconFill = iconFill;
        }
    }
}
