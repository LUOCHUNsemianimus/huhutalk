package com.huhutalk.api.phone;

import com.huhutalk.phone.IPhonePage;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Supplier;

/**
 * 一个「手机桌面 App」的描述符——附属模组注册 App 时构造的对象。
 *
 * <h2>这是什么</h2>
 * HuhuTalk 的桌面是一个图标网格，每个图标点开进入一个 {@link IPhonePage}。
 * 本类型描述网格里的<b>一个格子</b>：叫什么、什么底色、中心画什么、
 * 点开进入哪个页面。构造后用 {@link PhoneAppRegistry#register(PhoneApp)} 交给 HuhuTalk。
 *
 * <h2>最小用法</h2>
 * <pre>{@code
 * PhoneAppRegistry.register(
 *     PhoneApp.builder(ResourceLocation.fromNamespaceAndPath("mymod", "douyin"), "抖音")
 *             .color(0x161823)
 *             .icon(ResourceLocation.fromNamespaceAndPath("mymod", "textures/icon/douyin.png"), 256, 256)
 *             .page(DouyinPage::new)
 *             .build());
 * }</pre>
 *
 * <h2>两条硬约束（照做就不会出问题）</h2>
 * <ol>
 *   <li><b>仅客户端</b>。{@link IPhonePage} 是客户端类型，桌面只在客户端存在。
 *       请在客户端入口（{@code Dist.CLIENT} 的构造器 / 客户端 setup）或
 *       {@link PhoneAppRegisterEvent} 里注册，不要在服务端路径上碰它。</li>
 *   <li><b>{@link #id()} 必须全局唯一</b>。用你自己的 mod id 作命名空间即可。
 *       重复 id 的第二次注册会被忽略并打警告（不会崩溃）。</li>
 * </ol>
 *
 * <h2>桌面位置（槽位）</h2>
 * <p>
 * 桌面固定 {@link #MAX_SLOTS} 格，每格是一个「槽位」。默认<b>不指定</b>槽位，
 * 你的 App 会落到最小的空槽——也就是过去「一律追加在后面」的行为，所以已发布的模组什么都不用改。
 * 想固定位置就用 {@link Builder#slot(int)}。
 * <p>
 * 槽位 0 固定属于 HuhuTalk，占用不到；申请的槽位被别人占用时会<b>退让到空槽并打 WARN</b>，
 * 而不是让你的图标消失——「注册了但图标不出现」是最难排查的哑失败。
 *
 * <h2>图标中心的两种画法（二选一）</h2>
 * <ul>
 *   <li><b>贴图</b>：{@link Builder#icon(ResourceLocation, int, int)}。建议提供正方形贴图；
 *       非正方形会自动按较长边贴合、短边留白（不会拉伸）。</li>
 *   <li><b>文字 / emoji</b>：{@link Builder#center(String)} 或
 *       {@link Builder#center(String, float, int)}（例如 {@code "⚙"} 或 {@code "🦊"}）。</li>
 * </ul>
 * 两者都设时<b>贴图优先</b>。都不设则只是一个纯色圆角方块。
 *
 * <h2>关于「启动动画」</h2>
 * 桌面点开你的 App 时，默认播放「色块放大淡出」的启动动画——从你的<b>图标中心</b>生成一个
 * {@link Builder#color(int) 图标底色}的圆角色块，放大铺满屏幕并淡出，目标页在底下原地淡入。
 * 想改用通用的「从右侧滑入」转场就 {@code .launchAnimation(false)}。
 * <p>
 * 这条通路<b>不会</b>把玩家带进 HuhuTalk 内容区（没有底部导航），右键照常回桌面。
 * 想自己程序化打开就用 {@code PhoneOverlay.launchDesktopApp(page, slot)}。
 *
 * @see PhoneAppRegistry 注册入口
 * @see PhoneAppRegisterEvent 推荐的注册时机
 */
public final class PhoneApp {

    /** 兜底底色（未指定 {@link Builder#color(int)} 时使用） */
    private static final int DEFAULT_COLOR = 0xFF3F51B5;
    /** 兜底中心文字颜色 */
    private static final int DEFAULT_CENTER_COLOR = 0xFFFFFFFF;
    /**
     * 桌面槽位总数：三种屏幕比例里<b>最小</b>的网格容量（{@code 4:3} → 3 行 × 3 列）。
     * <p>
     * 布局刻意锁在这个范围内。网格随比例变化（20:9→15 格、9:16→12 格、4:3→9 格），
     * 而槽位布局是一份与比例无关的数据；若允许排到第 10 格，玩家把比例切到 4:3 就会丢图标。
     */
    public static final int MAX_SLOTS = 9;
    /** {@link Builder#slot(int)} 的默认值：不指定，自动落到最小空槽。 */
    public static final int SLOT_AUTO = -1;

    private final ResourceLocation id;
    private final String displayName;
    private final int iconColor;
    private final String centerText;
    private final float centerScale;
    private final int centerColor;
    private final ResourceLocation iconTexture;
    private final int iconTexWidth;
    private final int iconTexHeight;
    private final float iconFill;
    private final Supplier<IPhonePage> pageFactory;
    private final int order;
    private final int slot;
    private final boolean launchAnimation;

    private PhoneApp(Builder b) {
        this.id = b.id;
        this.displayName = b.displayName;
        this.iconColor = b.iconColor;
        this.centerText = b.centerText;
        this.centerScale = b.centerScale;
        this.centerColor = b.centerColor;
        this.iconTexture = b.iconTexture;
        this.iconTexWidth = b.iconTexWidth;
        this.iconTexHeight = b.iconTexHeight;
        this.iconFill = b.iconFill;
        this.pageFactory = b.pageFactory;
        this.order = b.order;
        this.slot = b.slot;
        this.launchAnimation = b.launchAnimation;
    }

    /** 唯一标识（命名空间建议用自己的 mod id）。用于去重，不显示给玩家。 */
    public ResourceLocation id() {
        return id;
    }

    /** 图标下方的名称文字（玩家可见，建议与内置 App 不重名）。 */
    public String displayName() {
        return displayName;
    }

    /** 图标底色（ARGB，alpha 会被强制为不透明）。 */
    public int iconColor() {
        return iconColor;
    }

    /** 图标中心的文字 / emoji；无则返回空串。 */
    public String centerText() {
        return centerText;
    }

    /** 中心文字的字号倍率。 */
    public float centerScale() {
        return centerScale;
    }

    /** 中心文字的颜色。 */
    public int centerColor() {
        return centerColor;
    }

    /** 图标贴图；为 null 表示走「文字 / emoji」画法。 */
    public ResourceLocation iconTexture() {
        return iconTexture;
    }

    /** 贴图源宽（像素）。 */
    public int iconTexWidth() {
        return iconTexWidth;
    }

    /** 贴图源高（像素）。 */
    public int iconTexHeight() {
        return iconTexHeight;
    }

    /** 贴图在图标方块内的占比（1.0 = 撑满）。 */
    public float iconFill() {
        return iconFill;
    }

    /**
     * 点开该 App 时新建的页面工厂；为 null 时点开显示内置的「敬请期待」页。
     * <p>
     * 每次点开都会调用一次，所以可以直接用 {@code MyPage::new}——
     * 不需要自己缓存实例，页面状态天然是一次性的。
     */
    public Supplier<IPhonePage> pageFactory() {
        return pageFactory;
    }

    /**
     * 排序权重（小的在前）；相同权重按注册顺序。仅在<b>没有指定 {@link #slot()} 时</b>影响
     * 自动落位的先后：自动落位总是从下标小的空槽开始填。
     */
    public int order() {
        return order;
    }

    /**
     * 桌面槽位（0 ~ {@link #MAX_SLOTS}-1）；{@link #SLOT_AUTO} 表示未指定、自动落到最小空槽。
     * <p>
     * 槽位 0 固定属于 HuhuTalk，外部注册占用它会失败并改为落到空槽（打 WARN，不抛异常）。
     * 申请的槽位被别人占用时同样退让到空槽——「图标不出现」比「位置不对」难排查得多。
     */
    public int slot() {
        return slot;
    }

    /**
     * 点开这个 App 时是否播放「图标色块放大 + 目标页原地淡入」的启动动画。
     * <p>
     * 默认 {@code true}。不想要就用 {@code .launchAnimation(false)}，回退成通用的
     * 「从右侧滑入」转场。
     */
    public boolean launchAnimation() {
        return launchAnimation;
    }

    /** 开始构造：{@code id} 唯一、{@code displayName} 为图标下方文字。 */
    public static Builder builder(ResourceLocation id, String displayName) {
        return new Builder(id, displayName);
    }

    public static final class Builder {
        private final ResourceLocation id;
        private final String displayName;
        private int iconColor = DEFAULT_COLOR;
        private String centerText = "";
        private float centerScale = 1.0f;
        private int centerColor = DEFAULT_CENTER_COLOR;
        private ResourceLocation iconTexture = null;
        private int iconTexWidth = 0;
        private int iconTexHeight = 0;
        private float iconFill = 1.0f;
        private Supplier<IPhonePage> pageFactory = null;
        private int order = 0;
        private int slot = SLOT_AUTO;
        private boolean launchAnimation = true;

        private Builder(ResourceLocation id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        /** 图标底色。可传 RGB（{@code 0xRRGGBB}）或 ARGB；alpha 最终强制不透明。 */
        public Builder color(int rgb) {
            this.iconColor = rgb;
            return this;
        }

        /** 图标中心用文字 / emoji（白色、1 倍字号）。 */
        public Builder center(String text) {
            return center(text, 1.0f, DEFAULT_CENTER_COLOR);
        }

        /** 图标中心用文字 / emoji，自定义字号倍率与颜色。emoji 建议 2.0 倍起步。 */
        public Builder center(String text, float scale, int color) {
            this.centerText = text == null ? "" : text;
            this.centerScale = scale;
            this.centerColor = color;
            return this;
        }

        /**
         * 图标中心用贴图，在方块内撑满。
         *
         * @param texture   贴图 ResourceLocation（形状如 {@code mymod:textures/icon/douyin.png}）
         * @param srcWidth  贴图源宽（像素）
         * @param srcHeight 贴图源高（像素）
         */
        public Builder icon(ResourceLocation texture, int srcWidth, int srcHeight) {
            return icon(texture, srcWidth, srcHeight, 1.0f);
        }

        /**
         * 图标中心用贴图。
         *
         * @param fill 贴图在方块内的占比，1.0 = 撑满、1.0 以下会在四周留白
         */
        public Builder icon(ResourceLocation texture, int srcWidth, int srcHeight, float fill) {
            this.iconTexture = texture;
            this.iconTexWidth = srcWidth;
            this.iconTexHeight = srcHeight;
            this.iconFill = fill;
            return this;
        }

        /** 点开该 App 进入的页面（每次点开新建一次）。不设则点开显示「敬请期待」。 */
        public Builder page(Supplier<? extends IPhonePage> factory) {
            this.pageFactory = factory == null ? null : factory::get;
            return this;
        }

        /**
         * 排序权重（小的在前）；仅在<b>没有指定 {@link #slot(int)} 时</b>生效：
         * 未指定槽位的 App 会按权重顺序依次落到最小的空槽。
         */
        public Builder order(int order) {
            this.order = order;
            return this;
        }

        /**
         * 指定桌面<b>槽位</b>（{@code 0} ~ {@code MAX_SLOTS-1}）。
         * <p>
         * 不调用本方法（或传 {@link PhoneApp#SLOT_AUTO}）= 自动落到最小空槽，
         * 也就是「追加在后面」的老行为——已发布的附属模组什么都不用改。
         * <p>
         * 注意：槽位 0 是 HuhuTalk 的，占不到；申请的槽位被占用时会退让到空槽并打 WARN，
         * 不会让你的图标消失。想要稳定位置就明确指定一个，或用一个空位较多的槽号。
         *
         * @throws IllegalArgumentException 槽位越界（在 {@link #build()} 时抛出）
         */
        public Builder slot(int slot) {
            this.slot = slot;
            return this;
        }

        /**
         * 点开时是否播放「色块放大淡出」启动动画。<b>默认开启</b>。
         * <p>
         * 动画从你的<b>图标中心</b>生成一个图标底色的圆角色块，由小放大到铺满整块屏幕并淡出，
         * 目标页在底下原地淡入——就是内置 HuhuTalk 点开时那个效果。
         * <p>
         * 传 {@code false} 则改用通用的「从右侧滑入」转场。
         * <p>
         * 播放期间普通点击被屏蔽，右键可以强制打断并回桌面；把设置里的「切屏动画」关掉时
         * 整个动画都会被跳过（瞬时切换），所以不需要你自己判断那个开关。
         */
        public Builder launchAnimation(boolean enabled) {
            this.launchAnimation = enabled;
            return this;
        }

        public PhoneApp build() {
            if (id == null) {
                throw new IllegalArgumentException("PhoneApp.id 不能为 null");
            }
            if (displayName == null || displayName.isBlank()) {
                throw new IllegalArgumentException("PhoneApp.displayName 不能为空（id=" + id + "）");
            }
            if (iconTexture != null && (iconTexWidth <= 0 || iconTexHeight <= 0)) {
                throw new IllegalArgumentException(
                        "PhoneApp 指定了贴图却给了非法尺寸 " + iconTexWidth + "x" + iconTexHeight + "（id=" + id + "）");
            }
            if (slot < SLOT_AUTO || slot >= MAX_SLOTS) {
                throw new IllegalArgumentException(
                        "PhoneApp 槽位越界：" + slot + "（合法 " + SLOT_AUTO + "=自动，或 0~"
                                + (MAX_SLOTS - 1) + "）（id=" + id + "）");
            }
            return new PhoneApp(this);
        }
    }
}
