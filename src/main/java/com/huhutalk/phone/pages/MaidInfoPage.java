package com.huhutalk.phone.pages;

import com.huhutalk.api.phone.ui.PhoneLayout;
import com.huhutalk.api.phone.ui.PhonePage;
import com.huhutalk.api.phone.ui.PhoneShapes;
import com.huhutalk.api.phone.ui.PhoneText;
import com.huhutalk.api.phone.ui.PhoneTextArea;
import com.huhutalk.api.phone.ui.PhoneTextInput;
import com.huhutalk.client.ChatHistoryStorage;
import com.huhutalk.client.MaidAvatarManager;
import com.huhutalk.client.MaidGroupHelper;
import com.huhutalk.client.MaidListCache;
import com.huhutalk.client.MaidStatusCache;
import com.huhutalk.client.SosBindCache;
import com.huhutalk.client.SosTaskCache;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.client.theme.ColorScheme;
import com.huhutalk.common.MaidStatus;
import com.huhutalk.common.vision.VisionAppearance;
import com.huhutalk.network.C2SDeleteContactPacket;
import com.huhutalk.network.C2SRequestMaidStatusPacket;
import com.huhutalk.network.C2SRequestSosTaskListPacket;
import com.huhutalk.network.C2SSetSosModePacket;
import com.huhutalk.network.C2SToggleSosBindPacket;
import com.huhutalk.network.S2CMaidListPacket;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 联系人设置页（消息列表页 / 联系人列表页点击女仆统一进入）。
 * <p>
 * UI：顶栏 #de7e7e；头部大头像（女仆照片）+ 名称/状态（1.75/1.25 倍字号）；页面底取自主题；
 * 全部设置项为圆角卡片（圆角 8）。分三组：
 * <ul>
 *   <li><b>管理</b>：发消息 / 删除记录 / 删除联系人 / 更新头像 / <b>女仆的样貌</b>
 *       （最后一项点一下展开一个内联的多行输入框，填写她的外貌描述，供识图认人用）。</li>
 *   <li><b>SOS呼叫</b>：SOS呼叫（绑定开关）/ SOS工作模式（点击展开原下拉）。</li>
 *   <li><b>分组设置</b>：现有分组选项（默认 + 自定义）+ 末行<b>内联的「新建分组」输入框</b>。</li>
 * </ul>
 * 新建分组把新组名写入持久化列表并分当前女仆到该组，联系人页据此同步展示（含空组）。
 * 逻辑（删除/绑定/模式/分组/头像）均沿用原有实现。
 *
 * <h2>「新建分组」曾经是个弹窗（已改）</h2>
 * 旧实现点「＋ 新建分组」会开一个自带居中对话框的 {@code Screen}。
 * 那套观感和手机页面不是一回事（弹窗把整块手机屏盖掉，连状态栏/水印栏一起），
 * 也与「内联编辑」的设计语言相悖。现在改成公开控件 {@link PhoneTextInput} 内联在列表末行，
 * 点它只开一个<b>完全透明</b>的 {@code PhoneTextCaptureScreen} 抢键盘 ——
 * 与设置 App 的 API Key / 模型名 / 接口地址三个输入框同一条通路。
 * 详见 {@link #groupNewInput} 与 {@link #commitNewGroup(String)}。
 *
 * <h2>改用公开骨架（批次 3）</h2>
 * 旧实现 {@code implements IPhonePage}，把每个页面都要抄一遍的样板都写在文件里：
 * 手绘顶栏（含「←」返回箭头）、手写 {@code navH()}、手写裁剪配对、
 * 以及在 {@code render} 与 {@code mouseClicked} 里<b>各算一遍</b>的行坐标。
 * 现在：
 * <ul>
 *   <li>顶栏 / 底色 / 滚动 / 裁剪 / 主题取色 → {@link PhonePage}；
 *       标题是 {@link #title()}（居中），返回键交给骨架（文案统一为「&lt; 返回」）；</li>
 *   <li>本页是混合卡片排版，走<b>模型 A（页面自管滚动）</b>：只实现 {@link #contentHeight()}；</li>
 *   <li>全部 y 由<b>唯一的</b> {@link #layout()} 产出（逻辑坐标），
 *       渲染走 {@link PhonePage#scrollY(int)}、命中走 {@link PhonePage#logicalY(double)}，
 *       两者严格互逆，所以「画在哪」与「点在哪」在结构上不可能不同步；</li>
 *   <li>画字 → {@link PhoneText}，命中 → {@link PhoneLayout}，圆角 → {@link PhoneShapes}。</li>
 * </ul>
 *
 * <h2>与旧实现的差异（刻意接受）</h2>
 * <ul>
 *   <li>页面底色 / 卡片底 / 三档文字色 / 分隔线 / 危险色改走主题色板（略有色差）；
 *       顶栏仍显式返回 {@link PhonePage#BRAND_TOPBAR_BG}，与聊天页 / 消息页一致；</li>
 *   <li>返回键文案由「←」变成骨架统一的「&lt; 返回」（命中宽度 60 也不随字体倍率缩放）；</li>
 *   <li>顶栏标题的 y 少了一个 {@code +1}（骨架统一按顶栏行高居中）；</li>
 *   <li>命中区间由闭区间改为骨架 {@link PhoneLayout#hit} 的半开区间（边界像素归属不同）；</li>
 *   <li>顶栏区域不再参与内容命中（骨架只把非返回键区域的点击透传给内容钩子，
 *       本页在 {@code onContentClick} 里显式拦掉 {@code localY < 0}，与旧实现一致）。</li>
 * </ul>
 */
public class MaidInfoPage extends PhonePage {

    /** 以下为「基准（1.0 倍）」像素值，运行时经 {@link PhoneText#px(int)} 缩放 */
    private static final int CARD_H = 30;             // 组内单行高度（紧凑）
    private static final int CARD_R = 8;
    private static final int RECT_PAD = 3;            // 大矩形内部上下留白
    private static final int GROUP_TITLE_TOP = 14;    // 分组标题距上一组底
    private static final int GROUP_TITLE_TO_CARD = 8; // 分组标题到本组第一项
    private static final int DROPDOWN_ROW_H = 16;
    private static final int DROPDOWN_VISIBLE_ROWS = 5;

    private final String stableId;
    private final String maidName;

    /** SOS 工作模式下拉是否展开 + 高亮光标下标 */
    private boolean modeDropdownOpen = false;
    private int modeCursor = 0;

    /**
     * 二次确认弹窗正在确认什么。
     * <p>
     * 用一个字段而不是两个 boolean：两种确认<b>结构上互斥</b>（弹窗只有一个），
     * 两个 boolean 迟早会出现"两个都为真"的非法状态。
     */
    private enum Confirm { NONE, DELETE_CONTACT, DELETE_GROUP }

    private Confirm confirm = Confirm.NONE;
    /** {@link Confirm#DELETE_GROUP} 时：待删除的分组名（空 = 无） */
    private String confirmGroup = "";

    /** 分组设置折叠栏是否展开 */
    private boolean groupDropdownOpen = false;

    /**
     * 「女仆的样貌」多行输入框是否展开。
     * <p>
     * 它是管理组第 5 行下方的一块内联编辑区：行本身只有一行高（放不下多行文本），
     * 所以点那一行才展开这个框，展开后由管理组的大矩形自动包住（见 {@link #layout()}）。
     */
    private boolean maidAppearanceOpen = false;

    /**
     * 「女仆的样貌」：这位女仆的外貌描述。
     *
     * <h2>它影响什么</h2>
     * 从<b>本女仆的聊天窗口</b>发图片时随包带给服务端，拼进识图提示词，
     * 于是女仆能认出照片里的自己（"画面里是你自己"）。
     * 没填时识图<b>逐字</b>走原来的默认提示词 —— 老玩家行为零变化。
     *
     * <h2>为什么在构造器里建</h2>
     * 它的初值取决于 {@link #stableId}（每个女仆一份描述），
     * 而 {@code stableId} 是构造参数，字段初始化表达式里拿不到。
     */
    private final PhoneTextArea appearanceInput;

    /** 是否有二次确认弹窗打开（弹窗期间吞掉页面的其它输入） */
    private boolean isConfirming() {
        return confirm != Confirm.NONE;
    }

    private void clearConfirm() {
        confirm = Confirm.NONE;
        confirmGroup = "";
    }

    /**
     * 「新建分组」的内联输入框（分组设置展开时，作为列表最后一行出现）。
     *
     * <h2>为什么不是弹窗（本页曾经的实现）</h2>
     * 旧实现点「＋ 新建分组」会 {@code setScreen(new PhoneGroupNameInputScreen(...))} ——
     * 由那个屏自己画一个居中对话框。手机页面的设计语言里没有"对话框"这种东西：
     * 手机屏本身就是一整块内容区，弹窗会把它整块盖掉（连同状态栏、水印栏一起），
     * 看起来像一个和手机无关的原版界面。
     * <p>
     * 现在改成公开控件 {@link PhoneTextInput} 的<b>内联</b>形态：框就长在分组列表最后一行，
     * 点它才开一个<b>完全透明</b>的 {@code PhoneTextCaptureScreen} 只为抢键盘
     * （手机页面收不到键盘事件，这条通路无法回避；见 {@link PhoneTextInput} 的类注释）。
     * 编辑期手机界面照常渲染在底下，玩家看到的就是"这一格在闪、我直接往里打字"。
     * <p>
     * 同一套做法已经用在设置 App 的「API Key / 模型名 / 接口地址」三个输入框上
     * （{@code VisionModelSettingsPage}），本页只是把它搬到联系人设置页。
     * <p>占位符在 {@link #renderNewGroupRow} 每帧用当前语言刷新，切语言立刻生效；不在构造期缓存译文。
     */
    private final PhoneTextInput groupNewInput = new PhoneTextInput()
            .maxLength(16)
            .onCommit(this::commitNewGroup);
    /** 短暂提示（toast）：文本 + 到期时间戳 */
    private String flashText = "";
    private long flashUntil = 0;

    // ==================== 缩放辅助（固定像素量） ====================

    private static int cardH() { return PhoneText.px(CARD_H); }
    private static int rectPad() { return PhoneText.px(RECT_PAD); }
    private static int groupTitleTop() { return PhoneText.px(GROUP_TITLE_TOP); }
    private static int groupTitleToCard() { return PhoneText.px(GROUP_TITLE_TO_CARD); }
    private static int dropdownRowH() { return PhoneText.px(DROPDOWN_ROW_H); }

    public MaidInfoPage(String stableId, String maidName) {
        this.stableId = stableId;
        this.maidName = maidName;
        this.appearanceInput = new PhoneTextArea()
                .maxLength(VisionAppearance.MAX_CHARS)
                .value(HuhuTalkClientSettings.getMaidAppearance(stableId))
                .onCommit(v -> HuhuTalkClientSettings.setMaidAppearance(stableId, v));
        PacketDistributor.sendToServer(new C2SRequestMaidStatusPacket(List.of(stableId)));
        if (SosTaskCache.isEmpty()) {
            PacketDistributor.sendToServer(new C2SRequestSosTaskListPacket());
        }
    }

    // ==================== 数据 ====================

    public String getStableId() { return stableId; }

    /** 从 MaidListCache 查该女仆的 MaidStatus（查不到返回 null，按离线默认分组） */
    private MaidStatus currentStatus() {
        for (S2CMaidListPacket.MaidDisplayInfo info : MaidListCache.getList()) {
            if (stableId.equals(info.stableId())) {
                return info.status();
            }
        }
        return null;
    }

    /** 当前分组：优先手动分组，未设置则按状态默认分组 */
    private String currentGroup() {
        return MaidGroupHelper.resolveGroup(stableId, currentStatus());
    }

    /**
     * 分组可选列表。
     * <p>
     * <b>玩家自定义分组在上、默认（状态）分组在下</b>：默认分组由女仆实时状态决定且<b>不可删</b>，
     * 把可删的自定义分组排在上面，删除标记（✕）就集中在列表上半段，
     * 不会和"点一下就把女仆改到别的组"混在一起 —— 改错组只要再点一下就好，删错组却要找回来。
     * <p>
     * 名单来源：
     * <ul>
     *   <li>自定义分组 = {@link HuhuTalkClientSettings#getCustomGroupNames()}（玩家建过、持久化在设置文件里）；</li>
     *   <li>默认分组 = 四个状态组常量。顺序与 {@code MaidGroupHelper.groupSortIndex} 一致。</li>
     * </ul>
     * 这里会再过滤一次"名字撞上默认分组"的条目：{@code addCustomGroup} 现在已经拦了，
     * 但<b>老设置文件</b>里可能残留（那个校验是后加的），过滤掉才不会出现两个「在线」。
     */
    private List<String> groupOptions() {
        List<String> list = new ArrayList<>();
        for (String g : HuhuTalkClientSettings.getCustomGroupNames()) {
            if (!HuhuTalkClientSettings.isDefaultGroupName(g) && !list.contains(g)) {
                list.add(g);
            }
        }
        list.add(MaidGroupHelper.GROUP_ONLINE);
        list.add(MaidGroupHelper.GROUP_OFFLINE);
        list.add(MaidGroupHelper.GROUP_SHUTDOWN);
        list.add(MaidGroupHelper.GROUP_DEAD);
        return list;
    }

    private void showFlash(String text) {
        flashText = text;
        flashUntil = System.currentTimeMillis() + 2000;
    }

    /** 点击"更新头像"：对在场女仆拍照生成头像 */
    private void updateAvatar() {
        int result = MaidAvatarManager.captureIfLoaded(stableId);
        switch (result) {
            case 1 -> showFlash(PhoneText.tr("gui.huhutalk.maid_info.avatar.updated"));
            case 2 -> showFlash(PhoneText.tr("gui.huhutalk.maid_info.avatar.fail"));
            default -> showFlash(PhoneText.tr("gui.huhutalk.maid_info.avatar.absent"));
        }
    }

    /** 新建分组成功后回调：提示（页面随下一帧重渲染即同步到分组列表） */
    public void onGroupCreated() {
        showFlash(PhoneText.tr("gui.huhutalk.maid_info.group.created"));
    }

    /**
     * 「新建分组」内联输入框提交（回车 / 点别处 / 失焦）时调用。
     *
     * <h2>校验失败为什么是"发个提示 + 留着已输入的字"</h2>
     * 旧弹窗把错误画在对话框里并<b>拒绝关闭</b>；内联形态没有"拒绝关闭"这个动作 ——
     * 捕获屏一结束就已经提交了。所以这里退一步：
     * <ul>
     *   <li>校验失败 → {@link #showFlash} 提示，<b>输入框里的字原样留着</b>
     *       （{@code PhoneTextInput.endEdit} 已经把最终文本写进控件），再点一下就能接着改；</li>
     *   <li>校验通过 → 落盘 + 把当前女仆分到新组 + 清空输入框，分组栏保持展开
     *       （与旧弹窗一致：玩家能立刻看到新组带着 ✓ 出现在列表里）。</li>
     * </ul>
     * ⚠ <b>不要在 onCommit 里重新 {@code setScreen} 打开捕获屏</b>：
     * 捕获屏的 {@code finish()} 是「先 endEdit、后 setScreen(null)」，
     * 这里新开的屏会被紧接着那一下关掉（想重试就让玩家再点一下输入框）。
     */
    private void commitNewGroup(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            showFlash(PhoneText.tr("gui.huhutalk.maid_info.group.name_empty"));
            return;
        }
        if (!HuhuTalkClientSettings.addCustomGroup(name)) {
            showFlash(PhoneText.tr("gui.huhutalk.maid_info.group.duplicate"));
            return;
        }
        // 把当前女仆分到新分组 → 联系人页据此同步展示（含空组）
        HuhuTalkClientSettings.setMaidGroup(stableId, name);
        groupNewInput.value("");
        onGroupCreated();
    }

    /** 把 taskUid 转为可读名称 */
    private static String formatTaskName(String uid) {
        try {
            String key = "task." + uid.replace(':', '.');
            Component c = Component.translatable(key);
            String localized = c.getString();
            if (localized.isEmpty() || localized.equals(key)) {
                return uid.substring(uid.indexOf(':') + 1);
            }
            return localized;
        } catch (RuntimeException e) {
            return uid;
        }
    }

    /** 把 {@code v} 钳制到 {@code [0, max]}（下拉光标与滚动窗口起点用） */
    private static int clamp(int v, int max) {
        return Math.clamp(v, 0, max);
    }

    // ==================== 骨架钩子 ====================

    /** 顶栏标题（居中）= 女仆名 */
    @Override
    protected String title() {
        return maidName;
    }

    /** 列表 / 条目类页面底色：主题里比主要背景深一档（旧实现硬编码 0xFFF0E4EA） */
    @Override
    protected int pageBackground() {
        return colors().pageBgList();
    }

    /** 顶栏锁成品牌色：新增配色方案时本页仍与聊天 / 消息 / 设置是同一台手机（旧实现硬编码 0xFFDE7E7E） */
    @Override
    protected int topBarBackground() {
        return BRAND_TOPBAR_BG;
    }

    /** 模型 A：页面自管滚动，只要报出内容总高 */
    @Override
    protected int contentHeight() {
        return layout().contentH;
    }

    // ==================== 布局 ====================

    /** 分组设置里的一个槽位：一个可选中的分组（{@code y}/{@code h} 为逻辑坐标） */
    private record GroupSlot(String label, int y, int h) {
    }

    /**
     * 每帧的布局结果，所有 y 都是<b>逻辑坐标</b>（内容坐标，<b>未含滚动</b>）；
     * 渲染时 {@code scrollY(y)}、命中时 {@code logicalY(localY)}。
     */
    private static final class Layout {
        int headerY, headerH, avatarX, avatarY, avatarH, avatarSize;
        int nameX, nameTop, statusTop;
        int manageTitleY;
        /** 管理组各行：发消息 / 删除记录 / 删除联系人 / 更新头像 / 女仆的样貌 */
        int[] cardY = new int[5];
        int manageRectY, manageRectH;
        /** 展开的「女仆的样貌」输入框（逻辑坐标；{@code -1} = 未展开，不渲染也不命中） */
        int appearanceBoxY = -1, appearanceBoxH = 0;
        int sosTitleY, bindCardY, modeCardY, modeListY, modeListH;
        int sosRectY, sosRectH;
        int groupTitleY, groupBoxY;
        int groupRectY, groupRectH;
        List<GroupSlot> groupSlots = new ArrayList<>();
        /** 「新建分组」内联输入框那一行（逻辑坐标；{@code -1} = 未展开，不渲染也不命中） */
        int groupNewRowY = -1, groupNewRowH = 0;
        int contentH;
    }

    /**
     * <b>唯一</b>的布局计算：{@link #renderContent} / {@link #onContentClick} /
     * {@link #mouseScrolled} / {@link #contentHeight} 都调它（不再有任何一处在别处重算行 y）。
     * <p>本页没有悬停高亮，所以不覆盖 {@link PhonePage#onContentMoved}（默认空实现）。
     */
    private Layout layout() {
        Font font = font();
        Layout L = new Layout();

        // 逻辑游标：内容区顶部留白（旧实现的 navH + sh(6)，减去顶栏即 sh(6)）
        int y = PhoneText.px(6);

        // 头部：大头像高度 = 名称(1.75x) + 状态(1.25x) 文字高度之和（随字体倍率缩放）
        int nameH = PhoneText.height(font, PhoneText.scale(1.75f));
        int statusH = PhoneText.height(font, PhoneText.scale(1.25f));
        L.avatarH = nameH + statusH;
        L.avatarSize = Math.max(PhoneText.px(24), L.avatarH);
        L.headerH = L.avatarSize + PhoneText.px(16);
        L.headerY = y;
        L.avatarY = L.headerY + (L.headerH - L.avatarSize) / 2;
        L.avatarX = PhoneText.px(12);
        L.nameX = L.avatarX + L.avatarSize + PhoneText.px(12);
        L.nameTop = L.avatarY + (L.avatarSize - (nameH + statusH)) / 2;
        L.statusTop = L.nameTop + nameH + PhoneText.px(2);
        y += L.headerH + groupTitleTop();

        // 管理 分组（一个大矩形内 5 行，灰色横线分隔）
        // 第 5 行是「女仆的样貌」；展开它的多行输入框时，下面 manageBottom 跟着变长，
        // 大矩形自动把它包进去 —— 所以 rectY/H 的公式本身不需要改。
        L.manageTitleY = y;
        y += groupTitleToCard();
        for (int i = 0; i < L.cardY.length; i++) {
            L.cardY[i] = y;
            y += cardH();
        }
        L.manageRectY = L.cardY[0] - rectPad();
        int manageBottom = L.cardY[L.cardY.length - 1] + cardH();
        if (maidAppearanceOpen) {
            L.appearanceBoxY = manageBottom + PhoneText.px(4);
            L.appearanceBoxH = appearanceInput.preferredHeight(font) + PhoneText.px(4);
            manageBottom = L.appearanceBoxY + L.appearanceBoxH;
        }
        L.manageRectH = manageBottom - L.manageRectY + rectPad();
        y = manageBottom + rectPad();
        y += groupTitleTop();

        // SOS 分组（大矩形内含 2 行 + 展开时的工作模式列表）
        L.sosTitleY = y;
        y += groupTitleToCard();
        L.bindCardY = y;
        y += cardH();
        L.modeCardY = y;
        L.modeListY = -1;
        L.modeListH = 0;
        int sosBottom = L.modeCardY + cardH();
        if (modeDropdownOpen && SosBindCache.isBound(stableId)) {
            L.modeListY = L.modeCardY + cardH();
            L.modeListH = Math.min(DROPDOWN_VISIBLE_ROWS, SosTaskCache.getAll().size()) * dropdownRowH();
            sosBottom = L.modeListY + L.modeListH;
        }
        L.sosRectY = L.bindCardY - rectPad();
        L.sosRectH = sosBottom - L.sosRectY + rectPad();
        y = sosBottom + rectPad();
        y += groupTitleTop();

        // 分组设置 分组（折叠栏：点击展开分组选项 + 新建分组，都在大矩形内）
        L.groupTitleY = y;
        y += groupTitleToCard();
        L.groupBoxY = y;
        y += cardH();
        int groupBottom = L.groupBoxY + cardH();
        if (groupDropdownOpen) {
            for (String g : groupOptions()) {
                L.groupSlots.add(new GroupSlot(g, y, cardH()));
                y += cardH();
            }
            // 最后一行 = 「新建分组」内联输入框。行高取 max(行高, 输入框高)：
            // 默认字体倍率下输入框(20px)比行(30px)矮，行高不变；
            // 字体倍率调小到输入框比行还高时才需要把行撑开（否则框会压到上面的分隔线）。
            L.groupNewRowY = y;
            L.groupNewRowH = Math.max(cardH(), groupNewInput.preferredHeight(font));
            y += L.groupNewRowH;
            groupBottom = y;
        }
        L.groupRectY = L.groupBoxY - rectPad();
        L.groupRectH = groupBottom - L.groupRectY + rectPad();
        y = groupBottom + rectPad();
        y += PhoneText.px(4);

        // 与旧实现逐字同式：内容总高不含顶栏，底部再留 sh(4)
        L.contentH = y - PhoneText.px(6) + PhoneText.px(4);
        return L;
    }

    // ==================== 渲染 ====================

    @Override
    protected void renderContent(GuiGraphics g, int originX, int originY, int w, int h, float partialTick) {
        Font font = font();
        ColorScheme c = colors();
        Layout L = layout();

        renderHeader(g, font, L, originX, originY);
        renderManageGroup(g, font, c, L, originX, originY);
        renderSosGroup(g, font, c, L, originX, originY);
        renderGroupSection(g, font, c, L, originX, originY);

        // 短暂提示（旧实现在自己的裁剪之外绘制；骨架的裁剪区就是内容区，绘制范围一致）
        if (System.currentTimeMillis() < flashUntil && !flashText.isEmpty()) {
            float fs = PhoneText.scale(1.0f);
            int lineH = Math.max(1, PhoneText.height(font, fs));
            // 英文 toast 更长：先按气泡可用宽度截断，再量/画这份实际译文，绝不溢出气泡
            String flashShown = PhoneText.truncate(font, flashText, Math.max(0, w - PhoneText.px(16)), fs);
            int msgW = PhoneText.width(font, flashShown, fs);
            int msgY = originY + h - PhoneText.px(30);
            int boxTop = msgY - PhoneText.px(3);
            int boxH = lineH + PhoneText.px(6);
            PhoneShapes.fillRoundedRect(g, originX + PhoneText.px(4), boxTop, w - PhoneText.px(8), boxH, 8, 0xCC000000);
            PhoneText.draw(g, font, flashShown, originX + (w - msgW) / 2f, boxTop + (boxH - lineH) / 2f + 1,
                    0xFFFFFFFF, fs);
        }

        if (isConfirming()) {
            // 文案由 confirmText(...) 按当前 Confirm/confirmGroup 集中生成，
            // 渲染与命中都只走 confirmLayout(...) 这一份几何（含真实行数）。
            renderConfirmDialog(g, font, c, originX, originY, w, h);
        }
    }

    /** 头部信息区：左侧大头像（女仆照片），右侧名称 + 状态，整体垂直居中 */
    private void renderHeader(GuiGraphics g, Font font, Layout L, int originX, int originY) {
        int avX = originX + L.avatarX;
        int avY = originY + scrollY(L.avatarY);
        int size = L.avatarSize;
        ResourceLocation tex = MaidAvatarManager.getOrLoadTexture(stableId);
        if (tex != null) {
            int[] d = MaidAvatarManager.getTextureSize(stableId);
            int tw = d != null ? d[0] : size;
            int th = d != null ? d[1] : size;
            g.blit(tex, avX, avY, size, size, 0.0f, 0.0f, tw, th, tw, th);
        } else {
            PhoneShapes.fillRoundedRect(g, avX, avY, size, size, 8, 0xFFB0BEC5);
        }

        String status = MaidStatusCache.get(stableId);
        if (status.isEmpty()) status = "…";
        PhoneText.draw(g, font, maidName, originX + L.nameX, originY + scrollY(L.nameTop),
                colors().textPrimary(), PhoneText.scale(1.75f));
        PhoneText.draw(g, font, status, originX + L.nameX, originY + scrollY(L.statusTop),
                colors().textSecondary(), PhoneText.scale(1.25f));
    }

    private void renderManageGroup(GuiGraphics g, Font font, ColorScheme c, Layout L, int originX, int originY) {
        float s = PhoneText.scale(1.0f);
        int lineH = Math.max(1, PhoneText.height(font, s));
        int rectX = originX + PhoneText.px(10);
        int rectW = width() - PhoneText.px(20);
        PhoneText.draw(g, font, PhoneText.tr("gui.huhutalk.maid_info.manage"), originX + PhoneText.px(12),
                originY + scrollY(L.manageTitleY) - PhoneText.px(4), c.textMuted(), s);
        // 大矩形（含 5 行，灰色横线分隔；展开外貌输入框时会自动包住它）
        PhoneShapes.fillRoundedRect(g, rectX, originY + scrollY(L.manageRectY), rectW, L.manageRectH, CARD_R, c.cardBg());
        String[] labels = {
                PhoneText.tr("gui.huhutalk.maid_info.action.message"),
                PhoneText.tr("gui.huhutalk.maid_info.action.delete_history"),
                PhoneText.tr("gui.huhutalk.maid_info.action.delete_contact"),
                PhoneText.tr("gui.huhutalk.maid_info.action.update_avatar"),
                PhoneText.tr("gui.huhutalk.maid_info.action.appearance"),
        };
        int[] labelColors = {c.textPrimary(), c.textPrimary(), c.dangerBg(), 0xFF2E7D32, c.textPrimary()};
        for (int i = 0; i < labels.length; i++) {
            int y = originY + scrollY(L.cardY[i]);
            int textY = y + (cardH() - lineH) / 2 + 1;
            PhoneText.draw(g, font, labels[i], rectX + PhoneText.px(12), textY, labelColors[i], s);
            if (i == labels.length - 1) {
                // 最后一行不画「›」（它不跳转，只展开下面的输入框），改画展开箭头 + 填写状态
                PhoneText.draw(g, font, maidAppearanceOpen ? "▲" : "▼",
                        rectX + rectW - PhoneText.px(16), textY, 0xFFB9AAB0, s);
                String state = HuhuTalkClientSettings.getMaidAppearance(stableId).isEmpty()
                        ? PhoneText.tr("gui.huhutalk.maid_info.appearance.unfilled")
                        : PhoneText.tr("gui.huhutalk.maid_info.appearance.filled");
                // 英文状态更长：量/画都用同一份截断后的实际译文，避免压到左侧标题
                int stateRight = rectX + rectW - PhoneText.px(22);
                int stateMaxW = Math.max(0, stateRight
                        - (rectX + PhoneText.px(12) + PhoneText.width(font, labels[i], s)) - PhoneText.px(6));
                String stateShown = PhoneText.truncate(font, state, stateMaxW, s);
                PhoneText.draw(g, font, stateShown,
                        stateRight - PhoneText.width(font, stateShown, s),
                        textY, c.textMuted(), s);
            } else {
                PhoneText.draw(g, font, "›", rectX + rectW - PhoneText.px(16), textY, 0xFFB9AAB0, s);
            }
            if (i < labels.length - 1 || L.appearanceBoxY >= 0) {
                drawDivider(g, c, rectX, rectW, y + cardH());
            }
        }
        renderAppearanceBox(g, c, L, originX, originY);
    }

    /**
     * 展开的「女仆的样貌」多行输入框（未展开时自动收成零尺寸）。
     *
     * <h2>⚠ 坐标系：页面自绘用<b>屏幕坐标</b>，控件的 {@code bounds} 用<b>内容坐标</b></h2>
     * 与 {@link #renderNewGroupRow} 完全同一条规矩（那里记录了踩坑经过）：
     * {@code fillRoundedRect} 要自己带 {@code originX/originY}，
     * 而 {@code PhoneWidget#render} 的 {@code bounds} 用 {@code scrollY(logical)}、不加原点。
     */
    private void renderAppearanceBox(GuiGraphics g, ColorScheme c, Layout L, int originX, int originY) {
        if (L.appearanceBoxY < 0) {
            appearanceInput.bounds(0, 0, 0, 0);
            return;
        }
        // 占位符每帧按当前语言刷新（构造期不缓存译文）；只改提示，不动已有内容与焦点
        appearanceInput.placeholder(PhoneText.tr("gui.huhutalk.maid_info.appearance.placeholder"));
        int cardX = PhoneText.px(10);
        int cardW = width() - PhoneText.px(20);
        int inX = cardX + PhoneText.px(8);
        int inW = cardW - PhoneText.px(16);
        int inH = appearanceInput.preferredHeight(font());
        int inY = scrollY(L.appearanceBoxY);

        // 垫一层比卡片深一档的底：输入框自己的底色也是 cardBg()，直接压在卡片上等于没有边框。
        PhoneShapes.fillRoundedRect(g, originX + inX - PhoneText.px(2), originY + inY - PhoneText.px(2),
                inW + PhoneText.px(4), inH + PhoneText.px(4), 6, c.pageBgList());

        appearanceInput.bounds(inX, inY, inW, inH);
        appearanceInput.render(g, originX, originY);
    }

    private void renderSosGroup(GuiGraphics g, Font font, ColorScheme c, Layout L, int originX, int originY) {
        float s = PhoneText.scale(1.0f);
        int lineH = Math.max(1, PhoneText.height(font, s));
        int rectX = originX + PhoneText.px(10);
        int rectW = width() - PhoneText.px(20);
        PhoneText.draw(g, font, PhoneText.tr("gui.huhutalk.maid_info.section.sos"), originX + PhoneText.px(12),
                originY + scrollY(L.sosTitleY) - PhoneText.px(4), c.textMuted(), s);
        // 大矩形（含 SOS呼叫 / 工作模式 2 行 + 展开时的工作模式列表）
        PhoneShapes.fillRoundedRect(g, rectX, originY + scrollY(L.sosRectY), rectW, L.sosRectH, CARD_R, c.cardBg());

        boolean bound = SosBindCache.isBound(stableId);
        int danger = c.dangerBg();
        // SOS 呼叫（绑定开关）
        int bindY = originY + scrollY(L.bindCardY);
        int bindTextY = bindY + (cardH() - lineH) / 2 + 1;
        PhoneText.draw(g, font, PhoneText.tr("gui.huhutalk.maid_info.sos.bind"), rectX + PhoneText.px(12), bindTextY,
                bound ? danger : c.textPrimary(), s);
        String bindState = bound
                ? PhoneText.tr("gui.huhutalk.maid_info.sos.bound")
                : PhoneText.tr("gui.huhutalk.maid_info.sos.unbound");
        // 右侧状态列：英文更长时截断，量/画共用截断结果，不越过左侧标题
        int bindRight = rectX + rectW - PhoneText.px(12);
        int bindMaxW = Math.max(0, bindRight
                - (rectX + PhoneText.px(12)
                        + PhoneText.width(font, PhoneText.tr("gui.huhutalk.maid_info.sos.bind"), s))
                - PhoneText.px(6));
        String bindShown = PhoneText.truncate(font, bindState, bindMaxW, s);
        PhoneText.draw(g, font, bindShown, bindRight - PhoneText.width(font, bindShown, s),
                bindTextY, bound ? danger : c.textSecondary(), s);
        drawDivider(g, c, rectX, rectW, bindY + cardH());

        // 工作模式（未绑定时置灰，点击展开原下拉）
        int modeY = originY + scrollY(L.modeCardY);
        int modeTextY = modeY + (cardH() - lineH) / 2 + 1;
        int modeColor = bound ? c.textPrimary() : c.textSecondary();
        PhoneText.draw(g, font, PhoneText.tr("gui.huhutalk.maid_info.sos.mode"), rectX + PhoneText.px(12), modeTextY, modeColor, s);
        String modeName = bound
                ? formatTaskName(SosBindCache.getTaskUid(stableId))
                : PhoneText.tr("gui.huhutalk.maid_info.sos.need_bind");
        // 右侧模式名：为 ▼ 箭头留出 px(26)，再按剩余宽度截断（英文任务名可能很长）
        int modeRight = rectX + rectW - PhoneText.px(26);
        int modeMaxW = Math.max(0, modeRight
                - (rectX + PhoneText.px(12)
                        + PhoneText.width(font, PhoneText.tr("gui.huhutalk.maid_info.sos.mode"), s))
                - PhoneText.px(6));
        String modeShown = PhoneText.truncate(font, modeName, modeMaxW, s);
        PhoneText.draw(g, font, modeShown, modeRight - PhoneText.width(font, modeShown, s),
                modeTextY, modeColor, s);
        PhoneText.draw(g, font, modeDropdownOpen ? "▲" : "▼",
                rectX + rectW - PhoneText.px(16), modeTextY, 0xFFB9AAB0, s);

        // 展开的工作模式列表（在 SOS 大矩形内部）
        if (bound && modeDropdownOpen && L.modeListY >= 0) {
            drawDivider(g, c, rectX, rectW, modeY + cardH());
            List<String> tasks = SosTaskCache.getAll();
            int listY = originY + scrollY(L.modeListY);
            int windowStart = clamp(modeCursor - DROPDOWN_VISIBLE_ROWS / 2,
                    Math.max(0, tasks.size() - DROPDOWN_VISIBLE_ROWS));
            for (int i = 0; i < DROPDOWN_VISIBLE_ROWS && windowStart + i < tasks.size(); i++) {
                int idx = windowStart + i;
                int rowY = listY + i * dropdownRowH();
                int textY = rowY + (dropdownRowH() - lineH) / 2 + 1;
                if (idx == modeCursor) {
                    PhoneShapes.fillRoundedRect(g, rectX + PhoneText.px(4), rowY, rectW - PhoneText.px(8),
                            dropdownRowH(), 6, 0xFF4A6FB5);
                    PhoneText.draw(g, font, formatTaskName(tasks.get(idx)), rectX + PhoneText.px(12),
                            textY, 0xFFFFFFFF, s);
                } else {
                    PhoneText.draw(g, font, formatTaskName(tasks.get(idx)), rectX + PhoneText.px(12),
                            textY, c.textPrimary(), s);
                }
                drawDivider(g, c, rectX, rectW, rowY + dropdownRowH());
            }
        }
    }

    private void renderGroupSection(GuiGraphics g, Font font, ColorScheme c, Layout L, int originX, int originY) {
        float s = PhoneText.scale(1.0f);
        int lineH = Math.max(1, PhoneText.height(font, s));
        int rectX = originX + PhoneText.px(10);
        int rectW = width() - PhoneText.px(20);
        PhoneText.draw(g, font, PhoneText.tr("gui.huhutalk.maid_info.section.groups"), originX + PhoneText.px(12),
                originY + scrollY(L.groupTitleY) - PhoneText.px(4), c.textMuted(), s);
        // 大矩形（含当前分组折叠栏 + 展开时的选项/新建分组）
        PhoneShapes.fillRoundedRect(g, rectX, originY + scrollY(L.groupRectY), rectW, L.groupRectH, CARD_R, c.cardBg());

        String current = currentGroup(); // 稳定分组标识：比较与落盘都用它
        int boxY = originY + scrollY(L.groupBoxY);
        int boxTextY = boxY + (cardH() - lineH) / 2 + 1;
        // 右侧留出 ▼ 箭头，当前分组名（含英文）超宽时截断；量/画共用同一份译文
        String currentLine = PhoneText.tr("gui.huhutalk.maid_info.group.current",
                MaidGroupHelper.displayGroup(current));
        int currentMaxW = Math.max(0, rectW - PhoneText.px(34));
        PhoneText.draw(g, font, PhoneText.truncate(font, currentLine, currentMaxW, s),
                rectX + PhoneText.px(12), boxTextY, c.textPrimary(), s);
        PhoneText.draw(g, font, groupDropdownOpen ? "▲" : "▼", rectX + rectW - PhoneText.px(16),
                boxTextY, 0xFFB9AAB0, s);

        // 展开时：分组选项 + 新建分组（都在分组大矩形内，灰色横线分隔）
        if (groupDropdownOpen) {
            drawDivider(g, c, rectX, rectW, boxY + cardH());
            for (GroupSlot slot : L.groupSlots) {
                int y = originY + scrollY(slot.y());
                int textY = y + (slot.h() - lineH) / 2 + 1;
                boolean isCurrent = slot.label().equals(current); // 稳定标识比较
                int col = isCurrent ? 0xFFB06A6A : c.textPrimary();
                PhoneText.draw(g, font, MaidGroupHelper.displayGroup(slot.label()), rectX + PhoneText.px(12), textY, col, s);
                if (isCurrent) {
                    PhoneText.draw(g, font, "✓", rectX + rectW - PhoneText.px(40), textY, 0xFFB06A6A, s);
                }
                // 自定义分组右端画「✕」= 可删除；默认（状态）分组不画（判据只有 isDeletableGroup 一处）
                if (isDeletableGroup(slot.label())) {
                    int markX = deleteMarkX(rectX, rectW);
                    int markW = deleteMarkW();
                    PhoneText.draw(g, font, "✕",
                            markX + (markW - PhoneText.width(font, "✕", s)) / 2f, textY, 0xFFC62828, s);
                }
                drawDivider(g, c, rectX, rectW, y + slot.h());
            }
        }
        renderNewGroupRow(g, c, L, originX, originY);
    }

    /**
     * 该分组名是否<b>可删除</b>（= 不是四个默认状态分组）。
     * <p>
     * 渲染"画不画 ✕"与命中"响不响应删除"共用它 —— 只有一处判据，
     * 不会出现"画了 ✕ 却删不掉"或"没画 ✕ 却能删"。
     */
    private static boolean isDeletableGroup(String name) {
        return !HuhuTalkClientSettings.isDefaultGroupName(name);
    }

    /** 「✕」标记矩形的左边界（分组行的内容坐标）。渲染与命中共享。 */
    private static int deleteMarkX(int rectX, int rectW) {
        return rectX + rectW - PhoneText.px(26);
    }

    /** 「✕」标记矩形的宽度（约 2.7 个字宽：够拇指点，又不会和"点整行改分组"抢边界）。 */
    private static int deleteMarkW() {
        return PhoneText.px(24);
    }

    /**
     * 删除一个<b>自定义</b>分组。
     *
     * <h2>为什么必须连带把女仆收回默认分组</h2>
     * 女仆的分组是"手动分组优先、否则按状态"（{@code MaidGroupHelper.resolveGroup}）。
     * 只删分组名的话，原本手动分到该组的女仆<b>手动分组字段还指着那个已经不存在的名字</b>，
     * 于是联系人列表里照样有一个分组标题 —— 一个既选不中、又删不掉的<b>幽灵分组</b>。
     * 所以这里遍历一次手动分组映射，把指向本组的条目清空（清空 = 回到按状态分组）。
     *
     * <p>默认（状态）分组一律拒绝：与 UI 上不画 ✕ 是同一条判据。
     */
    private void deleteGroup(String name) {
        if (!isDeletableGroup(name)) {
            return;
        }
        HuhuTalkClientSettings.removeCustomGroup(name);
        for (Map.Entry<String, String> e : HuhuTalkClientSettings.getMaidGroups().entrySet()) {
            if (name.equals(e.getValue())) {
                HuhuTalkClientSettings.setMaidGroup(e.getKey(), "");
            }
        }
        showFlash(PhoneText.tr("gui.huhutalk.maid_info.group.deleted", MaidGroupHelper.displayGroup(name)));
    }

    /**
     * 「新建分组」内联输入框那一行（分组栏收起时自动收成零尺寸）。
     *
     * <h2>⚠ 坐标系：页面自己画的用<b>屏幕坐标</b>，控件的 {@code bounds} 用<b>内容坐标</b></h2>
     * 这是本页最容易写错的一处，写错的表现极具迷惑性，务必读完：
     * <ul>
     *   <li>{@link PhoneShapes#fillRoundedRect} / {@code g.fill} / {@link PhoneText#draw}
     *       收的是<b>屏幕坐标</b>，页面里的调用点必须自己带上 {@code originX/originY}
     *       （看上面的分组行渲染：{@code originY + scrollY(slot.y())}）；</li>
     *   <li>{@link com.huhutalk.api.phone.ui.PhoneWidget#render} 收的
     *       {@code bounds} 是<b>内容坐标</b>，它内部<b>自己会加</b> {@code originX/originY}
     *       （顺便也就"不知道滚动偏移"，所以 bounds 用 {@code scrollY(logical)}）。</li>
     * </ul>
     * ⚠ <b>本方法曾经把对比底色条按内容坐标画（漏了 origin），于是整条底色上移了约一行，
     * 正好盖住分组列表最后一行的文字</b> —— 表现为"最后一个分组变成了一个灰色圆角方框"，
     * 而点它仍然会切到那个分组（命中用的是 {@code slot.y()}，本来就是那一行）。
     * 换排序之后受害者从"最新建的自定义分组"变成了"死亡"，非常难认。
     * <b>教训：同一个方法里混用两套坐标系时，宁可把 {@code originX/originY} 写在每一行上。</b>
     *
     * <p>命中由整行负责（见 {@code onContentClick}）：输入框比行矮，点在行内空白处也该算点它。
     */
    private void renderNewGroupRow(GuiGraphics g, ColorScheme c, Layout L, int originX, int originY) {
        if (L.groupNewRowY < 0) {
            // 收起态：收成零尺寸，既不渲染也不接受命中（与 hideControls 同一处理）
            groupNewInput.bounds(0, 0, 0, 0);
            return;
        }
        // 占位符每帧按当前语言刷新（构造期不缓存译文）；只改提示，不动已有内容与焦点
        groupNewInput.placeholder(PhoneText.tr("gui.huhutalk.maid_info.group.placeholder"));
        int cardX = PhoneText.px(10);
        int cardW = width() - PhoneText.px(20);
        int inX = cardX + PhoneText.px(8);
        int inW = cardW - PhoneText.px(16);
        int inH = groupNewInput.preferredHeight(font());
        int inY = scrollY(L.groupNewRowY + (L.groupNewRowH - inH) / 2);

        // 垫一层比卡片深一档的底：输入框自己的底色也是 cardBg()，直接压在卡片上等于没有边框。
        // ⚠ 屏幕坐标（+ originX/originY）—— 见上面的坐标说明，漏了就是"灰条上移一行"那个 bug。
        PhoneShapes.fillRoundedRect(g, originX + inX - PhoneText.px(2), originY + inY - PhoneText.px(2),
                inW + PhoneText.px(4), inH + PhoneText.px(4), 6, c.pageBgList());

        // 控件用内容坐标（它自己加 origin），与上面那行只差这一次换算
        groupNewInput.bounds(inX, inY, inW, inH);
        groupNewInput.render(g, originX, originY);
    }

    /** 组内灰色分隔线（两端内缩） */
    private static void drawDivider(GuiGraphics g, ColorScheme c, int rectX, int rectW, int y) {
        g.fill(rectX + PhoneText.px(8), y, rectX + rectW - PhoneText.px(8), y + 1, c.divider());
    }

    // ==================== 删除确认弹窗 ====================

    /**
     * 弹窗几何（<b>内容坐标</b>）：{@code x/y/w/h} 是外框，其余是按钮矩形与折行后的文字。
     * <p>渲染与命中<b>共用这一份</b>；文字行数决定外框高度，所以两边拿到的按钮位置完全一致。
     */
    private record ConfirmLayout(int x, int y, int w, int h,
                                 int btnW, int btnH, int btnY, int cancelX, int delX,
                                 int textX, int textTop, int lineH, int detailGap,
                                 List<FormattedCharSequence> titleLines,
                                 List<FormattedCharSequence> detailLines) {
    }

    /** 确认弹窗的文案：按当前 {@link #confirm}/{@link #confirmGroup} 生成，语义与旧实现逐字相同。 */
    private record ConfirmText(String title, String detail,
                               List<FormattedCharSequence> titleLines,
                               List<FormattedCharSequence> detailLines) {
    }

    /**
     * 按当前确认对象生成标题/正文，并用 {@code Font.split} 在给定宽度下<b>按词折行</b>
     * （{@code wrapWidth} 已是未乘缩放的字体像素）。两种确认共用这一处，不再各写一套标题判断。
     */
    private ConfirmText confirmText(Font font, int wrapWidth) {
        String title;
        String detail;
        if (confirm == Confirm.DELETE_GROUP) {
            title = PhoneText.tr("gui.huhutalk.maid_info.confirm.delete_group_title",
                    MaidGroupHelper.displayGroup(confirmGroup));
            detail = PhoneText.tr("gui.huhutalk.maid_info.confirm.delete_group_detail");
        } else {
            title = PhoneText.tr("gui.huhutalk.maid_info.confirm.delete_contact_title");
            detail = PhoneText.tr("gui.huhutalk.maid_info.confirm.delete_contact_detail");
        }
        int wrap = Math.max(1, wrapWidth);
        return new ConfirmText(title, detail,
                font.split(Component.literal(title), wrap),
                font.split(Component.literal(detail), wrap));
    }

    /** @param viewH 内容区可视高度（渲染时用渲染回调给的 h，命中时用 {@link #contentViewHeight()}，两者相等） */
    private ConfirmLayout confirmLayout(int viewH) {
        Font font = font();
        float s = PhoneText.scale(1.0f);
        int lineH = Math.max(1, PhoneText.height(font, s));

        // 宽度兜底：极窄时不让外框/按钮出现负宽；上限夹到手机可用宽度，普通尺寸数值不变
        int dlgW = Math.max(PhoneText.px(60), width() - PhoneText.px(40));
        dlgW = Math.min(dlgW, Math.max(1, width()));
        int textX = PhoneText.px(10);
        int textMaxW = Math.max(1, dlgW - textX * 2);
        ConfirmText text = confirmText(font, Math.max(1, (int) (textMaxW / s)));

        int padTop = PhoneText.px(12);
        int padBottom = PhoneText.px(8);
        int detailGap = PhoneText.px(2);
        int gapTextBtn = PhoneText.px(8);
        int btnH = PhoneText.px(22);
        int titleH = text.titleLines().size() * lineH;
        int detailH = text.detailLines().size() * lineH;
        int naturalH = padTop + titleH + detailGap + detailH + gapTextBtn + btnH + padBottom;
        // 高度按真实行数增长；只有可视区比它更矮时才压回，文字随后裁剪到按钮上方，正文不会压住按钮。
        int dlgH = Math.max(PhoneText.px(1), Math.min(naturalH, Math.max(PhoneText.px(1), viewH)));

        int dlgX = (width() - dlgW) / 2;
        int dlgY = viewH / 2 - dlgH / 2;
        int btnW = Math.max(PhoneText.px(10), (dlgW - PhoneText.px(30)) / 2);
        int btnY = dlgY + dlgH - btnH - padBottom;
        int cancelX = dlgX + PhoneText.px(10);
        int delX = dlgX + dlgW - PhoneText.px(10) - btnW;
        return new ConfirmLayout(dlgX, dlgY, dlgW, dlgH, btnW, btnH, btnY, cancelX, delX,
                dlgX + textX, dlgY + padTop, lineH, detailGap,
                text.titleLines(), text.detailLines());
    }

    /**
     * 二次确认弹窗（渲染与命中共用 {@link #confirmLayout} 那一份几何）。
     * <p>
     * 删除联系人与删除分组共用同一个框，只有"确认的是什么"不同（见 {@link #confirmText}）；
     * 标题与正文按词折行后<b>完整绘制</b>，不再用省略号丢弃警告。
     */
    private void renderConfirmDialog(GuiGraphics g, Font font, ColorScheme c,
                                     int originX, int originY, int w, int h) {
        float s = PhoneText.scale(1.0f);
        g.fill(originX, originY, originX + w, originY + h, 0x66000000);

        ConfirmLayout d = confirmLayout(h);
        int dlgX = originX + d.x();
        int dlgY = originY + d.y();
        PhoneShapes.fillRoundedRect(g, dlgX, dlgY, d.w(), d.h(), 8, 0xFFFAFAFA);

        int textX = originX + d.textX();
        int textRight = dlgX + d.w() - (d.textX() - d.x());
        int textTop = originY + d.textTop();
        int textBottom = originY + d.btnY() - PhoneText.px(4);
        // 只有存在正的文字空间才裁剪并绘制；区域无效则不画正文，绝不能越过按钮区
        if (textBottom > textTop && textRight > textX) {
            g.enableScissor(textX, textTop, textRight, textBottom);
            int ty = textTop;
            for (FormattedCharSequence line : d.titleLines()) {
                PhoneText.draw(g, font, line, textX, ty, c.textPrimary(), s);
                ty += d.lineH();
            }
            ty += d.detailGap();
            for (FormattedCharSequence line : d.detailLines()) {
                PhoneText.draw(g, font, line, textX, ty, c.textSecondary(), s);
                ty += d.lineH();
            }
            g.disableScissor();
        }

        int btnY = originY + d.btnY();
        int lineH = d.lineH();
        int btnTextY = btnY + (d.btnH() - lineH) / 2 + 1;
        PhoneShapes.fillRoundedRect(g, originX + d.cancelX(), btnY, d.btnW(), d.btnH(), d.btnH() / 2, 0xFFE0E0E0);
        int labelMaxW = Math.max(0, d.btnW() - PhoneText.px(8));
        String cancel = PhoneText.truncate(font, PhoneText.tr("gui.huhutalk.maid_info.confirm.cancel"), labelMaxW, s);
        PhoneText.draw(g, font, cancel,
                originX + d.cancelX() + (d.btnW() - PhoneText.width(font, cancel, s)) / 2f,
                btnTextY, c.textPrimary(), s);
        PhoneShapes.fillRoundedRect(g, originX + d.delX(), btnY, d.btnW(), d.btnH(), d.btnH() / 2, 0xFFE53935);
        String confirmLabel = PhoneText.truncate(font, PhoneText.tr("gui.huhutalk.maid_info.confirm.confirm"), labelMaxW, s);
        PhoneText.draw(g, font, confirmLabel,
                originX + d.delX() + (d.btnW() - PhoneText.width(font, confirmLabel, s)) / 2f,
                btnTextY, 0xFFFFFFFF, s);
    }

    // ==================== 鼠标事件 ====================

    /** 弹窗打开时，顶栏点击也必须先给弹窗（旧实现在返回键判定之前就处理弹窗） */
    @Override
    protected boolean onTopBarClick(double relX, double relY, int button, int action) {
        if (!isConfirming()) return false;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || action != GLFW.GLFW_PRESS) return false;
        handleConfirmClick(relX, relY - contentTop());
        return true;
    }

    @Override
    protected boolean onContentClick(double localX, double localY, int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (action != GLFW.GLFW_PRESS) return false;
        Layout L = layout();

        if (isConfirming()) {
            // 弹窗打开时吞掉内容区的一切左键（旧实现同样总是返回 true）
            handleConfirmClick(localX, localY);
            return true;
        }
        // 顶栏区域（骨架已把它交回内容钩子；非返回键区域在旧实现里直接被忽略）
        if (localY < 0) return false;

        double logical = logicalY(localY);
        int cardX = PhoneText.px(10);
        int cardW = width() - PhoneText.px(20);

        // 管理 4 项
        if (PhoneLayout.hit(localX, logical, cardX, L.cardY[0], cardW, cardH())) {
            PhoneOverlay.pushPage(new ChatPage(stableId, maidName));
            return true;
        }
        if (PhoneLayout.hit(localX, logical, cardX, L.cardY[1], cardW, cardH())) {
            if (ChatHistoryStorage.hasConversation(stableId)) {
                ChatHistoryStorage.deleteConversation(stableId);
                // 删除聊天记录时，把该会话收件箱里尚未领取的文件一股脑掉到玩家脚下
                PacketDistributor.sendToServer(new com.huhutalk.network.C2SDropTransferInboxPacket(stableId));
                showFlash(PhoneText.tr("gui.huhutalk.maid_info.history.deleted"));
            } else {
                showFlash(PhoneText.tr("gui.huhutalk.maid_info.history.empty"));
            }
            return true;
        }
        if (PhoneLayout.hit(localX, logical, cardX, L.cardY[2], cardW, cardH())) {
            confirm = Confirm.DELETE_CONTACT;
            return true;
        }
        if (PhoneLayout.hit(localX, logical, cardX, L.cardY[3], cardW, cardH())) {
            updateAvatar();
            return true;
        }
        // 第 5 行「女仆的样貌」：点一下展开 / 收起下方的多行输入框
        if (PhoneLayout.hit(localX, logical, cardX, L.cardY[4], cardW, cardH())) {
            maidAppearanceOpen = !maidAppearanceOpen;
            return true;
        }
        // 展开的输入框：**整块都是热区**（与「新建分组」那一行同一处理）——
        // 点在框内任意处都算点在它上面，不去凑控件自己的框体几何。
        if (L.appearanceBoxY >= 0
                && PhoneLayout.hit(localX, logical, cardX, L.appearanceBoxY, cardW, L.appearanceBoxH)) {
            appearanceInput.startEditing();
            return true;
        }

        // SOS 呼叫（绑定开关）
        if (PhoneLayout.hit(localX, logical, cardX, L.bindCardY, cardW, cardH())) {
            PacketDistributor.sendToServer(new C2SToggleSosBindPacket(stableId));
            return true;
        }

        // SOS 工作模式：先处理下拉列表项，再处理模式卡片展开
        boolean bound = SosBindCache.isBound(stableId);
        if (bound && modeDropdownOpen && L.modeListY >= 0
                && PhoneLayout.hit(localX, logical, cardX, L.modeListY, cardW, L.modeListH)) {
            List<String> tasks = SosTaskCache.getAll();
            int windowStart = clamp(modeCursor - DROPDOWN_VISIBLE_ROWS / 2,
                    Math.max(0, tasks.size() - DROPDOWN_VISIBLE_ROWS));
            int row = (int) ((logical - L.modeListY) / dropdownRowH());
            int idx = windowStart + row;
            if (idx >= 0 && idx < tasks.size()) {
                PacketDistributor.sendToServer(new C2SSetSosModePacket(stableId, tasks.get(idx)));
                modeDropdownOpen = false;
            }
            return true;
        }
        if (PhoneLayout.hit(localX, logical, cardX, L.modeCardY, cardW, cardH())) {
            if (bound) {
                modeDropdownOpen = !modeDropdownOpen;
                if (modeDropdownOpen) {
                    modeCursor = Math.max(0, SosTaskCache.getAll().indexOf(SosBindCache.getTaskUid(stableId)));
                }
            } else {
                showFlash(PhoneText.tr("gui.huhutalk.maid_info.sos.bind_first_toast"));
            }
            return true;
        }

        // 分组设置：折叠栏（点击展开/收起）
        if (PhoneLayout.hit(localX, logical, cardX, L.groupBoxY, cardW, cardH())) {
            groupDropdownOpen = !groupDropdownOpen;
            return true;
        }
        // 展开时的分组选项（都在折叠栏内部）
        for (GroupSlot slot : L.groupSlots) {
            if (!PhoneLayout.hit(localX, logical, cardX, slot.y(), cardW, slot.h())) continue;
            // 右端「✕」= 删除该自定义分组。判据（isDeletableGroup）与渲染画不画 ✕ 是同一条；
            // 命中矩形（deleteMarkX/W）也和渲染共用，不会"画在一处、点在另一处"。
            // ⚠ 必须先判 ✕ 再判整行，否则点 ✕ 会被当成"把女仆改到该组"。
            if (isDeletableGroup(slot.label())
                    && PhoneLayout.hit(localX, logical, deleteMarkX(cardX, cardW), slot.y(),
                            deleteMarkW(), slot.h())) {
                // 与「删除联系人」同样走二次确认：删分组会把组里的女仆全部改回按状态分组，
                // 单次点击就生效太容易误触（这里离"点一下切分组"只有 24px）。
                confirm = Confirm.DELETE_GROUP;
                confirmGroup = slot.label();
                return true;
            }
            HuhuTalkClientSettings.setMaidGroup(stableId, slot.label());
            groupDropdownOpen = false;
            showFlash(PhoneText.tr("gui.huhutalk.maid_info.group.assigned", MaidGroupHelper.displayGroup(slot.label())));
            return true;
        }
        // 「新建分组」那一行：**整行都是热区** —— 输入框比行矮，点在行内空白处也该算点在它上面。
        // 所以这里用行的逻辑坐标判定，命中后直接让控件开捕获屏（startEditing），
        // 不去凑控件自己的框体几何（那会把命中区缩成框那么大，很难点）。
        if (L.groupNewRowY >= 0
                && PhoneLayout.hit(localX, logical, cardX, L.groupNewRowY, cardW, L.groupNewRowH)) {
            groupNewInput.startEditing();
            return true;
        }
        return false;
    }

    /**
     * 二次确认弹窗的点击。
     * <p>
     * 「确认」按下去做什么由 {@link #confirm} 决定 —— 两种删除共用同一个框，
     * 所以这里必须显式分支，不能想当然（早期版本的 {@code cameraBackAction} 就是因为
     * 一个入口承担了两种语义而"点确认却退出了页面"）。
     *
     * @param x/y 弹窗坐标（弹窗画在内容区里，所以是<b>内容坐标</b>）
     */
    private void handleConfirmClick(double x, double y) {
        ConfirmLayout d = confirmLayout(contentViewHeight());

        if (PhoneLayout.hit(x, y, d.delX(), d.btnY(), d.btnW(), d.btnH())) {
            if (confirm == Confirm.DELETE_GROUP) {
                String group = confirmGroup;
                clearConfirm();
                deleteGroup(group);   // 内部还会把组里的女仆收回默认分组
                return;
            }
            PacketDistributor.sendToServer(new C2SDeleteContactPacket(stableId));
            ChatHistoryStorage.deleteConversation(stableId);
            // 删除联系人/会话时，一并把未领取文件掉到玩家脚下
            PacketDistributor.sendToServer(new com.huhutalk.network.C2SDropTransferInboxPacket(stableId));
            HuhuTalkClientSettings.setMaidGroup(stableId, "");
            clearConfirm();
            showFlash(PhoneText.tr("gui.huhutalk.maid_info.contact.deleted"));
            return;
        }
        boolean onCancel = PhoneLayout.hit(x, y, d.cancelX(), d.btnY(), d.btnW(), d.btnH());
        boolean insideDlg = PhoneLayout.hit(x, y, d.x(), d.y(), d.w(), d.h());
        if (onCancel || !insideDlg) {
            clearConfirm();
        }
    }

    /**
     * 下拉展开时滚轮改为「移动高亮光标」；其余情况交给骨架滚动内容。
     * <p>坐标：{@code relY} 是手机内容区坐标（顶栏在里面），减 {@link #contentTop()} 才是内容坐标，
     * 与 {@link #renderContent} 的绘制坐标一致。
     */
    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        if (isConfirming()) return true;
        double localY = relY - contentTop();
        Layout L = layout();
        int cardX = PhoneText.px(10);
        int cardW = width() - PhoneText.px(20);

        if (SosBindCache.isBound(stableId) && modeDropdownOpen && L.modeListY >= 0) {
            int listTop = scrollY(L.modeListY);
            if (relX >= cardX && relX <= cardX + cardW
                    && localY >= listTop && localY <= listTop + L.modeListH) {
                List<String> tasks = SosTaskCache.getAll();
                if (!tasks.isEmpty()) {
                    int step = scrollDelta > 0 ? -1 : 1;
                    modeCursor = clamp(modeCursor + step, tasks.size() - 1);
                }
                return true;
            }
        }
        return super.mouseScrolled(relX, relY, scrollDelta);
    }
}
