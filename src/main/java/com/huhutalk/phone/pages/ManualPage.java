package com.huhutalk.phone.pages;

import com.huhutalk.api.phone.ui.PhoneLayout;
import com.huhutalk.api.phone.ui.PhonePage;
import com.huhutalk.api.phone.ui.PhoneShapes;
import com.huhutalk.api.phone.ui.PhoneText;
import com.huhutalk.client.PhotoLibrary;
import com.huhutalk.client.theme.ColorScheme;
import com.huhutalk.event.ClientModEvents;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 手机内置手册 / 设置页：三级目录导航。
 * <p>
 * 一级 = 大栏目（{@link ManualSection}），点开进入其二级小栏目列表（{@link ManualEntry}），
 * 再点开小栏目进入正文（可滚动文本 + 可选图片）。
 * 右键（或顶栏「&lt; 返回」）逐级返回：正文 → 小栏目列表 → 一级列表 → 桌面。
 * 图片 PNG 放 {@code gameDir/huhutalk/manual/}，条目通过 {@code imageKey} 引用。
 *
 * <h2>改用公开骨架（批次 4）</h2>
 * 旧实现 {@code implements IPhonePage}，把每个页面都要抄一遍的样板都写在文件里：
 * 手绘顶栏与「‹ 返回」、手算顶栏高度（{@code sh(22)}）、手配对 {@code enableScissor}，
 * 并且在 {@code render} 与 {@code mouseClicked} 里<b>各算一遍</b>同一批行坐标
 * （「第 i 行画在哪」与「点在哪算第 i 行」靠人肉保持同步）。现在：
 * <ul>
 *   <li>顶栏 / 底色 / 裁剪 / 主题取色 → {@link PhonePage}；标题<b>左对齐</b>画在返回键右侧
 *       （见 {@link #renderTopBarExtras}），所以 {@link #title()} 返回空串；</li>
 *   <li>本页是混合排版，走<b>模型 A（页面自管滚动）</b>：只实现 {@link #contentHeight()}；</li>
 *   <li>三级的全部 y 由<b>唯一的</b> {@link #layout()} 产出（逻辑坐标），
 *       渲染走 {@link PhonePage#scrollY(int)}、命中走 {@link PhonePage#logicalY(double)}，
 *       两者严格互逆，所以「画在哪」与「点在哪」在结构上不可能不同步；</li>
 *   <li>画字 → {@link PhoneText}，命中 → {@link PhoneLayout}，圆角 → {@link PhoneShapes}。</li>
 * </ul>
 *
 * <h2>三级共用一个 {@code scrollOffset}</h2>
 * {@link PhonePage} 只有一个滚动偏移，而本页三级各有各的位置，所以每一级各留一个记忆字段
 * （{@link #rootScroll} / {@link #sectionScroll} / {@link #bodyScroll}）：
 * 正在显示的那一级在 {@link #renderContent} 末尾与骨架的偏移同步；
 * 切级别时<b>先</b>改级别字段（{@link #currentSection} / {@link #currentEntry}）、
 * <b>再</b> {@link #setScrollOffset(int)} —— 它按 {@link #maxScroll()} 钳制，而后者来自
 * {@link #contentHeight()}（当前级别），顺序反了就会拿旧级别的上限钳新级别的值。
 *
 * <h2>与旧实现的差异（刻意接受）</h2>
 * <ul>
 *   <li>页面底 / 卡片底 / 正文色改走主题色板（旧实现硬编码 {@code #f5ebf2} / 白 / 纯黑，略有色差）；
 *       顶栏仍显式返回 {@link PhonePage#BRAND_TOPBAR_BG}，与聊天 / 消息 / 设置页一致；</li>
 *   <li>返回键文案由「‹ 返回」变成骨架统一的「&lt; 返回」；</li>
 *   <li>顶栏高度改用骨架公式（一行字 + 8px ≈ 17px，旧实现硬编码 {@code sh(22)}），内容整体上移约 5px；</li>
 *   <li>列表不再单独裁剪到上下各留白 6px 的窄带内（骨架已把内容整体裁到内容区），
 *       贴着上下边界的那一行最多多露出 6px；</li>
 *   <li>顶栏区域上的滚轮不再滚动内容（骨架的 {@code mouseScrolled} 只在内容区生效）；</li>
 *   <li>命中区间由闭区间改为 {@link PhoneLayout#hit} 的半开区间（边界像素归属不同）。</li>
 * </ul>
 *
 * <h2>附属模组怎么往这里追加说明</h2>
 * 调 {@link #registerSection(String, String, java.util.List)}（纯新增的公开通路），
 * 你注册的栏目会<b>追加在内置栏目之后</b>，内置的顺序与内容不受影响。
 * 本页每次打开都会新建一个 {@code ManualPage}，所以"注册之后下一次打开手册"一定能看到；
 * 而注册本身在任何早于"玩家打开手册"的时机做都可以（模组构造期最省心）。
 */
public class ManualPage extends PhonePage {

    // ==================== 布局常量（基准像素，运行时经 PhoneText.px/scale 缩放） ====================

    /** 一级 / 二级列表的行高 */
    private static final int ROW_H = 34;
    /** 一级 / 二级列表的行间距 */
    private static final int ROW_GAP = 4;
    /** 列表卡片距手机左右边 */
    private static final int CARD_PAD = 8;
    /** 列表卡片圆角 */
    private static final int CARD_RADIUS = 8;
    /** 卡片文字距卡片左边 */
    private static final int CARD_TEXT_PAD = 10;
    /** 列表可视区的上下留白（在内容坐标里） */
    private static final int LIST_PAD = 6;
    /** 正文可滚动视口距内容区顶 */
    private static final int BODY_TOP = 4;
    /** 正文可滚动视口距内容区底 */
    private static final int BODY_BOTTOM = 6;
    /** 正文与底部固定配图之间的间距 */
    private static final int IMG_GAP = 4;
    /** 一级 / 二级列表行文字的缩放 */
    private static final float ROW_TEXT_SCALE = 0.9f;
    /** 顶栏标题的缩放（左对齐，不居中） */
    private static final float TOP_TITLE_SCALE = 0.85f;
    /** 顶栏标题与返回键文案之间的间距 */
    private static final int TOP_TITLE_GAP = 10;
    /** 滚动速度：1 单位滚轮 = 多少逻辑像素（保持旧实现的 {@code UiFont.sh(10)}） */
    private static final int SCROLL_SPEED = 10;
    /** 骨架画返回键文案的左边距（{@code PhonePage} 内部固定为 4），本页只用来把标题摆在它右边 */
    private static final int BACK_LABEL_X = 4;
    /** 与 {@code PhonePage} 内部的返回键文案一致的语言键，仅用于量它的宽度把标题摆到右边 */
    private static final String BACK_LABEL_KEY = "gui.huhutalk.page.back";

    /**
     * 正文配图最多占内容区可视高度的比例（其余留给正文）。
     * <p>
     * 手册里像「图9/图10」那种竖屏截图（798x1340）按宽度铺满后会高得把正文挤成一条缝，
     * 所以高度封顶、按比例回缩宽度（见 {@link #layout()}）。
     */
    private static final float IMG_MAX_HEIGHT_RATIO = 0.62f;

    // =============================================================================================
    // 附属模组追加的栏目（公开注册通路）
    // =============================================================================================

    /**
     * 由附属模组 {@linkplain #registerSection(String, String, java.util.List) 注册} 进来的栏目。
     * <p>
     * 它是一份<b>进程内全局</b>的表，与"当前打开着哪一个 ManualPage 实例"无关 ——
     * 每次构造 {@code ManualPage} 时把它的快照追加到实例自己的 {@link #sections} 里。
     * 这样"什么时候注册"就不敏感了：注册晚于某一次打开，下一次打开照样能看到。
     */
    private static final List<ManualSection> EXTERNAL_SECTIONS = new ArrayList<>();
    /** 去重键（{@code ownerModId + 标题}），防止同一个模组因为重复注册而出现两节一样的说明。 */
    private static final java.util.Set<String> EXTERNAL_KEYS = new java.util.LinkedHashSet<>();

    /**
     * {@code true} = 已经有人注册过（用于把"首次注册"打进日志，便于排查"我注册了但手册里没有"）。
     */
    private static boolean externalLogged;

    /**
     * 往手机手册里追加一节说明。这是给附属模组用的公开入口。
     *
     * <h2>怎么用</h2>
     * <pre>{@code
     * ManualPage.registerSection("mymod", "我的功能", List.of(
     *         new ManualPage.ManualEntry("标题", "正文（\n 可换行，会自动折行）", null)));
     * }</pre>
     *
     * <h2>时机</h2>
     * 在<b>玩家打开手册之前</b>的任何时候都可以，模组构造期最省心 ——
     * 本方法不依赖任何加载顺序，注册表是静态的，而手册每次打开都会重新读一遍。
     * 也因此<b>重复调用是安全的</b>（同一个 {@code ownerModId + title} 只生效第一次）。
     *
     * <h2>为什么不抛异常</h2>
     * 附属模组的注册出问题不该把玩家的手册（乃至手机）搞没：参数非法一律记一条警告后返回 false。
     *
     * @param ownerModId 注册方模组 id，只用于日志与去重；不要留空
     * @param title      一级栏目名（手册首页那一行文字）；不要留空，太长会顶到卡片右边界
     * @param entries    该栏目下的二级条目；空列表 = 注册了也看不见，所以会被拒绝
     * @return true = 本次注册生效；false = 被忽略（参数非法 / 重复 / 非客户端）
     */
    public static boolean registerSection(String ownerModId, String title, List<ManualEntry> entries) {
        if (ownerModId == null || ownerModId.isBlank() || title == null || title.isBlank()) {
            com.huhutalk.HuhuTalk.LOGGER.warn("[HuhuTalk/Manual] 注册手册栏目时 ownerModId/title 为空，已忽略");
            return false;
        }
        if (entries == null || entries.isEmpty()) {
            com.huhutalk.HuhuTalk.LOGGER.warn("[HuhuTalk/Manual] 手册栏目「{}」（{}）没有任何条目，已忽略", title, ownerModId);
            return false;
        }
        // 手册是纯客户端的东西，服务端注册没有意义 —— 与 PhoneAppRegistry 一致，挡掉而不是抛异常
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist != net.minecraftforge.api.distmarker.Dist.CLIENT) {
            com.huhutalk.HuhuTalk.LOGGER.warn("[HuhuTalk/Manual] 非客户端环境尝试注册手册栏目「{}」，已忽略", title);
            return false;
        }
        String key = ownerModId + "\u0000" + title;
        // 条目列表整体复制一份：调用方之后改自己的 list 不该影响已经注册进来的内容
        ManualSection section = new ManualSection(title, List.copyOf(entries));
        synchronized (ManualPage.class) {
            if (!EXTERNAL_KEYS.add(key)) {
                com.huhutalk.HuhuTalk.LOGGER.warn("[HuhuTalk/Manual] 手册栏目「{}」（{}）重复注册，已忽略后一次",
                        title, ownerModId);
                return false;
            }
            EXTERNAL_SECTIONS.add(section);
            if (!externalLogged) {
                externalLogged = true;
                com.huhutalk.HuhuTalk.LOGGER.info(
                        "[HuhuTalk/Manual] 已支持附属模组追加手册栏目（首次来自 {} 的「{}」）", ownerModId, title);
            } else {
                com.huhutalk.HuhuTalk.LOGGER.info("[HuhuTalk/Manual] 已注册手册栏目「{}」（{}）", title, ownerModId);
            }
        }
        return true;
    }

    /** 当前由附属模组追加进来的栏目快照（诊断用；手册渲染不直接调它）。 */
    public static List<ManualSection> externalSections() {
        synchronized (ManualPage.class) {
            return List.copyOf(EXTERNAL_SECTIONS);
        }
    }

    /** 二级条目：标题 + 正文（支持 \n 换行、自动折行）+ 可选图片文件名 */
    public record ManualEntry(String title, String body, String imageKey) {}
    /** 一级大栏目：标题 + 其下二级条目列表 */
    public record ManualSection(String title, List<ManualEntry> entries) {}

    /**
     * 手机功能键当前绑定的显示名（玩家改绑后手册要立刻跟着变）。
     * <p>
     * 手册正文原来把「鼠标按键4/5」写死，玩家把功能键改绑到 W 之后这段就变成假文档。
     * 与 {@code PhoneCameraPage#keyHint}、{@code ChatPage} 同一套做法：走
     * {@code getTranslatedKeyMessage()} 取当前绑定，而不是硬编码字符串。
     */
    private static String keyDisplayName(net.minecraft.client.KeyMapping mapping) {
        return mapping.getTranslatedKeyMessage().getString();
    }

    /**
     * 内置栏目 / 条目的<b>对象身份</b>集合（{@link java.util.IdentityHashMap}）。
     * <p>
     * 为什么不能只看前缀：附属模组注册的原文恰好以 {@code gui.huhutalk.manual.} 开头时，
     * 前缀判定会把它当成内置键去翻译（翻不到就显示键名），原文被吞掉。
     * 所以"内置与否"只认"这个对象是不是本类构造器建的那批"，外部对象一律原样返回。
     */
    private final java.util.Set<ManualSection> builtinSections =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final java.util.Set<ManualEntry> builtinEntries =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * 解析一条手册文案：<b>内置对象</b>的键按当前语言翻译（含按键占位符的键在此填入实际绑定），
     * 外部注册的对象一律原样返回。
     * <p>每次渲染/排版都调用，所以切语言下一帧生效，重绑按键也立刻反映。
     */
    private String resolve(Object owner, String raw) {
        if (raw == null || !isBuiltin(owner)) {
            return raw;
        }
        Object[] args = dynamicKeyArgs(raw);
        return args == null ? PhoneText.tr(raw) : PhoneText.tr(raw, args);
    }

    /** {@code owner} 是否为本类构造器建立的内置栏目 / 条目（按对象身份，不看字符串前缀）。 */
    private boolean isBuiltin(Object owner) {
        return (owner instanceof ManualSection s && builtinSections.contains(s))
                || (owner instanceof ManualEntry e && builtinEntries.contains(e));
    }

    /** 带按键占位符的内置键 → 当前实际绑定名（其余返回 null，表示无占位符）。 */
    private static Object[] dynamicKeyArgs(String key) {
        return switch (key) {
            case "gui.huhutalk.manual.phone.keys.title",
                 "gui.huhutalk.manual.phone.keys.body" -> new Object[]{
                    keyDisplayName(ClientModEvents.PHONE_CLICK),
                    keyDisplayName(ClientModEvents.PHONE_BACK)};
            case "gui.huhutalk.manual.chat.camera.body" -> new Object[]{
                    keyDisplayName(ClientModEvents.PHONE_CLICK),
                    keyDisplayName(ClientModEvents.PHONE_BACK)};
            // 独立屏幕的唤出键 = 「打开/关闭手机」，语音输入键 = 「按住说话」：
            // 玩家改绑后手册立刻跟着变，不再硬编码 O / H。
            case "gui.huhutalk.manual.settings.alt_screen.body" -> new Object[]{
                    keyDisplayName(ClientModEvents.TOGGLE_PHONE)};
            case "gui.huhutalk.manual.chat.voice.body" -> new Object[]{
                    keyDisplayName(ClientModEvents.QUICK_VOICE)};
            default -> null;
        };
    }

    private final List<ManualSection> sections;
    /** 当前打开的一级栏目；null = 一级列表 */
    private ManualSection currentSection = null;
    /** 当前打开的二级条目；null = 二级列表 */
    private ManualEntry currentEntry = null;

    /**
     * 一级栏目的滚动偏移。
     * <p>
     * 一级列表和二级列表是两套独立的滚动位置（{@link #sectionScroll} 属于二级），
     * 所以这里必须是另一个字段：从二级退回一级时，一级应该还停在玩家离开时的那一屏。
     * <p>
     * <b>为什么一级列表也需要滚动</b>：内置栏目已经 11 节，加上附属模组追加的会更多，
     * 而手机内容区默认只放得下 4 行左右（`(h - 页眉 - 留白) / (行高 + 行距)`）——
     * 没有滚动的话第 5 节往后<b>既不显示也点不到</b>，附属模组的栏目更是永远看不见。
     */
    private int rootScroll = 0;
    /** 二级列表滚动偏移 */
    private int sectionScroll = 0;
    /** 正文滚动偏移 */
    private int bodyScroll = 0;

    /**
     * 构造时把内置栏目 + {@link #EXTERNAL_SECTIONS} 的快照收进本实例自己的 {@link #sections}。
     * <p>内置标题与正文只存语言键，渲染/排版时经 {@link #resolve(String)} 解析，不被静态缓存。
     */
    public ManualPage() {
        sections = new ArrayList<>();
        sections.add(sec("gui.huhutalk.manual.sec.huhutalk",
                entry("gui.huhutalk.manual.huhutalk.what.title", "gui.huhutalk.manual.huhutalk.what.body"),
                entry("gui.huhutalk.manual.huhutalk.loading.title", "gui.huhutalk.manual.huhutalk.loading.body"),
                entry("gui.huhutalk.manual.huhutalk.extra_cmd.title", "gui.huhutalk.manual.huhutalk.extra_cmd.body")));
        sections.add(sec("gui.huhutalk.manual.sec.phone",
                entry("gui.huhutalk.manual.phone.draw_font.title", "gui.huhutalk.manual.phone.draw_font.body"),
                entry("gui.huhutalk.manual.phone.keys.title", "gui.huhutalk.manual.phone.keys.body"),
                entry("gui.huhutalk.manual.phone.emg_close.title", "gui.huhutalk.manual.phone.emg_close.body",
                        fig(1))));
        sections.add(sec("gui.huhutalk.manual.sec.settings",
                entry("gui.huhutalk.manual.settings.entry.title", "gui.huhutalk.manual.settings.entry.body"),
                entry("gui.huhutalk.manual.settings.alt_screen.title", "gui.huhutalk.manual.settings.alt_screen.body",
                        fig(11)),
                entry("gui.huhutalk.manual.settings.chat.title", "gui.huhutalk.manual.settings.chat.body", fig(2)),
                entry("gui.huhutalk.manual.settings.potion.title", "gui.huhutalk.manual.settings.potion.body", fig(3)),
                entry("gui.huhutalk.manual.settings.supply.title", "gui.huhutalk.manual.settings.supply.body", fig(3)),
                entry("gui.huhutalk.manual.settings.sos.title", "gui.huhutalk.manual.settings.sos.body"),
                entry("gui.huhutalk.manual.settings.anim.title", "gui.huhutalk.manual.settings.anim.body", fig(4))));
        sections.add(sec("gui.huhutalk.manual.sec.chat",
                entry("gui.huhutalk.manual.chat.input.title", "gui.huhutalk.manual.chat.input.body"),
                entry("gui.huhutalk.manual.chat.toolbar.title", "gui.huhutalk.manual.chat.toolbar.body", fig(5)),
                entry("gui.huhutalk.manual.chat.voice.title", "gui.huhutalk.manual.chat.voice.body", fig(6)),
                entry("gui.huhutalk.manual.chat.sticker.title", "gui.huhutalk.manual.chat.sticker.body", fig(7)),
                entry("gui.huhutalk.manual.chat.gallery.title", "gui.huhutalk.manual.chat.gallery.body", fig(8)),
                entry("gui.huhutalk.manual.chat.camera.title", "gui.huhutalk.manual.chat.camera.body", fig(9)),
                entry("gui.huhutalk.manual.chat.file.title", "gui.huhutalk.manual.chat.file.body", fig(10))));
        sections.add(sec("gui.huhutalk.manual.sec.contacts",
                entry("gui.huhutalk.manual.contacts.online.title", "gui.huhutalk.manual.contacts.online.body"),
                entry("gui.huhutalk.manual.contacts.offline.title", "gui.huhutalk.manual.contacts.offline.body"),
                entry("gui.huhutalk.manual.contacts.shutdown.title", "gui.huhutalk.manual.contacts.shutdown.body",
                        fig(13)),
                entry("gui.huhutalk.manual.contacts.settings.title", "gui.huhutalk.manual.contacts.settings.body",
                        fig(12))));
        sections.add(sec("gui.huhutalk.manual.sec.navbar",
                entry("gui.huhutalk.manual.navbar.signal.title", "gui.huhutalk.manual.navbar.signal.body"),
                entry("gui.huhutalk.manual.navbar.battery.title", "gui.huhutalk.manual.navbar.battery.body", fig(14))));
        sections.add(sec("gui.huhutalk.manual.sec.commands",
                entry("gui.huhutalk.manual.commands.move.title", "gui.huhutalk.manual.commands.move.body"),
                entry("gui.huhutalk.manual.commands.come.title", "gui.huhutalk.manual.commands.come.body"),
                entry("gui.huhutalk.manual.commands.stay.title", "gui.huhutalk.manual.commands.stay.body")));
        sections.add(sec("gui.huhutalk.manual.sec.compat",
                entry("gui.huhutalk.manual.compat.journeymap.title", "gui.huhutalk.manual.compat.journeymap.body")));
        sections.add(sec("gui.huhutalk.manual.sec.items",
                entry("gui.huhutalk.manual.items.watch.title", "gui.huhutalk.manual.items.watch.body", fig(15)),
                entry("gui.huhutalk.manual.items.wifi_block.title", "gui.huhutalk.manual.items.wifi_block.body",
                        fig(16)),
                entry("gui.huhutalk.manual.items.pro_phone.title", "gui.huhutalk.manual.items.pro_phone.body",
                        fig(17))));
        sections.add(sec("gui.huhutalk.manual.sec.outro",
                entry("gui.huhutalk.manual.outro.final.title", "gui.huhutalk.manual.outro.final.body")));

        // 附属模组追加的栏目：**一定放在最后**，内置栏目的顺序与内容是玩家已经看惯的，
        // 不该因为装了什么附属模组而变化。（注册通路见 registerSection）
        // 此刻 sections 里只有内置栏目：先按对象身份记下来，resolve 用它区分内置键与外部原文。
        for (ManualSection s : sections) {
            builtinSections.add(s);
            builtinEntries.addAll(s.entries());
        }
        synchronized (ManualPage.class) {
            sections.addAll(EXTERNAL_SECTIONS);
        }
    }

    private static ManualSection sec(String title, ManualEntry... entries) {
        return new ManualSection(title, List.of(entries));
    }

    private static ManualEntry entry(String title, String body) {
        return new ManualEntry(title, body, null);
    }

    /**
     * 带配图的条目。
     *
     * @param imageKey 配图资源键，见 {@link #fig(int)}。渲染时固定在正文下方
     *                 （见 {@link #layout()}），高度超过内容区 {@link #IMG_MAX_HEIGHT_RATIO} 会按比例缩小。
     */
    private static ManualEntry entry(String title, String body, String imageKey) {
        return new ManualEntry(title, body, imageKey);
    }

    /**
     * 手册配图的资源键：{@code assets/huhutalk/manual/manual_fig_N.png}。
     * <p>
     * 图片随模组打包（由 {@code tools/import_manual_assets.py} 从需求方的「模组配图」目录导入），
     * 由 {@code PhotoLibrary.readBytes} 的"jar 内兜底"那条路读出来 ——
     * 玩家不需要往 {@code .minecraft/huhutalk/manual/} 里手工拷图；
     * 想用自己的图覆盖时，把同名文件放进那个目录即可（磁盘优先）。
     */
    private static String fig(int n) {
        return "manual_fig_" + n;
    }

    // ==================== 骨架钩子 ====================

    /**
     * 顶栏不写居中标题。
     * <p>
     * 本页的标题按内置页面惯例<b>左对齐</b>画在返回键右边（见 {@link #renderTopBarExtras}），
     * 而骨架的 {@link PhonePage#title()} 是居中的，所以这里返回空串。
     */
    @Override
    protected String title() {
        return "";
    }

    /** 列表 / 条目类页面底色：主题里比主要背景深一档（旧实现硬编码 {@code 0xFFF5EBF2}） */
    @Override
    protected int pageBackground() {
        return colors().pageBgList();
    }

    /** 顶栏锁成品牌色：以后新增配色方案时本页仍与聊天 / 消息 / 设置是同一台手机（旧实现硬编码 {@code 0xFFDE7E7E}） */
    @Override
    protected int topBarBackground() {
        return BRAND_TOPBAR_BG;
    }

    /** 保持旧实现的滚动速度（{@code UiFont.sh(10)} = {@code PhoneText.px(10)}） */
    @Override
    protected double scrollSpeed() {
        return PhoneText.px(SCROLL_SPEED);
    }

    /**
     * 模型 A（页面自管滚动）：返回<b>当前级别</b>的内容总高。
     * <p>
     * 三级各有各的滚动位置，但骨架只有一个 {@code scrollOffset}，所以这里必须按当前级别
     * （{@link #currentEntry} / {@link #currentSection}）给出对应的高度，
     * {@link PhonePage#maxScroll()} 才会按这一级钳制。
     */
    @Override
    protected int contentHeight() {
        return layout().contentH;
    }

    /** 顶栏标题文本：一级列表显示「设置」，二 / 三级显示当前一级栏目名（旧实现的正文页也显示栏目名） */
    private String topBarTitle() {
        return currentSection != null
                ? resolve(currentSection, currentSection.title())
                : PhoneText.tr("gui.huhutalk.manual.root_title");
    }

    /**
     * 顶栏标题：<b>左对齐</b>画在骨架的返回键右边。
     * <p>
     * 起点 = 骨架画返回键的位置（{@code baseX + 4}）+ 返回键文案宽度 + {@link #TOP_TITLE_GAP}。
     * {@link #BACK_LABEL_KEY} 与 {@code PhonePage} 内部返回键用的是同一个键，这里只用来量宽度。
     */
    @Override
    protected void renderTopBarExtras(GuiGraphics g, int baseX, int baseY, int w, int barH, int textY) {
        Font font = font();
        int titleX = baseX + BACK_LABEL_X
                + PhoneText.width(font, PhoneText.tr(BACK_LABEL_KEY), PhoneText.scale(1.0f))
                + PhoneText.px(TOP_TITLE_GAP);
        float titleScale = PhoneText.scale(TOP_TITLE_SCALE);
        int avail = Math.max(0, w - (titleX - baseX) - PhoneText.px(6));
        PhoneText.draw(g, font, PhoneText.truncate(font, topBarTitle(), avail, titleScale),
                titleX, textY, colors().topBarText(), titleScale);
    }

    // ==================== 布局 ====================

    /**
     * 每帧的布局结果，所有 y 都是<b>逻辑坐标</b>（内容坐标、<b>未含滚动</b>）：
     * 渲染时 {@code scrollY(y)}、命中时 {@code logicalY(localY)}。
     */
    private static final class Layout {
        /** 当前级别的内容总高（{@link #contentHeight()} 的返回值） */
        int contentH;
        /** 卡片左右留白（逻辑像素） */
        int pad;
        /** 一倍字号的行高（顶栏对齐与行内垂直居中用） */
        int lineH;
        // ---- 列表级别（一级栏目 / 二级条目共用同一套几何）----
        int listCount;
        int listTop;
        int listBottom;
        int rowH;
        int gap;
        // ---- 正文级别 ----
        /** 正文可滚动视口的上下界（逻辑坐标） */
        int bodyTop;
        int bodyBottom;
        /** 底部固定配图（不参与滚动） */
        int imgX, imgY, imgW, imgH;
        ResourceLocation imgTex;
        int srcW, srcH;
        /** 折行后的正文 */
        List<FormattedCharSequence> bodyLines = List.of();
    }

    /** 列表行标题的来源：一级列表取栏目名、二级列表取条目名，其余渲染完全一致。 */
    private interface RowTitles {
        String at(int index);
    }

    /**
     * <b>唯一</b>的布局计算：{@link #renderContent} / {@link #onContentClick} /
     * {@link #contentHeight()} 都调它，不再有任何一处在别处重算行 y；
     * 渲染走 {@link PhonePage#scrollY(int)}、命中走 {@link PhonePage#logicalY(double)}，
     * 二者严格互逆，所以「画在哪」与「点在哪」结构上不可能不同步。
     * <p>
     * 每一级的 {@code contentH} 都写成「内容高 + (视口高 - 该级别的可视高)」，
     * 于是 {@link PhonePage#maxScroll()}（= {@code contentH - contentViewHeight()}）
     * 恰好等于旧实现那份 {@code max(0, 内容高 - 视口高)} —— 钳制范围逐像素一致。
     */
    private Layout layout() {
        Font font = font();
        int h = contentViewHeight();
        Layout L = new Layout();
        L.pad = PhoneText.px(CARD_PAD);
        L.lineH = Math.max(1, PhoneText.height(font, PhoneText.scale(1.0f)));

        if (currentEntry != null) {
            // ---- 正文级别 ----
            L.bodyTop = PhoneText.px(BODY_TOP);
            int viewBottom = h - PhoneText.px(BODY_BOTTOM);
            if (currentEntry.imageKey() != null) {
                ResourceLocation tex = PhotoLibrary.getOrLoadTexture(currentEntry.imageKey());
                int[] dims = PhotoLibrary.getTextureSize(currentEntry.imageKey());
                if (tex != null) {
                    int availW = width() - L.pad * 2;
                    int imgW = Math.min(availW, dims[0]);
                    int imgH = Math.max(1, (int) (dims[1] * (imgW / (float) Math.max(1, dims[0]))));
                    // 高度封顶：手册里的竖屏截图（如 798x1340）按宽度铺开后会高得把正文挤成一条缝。
                    // 封顶后按同一比例把宽度也缩回去（绝不拉伸），并水平居中。
                    int maxH = Math.max(1, (int) ((viewBottom - L.bodyTop) * IMG_MAX_HEIGHT_RATIO));
                    if (imgH > maxH) {
                        imgW = Math.max(1, (int) (imgW * (maxH / (float) imgH)));
                        imgH = maxH;
                    }
                    L.imgTex = tex;
                    L.srcW = dims[0];
                    L.srcH = dims[1];
                    L.imgW = imgW;
                    L.imgH = imgH;
                    L.imgX = L.pad + Math.max(0, (availW - imgW) / 2);
                    L.imgY = viewBottom - imgH;
                }
            }
            // 正文视口下界要让开底部固定的配图
            L.bodyBottom = viewBottom - (L.imgH > 0 ? L.imgH + PhoneText.px(IMG_GAP) : 0);
            L.bodyLines = wrapBody(font, width() - L.pad * 2);
            int textH = L.bodyLines.size() * font.lineHeight;
            L.contentH = textH + (h - (L.bodyBottom - L.bodyTop));
            return L;
        }

        // ---- 列表级别（一级栏目 / 二级条目共用同一套几何）----
        L.listCount = currentSection != null ? currentSection.entries().size() : sections.size();
        L.rowH = PhoneText.px(ROW_H);
        L.gap = PhoneText.px(ROW_GAP);
        L.listTop = PhoneText.px(LIST_PAD);
        L.listBottom = h - PhoneText.px(LIST_PAD);
        int listH = L.listCount * (L.rowH + L.gap);
        L.contentH = listH + (h - (L.listBottom - L.listTop));
        return L;
    }

    // ==================== 渲染 ====================

    @Override
    protected void renderContent(GuiGraphics g, int originX, int originY, int w, int h, float partialTick) {
        Layout L = layout();
        if (currentEntry != null) {
            renderBody(g, originX, originY, w, L);
        } else if (currentSection != null) {
            renderList(g, originX, originY, w, L, i -> currentSection.entries().get(i).title());
        } else {
            renderList(g, originX, originY, w, L, i -> sections.get(i).title());
        }
        // 正在显示的级别：把骨架的滚动偏移同步回本级别的记忆字段（切级别时用它还原上一屏）
        syncScrollField();
    }

    /**
     * 一级 / 二级列表：圆角卡片 + 「›  标题」，行 y 全部来自 {@link #layout()}。
     * <p>
     * 裁剪交给骨架（{@code PhonePage.render} 已把内容整体裁到内容区），这里只做视口外的行跳过。
     */
    private void renderList(GuiGraphics g, int originX, int originY, int w, Layout L, RowTitles titles) {
        Font font = font();
        ColorScheme c = colors();
        int clipTop = originY + L.listTop;
        int clipBottom = originY + L.listBottom;
        int cardW = w - L.pad * 2;
        int textPad = PhoneText.px(CARD_TEXT_PAD);
        int radius = PhoneText.px(CARD_RADIUS);
        float scale = PhoneText.scale(ROW_TEXT_SCALE);
        int cy = originY + scrollY(L.listTop);
        for (int i = 0; i < L.listCount; i++) {
            // 被滚出上边界的那几行直接跳过（继续累加 cy，不能 break —— 下面的行还有可能露出来）
            if (cy + L.rowH < clipTop) {
                cy += L.rowH + L.gap;
                continue;
            }
            if (cy > clipBottom) {
                break;
            }
            PhoneShapes.fillRoundedRect(g, originX + L.pad, cy, cardW, L.rowH, radius, c.cardBg());
            // 所有者按当前级别取：二级取条目、一级取栏目，交由 resolve 判内置/外置
            Object owner = currentSection != null ? currentSection.entries().get(i) : sections.get(i);
            String rowText = "›  " + resolve(owner, titles.at(i));
            PhoneText.draw(g, font,
                    PhoneText.truncate(font, rowText, Math.max(0, cardW - textPad - PhoneText.px(4)), scale),
                    originX + L.pad + textPad, cy + (L.rowH - L.lineH) / 2f, c.textPrimary(), scale);
            cy += L.rowH + L.gap;
        }
    }

    /**
     * 正文：底部固定配图（不参与滚动）+ 可滚动折行文本。
     * <p>
     * ⚠ 正文视口<b>窄于</b>内容区（底部让开了配图），所以这一处裁剪比骨架那层更窄，
     * 必须自己保留 —— 否则滚进来的文字会压到配图上。
     */
    private void renderBody(GuiGraphics g, int originX, int originY, int w, Layout L) {
        Font font = font();
        ColorScheme c = colors();

        if (L.imgTex != null) {
            g.blit(L.imgTex, originX + L.imgX, originY + L.imgY, L.imgW, L.imgH,
                    0.0f, 0.0f, L.srcW, L.srcH, L.srcW, L.srcH);
        }

        int clipTop = originY + L.bodyTop;
        int clipBottom = originY + L.bodyBottom;
        if (clipBottom <= clipTop) {
            return;
        }
        g.enableScissor(originX, clipTop, originX + w, clipBottom);
        try {
            int textY = originY + scrollY(L.bodyTop);
            for (FormattedCharSequence line : L.bodyLines) {
                if (textY + font.lineHeight >= clipTop && textY < clipBottom) {
                    g.drawString(font, line, originX + L.pad, textY, c.textPrimary(), false);
                }
                textY += font.lineHeight;
            }
        } finally {
            g.disableScissor();
        }
    }

    /**
     * 按当前条目正文折行（{@code \n} 分段，段内按宽度自动折行）。
     * <p>
     * 正文本身不随字体倍率缩放（与旧实现一致：行高就是 {@code font.lineHeight}）。
     */
    private List<FormattedCharSequence> wrapBody(Font font, int maxWidth) {
        List<FormattedCharSequence> out = new ArrayList<>();
        for (String para : resolve(currentEntry, currentEntry.body()).split("\n")) {
            if (para.isBlank()) {
                out.add(Component.literal(" ").getVisualOrderText());
                continue;
            }
            out.addAll(font.split(Component.literal(para), maxWidth));
        }
        return out;
    }

    // ==================== 交互 ====================

    /**
     * 内容区点击（<b>内容坐标</b>）：一级列表点开栏目、二级列表点开条目、正文页没有可点的东西。
     * <p>
     * ⚠ 切级别时<b>先</b>改 {@link #currentSection} / {@link #currentEntry}、<b>再</b>
     * {@link #setScrollOffset(int)}：后者按 {@link #maxScroll()} 钳制，而 {@code maxScroll()}
     * 来自 {@link #contentHeight()}（当前级别）。先钳制就会拿旧级别的上限钳新级别的值。
     */
    @Override
    protected boolean onContentClick(double localX, double localY, int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || action != GLFW.GLFW_PRESS) {
            return false;
        }
        // 正文页没有可点的东西（旧实现同样直接返回 false）
        if (currentEntry != null) {
            return false;
        }
        // 先把骨架里的当前位置刷回本级别的记忆字段，再按这一级做命中
        syncScrollField();

        Layout L = layout();
        double logical = logicalY(localY);
        int cardW = width() - L.pad * 2;
        for (int i = 0; i < L.listCount; i++) {
            int rowY = L.listTop + i * (L.rowH + L.gap);
            if (!PhoneLayout.hit(localX, logical, L.pad, rowY, cardW, L.rowH)) {
                continue;
            }
            if (currentSection != null) {
                currentEntry = currentSection.entries().get(i);
                bodyScroll = 0;
                setScrollOffset(bodyScroll);
            } else {
                currentSection = sections.get(i);
                currentEntry = null;
                sectionScroll = 0;
                bodyScroll = 0;
                setScrollOffset(sectionScroll);
            }
            return true;
        }
        return false;
    }

    /**
     * 逐级回退：正文 → 二级列表 → 一级列表 → false（交给上层 {@link PhoneOverlay#popPage()} 回桌面）。
     * <p>
     * ⚠ <b>先</b>改级别字段、<b>再</b> {@link #setScrollOffset(int)}（原因见 {@link #onContentClick}）。
     * <p>
     * 与旧实现逐字一致：退回二级列表时把正文滚动清零、退回一级列表时把二级滚动清零
     * （即"重进同一条目 / 同一个栏目都从顶部开始"），而一级列表保留玩家离开时的那一屏。
     */
    @Override
    public boolean onBackPressed() {
        if (currentEntry != null) {
            bodyScroll = 0;
            currentEntry = null;
            setScrollOffset(sectionScroll);
            return true;
        }
        if (currentSection != null) {
            sectionScroll = 0;
            currentSection = null;
            setScrollOffset(rootScroll);
            return true;
        }
        return false;
    }

    /**
     * 顶栏「&lt; 返回」按钮（骨架的钩子）。
     * <p>
     * 旧实现把这段逻辑写在 {@code mouseClicked} 的顶栏命中分支里：
     * {@code if (!onBackPressed()) PhoneOverlay.popPage();}。骨架把顶栏返回单独收成
     * {@code onBack()}，所以这里照抄同一逻辑 —— 「点返回键」与「右键返回」逐级回退的行为完全一致，
     * 在最外层（一级列表）仍然是出栈回桌面。
     */
    @Override
    protected void onBack() {
        if (!onBackPressed()) {
            PhoneOverlay.popPage();
        }
    }

    /** 把骨架的滚动偏移同步回"正在显示的那一级"的记忆字段。 */
    private void syncScrollField() {
        if (currentEntry != null) {
            bodyScroll = scrollOffset();
        } else if (currentSection != null) {
            sectionScroll = scrollOffset();
        } else {
            rootScroll = scrollOffset();
        }
    }
}
