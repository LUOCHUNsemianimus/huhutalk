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
 * 目前桌面点开 App 走的是通用「从右侧滑入」转场（与内置的「设置」一致）。
 * HuhuTalk 自己的图标另有一套「底色色块放大淡出」的启动动画，但那条通路与
 * 「进入 HuhuTalk 内容区（显示底部导航）」是耦合的，所以<b>暂未对外开放</b>。
 * 如果你需要，请在 HuhuTalk 仓库提需求，届时会加一个不依赖底部导航的公开重载。
 *
 * @see PhoneAppRegistry 注册入口
 * @see PhoneAppRegisterEvent 推荐的注册时机
 */
public final class PhoneApp {

    /** 兜底底色（未指定 {@link Builder#color(int)} 时使用） */
    private static final int DEFAULT_COLOR = 0xFF3F51B5;
    /** 兜底中心文字颜色 */
    private static final int DEFAULT_CENTER_COLOR = 0xFFFFFFFF;

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

    /** 排序权重（小的在前）；相同权重按注册顺序。内置 App 恒定在前面。 */
    public int order() {
        return order;
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

        /** 排序权重（小的在前）；内置 App 恒定排在所有外部 App 之前。 */
        public Builder order(int order) {
            this.order = order;
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
            return new PhoneApp(this);
        }
    }
}
