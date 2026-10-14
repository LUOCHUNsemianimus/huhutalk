package com.huhutalk.phone.pages;

import com.huhutalk.api.phone.ui.PhoneDropdown;
import com.huhutalk.api.phone.ui.PhonePage;
import com.huhutalk.api.phone.ui.PhoneShapes;
import com.huhutalk.api.phone.ui.PhoneText;
import com.huhutalk.api.phone.ui.PhoneTextArea;
import com.huhutalk.api.phone.ui.PhoneTextInput;
import com.huhutalk.api.phone.ui.PhoneWidget;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.client.theme.ColorScheme;
import com.huhutalk.client.vision.VisionSiteCache;
import com.huhutalk.common.vision.VisionAppearance;
import com.huhutalk.common.vision.VisionProvider;
import com.huhutalk.common.vision.VisionProviders;
import com.huhutalk.event.ClientModEvents;
import com.huhutalk.network.C2SRequestVisionSitesPacket;
import com.huhutalk.network.C2SVisionSiteEditPacket;
import com.huhutalk.network.S2CVisionSitesPacket;
import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「设置 App → 识图模型」页：按平台手风琴展开，逐个配置模型与 API Key，并选定识图走哪一家。
 *
 * <h2>交互约定（与需求逐条对应）</h2>
 * <ul>
 *   <li><b>点整行 = 展开 / 收起</b>：想看看某家有什么模型、配没配密钥，不会顺手把识图切过去。</li>
 *   <li><b>展开区里的「使用此平台」按钮 = 选中</b>：选中后整张卡染主题强调色并在页顶标出，
 *       识图即走此平台。</li>
 *   <li><b>API Key 掩码输入</b>：用新的公开控件 {@link PhoneTextInput}，显示为 {@code ••••}。
 *       而且服务端<b>根本不下发密钥</b>，这一格永远从空白开始 —— 页面显示的是
 *       "已配置 / 未配置"这个既有状态。</li>
 * </ul>
 *
 * <h2>数据来源与落点</h2>
 * <ul>
 *   <li>平台列表：服务端快照（{@link VisionSiteCache}），每次 {@link #onOpen()} 重新拉一次。</li>
 *   <li>API Key：写进<b>车万女仆的站点配置</b>
 *       （{@code config/touhou_little_maid/sites/llm.json}），与主模组同源；
 *       权限判定共用主模组的 {@code GameModeUtil.canEditSite}。</li>
 *   <li>选中的平台与模型：本模组的客户端设置（非机密），改完立刻同步给服务端会话缓存。</li>
 * </ul>
 *
 * <h2>布局纪律</h2>
 * {@link #layout()} 是渲染、命中、内容高度<b>三者共用的唯一一份计算</b>。
 * 骨架类注释里反复强调这条：布局只算一次，就不可能出现"画在一处、点在另一处"。
 */
public class VisionModelSettingsPage extends PhonePage {

    // ==================== 尺寸（基准像素，运行时按字体倍率缩放） ====================
    private static final int CARD_PAD = 8;
    private static final int CARD_R = 8;
    private static final int INNER_PAD = 12;
    private static final int HEADER_H = 30;
    private static final int LABEL_H = 11;
    private static final int BOX_H = 20;
    private static final int BTN_H = 22;
    private static final int GAP = 4;
    /** 摘要卡与第一张平台卡之间的间距 */
    private static final int SUMMARY_TO_LIST = 10;
    /** 卡片之间的间距 */
    private static final int CARD_GAP = 6;
    /** 「自定义…」下拉项文案 */
    private static final String CUSTOM_OPTION = "自定义…";
    /** 一次性提示的存活时长（毫秒） */
    private static final long TOAST_MS = 2200L;

    /*
     * 预设表自检（静态初始化块，没法挂 Javadoc，所以用块注释）：
     * 下拉最多渲染 PhoneDropdown.VISIBLE_ROWS 行，还要留一位给「自定义…」，
     * 所以预设模型数不能超过 VisionProviders.MAX_MODELS_PER_PROVIDER。
     *
     * 超限的症状非常隐蔽 —— 末尾的选项渲染不出来也点不到，界面看起来一切正常。
     * 所以在类加载时打一条 WARN，把这个约束从"注释里的一句话"变成"日志里的一行"。
     */
    static {
        for (VisionProvider p : VisionProviders.all()) {
            if (p.models().size() > VisionProviders.MAX_MODELS_PER_PROVIDER) {
                LogUtils.getLogger().warn(
                        "[HuhuTalk] 识图预设 {} 的模型数 {} 超过上限 {}，下拉末尾的选项将无法点选"
                                + "（PhoneDropdown 只渲染前 {} 行）",
                        p.siteId(), p.models().size(), VisionProviders.MAX_MODELS_PER_PROVIDER,
                        PhoneDropdown.VISIBLE_ROWS);
            }
        }
    }

    // ==================== 状态 ====================
    private final Set<String> expanded = new HashSet<>();
    private final Map<String, PhoneDropdown> dropdowns = new HashMap<>();
    private final Map<String, PhoneTextInput> modelInputs = new HashMap<>();
    private final Map<String, PhoneTextInput> keyInputs = new HashMap<>();
    private final Map<String, PhoneTextInput> urlInputs = new HashMap<>();
    private String toast = "";
    private long toastAt = 0L;

    /**
     * 「你的样貌」：主人（玩家）自己的外貌描述。
     *
     * <h2>它影响什么</h2>
     * 识图时拼进视觉模型的提示词，让女仆能判断"画面里那个人是主人"，
     * 并认出主人正在做什么（在看天、在放方块…）。两段描述都为空时，
     * 识图<b>逐字</b>走原来的默认提示词 —— 老玩家行为零变化。
     *
     * <h2>为什么改完要 {@link ClientModEvents#pushVisionSelection()}</h2>
     * 真正的识图请求由<b>服务端</b>发起，它只知道会话缓存里的那份 {@code VisionSettings}；
     * 所以外貌描述必须和"用哪个平台"一起推过去，否则服务端拼出来的是没有样貌的旧提示词。
     */
    private final PhoneTextArea appearanceInput = new PhoneTextArea()
            .maxLength(VisionAppearance.MAX_CHARS)
            .visibleLines(4)
            .placeholder("描述你的外貌，例如：黑色短发、蓝眼睛、白色衬衫…")
            .value(HuhuTalkClientSettings.getPlayerAppearance())
            .onCommit(v -> {
                HuhuTalkClientSettings.setPlayerAppearance(v);
                ClientModEvents.pushVisionSelection();
            });

    public VisionModelSettingsPage() {
    }

    // ==================== 生命周期 ====================

    /**
     * 每次打开都重新拉一次平台列表。
     * <p>
     * 这样玩家在车万女仆自己的 AI 设置里改了站点（改地址、清密钥、新建站点），
     * 回到手机重开本页就能看到最新状态，不需要重启游戏。
     */
    @Override
    public void onOpen() {
        PacketDistributor.sendToServer(new C2SRequestVisionSitesPacket());
        // 外貌描述是持久化的，但页面实例可能复用：开页时重新对齐一次，
        // 免得"设置文件里已经是新值、输入框还停在旧值"。
        appearanceInput.value(HuhuTalkClientSettings.getPlayerAppearance());
    }

    @Override
    protected String title() {
        return "识图模型";
    }

    @Override
    protected int pageBackground() {
        return colors().pageBgList();
    }

    @Override
    protected int topBarBackground() {
        return BRAND_TOPBAR_BG;
    }

    // ==================== 布局 ====================

    /** 一张平台卡的全部几何信息（内容坐标）。 */
    private static final class Card {
        S2CVisionSitesPacket.SiteEntry entry;
        VisionProvider preset;
        String displayName;
        boolean expanded;
        /** 下拉里"自定义…"那一项的下标 */
        int customIdx;

        int top;
        int height;
        int headerTop;
        int headerH;
        int bodyTop;

        int taglineY = -1;
        int noteY = -1;
        int modelLabelY;
        int modelRowY;
        int customRowY = -1;
        int keyLabelY;
        int keyRowY;
        int urlLabelY = -1;
        int urlRowY = -1;
        int useBtnY;
    }

    private int summaryTop;
    private int summaryHeight;
    /** 「你的样貌」卡：卡片顶 / 卡片高 / 标题行（均为内容坐标） */
    private int appearanceTop;
    private int appearanceHeight;
    private int appearanceLabelY;
    private int laidOutHeight;

    private static int scaled(int px) {
        return PhoneText.px(px);
    }

    private static List<String> presetModels(VisionProvider p) {
        return p == null ? List.of() : p.models();
    }

    /** 这个平台允许玩家改接口地址吗（只有「自定义」与玩家自建站点允许）。 */
    private static boolean urlEditable(VisionProvider p) {
        return p == null || p.editableUrl();
    }

    private static String[] dropdownOptions(VisionProvider p) {
        List<String> ms = presetModels(p);
        String[] out = new String[ms.size() + 1];
        for (int i = 0; i < ms.size(); i++) {
            out[i] = ms.get(i);
        }
        out[ms.size()] = CUSTOM_OPTION;
        return out;
    }

    /**
     * 当前应该选中下拉的第几项：
     * <ul>
     *   <li>处于「自定义模型」模式 → 「自定义…」</li>
     *   <li>记过模型且命中预设 → 那一项</li>
     *   <li>记过但不在预设里 → 「自定义…」</li>
     *   <li>没记过 → 预设默认那一项</li>
     * </ul>
     */
    private static int modelIndex(VisionProvider p, String siteId) {
        List<String> ms = presetModels(p);
        if (HuhuTalkClientSettings.isVisionCustomModel(siteId)) {
            return ms.size();
        }
        String stored = HuhuTalkClientSettings.getVisionModel(siteId);
        if (!stored.isBlank()) {
            int i = ms.indexOf(stored);
            return i >= 0 ? i : ms.size();
        }
        String def = p != null ? p.resolveDefaultModel() : "";
        return Math.max(ms.indexOf(def), 0);
    }

    /** 玩家为该平台实际要用的模型名（自定义模式取输入框内容）。 */
    private String effectiveModel(Card c) {
        String id = c.entry.siteId();
        if (HuhuTalkClientSettings.isVisionCustomModel(id)) {
            PhoneTextInput in = modelInputs.get(id);
            return in == null ? "" : in.value().trim();
        }
        List<String> ms = presetModels(c.preset);
        int idx = modelIndex(c.preset, id);
        if (idx < ms.size()) {
            return ms.get(idx);
        }
        PhoneTextInput in = modelInputs.get(id);
        return in == null ? "" : in.value().trim();
    }

    /** 算出全部卡片的几何与内容总高度。<b>渲染 / 命中 / contentHeight 共用本方法。</b> */
    private List<Card> layout() {
        Font font = font();
        int w = width();
        int cardW = w - scaled(CARD_PAD) * 2;
        int innerW = cardW - scaled(INNER_PAD) * 2;
        float textScale = PhoneText.scale(1.0f);
        int baseLineH = PhoneText.height(font, textScale);

        List<Card> cards = new ArrayList<>();
        int y = scaled(10);

        // ---- 页顶摘要卡 ----
        int sumLines = 1 + (VisionSiteCache.canEdit() ? 0 : 1) + (VisionSiteCache.isEmpty() ? 1 : 0);
        summaryTop = y;
        summaryHeight = scaled(10) * 2 + sumLines * (baseLineH + scaled(2));
        y += summaryHeight + scaled(SUMMARY_TO_LIST);

        // ---- 「你的样貌」卡（摘要卡之下、平台列表之上）----
        // 位置选在摘要卡下方而不是页面最顶：摘要卡是这页的"标题"（当前走哪家、用哪个模型），
        // 把它压到外貌卡下面会削弱页面的识别度。
        appearanceTop = y;
        appearanceLabelY = appearanceTop + scaled(10);
        int appearanceBoxY = appearanceLabelY + scaled(LABEL_H) + scaled(2);
        appearanceHeight = (appearanceBoxY - appearanceTop)
                + appearanceInput.preferredHeight(font) + scaled(10);
        y += appearanceHeight + scaled(SUMMARY_TO_LIST);
        // 控件用视图坐标（scrollY(logical)），与 placeControls 同一条规矩
        int appearInnerX = scaled(CARD_PAD) + scaled(INNER_PAD);
        appearanceInput.bounds(appearInnerX, scrollY(appearanceBoxY), innerW,
                appearanceInput.preferredHeight(font));

        // ---- 平台卡 ----
        for (S2CVisionSitesPacket.SiteEntry e : VisionSiteCache.sites()) {
            VisionProvider p = VisionProviders.byId(e.siteId());
            Card c = new Card();
            c.entry = e;
            c.preset = p;
            c.displayName = p != null ? p.displayName() : e.siteId();
            c.customIdx = presetModels(p).size();
            c.expanded = expanded.contains(e.siteId());
            c.top = y;
            c.headerTop = y;
            c.headerH = scaled(HEADER_H);
            c.bodyTop = y + c.headerH;

            // 控件一律先建出来：未展开的会被 hideControls 收成零尺寸，
            // 但下拉的"展开高度"是布局的输入，必须在这一帧就能问到。
            PhoneDropdown dd = dropdown(e.siteId(), p);
            ensureKeyInput(e.siteId());
            if (urlEditable(p)) {
                ensureUrlInput(e.siteId());
            }

            int end = c.bodyTop;
            if (c.expanded) {
                int cy = c.bodyTop + scaled(4);

                if (p != null && !p.tagline().isBlank()) {
                    c.taglineY = cy;
                    cy += baseLineH + scaled(2);
                }
                if (p != null && p.hasNote()) {
                    c.noteY = cy;
                    cy += baseLineH + scaled(2);
                }
                cy += scaled(GAP);

                c.modelLabelY = cy;
                cy += scaled(LABEL_H);
                c.modelRowY = cy;
                cy += scaled(BOX_H);
                // 展开的下拉列表画在行下方；把它的高度预留出来，免得盖住密钥那一行
                cy += dd.expandedHeight();

                if (modelIndex(p, e.siteId()) == c.customIdx) {
                    c.customRowY = cy;
                    cy += scaled(BOX_H);
                    ensureModelInput(e.siteId());
                }
                cy += scaled(GAP);

                c.keyLabelY = cy;
                cy += scaled(LABEL_H);
                c.keyRowY = cy;
                cy += scaled(BOX_H);

                if (urlEditable(p)) {
                    cy += scaled(GAP);
                    c.urlLabelY = cy;
                    cy += scaled(LABEL_H);
                    c.urlRowY = cy;
                    cy += scaled(BOX_H);
                }
                cy += scaled(GAP);

                c.useBtnY = cy;
                cy += scaled(BTN_H) + scaled(10);
                end = cy;
            }

            c.height = end - c.top;
            y = end + scaled(CARD_GAP);
            cards.add(c);

            if (c.expanded) {
                placeControls(c, scaled(CARD_PAD) + scaled(INNER_PAD), innerW);
            } else {
                hideControls(e.siteId());
            }
        }

        laidOutHeight = y + scaled(8);
        return cards;
    }

    /**
     * 摆放展开态的控件。
     *
     * <h2>⚠ 坐标系：这里必须用<b>视图坐标</b>（{@code scrollY(logical)}），不是逻辑坐标</h2>
     * {@link com.huhutalk.api.phone.ui.PhoneWidget#render} 只在 {@code bounds} 给的 y 上
     * <b>加内容区原点</b>，它<b>不知道滚动偏移</b>。页面自己画的东西走
     * {@code originY + scrollY(logical)}，所以控件也必须按同一套坐标摆 ——
     * 否则控件会被钉在屏幕上不动，而它所在的卡片正常滚动（实测踩过）。
     * <p>
     * 与之配套：命中判定必须传<b>未滚动的本地坐标</b>（{@code localY}），
     * 因为 {@code scrollY(logicalY(localY)) == localY}。两边只要都按这一条来就是一致的。
     */
    private void placeControls(Card c, int x, int w) {
        String id = c.entry.siteId();
        PhoneDropdown dd = dropdowns.get(id);
        if (dd != null) {
            dd.bounds(x, scrollY(c.modelRowY), w, scaled(BOX_H));
            dd.selected(modelIndex(c.preset, id));
        }
        PhoneTextInput mi = modelInputs.get(id);
        if (mi != null && c.customRowY >= 0) {
            mi.bounds(x, scrollY(c.customRowY), w, scaled(BOX_H));
        }
        PhoneTextInput ki = keyInputs.get(id);
        if (ki != null) {
            ki.bounds(x, scrollY(c.keyRowY), w, scaled(BOX_H));
        }
        PhoneTextInput ui = urlInputs.get(id);
        if (ui != null) {
            boolean shown = c.urlRowY >= 0;
            ui.bounds(x, scrollY(shown ? c.urlRowY : 0), w, shown ? scaled(BOX_H) : 0);
        }
    }

    /** 收起态：把控件收成零尺寸，既不渲染也不接受命中。 */
    private void hideControls(String siteId) {
        PhoneDropdown dd = dropdowns.get(siteId);
        if (dd != null) {
            dd.setExpanded(false);
            dd.bounds(0, 0, 0, 0);
        }
        zero(modelInputs.get(siteId));
        zero(keyInputs.get(siteId));
        zero(urlInputs.get(siteId));
    }

    private static void zero(PhoneTextInput in) {
        if (in != null) {
            in.bounds(0, 0, 0, 0);
        }
    }

    @Override
    protected int contentHeight() {
        layout();
        return laidOutHeight;
    }

    // ==================== 控件工厂（懒建，以保住展开态等内部状态） ====================

    /** 建（或取）某平台的下拉。<b>返回值是需要的</b>：布局要问它的展开高度。 */
    private PhoneDropdown dropdown(String siteId, VisionProvider p) {
        return dropdowns.computeIfAbsent(siteId, id -> {
            PhoneDropdown dd = new PhoneDropdown().options(dropdownOptions(p));
            dd.onChange(idx -> onModelPicked(id, p, idx));
            return dd;
        });
    }

    /**
     * 建（或取）某平台的「自定义模型」输入框。
     * <p>
     * <b>必须预填</b>：控件是每次打开页面新建的，而"记住的模型"是持久化的。
     * 不预填的话，重开页面会看到"摘要有模型名、输入框却空着"，
     * 此时实际发出去的模型名与摘要显示的不一致。
     */
    private void ensureModelInput(String siteId) {
        modelInputs.computeIfAbsent(siteId, id -> new PhoneTextInput()
                .value(HuhuTalkClientSettings.isVisionCustomModel(id)
                        ? HuhuTalkClientSettings.getVisionModel(id) : "")
                .placeholder("输入模型名，回车保存")
                .onCommit(v -> {
                    HuhuTalkClientSettings.setVisionCustomModel(id, true);
                    HuhuTalkClientSettings.setVisionModel(id, v);
                    pushIfActive(id);
                }));
    }

    private void ensureKeyInput(String siteId) {
        keyInputs.computeIfAbsent(siteId, id -> new PhoneTextInput()
                .placeholder("粘贴 API Key，回车保存")
                .masked(true)
                .onCommit(v -> onKeyCommitted(id, v)));
    }

    private void ensureUrlInput(String siteId) {
        urlInputs.computeIfAbsent(siteId, id -> new PhoneTextInput()
                .placeholder("https://…/v1/chat/completions")
                .onCommit(v -> {
                    if (v.isBlank()) {
                        showToast("接口地址不能为空");
                        return;
                    }
                    if (!VisionSiteCache.canEdit()) {
                        showToast("当前环境无权修改服务器 AI 配置");
                        return;
                    }
                    PacketDistributor.sendToServer(new C2SVisionSiteEditPacket(id, null, v));
                }));
    }

    // ==================== 渲染 ====================

    @Override
    protected void renderContent(GuiGraphics g, int originX, int originY, int w, int h, float partialTick) {
        Font font = font();
        ColorScheme col = colors();
        float ts = PhoneText.scale(1.0f);
        List<Card> cards = layout();

        renderSummary(g, font, col, originX, originY, w);
        renderAppearance(g, font, col, originX, originY, w);

        int cardX = originX + scaled(CARD_PAD);
        int cardW = w - scaled(CARD_PAD) * 2;

        for (Card c : cards) {
            int top = originY + scrollY(c.top);
            boolean active = c.entry.siteId().equals(HuhuTalkClientSettings.getVisionSiteId());
            PhoneShapes.fillRoundedRect(g, cardX, top, cardW, c.height, scaled(CARD_R),
                    active ? col.accentSoft() : col.cardBg());

            // ---- 头部 ----
            int hY = originY + scrollY(c.headerTop);
            PhoneText.drawLeftIn(g, font, c.displayName, cardX, hY, cardW, c.headerH,
                    scaled(INNER_PAD), active ? col.accent() : col.textPrimary(), ts);
            String badge = active ? "使用中" : (c.entry.hasKey() ? "已配置密钥" : "未配置密钥");
            int bw = PhoneText.width(font, badge, ts);
            PhoneText.draw(g, font, badge,
                    cardX + cardW - scaled(INNER_PAD) - bw - scaled(16),
                    hY + (c.headerH - PhoneText.height(font, ts)) / 2f,
                    active ? col.accent() : col.textMuted(), ts);
            PhoneText.draw(g, font, c.expanded ? "▲" : "▼",
                    cardX + cardW - scaled(INNER_PAD) - scaled(10),
                    hY + (c.headerH - PhoneText.height(font, ts)) / 2f,
                    col.textMuted(), PhoneText.scale(0.7f));

            if (!c.expanded) {
                continue;
            }

            int innerX = cardX + scaled(INNER_PAD);
            int innerW = cardW - scaled(INNER_PAD) * 2;

            if (c.taglineY >= 0) {
                PhoneText.drawLeftIn(g, font, c.preset.tagline(), cardX,
                        originY + scrollY(c.taglineY), cardW,
                        PhoneText.height(font, ts) + scaled(2), scaled(INNER_PAD), col.textSecondary(), ts);
            }
            if (c.noteY >= 0) {
                PhoneText.drawLeftIn(g, font, "⚠ " + c.preset.note(), cardX,
                        originY + scrollY(c.noteY), cardW,
                        PhoneText.height(font, ts) + scaled(2), scaled(INNER_PAD), col.textMuted(), ts);
            }

            PhoneText.draw(g, font, "可用模型", innerX, originY + scrollY(c.modelLabelY),
                    col.textSecondary(), PhoneText.scale(0.8f));

            String keyState = c.entry.hasKey() ? "已配置（留空不改动）" : "未配置";
            PhoneText.draw(g, font, "API Key", innerX, originY + scrollY(c.keyLabelY),
                    col.textSecondary(), PhoneText.scale(0.8f));
            PhoneText.draw(g, font, keyState,
                    innerX + PhoneText.width(font, "API Key", PhoneText.scale(0.8f)) + scaled(6),
                    originY + scrollY(c.keyLabelY), col.textMuted(), PhoneText.scale(0.8f));

            if (c.urlLabelY >= 0) {
                PhoneText.draw(g, font, "接口地址", innerX, originY + scrollY(c.urlLabelY),
                        col.textSecondary(), PhoneText.scale(0.8f));
            }

            renderUseButton(g, font, col, c, innerX, originY, innerW);
        }

        // 下拉列表会画到自己的矩形之外，必须最后画，否则会被后面的卡盖住
        for (Card c : cards) {
            if (!c.expanded) {
                continue;
            }
            String id = c.entry.siteId();
            PhoneDropdown dd = dropdowns.get(id);
            if (dd != null) {
                dd.render(g, originX, originY);
            }
            PhoneTextInput mi = modelInputs.get(id);
            if (mi != null && c.customRowY >= 0) {
                mi.render(g, originX, originY);
            }
            PhoneTextInput ki = keyInputs.get(id);
            if (ki != null) {
                ki.render(g, originX, originY);
            }
            PhoneTextInput ui = urlInputs.get(id);
            if (ui != null && c.urlRowY >= 0) {
                ui.render(g, originX, originY);
            }
        }

        renderToast(g, font, col, originX, originY, w);
    }

    /** 页顶摘要：当前走哪家、用哪个模型；无权限 / 未加载时各多一行。 */
    private void renderSummary(GuiGraphics g, Font font, ColorScheme col,
                               int originX, int originY, int w) {
        int cardX = originX + scaled(CARD_PAD);
        int cardW = w - scaled(CARD_PAD) * 2;
        int top = originY + scrollY(summaryTop);
        PhoneShapes.fillRoundedRect(g, cardX, top, cardW, summaryHeight, scaled(CARD_R), col.cardBg());

        float ts = PhoneText.scale(1.0f);
        int lineH = PhoneText.height(font, ts) + scaled(2);
        int y = top + scaled(10);

        String siteId = HuhuTalkClientSettings.getVisionSiteId();
        String name = VisionProviders.displayNameOf(siteId);
        String model = HuhuTalkClientSettings.getEffectiveVisionModel();
        PhoneText.drawLeftIn(g, font, "当前： " + name + (model.isBlank() ? "" : " · " + model),
                cardX, y, cardW, lineH, scaled(INNER_PAD), col.textPrimary(), ts);
        y += lineH;

        if (VisionSiteCache.isEmpty()) {
            PhoneText.drawLeftIn(g, font, "正在读取服务器上的平台列表…", cardX, y, cardW, lineH,
                    scaled(INNER_PAD), col.textMuted(), ts);
            y += lineH;
        }
        if (!VisionSiteCache.canEdit()) {
            PhoneText.drawLeftIn(g, font, "⚠ 当前环境无权修改服务器 AI 配置，只能查看与切换",
                    cardX, y, cardW, lineH, scaled(INNER_PAD), col.dangerBg(), ts);
        }
    }

    /**
     * 「你的样貌」卡：标题 + 一行说明 + 多行输入框。
     *
     * <h2>⚠ 坐标系</h2>
     * 页面自绘的 {@code fillRoundedRect} / {@link PhoneText#draw} 用<b>屏幕坐标</b>
     * （所以这里每处都带 {@code originY + scrollY(...)}）；而控件
     * {@link com.huhutalk.api.phone.ui.PhoneWidget#render} 收的 {@code bounds} 是<b>内容坐标</b>，
     * 它自己会加原点 —— 见 {@link #placeControls} 的说明。
     */
    private void renderAppearance(GuiGraphics g, Font font, ColorScheme col,
                                  int originX, int originY, int w) {
        int cardX = originX + scaled(CARD_PAD);
        int cardW = w - scaled(CARD_PAD) * 2;
        int top = originY + scrollY(appearanceTop);
        PhoneShapes.fillRoundedRect(g, cardX, top, cardW, appearanceHeight, scaled(CARD_R), col.cardBg());

        PhoneText.drawLeftIn(g, font, "你的样貌", cardX, originY + scrollY(appearanceLabelY), cardW,
                scaled(LABEL_H), scaled(INNER_PAD), col.textPrimary(), PhoneText.scale(0.9f));
        String hint = "让女仆在照片里认出你";
        PhoneText.draw(g, font, hint,
                cardX + cardW - scaled(INNER_PAD) - PhoneText.width(font, hint, PhoneText.scale(0.75f)),
                originY + scrollY(appearanceLabelY)
                        + (scaled(LABEL_H) - PhoneText.height(font, PhoneText.scale(0.75f))) / 2f,
                col.textMuted(), PhoneText.scale(0.75f));

        appearanceInput.render(g, originX, originY);
    }

    /** 「使用此平台」按钮。选中态是主题强调色实心胶囊 + 白字。 */
    private void renderUseButton(GuiGraphics g, Font font, ColorScheme col, Card c,
                                 int innerX, int originY, int innerW) {
        boolean active = c.entry.siteId().equals(HuhuTalkClientSettings.getVisionSiteId());
        boolean usable = !c.entry.url().isBlank();
        int y = originY + scrollY(c.useBtnY);
        int h = scaled(BTN_H);
        int bg = active ? col.accent() : (usable ? col.accentSoft() : col.neutralBg());
        int fg = active ? 0xFFFFFFFF : (usable ? col.textPrimary() : col.textMuted());
        PhoneShapes.fillRoundedRect(g, innerX, y, innerW, h, h / 2, bg);
        String label = active ? "✓ 识图正在使用此平台" : (usable ? "使用此平台" : "接口地址为空，无法使用");
        PhoneText.drawCenteredIn(g, font, label, innerX, y, innerW, h, fg, PhoneText.scale(0.85f));
    }

    private void renderToast(GuiGraphics g, Font font, ColorScheme col,
                             int originX, int originY, int w) {
        if (toast.isEmpty() || System.currentTimeMillis() - toastAt > TOAST_MS) {
            return;
        }
        int cardX = originX + scaled(CARD_PAD);
        int cardW = w - scaled(CARD_PAD) * 2;
        int h = scaled(22);
        int y = originY + contentViewHeight() - h - scaled(6);
        PhoneShapes.fillRoundedRect(g, cardX, y, cardW, h, h / 2, col.dangerBg());
        PhoneText.drawCenteredIn(g, font, toast, cardX, y, cardW, h, 0xFFFFFFFF, PhoneText.scale(0.8f));
    }

    private void showToast(String text) {
        toast = text;
        toastAt = System.currentTimeMillis();
    }

    // ==================== 交互 ====================

    @Override
    protected boolean onContentClick(double localX, double localY, int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        // 卡片与按钮用逻辑坐标（与 scrollY 对称），控件用视图坐标（见 placeControls 的说明）
        int logical = logicalY(localY);
        List<Card> cards = layout();

        // 第一趟：展开的下拉列表画在后面的卡之上，必须先拿到点击，
        // 否则点列表项会被误判成点到了下一张卡的头部。
        for (Card c : cards) {
            if (!c.expanded) {
                continue;
            }
            PhoneDropdown dd = dropdowns.get(c.entry.siteId());
            if (dd == null) {
                continue;
            }
            if (action == GLFW.GLFW_PRESS && dd.mousePressed(localX, localY, button)) {
                return true;
            }
        }

        // 第二趟：文本框（点它 = 开透明键盘捕获屏）。
        // 「你的样貌」在前：它所在的外貌卡画在平台卡之上，命中顺序必须与绘制顺序一致。
        if (dispatchText(appearanceInput, localX, localY, button, action)) {
            return true;
        }
        for (Card c : cards) {
            if (!c.expanded) {
                continue;
            }
            if (dispatchText(modelInputs.get(c.entry.siteId()), localX, localY, button, action)
                    || dispatchText(keyInputs.get(c.entry.siteId()), localX, localY, button, action)
                    || dispatchText(urlInputs.get(c.entry.siteId()), localX, localY, button, action)) {
                return true;
            }
        }

        if (action != GLFW.GLFW_PRESS) {
            return false;
        }

        // 第三趟：卡片头部（展开/收起）与「使用此平台」
        int cardX = scaled(CARD_PAD);
        int cardW = width() - scaled(CARD_PAD) * 2;
        for (Card c : cards) {
            if (hit(cardX, c.headerTop, cardW, c.headerH, localX, logical)) {
                toggleExpanded(c.entry.siteId());
                return true;
            }
            if (c.expanded) {
                int innerX = cardX + scaled(INNER_PAD);
                int innerW = cardW - scaled(INNER_PAD) * 2;
                if (hit(innerX, c.useBtnY, innerW, scaled(BTN_H), localX, logical)) {
                    usePlatform(c);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 把一次点击转给某个文本控件（尺寸为 0 的跳过）。
     * <p>
     * 只处理 PRESS：这些控件都不需要拖动，{@code PhoneWidget#mouseReleased} 是空实现。
     * <p>
     * 参数类型是 {@link PhoneWidget} 而不是 {@link PhoneTextInput}：
     * 本页现在同时有单行（模型名 / API Key / 接口地址）与多行（你的样貌）两种控件。
     */
    private static boolean dispatchText(PhoneWidget in, double x, double y,
                                        int button, int action) {
        if (in == null || in.width() <= 0) {
            return false;
        }
        return action == GLFW.GLFW_PRESS && in.mousePressed(x, y, button);
    }

    @Override
    protected void onContentMoved(double localX, double localY) {
        // 控件用视图坐标，所以这里也传未滚动的本地坐标（见 placeControls 的说明）
        for (Card c : layout()) {
            if (!c.expanded) {
                continue;
            }
            PhoneDropdown dd = dropdowns.get(c.entry.siteId());
            if (dd != null) {
                dd.mouseDragged(localX, localY);
            }
        }
    }

    /*
     * ⚠ 这里刻意【不】重写 mouseScrolled。
     *
     * 曾经的写法是"展开的下拉先吃掉滚轮，剩下的给页面滚"，结果实测发现：
     * PhoneDropdown.mouseScrolled 只要处于展开态就 return true（它的设计意图是
     * "滚轮换选中项"），于是**只要有任何一张卡的下拉是开着的，整页就再也滚不动了** ——
     * 手机上滚页面靠的是拖拽（走同一条 mouseScrolled 通路），一展开下拉就卡死。
     *
     * 为什么不改成"光标在列表范围内才转给它"：手机没有滚轮，拖拽起点落在哪儿并不能
     * 说明玩家想滚谁；而下拉只显示 VISIBLE_ROWS 行、点一下就选中，
     * 根本不需要滚轮。所以正确的做法是把它交给 PhonePage 的默认实现（滚页面）。
     *
     * 代价：PhoneDropdown 的"滚轮换选项"在本页不可用（手机上本来也没有），
     * 并且它只渲染前 VISIBLE_ROWS 行 —— 所以各平台的预设模型数被限制在
     * VISIBLE_ROWS - 1（给「自定义…」留一位），见 dropdownOptions 的说明。
     */

    // ==================== 业务动作 ====================

    private void toggleExpanded(String siteId) {
        if (!expanded.remove(siteId)) {
            expanded.add(siteId);
        }
    }

    /** 下拉选了某个模型。 */
    private void onModelPicked(String siteId, VisionProvider p, int idx) {
        List<String> ms = presetModels(p);
        if (idx >= 0 && idx < ms.size()) {
            HuhuTalkClientSettings.setVisionCustomModel(siteId, false);
            HuhuTalkClientSettings.setVisionModel(siteId, ms.get(idx));
        } else {
            // 切到「自定义…」：进入自定义模式，并把"记住的模型"与输入框一起清空。
            // 两边都清是为了让「输入框内容」始终等于「自定义模式下真正会用的模型名」——
            // 否则会出现"摘要显示上一个预设、实际却发不出去"的错位。
            HuhuTalkClientSettings.setVisionCustomModel(siteId, true);
            HuhuTalkClientSettings.setVisionModel(siteId, "");
            PhoneTextInput in = modelInputs.get(siteId);
            if (in != null) {
                in.value("");
            }
        }
        pushIfActive(siteId);
    }

    /** API Key 输入完成（回车 / 点别处）。 */
    private void onKeyCommitted(String siteId, String value) {
        if (value == null || value.isBlank()) {
            // 留空 = 不改动。刻意如此：否则一次误按回车就会把已配好的密钥清掉。
            // 真要清除，用主模组自己的 AI 设置界面（那里能编辑站点）。
            showToast("密钥留空，未做改动");
            return;
        }
        if (!VisionSiteCache.canEdit()) {
            showToast("当前环境无权修改服务器 AI 配置");
            return;
        }
        PacketDistributor.sendToServer(new C2SVisionSiteEditPacket(siteId, value, null));
        showToast("密钥已提交");
    }

    /** 「使用此平台」。 */
    private void usePlatform(Card c) {
        if (c.entry.url().isBlank()) {
            showToast("请先填写接口地址");
            return;
        }
        if (!c.entry.hasKey()) {
            showToast("该平台还没配 API Key，识图会失败");
        }
        HuhuTalkClientSettings.setVisionSiteId(c.entry.siteId());
        // 玩家没为该平台选过模型时，把当前生效的那个写下来，免得服务端再去猜
        if (HuhuTalkClientSettings.getVisionModel(c.entry.siteId()).isBlank()) {
            String m = effectiveModel(c);
            if (m.isBlank()) {
                showToast("自定义模型名为空，这次会用平台默认模型");
            } else {
                HuhuTalkClientSettings.setVisionModel(c.entry.siteId(), m);
            }
        }
        ClientModEvents.pushVisionSelection();
    }

    /** 若改的是当前选中的平台，立刻把选择重新同步给服务端。 */
    private void pushIfActive(String siteId) {
        if (siteId != null && siteId.equals(HuhuTalkClientSettings.getVisionSiteId())) {
            ClientModEvents.pushVisionSelection();
        }
    }
}
