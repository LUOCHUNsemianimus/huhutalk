package com.huhutalk.phone.pages;

import com.huhutalk.client.PhotoLibrary;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.phone.UiFont;
import com.huhutalk.phone.UiShapes;
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
 * 右键逐级返回：正文 → 小栏目列表 → 大栏目列表 → 桌面。
 * <p>
 * 背景色 {@link #PAGE_BG}（#f5ebf2）、黑色字体。二级列表与正文滚动区用 scissor 限定在内容区，
 * 不越出顶栏/手机边界。图片 PNG 放 {@code gameDir/huhutalk/manual/}，条目通过 {@code imageKey} 引用。
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

    /** 二级条目：标题 + 正文（支持 \n 换行、自动折行）+ 可选图片文件名 */
    public record ManualEntry(String title, String body, String imageKey) {}
    /** 一级大栏目：标题 + 其下二级条目列表 */
    public record ManualSection(String title, List<ManualEntry> entries) {}

    private final List<ManualSection> sections;
    /** 当前打开的一级栏目；null = 一级列表 */
    private ManualSection currentSection = null;
    /** 当前打开的二级条目；null = 二级列表 */
    private ManualEntry currentEntry = null;
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
                entry("关于加载", "模组采用了一些下发加载票据的方法，因此会有额外的性能开销。所以，请节制的使用Pro版手机。"),
                entry("额外指令", "在此基础上，添加了一些额外的指令（以后可能还加），可以使用自然语言，去往某坐标/呆在某坐标/来我身边"
                        + "指挥女仆AI，支持去某地做某事，呆在某地做某事等格式（具体见指南8）。")));
        sections.add(sec("关于手机",
                entry("绘制与字体", "手机使用overlay绘制。\n"
                        + "手机里的字体大小会跟随玩家设置的GUI界面尺寸而改变，所以请随心的调整你喜欢的字体比例。\n"
                        + "如果你使用了大GUI却想要小字体/使用了小GUI却想要大字体 ，在huhutalk内部的设置页面可再次缩放字体，"
                        + "但可能会有一些字体渲染问题出现。"),
                entry("辅助键（默认alt）", "手机需要按住辅助键（默认alt）来释放鼠标进行操作。\n"
                        + "默认左键确认、右键返回上一级（当然也可以点击左上角的返回按钮）。"),
                entry("顶部导航栏", "手机的上部导航栏（标注有时间、电量以及信号）是手机的位置拖动功能区，请将它移动到任何你喜欢的位置！"),
                entry("紧急关闭", "顶部导航栏的右角有一个紧急关闭按钮（叉叉），用来应对某种特殊情况。")));
        sections.add(sec("关于设置",
                entry("设置入口", "设置界面位于huhutalk内部，请进入huhutalk，用底部导航栏切换到最右侧选项。\n"
                        + "ps：因为这个模组叫huhutalk，只是觉得只有一个孤零零的APP太奇怪了，所以我打算后期多做点APP。"),
                entry("屏幕大小", "最高等于屏幕实际高度，最低等于屏幕的1/2（暂时没考虑有人用特别大的屏幕），如果你把屏幕缩小到字体都放不下，"
                        + "那么，滑块就没有办法固定——其实算是个小bug，但是可以防止卡位置，所以它是好bug。"),
                entry("屏幕比例", "支持20：9、16：9、4：3，内部UI会自适应内容。"),
                entry("消息免打扰", "打开会关闭红点提醒，同时关闭消息提示音，默认为关闭状态。"),
                entry("女仆悄悄话", "打开会关闭女仆的AI气泡显示——给您更私密的聊天体验"),
                entry("沉浸式聊天", "开启后所有有手机唤起的聊天，将不会在左下角公屏产生女仆回复。\n"
                        + "由于手机内的消息渲染特意加了输入延迟，所以事实上左下角公屏的内容会率先抵达，此选项是为提升沉浸感。"),
                entry("亮度滑块", "“手机壳也可以调暗。”"),
                entry("手机壳", "可以选择你背包内的任何完整方块，但是，有点不好看。"),
                entry("SOS", "在女仆界面，可以绑定女仆作为你的SOS召唤人，紧急情况，按这个按钮把他们召唤过来。"),
                entry("打字耗时", "此选项是为了增强真实感，给女仆的每条消息做了断句处理，并且根据字符的多少加了回复前摇"
                        + "（抢在他发完之前再发一句话，会让他一次渲染完）"),
                entry("动画", "“谁不想要一个漂亮的UI动画？”")));
        sections.add(sec("聊天页",
                entry("输入框", "点击即可开始输入按esc退出输入，回车发送。"),
                entry("底部扩展栏", "从左至右分别为语音输入、发送表情包、图库、拍照、附加栏。"),
                entry("语音输入", "默认按h键开始输入，但值得注意的是，此种输入方式不会触发私密聊天！"),
                entry("表情包", "你发一个表情包，女仆一定会回一个表情包，但实际上不是llm回的（要不然token要烧完了）。"
                        + "当然，您可以要求女仆和你发送表情包。"),
                entry("图库", "在拍照界面点击保存的照片才会储存在这里，一次仅能发送一张图片，支持图片和文字一起发送，"
                        + "但需要等待图片完成识别后，女仆才会回复。\n"
                        + "注：借用了车万女仆的原生链路，目前仅开通了deepseek最新模型的识图功能，"
                        + "如果已经在原模组填入了ds API key，那么便可直接使用图片相关功能。\n"
                        + "注：识图延迟挺高，请耐心等待orz。"),
                entry("拍照", "如果不点击保存，那么点击确认的图片仅会在此次发送中生效，不会存储到本地。")));
        sections.add(sec("联系人设置",
                entry("设置头像", "默认会给女仆拍一张大头贴，后续考虑增加以表情包当头像/或者更多姿势。"),
                entry("删除聊天", "仅删除huhutalk附属模组的聊天存储，实际上下文仍然会存储在车万女仆模组之内。"),
                entry("删除联系人", "如果女仆不小心死了（大哭），总得有个把它删掉的办法（嗷嗷哭）。"),
                entry("绑定SOS", "可以自行定义召唤哪个女仆，并且以什么模式将其召唤过来（针对不同的“兵种”），"
                        + "最多可添加四位女仆成为您的魔王护。")));
        sections.add(sec("联系人",
                entry("在线", "表示女仆正被加载且可通话"),
                entry("离线", "表示女仆处于被卸载区块内，已经无法通话，需要通过红狐狸卷轴寻找。"),
                entry("已关机", "女仆现在饰品栏里没有手机。")));
        sections.add(sec("桌面",
                entry("信号栏", "不好的维度会产生信号延迟，末地为0.5s，地狱为1s。"),
                entry("电池", "“是核能的。”")));
        sections.add(sec("指令",
                entry("Move to/去…等我", "女仆将去你指示的地点等待，当你靠近她身边，她会开始跟随你，此操作可以附带工作模式。"),
                entry("Come/到我这儿来", "女仆会到你身边来，如果离得近，她会走过来，离得远，则会传送。"),
                entry("Stay/去…呆着", "女仆会去往你指示的地点。开启home模式，在你经过后不会跟随你。")));
        sections.add(sec("模组兼容",
                entry("旅行地图", "兼容旅行地图（Journey map）。\n"
                        + "可使用自然语言指挥女仆到达路径点工作，例：“去快乐老家把菜一收。”“去史莱姆牧场等着我”")));
        sections.add(sec("物品与方块",
                entry("电话手表", "它是造价较低廉的手机，但是它没有流量卡，所以需要搭配WIFI方块使用。\n"
                        + "处在WIFI方块直径三个区块内的持有电话手表的女仆，可以保持常驻加载，会为其下发加载票据。"),
                entry("WIFI方块", "是本模组的节能设计，避免过多的女仆聚集在一起，每个都下发强制加载票据的性能问题，"
                        + "在其周围没有持有电话手表的女仆时，不会对周围的区块下发加载票据，所以不会产生性能开销。\n"
                        + "当且仅当周围区块有手表女仆时，才会对女仆所在的区块下发强制加载票据。"),
                entry("Pro版手机", "会持续为玩家的女仆下发强制加载的票据，但为保证性能，仅下发一区块的加载票据，"
                        + "所以——如果你的女仆是闪电侠，有可能会跑到未加载区块里。")));
        sections.add(sec("写在最后",
                entry("写在最后", "本模组由我（罗春）使用AI工作流协助开发，如果有任何不足，烦请反映至Anirtual@gmail.com，谢谢！\n"
                        + "还有——感谢你们的下载与体验，我爱你们！")));
    }

    private static ManualSection sec(String title, ManualEntry... entries) {
        return new ManualSection(title, List.of(entries));
    }

    private static ManualEntry entry(String title, String body) {
        return new ManualEntry(title, body, null);
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

    /** 一级大栏目列表（内容不多，不滚动） */
    private void renderSectionRoot(GuiGraphics g, int baseX, int baseY) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        Font font = Minecraft.getInstance().font;
        renderHeader(g, font, baseX, baseY, w, "设置");

        int headerH = UiFont.sh(22);
        int pad = UiFont.sh(8);
        int top = baseY + headerH + UiFont.sh(6);
        int bottom = baseY + h - UiFont.sh(6);
        g.enableScissor(baseX, top, baseX + w, bottom);
        int rowH = UiFont.sh(34);
        int gap = UiFont.sh(4);
        int cy = top;
        for (ManualSection s : sections) {
            if (cy + rowH > bottom) break;
            UiShapes.fillRoundedRect(g, baseX + pad, cy, w - pad * 2, rowH, UiFont.sh(8), CARD_BG);
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
            UiShapes.fillRoundedRect(g, baseX + pad, cy, w - pad * 2, rowH, UiFont.sh(8), CARD_BG);
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
                int sw = Math.min(availW, dims[0]);
                int imgW = sw;
                imgH = Math.max(1, (int) (dims[1] * (sw / (float) Math.max(1, dims[0]))));
                g.blit(imgTex, baseX + pad, contentBottom - imgH, imgW, imgH,
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

        // 顶栏返回：逐级回退
        if (hitHeaderBack(w, relX, relY)) {
            onBackPressed();
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
        // 一级列表
        int cy = top;
        for (ManualSection s : sections) {
            if (cy + rowH > bottom) break;
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
        return false;
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
