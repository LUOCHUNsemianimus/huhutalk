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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 手机内置手册 / 设置页：两级目录导航。
 * <p>
 * 一级 = 大栏目（{@link ManualSection}），点开进入其二级小栏目列表（{@link ManualEntry}），
 * 再点开小栏目进入正文（可滚动文本 + 可选图片）。
 * 右键逐级返回：正文 → 小栏目列表 → 大栏目列表 → 桌面。
 * <p>
 * 背景色 {@link #PAGE_BG}（#f5ebf2）、黑色字体。图片 PNG 放 {@code gameDir/huhutalk/manual/}，
 * 条目通过 {@code imageKey} 引用，由 {@link PhotoLibrary} 加载渲染。
 */
public class ManualPage implements IPhonePage {

    /** 页面底色 #f5ebf2 */
    private static final int PAGE_BG = 0xFFF5EBF2;
    /** 顶栏底色 */
    private static final int HEADER_BG = 0xFFF06292;
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
        sections.add(new ManualSection("关于 huhutalk（狐狐talk）",
                split("（1）狐狐talk是一款车万女仆（Touhou Little Maid）的附属模组。其主要目的是为了补充不能远程和女仆聊天的空缺，"
                        + "并且为女仆定制了专属于手机UI的聊天方式/新的接收路径/隐私模式的开关，欢迎并且感谢大家体验！\n"
                        + "（2）模组采用了一些下发加载票据的方法，因此会有额外的性能开销。所以，请节制的使用Pro版手机。\n"
                        + "（3）在此基础上，添加了一些额外的指令（以后可能还加），可以使用自然语言，去往某坐标/呆在某坐标/来我身边"
                        + "指挥女仆AI，支持去某地做某事，呆在某地做某事等格式（具体见指南8）。\n"
                        + "——本模组由AI协助开发（我不是科班出身）。")));
        sections.add(new ManualSection("关于手机（这是啥？）",
                split("（1）手机使用overlay绘制。\n"
                        + "手机里的字体大小会跟随玩家设置的GUI界面尺寸而改变，所以请随心的调整你喜欢的字体比例。\n"
                        + "如果你使用了大GUI却想要小字体/使用了小GUI却想要大字体 ，在huhutalk内部的设置页面可再次缩放字体，"
                        + "但可能会有一些字体渲染问题出现。\n"
                        + "（2）手机需要按住辅助键（默认alt）来释放鼠标进行操作。\n"
                        + "默认左键确认、右键返回上一级（当然也可以点击左上角的返回按钮）。\n"
                        + "（3）手机的上部导航栏。\n"
                        + "（标注有时间、电量以及信号）是手机的位置拖动功能区，请将它移动到任何你喜欢的位置！\n"
                        + "（4）顶部导航栏的右角。\n"
                        + "有一个紧急关闭按钮（叉叉），用来应对某种特殊情况。")));
        sections.add(new ManualSection("关于设置（挑重要的说）",
                split("（1）设置界面为什么不在桌面上？\n"
                        + "设置界面位于huhutalk内部，请进入huhutalk，用底部导航栏切换到最右侧选项。\n"
                        + "ps：因为这个模组叫huhutalk，只是觉得只有一个孤零零的APP太奇怪了，所以我打算后期多做点APP。\n"
                        + "（2）屏幕大小滑块\n"
                        + "最高等于屏幕实际高度，最低等于屏幕的1/2（暂时没考虑有人用特别大的屏幕），如果你把屏幕缩小到字体都放不下，"
                        + "那么，滑块就没有办法固定——其实算是个小bug，但是可以防止卡位置，所以它是好bug。\n"
                        + "（3）屏幕比例\n"
                        + "支持20：9、16：9、4：3，内部UI会自适应内容。\n"
                        + "（4）消息免打扰\n"
                        + "打开会关闭红点提醒，同时关闭消息提示音，默认为关闭状态。\n"
                        + "（5）女仆悄悄话\n"
                        + "打开会关闭女仆的AI气泡显示——给您更私密的聊天体验\n"
                        + "（6）沉浸式聊天\n"
                        + "开启后所有有手机唤起的聊天，将不会在左下角公屏产生女仆回复。\n"
                        + "由于手机内的消息渲染特意加了输入延迟，所以事实上左下角公屏的内容会率先抵达，此选项是为提升沉浸感。\n"
                        + "（7）亮度滑块\n"
                        + "“手机壳也可以调暗。”\n"
                        + "（8）手机壳选项\n"
                        + "可以选择你背包内的任何完整方块，但是，有点不好看。\n"
                        + "（9）SOS\n"
                        + "在女仆界面，可以绑定女仆作为你的SOS召唤人，紧急情况，按这个按钮把他们召唤过来。\n"
                        + "（10）打字耗时选项\n"
                        + "此选项是为了增强真实感，给女仆的每条消息做了断句处理，并且根据字符的多少加了回复前摇"
                        + "（抢在他发完之前再发一句话，会让他一次渲染完）\n"
                        + "（11）动画\n"
                        + "“谁不想要一个漂亮的UI动画？”")));
        sections.add(new ManualSection("女仆聊天页细则",
                split("（1）输入框\n"
                        + "点击即可开始输入按esc退出输入，回车发送。\n"
                        + "（2）底部扩展栏\n"
                        + "从左至右分别为语音输入、发送表情包、图库、拍照、附加栏。\n"
                        + "（3）语音输入\n"
                        + "默认按h键开始输入，但值得注意的是，此种输入方式不会触发私密聊天！\n"
                        + "（4）表情包\n"
                        + "你发一个表情包，女仆一定会回一个表情包，但实际上不是llm回的（要不然token要烧完了）。"
                        + "当然，您可以要求女仆和你发送表情包。\n"
                        + "（5）图库\n"
                        + "在拍照界面点击保存的照片才会储存在这里，一次仅能发送一张图片，支持图片和文字一起发送，"
                        + "但需要等待图片完成识别后，女仆才会回复。\n"
                        + "注：借用了车万女仆的原生链路，目前仅开通了deepseek最新模型的识图功能，"
                        + "如果已经在原模组填入了ds API key，那么便可直接使用图片相关功能。\n"
                        + "注：识图延迟挺高，请耐心等待orz。\n"
                        + "（6）拍照\n"
                        + "如果不点击保存，那么点击确认的图片仅会在此次发送中生效，不会存储到本地。")));
        sections.add(new ManualSection("联系人设置栏",
                split("（1）设置头像\n"
                        + "默认会给女仆拍一张大头贴，后续考虑增加以表情包当头像/或者更多姿势。\n"
                        + "（2）删除聊天\n"
                        + "仅删除huhutalk附属模组的聊天存储，实际上下文仍然会存储在车万女仆模组之内。\n"
                        + "（3）删除联系人\n"
                        + "如果女仆不小心死了（大哭），总得有个把它删掉的办法（嗷嗷哭）。\n"
                        + "（4）绑定SOS\n"
                        + "可以自行定义召唤哪个女仆，并且以什么模式将其召唤过来（针对不同的“兵种”），"
                        + "最多可添加四位女仆成为您的魔王护。")));
        sections.add(new ManualSection("联系人页",
                split("（1）在线\n"
                        + "表示女仆正被加载且可通话\n"
                        + "（2）离线\n"
                        + "表示女仆处于被卸载区块内，已经无法通话，需要通过红狐狸卷轴寻找。\n"
                        + "（3）已关机\n"
                        + "女仆现在饰品栏里没有手机。")));
        sections.add(new ManualSection("桌面",
                split("（1）信号栏\n"
                        + "不好的维度会产生信号延迟，末地为0.5s，地狱为1s。\n"
                        + "（2）电池\n"
                        + "“是核能的。”")));
        sections.add(new ManualSection("关于指令",
                split("（1）Move to/去…等我\n"
                        + "女仆将去你指示的地点等待，当你靠近她身边，她会开始跟随你，此操作可以附带工作模式。\n"
                        + "（2）Come/到我这儿来\n"
                        + "女仆会到你身边来，如果离得近，她会走过来，离得远，则会传送。\n"
                        + "（3）Stay/去…呆着\n"
                        + "女仆会去往你指示的地点。开启home模式，在你经过后不会跟随你。")));
        sections.add(new ManualSection("其他模组兼容",
                split("（1）兼容旅行地图（Journey map）\n"
                        + "可使用自然语言指挥女仆到达路径点工作，例：“去快乐老家把菜一收。”“去史莱姆牧场等着我”")));
        sections.add(new ManualSection("模组物品与方块",
                split("（1）电话手表\n"
                        + "它是造价较低廉的手机，但是它没有流量卡，所以需要搭配WIFI方块使用。\n"
                        + "处在WIFI方块直径三个区块内的持有电话手表的女仆，可以保持常驻加载，会为其下发加载票据。\n"
                        + "（2）WIFI方块\n"
                        + "是本模组的节能设计，避免过多的女仆聚集在一起，每个都下发强制加载票据的性能问题，"
                        + "在其周围没有持有电话手表的女仆时，不会对周围的区块下发加载票据，所以不会产生性能开销。\n"
                        + "当且仅当周围区块有手表女仆时，才会对女仆所在的区块下发强制加载票据。\n"
                        + "（3）Pro版手机\n"
                        + "会持续为玩家的女仆下发强制加载的票据，但为保证性能，仅下发一区块的加载票据，"
                        + "所以——如果你的女仆是闪电侠，有可能会跑到未加载区块里。")));
        sections.add(new ManualSection("写在最后",
                split("本模组由我（罗春）使用AI工作流协助开发，如果有任何不足，烦请反映至Anirtual@gmail.com，谢谢！\n"
                        + "还有——感谢你们的下载与体验，我爱你们！")));
    }

    /** 按"（数字）"标记把一段含多个小栏目的正文拆成二级条目列表；无编号则整体一个条目。 */
    private static List<ManualEntry> split(String body) {
        List<ManualEntry> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?=（\\d+）)").matcher(body);
        int prev = 0;
        boolean hasSub = false;
        while (m.find()) {
            hasSub = true;
            String part = body.substring(prev, m.start()).trim();
            prev = m.start();
            if (!part.isEmpty()) {
                out.add(new ManualEntry(firstLine(part), part, null));
            }
            // 重新定位到该分栏起点
        }
        if (hasSub) {
            String last = body.substring(prev).trim();
            if (!last.isEmpty()) {
                out.add(new ManualEntry(firstLine(last), last, null));
            }
        }
        if (out.isEmpty()) {
            String b = body.trim();
            out.add(new ManualEntry(firstLine(b), b, null));
        }
        return out;
    }

    private static String firstLine(String s) {
        int idx = s.indexOf('\n');
        return idx < 0 ? s : s.substring(0, idx).trim();
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

    /** 一级大栏目列表 */
    private void renderSectionRoot(GuiGraphics g, int baseX, int baseY) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        Font font = Minecraft.getInstance().font;
        renderHeader(g, font, baseX, baseY, w, "设置");

        int headerH = UiFont.sh(22);
        int pad = UiFont.sh(8);
        int top = baseY + headerH + UiFont.sh(6);
        int rowH = UiFont.sh(34);
        int gap = UiFont.sh(4);
        int cy = top;
        for (ManualSection s : sections) {
            if (cy + rowH > baseY + h - UiFont.sh(6)) break;
            UiShapes.fillRoundedRect(g, baseX + pad, cy, w - pad * 2, rowH, UiFont.sh(8), CARD_BG);
            drawScaledText(g, font, "›  " + s.title(), baseX + pad + UiFont.sh(10),
                    cy + (rowH - lineH(font)) / 2, TEXT_COLOR, UiFont.sc(0.9f));
            cy += rowH + gap;
        }
    }

    /** 二级小栏目列表（当前大栏目下） */
    private void renderSectionList(GuiGraphics g, int baseX, int baseY) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        Font font = Minecraft.getInstance().font;
        renderHeader(g, font, baseX, baseY, w, currentSection.title());

        int headerH = UiFont.sh(22);
        int pad = UiFont.sh(8);
        int top = baseY + headerH + UiFont.sh(6);
        int rowH = UiFont.sh(34);
        int gap = UiFont.sh(4);
        int contentBottom = baseY + h - UiFont.sh(6);
        int contentH = currentSection.entries().size() * (rowH + gap);
        int viewH = contentBottom - top;
        int maxScroll = Math.max(0, contentH - viewH);
        sectionScroll = clamp(sectionScroll, maxScroll);

        int cy = top - (int) sectionScroll;
        for (ManualEntry e : currentSection.entries()) {
            if (cy + rowH < baseY + headerH) { cy += rowH + gap; continue; }
            if (cy > contentBottom) break;
            UiShapes.fillRoundedRect(g, baseX + pad, cy, w - pad * 2, rowH, UiFont.sh(8), CARD_BG);
            drawScaledText(g, font, "›  " + e.title(), baseX + pad + UiFont.sh(10),
                    cy + (rowH - lineH(font)) / 2, TEXT_COLOR, UiFont.sc(0.9f));
            cy += rowH + gap;
        }
    }

    /** 正文详情（可滚动 + 可选图片） */
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

        int textX = baseX + pad;
        int textY = contentTop - (int) scroll;
        for (FormattedCharSequence line : lines) {
            if (textY + lh >= contentTop && textY < bodyBottom) {
                g.drawString(font, line, textX, textY, TEXT_COLOR, false);
            }
            textY += lh;
        }
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

        if (currentEntry != null) {
            // 正文：点返回已处理；其余交给滚轮
            return false;
        }
        if (currentSection != null) {
            // 二级列表：点条目进入正文
            int contentBottom = h - UiFont.sh(6);
            int viewH = contentBottom - top;
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
        // 一级列表：点大栏目进入其二级列表
        int cy = top;
        for (ManualSection s : sections) {
            if (cy + rowH > h - UiFont.sh(6)) break;
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
