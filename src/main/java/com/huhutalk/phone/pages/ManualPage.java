package com.huhutalk.phone.pages;

import com.huhutalk.client.PhotoLibrary;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.phone.UiFont;
import com.huhutalk.api.phone.ui.PhoneShapes;
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
 * 手机内置手册 / 设置页：两级目录导航。
 * <p>
 * 一级 = 大栏目（{@link ManualSection}），点开进入其二级小栏目列表（{@link ManualEntry}），
 * 再点开小栏目进入正文（可滚动文本 + 可选图片）。
 * 右键（或顶栏「‹ 返回」）逐级返回：正文 → 小栏目列表 → 一级列表 → 桌面。
 * <p>
 * 顶栏返回的命中区（{@link #hitHeaderBack}）与绘制（{@link #renderHeader}）共用
 * {@code UiFont.sh(22)} 这一份几何；"一级列表再往回"走公共通路
 * {@link PhoneOverlay#popPage()}，与 {@code api} 层 {@code PhonePage#onBack()} 的默认行为一致。
 * <p>
 * 背景色 {@link #PAGE_BG}（#f5ebf2）、黑色字体。二级列表与正文滚动区用 scissor 限定在内容区，
 * 不越出顶栏/手机边界。图片 PNG 放 {@code gameDir/huhutalk/manual/}，条目通过 {@code imageKey} 引用。
 *
 * <h2>附属模组怎么往这里追加说明</h2>
 * 调 {@link #registerSection(String, String, java.util.List)}（纯新增的公开通路），
 * 你注册的栏目会<b>追加在内置栏目之后</b>，内置的顺序与内容不受影响。
 * 本页每次打开都会新建一个 {@code ManualPage}，所以"注册之后下一次打开手册"一定能看到；
 * 而注册本身在任何早于"玩家打开手册"的时机做都可以（模组构造期最省心）。
 */
public class ManualPage implements IPhonePage {

    /** 页面底色 #f5ebf2 */
    private static final int PAGE_BG = 0xFFF5EBF2;
    /** 顶栏底色：改成 HuhuTalk 品牌粉，与聊天/消息/设置/图库等页面统一（原来跟主题是 #f06292） */
    private static final int HEADER_BG = com.huhutalk.api.phone.ui.PhonePage.BRAND_TOPBAR_BG;
    /** 黑色文字 */
    private static final int TEXT_COLOR = 0xFF000000;
    /** 次要文字（浅黑灰） */
    private static final int TEXT_SUB = 0xFF555555;
    /** 条目卡片底色 */
    private static final int CARD_BG = 0xFFFFFFFF;
    /**
     * 正文配图最多占内容区可视高度的比例（其余留给正文）。
     * <p>
     * 手册里像「图9/图10」那种竖屏截图（798x1340）按宽度铺满后会高得把正文挤成一条缝，
     * 所以高度封顶、按比例回缩宽度（见 {@link #renderBody}）。
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
        if (net.neoforged.fml.loading.FMLEnvironment.dist != net.neoforged.api.distmarker.Dist.CLIENT) {
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
    private float rootScroll = 0f;
    /** 二级列表滚动偏移 */
    private float sectionScroll = 0f;
    /** 正文滚动偏移 */
    private float scroll = 0f;

    public ManualPage() {
        sections = new ArrayList<>();
        sections.add(sec("huhutalk",
                entry("huhutalk", "狐狐talk是一款车万女仆（Touhou Little Maid）的附属模组。其主要目的是为了补充不能远程和女仆聊天的空缺，"
                        + "并且为女仆定制了专属于手机UI的聊天方式/新的接收路径/隐私模式的开关，欢迎并且感谢大家体验！\n"
                        + "——本模组由AI协助开发（我不是科班出身）。"),
                entry("关于加载", "模组采用了一些下发加载票据的方法，因此会有额外的性能开销。具体开销可见模组物品栏目。所以，请节制的使用Pro版手机。"),
                entry("额外指令", "在此基础上，添加了一些额外的指令（以后可能还加），可以使用自然语言，去往某坐标/呆在某坐标/来我身边"
                        + "指挥女仆AI，支持去某地做某事，呆在某地做某事等格式（具体见指南8）。")));
        sections.add(sec("关于手机",
                entry("绘制与字体", "手机使用overlay绘制。\n"
                        + "手机里的字体大小会跟随玩家设置的GUI界面尺寸而改变，所以请随心的调整你喜欢的字体比例。\n"
                        + "如果你使用了大GUI却想要小字体/使用了小GUI却想要大字体 ，在huhutalk内部的设置页面可再次缩放字体，"
                        + "但可能会有一些字体渲染问题出现。"),
                // 原来这条叫「辅助键（默认alt）」—— 那条"按住 Alt 释放鼠标"的通路早已拆除，
                // 现在手机用独立光标，只吃玩家绑定的两个功能键（默认鼠标侧键 4/5）。
                entry("操作键（默认鼠标按键4/5）", "手机需要用已绑定的功能键位来操作。左键=鼠标按键4，右键=鼠标侧键5，滚轮=按住按键4拖动。\n"
                        + "默认左键确认、右键返回上一级（当然也可以点击左上角的返回按钮）。"),
                // 「顶部导航栏」这条已按需求方要求并进「备用屏幕选项」（见下一节），从本节移走。
                entry("紧急关闭", "顶部导航栏的右角有一个隐形的紧急关闭按钮（叉叉），用来应对某种特殊情况。（见图1）",
                        fig(1))));
        sections.add(sec("关于设置",
                entry("设置入口", "设置界面位于huhutalk内部，请进入huhutalk，用底部导航栏切换到最右侧选项——外头那个设置暂时没功能。\n"
                        + "ps：因为这个模组叫huhutalk，只是觉得只有一个孤零零的APP太奇怪了，所以我打算后期多做点APP。"),
                // 「备用屏幕选项」= 原「屏幕大小」+「屏幕比例」+「顶部导航栏」+「亮度滑块」+「手机壳」五条并成一条。
                // 需求方要求合并后仍看得出每句原本是哪一项 → 统一用「原标题：」前缀（见交接文档的合并约定）。
                entry("备用屏幕选项（只能调节配用屏幕）", "玩家即使没有手机，也可以按o唤出独立屏幕。\n"
                        + "最高等于屏幕实际高度，最低等于屏幕的1/2（暂时没考虑有人用特别大的屏幕），如果你把屏幕缩小到字体都放不下，"
                        + "那么，滑块就没有办法固定——其实算是个小bug，但是可以防止卡位置，所以它是好bug。（图11）\n"
                        + "比例：支持20：9、16：9、4：3，内部UI会自适应内容。\n"
                        + "顶部导航栏：手机的上部导航栏（标注有时间、电量以及信号）是手机备用屏幕的位置拖动功能区，请将它移动到任何你喜欢的位置！\n"
                        + "亮度滑块：“手机壳也可以调暗。”\n"
                        + "手机壳：可以选择你背包内的任何完整方块，但是，有点不好看。",
                        fig(11)),
                // 「聊天设置」= 原「消息免打扰」+「女仆悄悄话」+「沉浸式聊天」三条并成一条（需求方原话：这三条合为一条）
                entry("聊天设置", "消息免打扰：打开会关闭红点提醒，同时关闭消息提示音，默认为关闭状态。\n"
                        + "女仆悄悄话：打开会关闭女仆的AI气泡显示——给您更私密的聊天体验\n"
                        + "沉浸式聊天：开启后所有有手机唤起的聊天，将不会在左下角公屏产生女仆回复。\n"
                        + "由于手机内的消息渲染特意加了输入延迟，所以事实上左下角公屏的内容会率先抵达，此选项是为提升沉浸感。（见图2）",
                        fig(2)),
                entry("药水复活", "开启后，你死亡时只要身上带着Pro手机、且女仆背包里有治疗药水，她就会在死亡后5秒后把药水远程送过来（直接砸你身上），"
                        + "并把你复活到药水能恢复的血量；掉进岩浆还会额外补一瓶抗火药水，掉进虚空则不会救援。\n"
                        + "每次触发都会请女仆走一次AI叙事，因此会消耗token——不想烧token的话可以在这里关掉。（见图3）",
                        fig(3)),
                entry("远程补给", "特殊工作模式，进入后女仆会给你补过低的生命值/饱食度。并且在退出模式时触发LLM，以此选项关闭模型对话。（见图3）",
                        fig(3)),
                entry("SOS", "在女仆界面，可以绑定女仆作为你的SOS召唤人，紧急情况，按这个按钮把他们召唤过来。"),
                // 「切屏与消息动画」= 原「打字耗时」（改名「消息动画」）+「动画」（改名「切屏动画」）并成一条
                entry("切屏与消息动画", "消息动画：此选项是为了增强真实感，给女仆的每条消息做了断句处理，并且根据字符的多少加了回复前摇"
                        + "（抢在他发完之前再发一句话，会让他一次渲染完）\n"
                        + "切屏动画：这个选项管理切屏动画——“谁不想要一个漂亮的UI动画？”（见图4）",
                        fig(4))));
        sections.add(sec("聊天页",
                entry("输入框", "点击即可开始输入按esc退出输入，回车发送。"),
                entry("底部扩展栏", "从左至右分别为语音输入、发送表情包、图库、拍照、附加栏。（见图5）", fig(5)),
                entry("语音输入", "默认按h键开始输入，但值得注意的是，此种输入方式不会触发私密聊天！（图6）", fig(6)),
                entry("表情包", "你发一个表情包，女仆一定会回一个表情包，但实际上不是llm回的（要不然token要烧完了）。当然，您可以要求女仆和你发送表情包。（图7）",
                        fig(7)),
                entry("图库", "在拍照界面点击保存的照片才会储存在这里，一次仅能发送一张图片，支持图片和文字一起发送，但需要等待图片完成识别后，女仆才会回复。（图8）\n"
                        + "注：借用了车万女仆的原生链路，目前仅开通了deepseek最新模型的识图功能，如果已经在原模组填入了ds API key，那么便可直接使用图片相关功能。\n"
                        + "注：识图延迟挺高，请耐心等待orz。",
                        fig(8)),
                entry("拍照", "按鼠标按键4拍照，长按鼠标按键4保存，鼠标按键5退出。\n"
                        + "如果不点击保存，那么点击确认的图片仅会在此次发送中生效，不会存储到本地。（图9）",
                        fig(9)),
                // 新增条目：玩家把物品随消息发给女仆（P→M 传文件）
                entry("传文件", "点击想发送的物品选中到待发送栏位，点击发送栏位内取消，返回聊天页面点击发送键发送。（图10）",
                        fig(10))));
        // 「联系人设置」按需求方要求**降级为小栏目**（不再是一个大栏目），并入「联系人」大栏目最后一条；
        // 它原来的四条内容并成这一条（合并约定同前：每条原文以「原标题：」开头）。
        sections.add(sec("联系人",
                entry("在线", "表示女仆正被加载且可通话"),
                entry("离线", "表示女仆处于被卸载区块内，已经无法通话，需要通过红狐狸卷轴寻找。"),
                entry("已关机", "女仆现在饰品栏里没有手机。（图13）", fig(13)),
                entry("联系人设置", "设置头像：默认会给女仆拍一张大头贴，后续考虑增加以表情包当头像/或者更多姿势。\n"
                        + "删除聊天：仅删除huhutalk附属模组的聊天存储，实际上下文仍然会存储在车万女仆模组之内。\n"
                        + "删除联系人：如果女仆不小心死了（大哭），总得有个把它删掉的办法（嗷嗷哭）。\n"
                        + "但是如果女仆只是因为丢失（区块未加载）而被删除联系人，下次她拿起手机会被重新录入联系人。\n"
                        + "绑定SOS：可以自行定义召唤哪个女仆，并且以什么模式将其召唤过来（针对不同的“兵种”），最多可添加四位女仆成为您的魔王护。（图12）",
                        fig(12))));
        // 「桌面」按需求方改名成「导航栏」（里面的内容说的就是顶部那条状态栏）
        sections.add(sec("导航栏",
                entry("信号栏", "不好的维度会产生信号延迟，末地为0.5s，地狱为1s。"),
                entry("电池", "“是核能的。”（图14）", fig(14))));
        sections.add(sec("指令",
                entry("Move to/去…等我", "女仆将去你指示的地点等待，当你靠近她身边，她会开始跟随你，此操作可以附带工作模式。"),
                entry("Come/到我这儿来", "女仆会到你身边来，如果离得近，她会走过来，离得远，则会传送。"),
                entry("Stay/去…呆着", "女仆会去往你指示的地点。开启home模式，在你经过后不会跟随你。")));
        sections.add(sec("模组兼容",
                entry("旅行地图", "兼容旅行地图（Journey map）。\n"
                        + "可使用自然语言指挥女仆到达路径点工作，例：“去快乐老家把菜一收。”“去史莱姆牧场等着我”")));
        sections.add(sec("物品与方块",
                entry("电话手表", "它是造价较低廉的手机，但是它没有流量卡，所以需要搭配WIFI方块使用。\n"
                        + "处在WIFI方块直径三个区块内的持有电话手表的女仆，可以保持常驻加载，会为其下发加载票据。（图15）",
                        fig(15)),
                entry("WIFI方块", "是本模组的节能设计，避免过多的女仆聚集在一起，每个都下发强制加载票据的性能问题，在其周围没有持有电话手表的女仆时，不会对周围的区块下发加载票据，所以不会产生性能开销。\n"
                        + "当且仅当周围区块有手表女仆时，才会对女仆所在的区块下发强制加载票据。（图16）",
                        fig(16)),
                entry("Pro版手机", "会持续为玩家的女仆下发强制加载的票据，但为保证性能，仅下发一区块的加载票据，所以——如果你的女仆是闪电侠，有可能会跑到未加载区块里。（图17）",
                        fig(17))));
        sections.add(sec("写在最后",
                entry("总之的总之！", "本模组由我（罗春）使用AI工作流协助开发，如果有任何bug/建议，烦请反映至Anirtual@gmail.com，谢谢！\n"
                        + "还有——感谢你们的下载与体验，我爱你们！")));

        // 附属模组追加的栏目：**一定放在最后**，内置栏目的顺序与内容是玩家已经看惯的，
        // 不该因为装了什么附属模组而变化。（注册通路见 registerSection）
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
     * @param imageKey 配图资源键，见 {@link #fig(int)}。渲染时固定在正文下方（
     *                 {@link #renderBody}），高度超过内容区 {@link #IMG_MAX_HEIGHT_RATIO} 会按比例缩小。
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

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics g, int baseX, int baseY, float partialTick) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        g.fill(baseX, baseY, baseX + w, baseY + h, PAGE_BG);

        if (currentEntry != null) {
            renderBody(g, baseX, baseY);
        } else if (currentSection != null) {
            renderSectionList(g, baseX, baseY);
        } else {
            renderSectionRoot(g, baseX, baseY);
        }
    }

    /** 通用顶栏：返回 + 标题 */
    private void renderHeader(GuiGraphics g, Font font, int baseX, int baseY, int w, String title) {
        int headerH = UiFont.sh(22);
        g.fill(baseX, baseY, baseX + w, baseY + headerH, HEADER_BG);
        drawScaledText(g, font, "‹ 返回", baseX + UiFont.sh(8), baseY + (headerH - lineH(font)) / 2,
                0xFFFFFFFF, UiFont.sc(1.0f));
        int backW = (int) (font.width("‹ 返回") * UiFont.sc(1.0f));
        drawScaledText(g, font, title, baseX + UiFont.sh(8) + backW + UiFont.sh(10),
                baseY + (headerH - lineH(font)) / 2, 0xFFFFFFFF, UiFont.sc(0.85f));
    }

    /**
     * 一级大栏目列表。
     *
     * <h2>为什么要滚动（第 7 轮改的）</h2>
     * 原实现是"装不下就 break"：既没有 scissor、也没有滚动。
     * 内置栏目已经有 11 节，而手机内容区默认只放得下 4 行左右，
     * 于是第 5 节起<b>既不显示也点不到</b> —— 附属模组追加的栏目永远看不到，
     * 「模组兼容」「物品与方块」这些内置栏目也一样。
     * 现在照抄二级列表那套已经验证过的写法（clamp + scissor + 滚动偏移）。
     *
     * <p>注意它与 {@link #renderSectionList}、{@link #mouseClicked}、
     * {@link #mouseScrolled} 必须共用同一份几何（top/bottom/rowH/gap/maxScroll），
     * 否则就会出现"看到的位置和点到的位置不一样"。
     */
    private void renderSectionRoot(GuiGraphics g, int baseX, int baseY) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        Font font = Minecraft.getInstance().font;
        renderHeader(g, font, baseX, baseY, w, "设置");

        int headerH = UiFont.sh(22);
        int pad = UiFont.sh(8);
        int top = baseY + headerH + UiFont.sh(6);
        int bottom = baseY + h - UiFont.sh(6);
        int rowH = UiFont.sh(34);
        int gap = UiFont.sh(4);
        int viewH = bottom - top;
        int contentH = sections.size() * (rowH + gap);
        int maxScroll = Math.max(0, contentH - viewH);
        rootScroll = clamp(rootScroll, maxScroll);

        g.enableScissor(baseX, top, baseX + w, bottom);
        int cy = top - (int) rootScroll;
        for (ManualSection s : sections) {
            // 被滚出上边界的那几行直接跳过（继续累加 cy，不能 break —— 下面的行还有可能露出来）
            if (cy + rowH < top) { cy += rowH + gap; continue; }
            if (cy > bottom) break;
            PhoneShapes.fillRoundedRect(g, baseX + pad, cy, w - pad * 2, rowH, UiFont.sh(8), CARD_BG);
            drawScaledText(g, font, "›  " + s.title(), baseX + pad + UiFont.sh(10),
                    cy + (rowH - lineH(font)) / 2, TEXT_COLOR, UiFont.sc(0.9f));
            cy += rowH + gap;
        }
        g.disableScissor();
    }

    /** 二级小栏目列表（可滚动，scissor 限定内容区） */
    private void renderSectionList(GuiGraphics g, int baseX, int baseY) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        Font font = Minecraft.getInstance().font;
        renderHeader(g, font, baseX, baseY, w, currentSection.title());

        int headerH = UiFont.sh(22);
        int pad = UiFont.sh(8);
        int top = baseY + headerH + UiFont.sh(6);
        int bottom = baseY + h - UiFont.sh(6);
        int rowH = UiFont.sh(34);
        int gap = UiFont.sh(4);
        int contentH = currentSection.entries().size() * (rowH + gap);
        int viewH = bottom - top;
        int maxScroll = Math.max(0, contentH - viewH);
        sectionScroll = clamp(sectionScroll, maxScroll);

        g.enableScissor(baseX, top, baseX + w, bottom);
        int cy = top - (int) sectionScroll;
        for (ManualEntry e : currentSection.entries()) {
            if (cy + rowH < top) { cy += rowH + gap; continue; }
            if (cy > bottom) break;
            PhoneShapes.fillRoundedRect(g, baseX + pad, cy, w - pad * 2, rowH, UiFont.sh(8), CARD_BG);
            drawScaledText(g, font, "›  " + e.title(), baseX + pad + UiFont.sh(10),
                    cy + (rowH - lineH(font)) / 2, TEXT_COLOR, UiFont.sc(0.9f));
            cy += rowH + gap;
        }
        g.disableScissor();
    }

    /** 正文详情（可滚动 + 可选图片，scissor 限定内容区） */
    private void renderBody(GuiGraphics g, int baseX, int baseY) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        Font font = Minecraft.getInstance().font;
        renderHeader(g, font, baseX, baseY, w, currentSection.title());

        int headerH = UiFont.sh(22);
        int pad = UiFont.sh(8);
        int contentTop = baseY + headerH + UiFont.sh(4);
        int contentBottom = baseY + h - UiFont.sh(6);

        // 图片：存在则固定底部
        int imgH = 0;
        if (currentEntry.imageKey() != null) {
            ResourceLocation imgTex = PhotoLibrary.getOrLoadTexture(currentEntry.imageKey());
            int[] dims = PhotoLibrary.getTextureSize(currentEntry.imageKey());
            if (imgTex != null) {
                int availW = w - pad * 2;
                int imgW = Math.min(availW, dims[0]);
                imgH = Math.max(1, (int) (dims[1] * (imgW / (float) Math.max(1, dims[0]))));
                // 高度封顶：手册里的竖屏截图（如 798x1340）按宽度铺开后会高得把正文挤成一条缝。
                // 封顶后按同一比例把宽度也缩回去（绝不拉伸），并水平居中。
                int maxH = Math.max(1, (int) ((contentBottom - contentTop) * IMG_MAX_HEIGHT_RATIO));
                if (imgH > maxH) {
                    imgW = Math.max(1, (int) (imgW * (maxH / (float) imgH)));
                    imgH = maxH;
                }
                g.blit(imgTex, baseX + pad + Math.max(0, (availW - imgW) / 2), contentBottom - imgH, imgW, imgH,
                        0.0f, 0.0f, dims[0], dims[1], dims[0], dims[1]);
            }
        }
        int bodyBottom = contentBottom - (imgH > 0 ? imgH + UiFont.sh(4) : 0);
        int bodyAreaH = bodyBottom - contentTop;

        List<FormattedCharSequence> lines = wrapBody(font, w - pad * 2);
        int lh = font.lineHeight;
        int totalH = lines.size() * lh;
        float maxScroll = Math.max(0, totalH - bodyAreaH);
        scroll = clamp(scroll, maxScroll);

        g.enableScissor(baseX, contentTop, baseX + w, bodyBottom);
        int textX = baseX + pad;
        int textY = contentTop - (int) scroll;
        for (FormattedCharSequence line : lines) {
            if (textY + lh >= contentTop && textY < bodyBottom) {
                g.drawString(font, line, textX, textY, TEXT_COLOR, false);
            }
            textY += lh;
        }
        g.disableScissor();
    }

    private static float clamp(float v, float max) {
        return Math.max(0, Math.min(v, max));
    }

    private List<FormattedCharSequence> wrapBody(Font font, int maxWidth) {
        List<FormattedCharSequence> out = new ArrayList<>();
        for (String para : currentEntry.body().split("\n")) {
            if (para.isBlank()) {
                out.add(Component.literal(" ").getVisualOrderText());
                continue;
            }
            out.addAll(font.split(Component.literal(para), maxWidth));
        }
        return out;
    }

    // ==================== 交互 ====================

    /** 顶栏"返回"命中（返回上一级） */
    private boolean hitHeaderBack(int w, double relX, double relY) {
        int headerH = UiFont.sh(22);
        int backW = UiFont.sh(8) + (int) (Minecraft.getInstance().font.width("‹ 返回") * UiFont.sc(1.0f)) + UiFont.sh(8);
        return relY <= headerH && relX <= backW;
    }

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (action != GLFW.GLFW_PRESS) return false;

        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int headerH = UiFont.sh(22);

        // 顶栏返回：逐级回退。
        // ⚠ 这里必须看 onBackPressed() 的返回值：它在一级列表（最外层）会返回 false =
        //   "我已经没有上一级了，交给上层"。旧代码无条件 return true，于是点在最外层时
        //   **点击被吞掉、却什么都不发生** —— 玩家看到的就是"手册返回键是坏的"。
        //   false 时补上公共通路 PhoneOverlay.popPage()，与 api 层 PhonePage#onBack() 的默认行为一致。
        if (hitHeaderBack(w, relX, relY)) {
            if (!onBackPressed()) {
                PhoneOverlay.popPage();
            }
            return true;
        }

        int pad = UiFont.sh(8);
        int rowH = UiFont.sh(34);
        int gap = UiFont.sh(4);
        int top = headerH + UiFont.sh(6);
        int bottom = h - UiFont.sh(6);

        if (currentEntry != null) {
            return false;
        }
        if (currentSection != null) {
            int viewH = bottom - top;
            int contentH = currentSection.entries().size() * (rowH + gap);
            int maxScroll = Math.max(0, contentH - viewH);
            sectionScroll = clamp(sectionScroll, maxScroll);
            int cy = top - (int) sectionScroll;
            for (ManualEntry e : currentSection.entries()) {
                if (relX >= pad && relX <= w - pad && relY >= cy && relY <= cy + rowH) {
                    currentEntry = e;
                    scroll = 0;
                    return true;
                }
                cy += rowH + gap;
            }
            return false;
        }
        // 一级列表（可滚动：几何必须与 renderSectionRoot 完全一致）
        int viewH = bottom - top;
        int contentH = sections.size() * (rowH + gap);
        int maxScroll = Math.max(0, contentH - viewH);
        rootScroll = clamp(rootScroll, maxScroll);
        int cy = top - (int) rootScroll;
        for (ManualSection s : sections) {
            if (cy + rowH < top) { cy += rowH + gap; continue; }
            if (cy > bottom) break;
            if (relX >= pad && relX <= w - pad && relY >= cy && relY <= cy + rowH) {
                currentSection = s;
                currentEntry = null;
                sectionScroll = 0;
                scroll = 0;
                return true;
            }
            cy += rowH + gap;
        }
        return false;
    }

    @Override
    public boolean onBackPressed() {
        // 逐级回退：正文 → 二级列表 → 一级列表 → false(交给上层 popPage 回桌面)
        if (currentEntry != null) {
            currentEntry = null;
            scroll = 0;
            return true;
        }
        if (currentSection != null) {
            currentSection = null;
            sectionScroll = 0;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        // 向上滚（scrollDelta<0）= 向上翻（内容下移露出更上面），故用 -scrollDelta
        if (currentEntry != null) {
            scroll += (float) (-scrollDelta * UiFont.sh(10));
            return true;
        }
        if (currentSection != null) {
            sectionScroll += (float) (-scrollDelta * UiFont.sh(10));
            return true;
        }
        // 一级列表（第 7 轮补的）：装得下就什么都不做，保持改动前的行为；
        // 装不下才滚，否则第 5 节往后的栏目永远够不着。
        int h = PhoneOverlay.getPhoneHeight();
        int top = UiFont.sh(22) + UiFont.sh(6);
        int bottom = h - UiFont.sh(6);
        int rowH = UiFont.sh(34);
        int gap = UiFont.sh(4);
        int maxScroll = Math.max(0, sections.size() * (rowH + gap) - (bottom - top));
        if (maxScroll <= 0) {
            return false;
        }
        rootScroll = clamp(rootScroll + (float) (-scrollDelta * UiFont.sh(10)), maxScroll);
        return true;
    }

    // ==================== 工具 ====================

    private static int lineH(Font font) {
        return font.lineHeight;
    }

    private static void drawScaledText(GuiGraphics g, Font font, String text, float x, float y, int color, float scale) {
        float inv = 1.0f / scale;
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(font, text, x * inv, y * inv, color, false);
        g.pose().popPose();
    }
}
