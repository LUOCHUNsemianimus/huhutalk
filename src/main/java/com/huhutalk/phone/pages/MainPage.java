package com.huhutalk.phone.pages;

import com.huhutalk.HuhuTalk;
import com.huhutalk.api.phone.PhoneApp;
import com.huhutalk.api.phone.PhoneAppRegistry;
import com.huhutalk.client.NotificationManager;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.api.phone.ui.PhoneShapes;
import com.huhutalk.api.phone.ui.PhoneText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *   <li><b>位置由槽位决定，不由列表顺序决定</b>：每个 App 自带 {@link AppInfo#slot}，
 *       允许留空、允许跳号；内置与附属模组共用同一套槽位表（见 {@link #SLOT_COUNT}）</li>
 *   <li>每个图标中心显示文字或 emoji，图标下方标注其灰字名称（0.8 倍、带阴影描边）</li>
 *   <li>HuhuTalk 图标：右上角未读红点，点击进入 App 内容区（由 {@code AppBehavior.TALK} 驱动）</li>
 *   <li>「手册」图标打开手机内置手册（{@link ManualPage}）</li>
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
    /**
     * 每行 app 图标个数。
     * <p>包内可见（不是 private）：「桌面图标管理」页的网格列数必须与桌面完全一致，
     * 它直接引用这个常量而不是各写一个 3 —— 两处一旦漂移，管理页的格子就和槽位对不上了。
     */
    static final int APP_COLS = 3;
    /**
     * 桌面槽位总数 = 三种屏幕比例的<b>最小</b>容量（{@code 4:3} → 3 行 × {@link #APP_COLS} 列）。
     * <p>
     * 布局被刻意锁在这个范围内：网格容量随长宽比变化（20:9→15 格、9:16→12 格、4:3→9 格，
     * 见 {@link #gridRows()}），但槽位布局是<b>一份与长宽比无关的数据</b>。若允许排到第 10 格，
     * 玩家把比例从 20:9 切到 4:3 就会「丢图标」；锁在 9 格内则三种比例都装得下。
     */
    public static final int SLOT_COUNT = PhoneApp.MAX_SLOTS;
    /**
     * HuhuTalk 自己的槽位：恒定占用，且<b>不接受外部注册抢占</b>。
     * 后续「游戏内热切换」也只允许调整其他 App 的位置（作者原话：HuhuTalk 不动）。
     */
    public static final int SLOT_TALK = 0;
    /**
     * 主应用在持久化布局里的稳定 key。
     * <p>
     * 它同时是「不可移动 / 不可移除」的判据 —— {@link #moveDesktopApp} / {@link #removeDesktopApp}
     * 见到它就拒绝。用 key 而不是槽位号判，是因为 key 是布局数据里的东西、槽位号不是：
     * 万一玩家的配置文件里被人手改出"把 HuhuTalk 挪走"的记录，照样会被这里挡掉。
     */
    public static final String KEY_TALK = "huhutalk";
    /**
     * 「设置」App 在持久化布局里的稳定 key。
     * <p>
     * 设置是桌面设置的唯一入口（「桌面图标管理」自己就长在设置里），所以它
     * <b>允许拖动换位，但不允许移除</b>：删掉就再也找不回来了。
     * 「不可移除」由 {@link #isDesktopRemovable} 单独判定，<b>刻意不并入</b>
     * {@link #isDesktopFixed} —— 后者会连拖动一起禁掉，设置就动不了了。
     */
    public static final String KEY_SETTINGS = "settings";
    /**
     * 「手册」App 的稳定 key（内部使用）。
     * <p>
     * 与 {@link #KEY_TALK} / {@link #KEY_SETTINGS} 同性质：它是布局/行为判据，不是显示文案。
     * 名称只在这里的 {@link #builtinNameKey(String)} 里映射到语言键。
     */
    private static final String KEY_MANUAL = "manual";
    /** 图标下方名称的灰字颜色 */
    private static final int LABEL_COLOR = 0xFF7A7A7A;
    /** 桌面中央表情边长 = 手机内容宽度 × 该比例 */
    private static final float STICKER_WIDTH_RATIO = 2.0f / 3.0f;
    /** 女仆表情资源命名空间与目录（与聊天页一致） */
    private static final String EMOJI_NAMESPACE = "touhou_little_maid";
    private static final String EMOJI_DIR = "textures/chat_bubble/maid_emoji";
    /** HuhuTalk APP 图标贴图（三档可选：高清 symbol2 / 低清 symbol / 狐狸 emoji） */
    private static final ResourceLocation ICON_SYMBOL2 =
            new net.minecraft.resources.ResourceLocation("huhutalk", "textures/huhutalk_symbol.png");
    private static final int ICON_SYMBOL2_SIZE = 256;
    private static final ResourceLocation ICON_SYMBOL =
            new net.minecraft.resources.ResourceLocation("huhutalk", "textures/huhutalk_symbol_legacy.png");
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

    /**
     * 内置 App 的元数据（类加载时由 {@link #buildApps()} 构建一次）。
     * <p>
     * <b>列表顺序不再决定位置</b>：每个 App 自带 {@link AppInfo#slot}，位置由槽位算。
     * 这个列表只用来「圈出内置 App 占了哪些槽位」——内置优先，外部注册再去填空位。
     */
    private static final List<AppInfo> apps = buildApps();

    /**
     * 桌面槽位表：{@code slots()[i]} = 第 i 格的 App，{@code null} = 空槽。这是「位置」的唯一真相来源。
     * <p>
     * 为什么从「按列表下标排布」改成「按槽位排布」：下标是隐式的——删一个 App、插一个 App，
     * 后面所有图标一起位移；而附属模组的注册又只能追加，于是我们自己的新 App（相机等）
     * 永远拿不到想要的格子。槽位是显式数据，才能做到「允许留空、允许跳号、位置自由」。
     * <p>
     * 为什么不直接把外部 App 塞进 {@link #apps}：{@code apps} 是 {@code static final}、
     * 在类加载时定型，而附属模组的注册时机不由我们控制。改成「按注册版本号懒合并」之后，
     * 附属模组<b>任何时候</b>注册都能在下一帧生效，也不会出现「注册成功但图标不出现」这种哑失败。
     */
    private static AppInfo[] mergedSlots = null;
    /** {@link #mergedSlots} 对应的注册版本号；与 {@link PhoneAppRegistry#generation()} 不一致时重建 */
    private static int mergedGeneration = -1;
    /** {@link #mergedSlots} 对应的<b>布局</b>版本号；与 {@code HuhuTalkClientSettings#getDesktopLayoutGeneration()} 不一致时重建 */
    private static int mergedLayoutGeneration = -1;
    /**
     * 全部候选 App（内置 + 外部，<b>含被玩家移除的</b>），按 key 索引。
     * <p>与 {@link #mergedSlots} 同一批重建。管理页要靠它把"已移除"的图标画出来。
     */
    private static Map<String, AppInfo> mergedByKey = null;

    /**
     * 桌面当前应该显示的槽位表 = 内置 {@link #apps} + {@link PhoneAppRegistry} 里的外部 App。
     * <p>
     * 每帧调用是便宜的：只有注册版本号变化时才真正重建。
     * 首次调用会广播 {@link com.huhutalk.api.phone.PhoneAppRegisterEvent}（见 {@link PhoneAppRegistry#apps()}），
     * 于是附属模组不需要关心"什么时候注册才来得及"。
     */
    private static AppInfo[] slots() {
        // 顺序要紧：先取列表（这一步可能广播注册事件），再读版本号。
        // 反过来会在"广播期间刚好有人注册"时把版本号读成旧值，导致缓存永不更新。
        List<PhoneApp> external = PhoneAppRegistry.apps();
        int generation = PhoneAppRegistry.generation();
        int layoutGeneration = HuhuTalkClientSettings.getDesktopLayoutGeneration();
        if (mergedSlots == null || mergedGeneration != generation
                || mergedLayoutGeneration != layoutGeneration) {
            rebuild(external);
            mergedGeneration = generation;
            mergedLayoutGeneration = layoutGeneration;
        }
        return mergedSlots;
    }

    /** 全部候选 App（含被移除的），按 key 索引。管理页用。 */
    private static Map<String, AppInfo> allByKey() {
        slots(); // 触发一次重建/缓存命中
        return mergedByKey;
    }

    // ==================== 显示名称（语言边界） ====================
    //
    // 稳定 key（huhutalk / manual / settings）是布局与行为的唯一判据，名字只是给玩家看的文案。
    // 这里把「内置 key → 语言键」的映射收成一处：桌面标签、DesktopSlot 快照都在显示边界调用，
    // 于是 AppInfo.name 保持原样（外部注册名/内置兜底名），翻译结果不落进任何静态字段。

    /**
     * 内置 App 的显示名称键；外部注册的 App 返回 {@code null}（名称原样显示）。
     * <p>只认 {@link AppInfo#key} 这个稳定标识，不认名字 —— 名字会随语言变。
     */
    private static String builtinNameKey(String key) {
        if (KEY_TALK.equals(key)) {
            return "gui.huhutalk.app.huhutalk";
        }
        if (KEY_SETTINGS.equals(key)) {
            return "gui.huhutalk.app.settings";
        }
        if (KEY_MANUAL.equals(key)) {
            return "gui.huhutalk.app.manual";
        }
        // 内置性质的「相机 / 图库」走公开注册通路（PhoneApp id = 稳定存档 key），
        // 这里只加它们的显示名映射；其余外部 App 的注册名一律原样。
        if ("huhutalk:camera".equals(key)) {
            return "gui.huhutalk.app.camera";
        }
        if ("huhutalk:gallery".equals(key)) {
            return "gui.huhutalk.app.gallery";
        }
        return null;
    }

    /**
     * App 在界面上的名称：内置 App 按 {@code key} 解析为当前语言的文案，外部 App 原样返回注册名。
     * <p>每次调用都重新查语言表，因此切换语言后下一帧即更新，不会被静态缓存冻住。
     */
    static String displayName(String key, String registeredName) {
        String nameKey = builtinNameKey(key);
        return nameKey == null ? registeredName : PhoneText.tr(nameKey);
    }

    /**
     * 重建候选表与槽位表。两个缓存键（注册表版本、布局版本）任一变化都会走到这里。
     */
    private static void rebuild(List<PhoneApp> external) {
        // ① 候选表：内置先、外部后（顺序与改造前一致，稳定排序才有意义）
        Map<String, AppInfo> byKey = new LinkedHashMap<>();
        for (AppInfo a : apps) {
            byKey.put(a.key, a);
        }
        for (PhoneApp app : external) {
            AppInfo a = toAppInfo(app, app.slot());
            // 同 id 在注册表里已被去重，这里 putIfAbsent 只是防御
            byKey.putIfAbsent(a.key, a);
        }
        mergedByKey = byKey;
        // ② 槽位表：把玩家长久化的布局叠上去
        mergedSlots = placeByLayout(byKey);
    }

    /**
     * 把候选 App 摆进 {@link #SLOT_COUNT} 个槽位。
     *
     * <h2>优先级（从高到低）</h2>
     * <ol>
     *   <li><b>HuhuTalk 恒定槽位 0</b> —— 玩家的布局数据也改不了它（配置文件被手改也挡得住）；</li>
     *   <li><b>「设置」</b> —— 唯一入口，优先于任何可移除 App；它仍可拖动，只是永不被挤掉/被隐藏；</li>
     *   <li><b>玩家在「桌面图标管理」里指定的位置</b>（{@code HuhuTalkClientSettings#getDesktopSlot}）；</li>
     *   <li><b>App 自己声明的位置</b>：内置 App 的槽位、外部 App 的 {@code PhoneApp#slot()}；</li>
     *   <li>都没声明 → <b>最小空槽</b>（即过去的"追加在后面"）。</li>
     * </ol>
     *
     * <h2>为什么是"稳定排序 + 依次落位"，而不是"直接按下标赋值"</h2>
     * 直接赋值遇到两个 App 抢同一格时，谁赢取决于遍历顺序 —— 那是隐式的、换个 Map 实现就变。
     * 排序后落位则把"谁让路"写成显式规则：<b>优先级高的先占，冲突的一方退让到最小空槽并打 WARN</b>。
     * 「图标不出现」这种哑失败比「位置不对」难排查得多，所以退让而不是丢弃（沿用既有纪律）。
     *
     * <h2>玩家没动过任何图标时</h2>
     * 全部候选都是优先级 3，顺序 = 内置在前、外部按注册顺序 → 与改造前<b>逐格相同</b>。
     */
    private static AppInfo[] placeByLayout(Map<String, AppInfo> byKey) {
        AppInfo[] out = new AppInfo[SLOT_COUNT];
        List<AppInfo> queue = new ArrayList<>();
        for (AppInfo a : byKey.values()) {
            if (a.hidden) {
                continue; // 内置的"逻辑保留但暂不显示"，与玩家移除无关
            }
            if (isDesktopRemovable(a.key) && HuhuTalkClientSettings.isDesktopHidden(a.key)) {
                continue; // 玩家移除的；不可移除的 App 即使旧配置标了 hidden 也照常显示
            }
            queue.add(a);
        }
        // 稳定排序：权重相同的保持上面的顺序（内置先、外部后）
        queue.sort(Comparator.comparingInt(MainPage::wishPriority));
        for (AppInfo a : queue) {
            int want = wishSlot(a);
            int at = -1;
            if (want >= 0) {
                if (want >= SLOT_COUNT) {
                    // 只可能来自被手改的配置文件：声明槽位在 build() 时已校验过
                    HuhuTalk.LOGGER.warn("[HuhuTalk/PhoneApp] App「{}」申请的槽位 {} 越界（合法 0~{}），已改放空槽",
                            a.name, want, SLOT_COUNT - 1);
                } else if (out[want] == null) {
                    at = want;
                } else {
                    HuhuTalk.LOGGER.warn("[HuhuTalk/PhoneApp] 槽位 {} 已被「{}」占用，App「{}」已改放空槽",
                            want, out[want].name, a.name);
                }
            }
            if (at < 0) {
                at = firstFreeSlot(out);
                if (at < 0) {
                    HuhuTalk.LOGGER.warn("[HuhuTalk/PhoneApp] 桌面 {} 格已满，App「{}」未能显示",
                            SLOT_COUNT, a.name);
                    continue;
                }
            }
            // ★ AppInfo 的 slot 必须等于它在数组里的下标：启动动画的原点、
            //   点击命中、管理页的高亮全都靠"下标即位置"这一条。
            out[at] = a.withSlot(at);
        }
        return out;
    }

    /** 落位优先级：数字小的先占。见 {@link #placeByLayout}。 */
    private static int wishPriority(AppInfo a) {
        if (KEY_TALK.equals(a.key)) {
            return 0;
        }
        if (KEY_SETTINGS.equals(a.key)) {
            // 设置优先于所有可移除 App：桌面被填满时也必须给它留出入口
            // （尤其是旧配置把它标成 hidden、桌面又已满的情况）。
            return 1;
        }
        int user = HuhuTalkClientSettings.getDesktopSlot(a.key);
        return user >= 0 && user < SLOT_COUNT ? 2 : 3;
    }

    /** 该 App 期望落在哪个槽位；-1 = 没有期望（自动落位）。 */
    private static int wishSlot(AppInfo a) {
        if (KEY_TALK.equals(a.key)) {
            return SLOT_TALK; // 恒定，玩家的布局数据也改不了
        }
        int user = HuhuTalkClientSettings.getDesktopSlot(a.key);
        if (user >= 0 && user < SLOT_COUNT) {
            return user;
        }
        return a.slot;
    }

    /** 最小空槽下标；没有空位返回 -1。 */
    private static int firstFreeSlot(AppInfo[] slots) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                return i;
            }
        }
        return -1;
    }

    /** 把外部注册的 {@link PhoneApp} 转成桌面内部使用的 {@link AppInfo}。 */
    private static AppInfo toAppInfo(PhoneApp app, int slot) {
        return new AppInfo(slot, app.id().toString(), AppBehavior.PLAIN, false,
                app.displayName(), app.iconColor(), app.centerText(), false,
                app.centerScale(), app.centerColor(),
                app.pageFactory() != null, false, app.launchAnimation(), app.pageFactory(),
                app.iconTexture(), app.iconTexWidth(), app.iconTexHeight(), app.iconFill());
    }

    /** 桌面中央表情贴图（每次进入桌面时选择一次） */
    private ResourceLocation stickerTex = null;
    private int stickerW = 24, stickerH = 24;
    private boolean stickerChosen = false;

    public MainPage() {
        // apps 由 buildApps() 静态构建一次，构造函数不再往里 add，避免反复 new 造成重复图标
    }

    /**
     * 一次性构建全部内置 App 元数据。
     * <p>
     * <b>书写顺序不重要</b>——位置由每个 App 自己的 {@link AppInfo#slot} 决定；
     * 之前是「列表下标即格位」，删一个插一个后面全位移，这也是本次改成槽位的直接原因。
     * <p>
     * 注意 {@code behavior} 取代了过去散在各处的 {@code "SOS".equals(app.name)} 这类<b>按名字判功能</b>的写法：
     * 名字是给玩家看的文案，改名/挪位不该让红点或入口静默失联。
     */
    private static List<AppInfo> buildApps() {
        List<AppInfo> list = new ArrayList<>();
        // 0 HuhuTalk：狐狸 emoji 居中（2.25 倍），点击进入 App 内容区（消息页 + 底部导航）。底色用 #de6f8d
        // 恒定占 SLOT_TALK，不参与后续的「游戏内热切换」。
        list.add(new AppInfo(SLOT_TALK, KEY_TALK, AppBehavior.TALK, true,
                "HuhuTalk", 0xFFDE6F8D, "🦊", true, 2.25f, 0xFFFFFFFF, true, false, null));
        // 1 刻意留空：预留给「相机」App —— 它作为外部注册走 PhoneAppRegistry（见 Phase 3 的计划），
        //   显式申请槽位 1。留空而不是先占住，是为了让「我们自己的 App 也能走公开注册通路」这条验证成立。
        // 2 手册：手机内置手册（拆分前，「设置」图标指向的就是这个页面）
        list.add(new AppInfo(2, KEY_MANUAL, AppBehavior.MANUAL, false,
                "手册", 0xFFEFEBE9, "📖", true, 2.0f, 0xFF5D4037, true, false, ManualPage::new));
        // 5 设置：设置 App 主页（用公开骨架 PhonePage 画，配色跟主题）。
        //   目前只有「识图模型」一栏（见 SettingsAppPage）；底部导航那个老设置页不受影响。
        //   图标用 256×256 贴图（assets/huhutalk/textures/icon/settings.png）；
        //   中心的 ⚙ 与底色保留 —— 底色是启动动画色块色，文字是贴图缺失时的兜底。
        list.add(new AppInfo(5, KEY_SETTINGS, AppBehavior.PLAIN, false,
                "设置", 0xFF727377, "⚙", true, 2.0f, 0xFFF5F5F5, true, false, false,
                SettingsAppPage::new,
                new net.minecraft.resources.ResourceLocation("huhutalk", "textures/icon/settings.png"),
                256, 256, 1.0f));
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
        stickerTex = new net.minecraft.resources.ResourceLocation(EMOJI_NAMESPACE, EMOJI_DIR + "/" + file);
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
     * 桌面图标的列间距基准：页面左右边距与相邻图标之间的间隙都用它。
     * <p>
     * {@link #appRect}（绘制与命中共用）和图标下方名称的可用宽度必须取同一个值，
     * 否则只改一处就会让名称的居中/越界判断与图标槽位漂移。
     */
    private static int appMargin(int w) {
        return Math.max(4, (int) (w * 0.06));
    }

    /**
     * 计算某个<b>槽位</b>的图标矩形（内容区相对坐标，未含 baseX/baseY）。
     * 渲染（加 baseX/baseY）与点击命中（直接用 relX/relY）共用，保证一致。
     * 纵向步进含图标下方名称留白，避免名称与下一行重叠。
     * out = {x, y, iconW, iconH}。
     *
     * @param slot 槽位下标，见 {@link #SLOT_COUNT}
     */
    private static void appRect(int w, int h, int slot, int[] out) {
        Font font = Minecraft.getInstance().font;
        int margin = appMargin(w);
        int iconW = Math.max(10, (w - margin * (APP_COLS + 1)) / APP_COLS);
        int iconH = iconW; // 圆角正方形
        int labelH = Math.max(3, Math.round(font.lineHeight * PhoneText.scale(0.8f)));
        int rowGap = Math.max(3, (int) (h * 0.015));
        int cellStep = iconH + labelH + rowGap;
        int startY = gridStartY(h);
        int row = slot / APP_COLS;
        int col = slot % APP_COLS;
        out[0] = margin + col * (iconW + margin);
        out[1] = startY + row * cellStep;
        out[2] = iconW;
        out[3] = iconH;
    }

    /** 按槽位取 App；空槽返回 null。 */
    private static AppInfo appAt(int slot) {
        AppInfo[] slots = slots();
        return slot >= 0 && slot < slots.length ? slots[slot] : null;
    }

    /** 找第一个符合该行为的槽位下标；没有返回 -1。 */
    private static int slotOf(AppBehavior behavior) {
        AppInfo[] slots = slots();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null && slots[i].behavior == behavior) {
                return i;
            }
        }
        return -1;
    }

    /** 计算 HuhuTalk 图标的红点位置，渲染与点击共用 */
    private boolean getTalkIconPos(int[] out) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int slot = slotOf(AppBehavior.TALK);
        if (slot >= 0) {
            appRect(w, h, slot, out);
            return true;
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
        AppInfo[] slots = slots();
        for (int slot = 0; slot < slots.length; slot++) {
            AppInfo app = slots[slot];
            if (app == null || app.hidden) continue; // 空槽 / 暂隐藏（如「狐狐三十分」）

            appRect(w, h, slot, rect);
            int x = baseX + rect[0];
            int y = baseY + rect[1];
            int iconW = rect[2];
            int iconH = rect[3];

            // 圆角正方形图标
            int r = Math.max(4, iconW / 4);
            PhoneShapes.fillRoundedRect(graphics, x, y, iconW, iconH, r, app.color | 0xFF000000);

            // 图标中心内容：HuhuTalk 按玩家选择的风格（高清/低清贴图或狐狸 emoji），其它用文字/emoji
            // 判据是 behavior 而不是名字——名字是给玩家看的文案，改名/挪位不该让图标画法失联
            if (app.behavior == AppBehavior.TALK) {
                String style = HuhuTalkClientSettings.getHuhutalkIconStyle();
                if ("fox".equals(style)) {
                    // 狐狸 emoji（与其它 app 一样用大字）
                    String fox = "🦊";
                    float cs = PhoneText.scale(2.25f);
                    int cw = PhoneText.width(font, fox, cs);
                    int ch = PhoneText.height(font, cs);
                    int foxX = x + (iconW - cw) / 2;
                    int foxY = y + (iconH - ch) / 2;
                    PhoneText.draw(graphics, font, fox, foxX, foxY, 0xFFFFFFFF, cs);
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
                float cs = PhoneText.scale(app.centerScale);
                int cw = PhoneText.width(font, app.center, cs);
                int ch = PhoneText.height(font, cs);
                int cx = x + (iconW - cw) / 2;
                int cy = y + (iconH - ch) / 2;
                PhoneText.draw(graphics, font, app.center, cx, cy, app.centerColor, cs);
            }

            // 图标下方名称（灰字，0.8 倍，带阴影描边）
            // 名称在显示边界按稳定 key 解析当前语言；测量与绘制用同一个字符串
            String appLabel = displayName(app.key, app.name);
            float ls = PhoneText.scale(0.8f);
            // 本槽位可用宽度 = 图标宽 + 一个列间距（与 appRect 共用 appMargin，避免公式漂移）。
            // 居中后最多各伸入左右间隙一半，绝不会跨到相邻图标的槽位里。
            int labelMargin = appMargin(w);
            int maxLabelW = iconW + labelMargin;
            appLabel = PhoneText.truncate(font, appLabel, maxLabelW, ls);
            int lw = PhoneText.width(font, appLabel, ls);
            int lx = x + (iconW - lw) / 2;
            int ly = y + iconH + 1;
            PhoneText.drawShaded(graphics, font, appLabel, lx, ly, LABEL_COLOR, ls);

            // HuhuTalk 未读红点
            if (app.unreadBadge) {
                int unread = NotificationManager.getTotalUnread();
                if (unread > 0) {
                    int dotX = x + iconW - 12;
                    int dotY = y - 6;
                    graphics.fill(dotX, dotY, dotX + 12, dotY + 12, 0xFFFF0000);
                    String num = unread > 99 ? "99+" : String.valueOf(unread);
                    PhoneText.draw(graphics, font, num, dotX + 2, dotY + 1, 0xFFFFFFFF,
                            PhoneText.scale(0.75f));
                }
            }
            // 手册首次未看：右上角小红点提示
            if (app.behavior == AppBehavior.MANUAL && !HuhuTalkClientSettings.isManualSeen()) {
                int dotX = x + iconW - 8;
                int dotY = y - 5;
                graphics.fill(dotX, dotY, dotX + 9, dotY + 9, 0xFFFF0000);
            }
        }
    }

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (action != GLFW.GLFW_PRESS) return false;

        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int[] rect = new int[4];
        AppInfo[] slots = slots();
        for (int slot = 0; slot < slots.length; slot++) {
            AppInfo app = slots[slot];
            if (app == null || app.hidden) continue;

            appRect(w, h, slot, rect);
            int x = rect[0], y = rect[1], iconW = rect[2], iconH = rect[3];

            if (relX >= x && relX <= x + iconW && relY >= y && relY <= y + iconH) {
                // 行为全部由 AppBehavior 决定，与名字、与格位都无关
                switch (app.behavior) {
                    // 进入 HuhuTalk App 内容区（消息页 + 底部导航）
                    case TALK -> {
                        PhoneOverlay.openChatApp();
                        return true;
                    }
                    // 点击即标记已看，消除首次红点；随后照常进入手册页
                    case MANUAL -> HuhuTalkClientSettings.setManualSeen(true);
                    // 普通 App：往下走统一的开页逻辑
                    case PLAIN -> {
                    }
                }
                IPhonePage target = app.implemented && app.pageFactory != null
                        ? app.pageFactory.get()
                        : new ComingSoonPage();
                if (app.launchAnimation) {
                    // 启动动画：从图标中心生成该 App 底色的色块、放大淡出，目标页原地淡入。
                    // 注意它**不进入** HuhuTalk 内容区（没有底部导航），桌面仍留在栈底，右键照常回桌面。
                    PhoneOverlay.launchDesktopApp(target, slot);
                } else {
                    PhoneOverlay.pushPage(target);
                }
                return true;
            }
        }
        return false;
    }

    /** 红点坐标计算（预留未来动画使用） */
    public int[] getTalkIconPosition() {
        int[] pos = new int[4];
        if (getTalkIconPos(pos)) return pos;
        return null;
    }

    /**
     * 指定<b>槽位</b>的图标中心（屏幕绝对坐标）+ 边长 + 底色。
     * 供通用启动动画（{@code PhoneOverlay.launchApp}）作为色块生成原点、初始尺寸与颜色。
     * 返回 {centerX, centerY, iconW, colorRGB}；该槽位为空返回 null。
     */
    public static int[] appIconCenterScreen(int slot) {
        AppInfo a = appAt(slot);
        if (a == null) {
            return null;
        }
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int[] rect = new int[4];
        appRect(w, h, slot, rect);
        int cx = rect[0] + rect[2] / 2;
        int cy = rect[1] + rect[3] / 2;
        int sx = PhoneOverlay.getPhoneX() + cx;
        int sy = PhoneOverlay.getPhoneY() + PhoneOverlay.getStatusBarHeight() + cy;
        return new int[]{ sx, sy, rect[2], a.color & 0x00FFFFFF };
    }

    /**
     * 按 App <b>名字</b>查图标中心（保留的旧入口，供按名字定位的调用方使用）。
     * <p>
     * 内置入口（HuhuTalk 的启动动画）已改走 {@link #appIconCenterScreen(int)}+{@link #SLOT_TALK}，
     * 不再经过名字——否则附属模组起个同名 App 就会把内置动画的原点抢走。
     */
    public static int[] appIconCenterScreen(String appName) {
        AppInfo[] slots = slots();
        for (int slot = 0; slot < slots.length; slot++) {
            if (slots[slot] != null && slots[slot].name.equals(appName)) {
                return appIconCenterScreen(slot);
            }
        }
        return null;
    }

    // ==================== 「桌面图标管理」的公开 API ====================
    //
    // 管理页只通过这些方法读写布局，自己不碰 HuhuTalkClientSettings 的桌面字段。
    // 这样"位置在哪里决定"只有一个答案（placeByLayout），页面拿到的永远是合并之后的事实，
    // 不会出现"页面以为挪到了 3 号格、桌面却画在 5 号格"这种两边各算一遍的老毛病。

    /** 桌面槽位数（= 管理页网格的格数）。 */
    public static int desktopSlotCount() {
        return SLOT_COUNT;
    }

    /** 已占用格数（设置页那一行显示 {@code x/9} 用）。 */
    public static int desktopUsedCount() {
        int n = 0;
        for (AppInfo a : slots()) {
            if (a != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * 桌面槽位表快照：下标 = 槽位 = 管理页网格格号；空槽的 {@link DesktopSlot#key} 为 {@code null}。
     * <p>长度恒为 {@link #SLOT_COUNT}，所以管理页的网格与桌面槽位是<b>同一个下标空间</b>，
     * 不需要任何映射表。
     */
    public static List<DesktopSlot> desktopSlots() {
        AppInfo[] arr = slots();
        List<DesktopSlot> out = new ArrayList<>(arr.length);
        for (int i = 0; i < arr.length; i++) {
            out.add(arr[i] == null ? DesktopSlot.empty(i) : DesktopSlot.of(arr[i]));
        }
        return out;
    }

    /** 被玩家移除的 App（可以放回桌面）。主应用永不在此列。 */
    public static List<DesktopSlot> desktopRemoved() {
        List<DesktopSlot> out = new ArrayList<>();
        for (AppInfo a : allByKey().values()) {
            if (a.hidden || !isDesktopRemovable(a.key)) {
                continue;
            }
            if (HuhuTalkClientSettings.isDesktopHidden(a.key)) {
                out.add(DesktopSlot.of(a));
            }
        }
        return out;
    }

    /** 该 key 是否是「固定不可动」的主应用（HuhuTalk）。 */
    public static boolean isDesktopFixed(String key) {
        return KEY_TALK.equals(key);
    }

    /**
     * 该 key 的 App 能否从桌面移除（画 ✕ / 点击移除 / 拖进回收栏都看它）。
     * <p>
     * <b>「不可移除」不等于「不可拖动」</b>：「设置」允许换位，所以它<b>不是</b>
     * {@link #isDesktopFixed}。两个判据刻意分开，避免为了挡住删除叉号而把拖动一起禁掉。
     */
    public static boolean isDesktopRemovable(String key) {
        return !isDesktopFixed(key) && !KEY_SETTINGS.equals(key);
    }

    /**
     * 把某个 App 挪到目标槽位。
     * <p>
     * <b>拖到别人身上 = 两人换位</b>，而不是把对方挤到别处：后者会让"拖一下"连累第三个图标，
     * 与玩家的预期不符。目标格为空则是单纯移入。
     *
     * @return true = 布局确实变了（或本来就在目标格）
     */
    public static boolean moveDesktopApp(String key, int targetSlot) {
        if (key == null || isDesktopFixed(key)) {
            return false; // 主应用固定在第一排第一位：不可拖、不可换
        }
        if (targetSlot < 0 || targetSlot >= SLOT_COUNT || targetSlot == SLOT_TALK) {
            return false;
        }
        AppInfo[] cur = slots();
        int from = -1;
        for (int i = 0; i < cur.length; i++) {
            if (cur[i] != null && key.equals(cur[i].key)) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            return false; // 不在桌面上（可能已被移除）
        }
        if (from == targetSlot) {
            return true;
        }
        AppInfo occupant = cur[targetSlot];
        HuhuTalkClientSettings.setDesktopSlot(key, targetSlot);
        if (occupant != null && !isDesktopFixed(occupant.key)) {
            HuhuTalkClientSettings.setDesktopSlot(occupant.key, from);
        }
        return true;
    }

    /** 从桌面移除某 App（只是不画，随时能从管理页底部放回）。主应用与「设置」拒绝。 */
    public static boolean removeDesktopApp(String key) {
        if (key == null || !isDesktopRemovable(key)) {
            return false;
        }
        return HuhuTalkClientSettings.setDesktopHidden(key, true);
    }

    /** 把移除过的 App 放回桌面（回到默认位置；被占则按既有纪律退让到最小空槽）。 */
    public static boolean restoreDesktopApp(String key) {
        if (key == null || !isDesktopRemovable(key)) {
            return false;
        }
        return HuhuTalkClientSettings.setDesktopHidden(key, false);
    }

    /**
     * 一个槽位的<b>只读快照</b>，供「桌面图标管理」页渲染与命中。
     * <p>
     * {@link AppInfo} 的字段是包内可见的，管理页虽然同包也能直接读；但走这个快照有两条好处：
     * 页面<b>改不动</b>桌面状态（只能通过 {@link #moveDesktopApp} 等入口），
     * 而且主应用那种"图标样式由设置现算"的特殊情况在这里被抹平成一个字段。
     */
    public static final class DesktopSlot {
        /** 槽位（= 网格格号）；空槽为它自己的格号 */
        public final int slot;
        /** 稳定标识；{@code null} = 空槽 */
        public final String key;
        public final String name;
        public final int color;
        public final String center;
        public final boolean emoji;
        public final float centerScale;
        public final int centerColor;
        /** 贴图图标；{@code null} = 画 {@link #center} 文字/emoji */
        public final ResourceLocation iconTexture;
        public final int iconTexWidth;
        public final int iconTexHeight;
        public final float iconFill;
        /** 是否是固定不可动的主应用 */
        public final boolean fixed;

        private DesktopSlot(int slot, String key, String name, int color, String center, boolean emoji,
                            float centerScale, int centerColor, ResourceLocation iconTexture,
                            int iconTexWidth, int iconTexHeight, float iconFill, boolean fixed) {
            this.slot = slot;
            this.key = key;
            this.name = name;
            this.color = color;
            this.center = center;
            this.emoji = emoji;
            this.centerScale = centerScale;
            this.centerColor = centerColor;
            this.iconTexture = iconTexture;
            this.iconTexWidth = iconTexWidth;
            this.iconTexHeight = iconTexHeight;
            this.iconFill = iconFill;
            this.fixed = fixed;
        }

        /** 空槽 */
        static DesktopSlot empty(int slot) {
            return new DesktopSlot(slot, null, "", 0, "", false, 1f, 0, null, 0, 0, 1f, false);
        }

        static DesktopSlot of(AppInfo a) {
            ResourceLocation tex = a.iconTexture;
            int tw = a.iconTexWidth;
            int th = a.iconTexHeight;
            if (a.behavior == AppBehavior.TALK) {
                // 主应用的图标在桌面上是「按设置里的风格」现算的（高清贴图 / 低清贴图 / 狐狸 emoji），
                // 不走 AppInfo.iconTexture（那条字段是附属模组注册的 App 专用）。
                // 管理页必须与桌面长得一模一样，所以在这里把风格解析成结果。
                String style = HuhuTalkClientSettings.getHuhutalkIconStyle();
                if ("fox".equals(style)) {
                    tex = null;
                } else if ("symbol".equals(style)) {
                    tex = ICON_SYMBOL;
                    tw = th = ICON_SYMBOL_SIZE;
                } else {
                    tex = ICON_SYMBOL2;
                    tw = th = ICON_SYMBOL2_SIZE;
                }
            }
            return new DesktopSlot(a.slot, a.key, displayName(a.key, a.name), a.color, a.center, a.emoji, a.centerScale,
                    a.centerColor, tex, tw, th, a.iconFill, KEY_TALK.equals(a.key));
        }
    }

    /**
     * 内置 App 的点击行为。
     * <p>
     * 为什么是枚举而不是 lambda：后续的「游戏内热切换」要把布局落盘，
     * 行为必须是<b>可序列化的稳定标识</b>——lambda 进不了 JSON，
     * 而名字字符串会因为改名/翻译而失联（这正是本次要拆掉的老写法）。
     */
    enum AppBehavior {
        /** 进入 HuhuTalk 内容区（消息页 + 底部导航 + 启动动画） */
        TALK,
        /** 打开手机内置手册（并消除首次未看红点） */
        MANUAL,
        /** 普通：有 pageFactory 就进页面，否则进「敬请期待」 */
        PLAIN
    }

    public static class AppInfo {
        /** 所在槽位（0 ~ {@link #SLOT_COUNT}-1）；决定图标画在哪一格，与列表顺序无关。 */
        final int slot;
        /**
         * <b>稳定标识</b>：布局落盘、拖动换位、移除/恢复全靠它。
         * <p>
         * 内置 App 用固定短名（{@code huhutalk} / {@code manual} / {@code settings}），
         * 附属模组的 App 用 {@code PhoneApp#id()} 的字符串形式。
         * <b>刻意不用 {@link #name}</b>：名字是给玩家看的文案，改名/翻译会让布局静默失联
         * —— 这正是本轮要拆掉的那种"按名字判功能"的老写法。
         */
        final String key;
        /** 点击行为。取代了原先散在各处的 {@code "SOS".equals(name)} 这类按名字判功能的写法。 */
        final AppBehavior behavior;
        /** 是否画未读红点（目前只有 HuhuTalk）。 */
        final boolean unreadBadge;
        final String name;            // 图标下方全名
        final int color;              // 图标底色
        final String center;          // 图标中心内容（文字或 emoji）
        final boolean emoji;          // 中心是否为 emoji（配合 centerScale 用大字号）
        final float centerScale;      // 中心内容字号倍率
        final int centerColor;        // 中心内容颜色
        final boolean implemented;    // 是否已实现（否则点开 ComingSoon）
        final boolean hidden;         // 暂隐藏（逻辑保留）
        /**
         * 点开时是否播放「色块放大」启动动画。
         * <p>
         * 内置 App 一律 {@code false}：HuhuTalk 走自己的 {@code openChatApp()} 通路，
         * 手册/设置保持原本的「从右滑入」，不因为这次改动而变样。
         * 附属模组注册的 App 由 {@code PhoneApp.launchAnimation} 决定，默认开启。
         */
        final boolean launchAnimation;
        final Supplier<IPhonePage> pageFactory;
        /**
         * 图标中心的贴图（附属模组注册的 App 用）；为 null 表示走「文字 / emoji」画法。
         * 内置 App 全部为 null —— HuhuTalk 自己的贴图图标走的是它专用的特殊分支，不经过这里。
         */
        final ResourceLocation iconTexture;
        final int iconTexWidth;       // 贴图源宽（像素）
        final int iconTexHeight;      // 贴图源高（像素）
        final float iconFill;         // 贴图在图标方块内的占比（1.0 = 撑满）

        /** 内置 App 用的构造器（无贴图图标、不播启动动画）。 */
        AppInfo(int slot, String key, AppBehavior behavior, boolean unreadBadge,
                String name, int color, String center, boolean emoji, float centerScale, int centerColor,
                boolean implemented, boolean hidden, Supplier<IPhonePage> pageFactory) {
            this(slot, key, behavior, unreadBadge, name, color, center, emoji, centerScale, centerColor,
                    implemented, hidden, false, pageFactory, null, 0, 0, 1.0f);
        }

        /** 完整构造器：多出「贴图图标」一组字段，供附属模组注册的 App 使用。 */
        AppInfo(int slot, String key, AppBehavior behavior, boolean unreadBadge,
                String name, int color, String center, boolean emoji, float centerScale, int centerColor,
                boolean implemented, boolean hidden, boolean launchAnimation,
                Supplier<IPhonePage> pageFactory,
                ResourceLocation iconTexture, int iconTexWidth, int iconTexHeight, float iconFill) {
            this.slot = slot;
            this.key = key;
            this.behavior = behavior;
            this.unreadBadge = unreadBadge;
            this.name = name;
            this.color = color;
            this.center = center;
            this.emoji = emoji;
            this.centerScale = centerScale;
            this.centerColor = centerColor;
            this.implemented = implemented;
            this.hidden = hidden;
            this.launchAnimation = launchAnimation;
            this.pageFactory = pageFactory;
            this.iconTexture = iconTexture;
            this.iconTexWidth = iconTexWidth;
            this.iconTexHeight = iconTexHeight;
            this.iconFill = iconFill;
        }

        /**
         * 换一个槽位（其余字段逐字复制）。
         * <p>
         * {@link #slot} 是 {@code final}，而布局需要在合并阶段把 App 摆到另一个格子 ——
         * 与其把它改成可变（那会让"两个 AppInfo 指向同一格"变成可能），不如复制一份新的。
         * 结果是<b>位置只在 {@link #placeByLayout} 一处决定</b>。
         */
        AppInfo withSlot(int newSlot) {
            if (newSlot == this.slot) {
                return this;
            }
            return new AppInfo(newSlot, key, behavior, unreadBadge, name, color, center, emoji,
                    centerScale, centerColor, implemented, hidden, launchAnimation, pageFactory,
                    iconTexture, iconTexWidth, iconTexHeight, iconFill);
        }
    }
}
