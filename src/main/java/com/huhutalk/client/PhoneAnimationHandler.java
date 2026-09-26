package com.huhutalk.client;

import com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder.Animation;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.file.AnimationFile;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.resource.GeckoLibCache;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.util.json.JsonAnimationUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.huhutalk.HuhuTalk;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 「使用手机」动画的文件加载与合并。
 * <p>
 * 动画文件随本模组 jar 分发：
 * {@code assets/huhutalk/animation/huhutalk_phone.animation.json}，
 * 内含 {@code huhutalk_phone_raise} / {@code _hold} / {@code _lower} 三个动画。
 * <p>
 * 之所以不用 TLM 的 {@code DefaultGeckoAnimationEvent}（虽然它是官方扩展点）：
 * 实测该事件在本环境下未按预期触发，导致动画始终未并入。改为由
 * {@link com.huhutalk.mixin.GeckoModelLoaderMixin} 在
 * {@code GeckoModelLoader.registerMaidAnimations} 入口确定性注入——
 * 该方法是 TLM 把"默认动画表"复制进每个 gecko 模型（含酒狐）的必经之路，
 * 因此在那里补进去的动画会对所有女仆 gecko 模型生效。
 */
public final class PhoneAnimationHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("huhutalk.phone_anim");

    /** 我们的动画文件（只含 huhutalk_phone_* 三个动画） */
    public static final ResourceLocation ANIMATION_FILE =
            ResourceLocation.fromNamespaceAndPath(HuhuTalk.MOD_ID, "animation/huhutalk_phone.animation.json");

    /** 已解析的动画文件缓存；解析失败时为 null，下次注入会重试 */
    private static volatile AnimationFile parsedCache = null;

    /** 是否已成功注入过（诊断用） */
    private static volatile boolean merged = false;

    private PhoneAnimationHandler() {
    }

    /** 上一次是否成功注入了我们的动画。 */
    public static boolean lastMergeSucceeded() {
        return merged;
    }

    /**
     * 确保我们的三个动画存在于目标动画表中。
     * <p>
     * 由 mixin 在 {@code registerMaidAnimations} 入口调用：
     * <ul>
     *   <li>若目标表（通常是 {@code DEFAULT_MAID_ANIMATION_FILE}）已有我们的动画 → 直接返回，
     *       这样后续每个模型包随后都会从该表复制到我们的动画。</li>
     *   <li>否则解析我们的动画文件并补进去。</li>
     * </ul>
     */
    public static void ensureAnimations(AnimationFile target) {
        if (target == null) {
            return;
        }
        if (target.animations().containsKey(PhoneAnimationRegistration.ANIM_RAISE)) {
            // 已经被注入过（目标表就是默认表），无需重复
            return;
        }
        AnimationFile ours = loadOurFile();
        if (ours == null) {
            return;
        }
        for (Map.Entry<String, Animation> e : ours.animations().entrySet()) {
            if (!target.animations().containsKey(e.getKey())) {
                target.putAnimation(e.getKey(), e.getValue());
            }
        }
        boolean ok = target.animations().containsKey(PhoneAnimationRegistration.ANIM_RAISE)
                && target.animations().containsKey(PhoneAnimationRegistration.ANIM_HOLD)
                && target.animations().containsKey(PhoneAnimationRegistration.ANIM_LOWER);
        merged = ok;
        if (ok) {
            LOGGER.info("[HuhuTalk] 手机动画已注入默认动画表: {} (共 {} 个动画)",
                    ANIMATION_FILE, ours.animations().size());
        } else {
            LOGGER.error("[HuhuTalk] 手机动画注入后校验失败！文件={}", ANIMATION_FILE);
        }
    }

    /** 解析并缓存我们自己的动画文件；失败返回 null。 */
    private static AnimationFile loadOurFile() {
        AnimationFile cached = parsedCache;
        if (cached != null) {
            return cached;
        }
        synchronized (PhoneAnimationHandler.class) {
            if (parsedCache != null) {
                return parsedCache;
            }
            try (InputStream stream = Minecraft.getInstance().getResourceManager().open(ANIMATION_FILE)) {
                JsonObject json = GsonHelper.fromJson(CustomPackLoader.GSON,
                        new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
                if (json == null) {
                    LOGGER.error("[HuhuTalk] 动画文件解析为 null: {}", ANIMATION_FILE);
                    return null;
                }
                AnimationFile file = new AnimationFile();
                // 复用 TLM 已初始化的解析器实例（与 GeckoModelLoader.getAnimationFile 同一做法）
                var parser = GeckoLibCache.getInstance().parser;
                for (Map.Entry<String, JsonElement> entry : JsonAnimationUtils.getAnimations(json)) {
                    String name = entry.getKey();
                    Animation anim = JsonAnimationUtils.deserializeJsonToAnimation(
                            JsonAnimationUtils.getAnimation(json, name), parser);
                    file.putAnimation(name, anim);
                }
                if (file.animations().isEmpty()) {
                    LOGGER.error("[HuhuTalk] 动画文件里没有解析到任何动画: {}", ANIMATION_FILE);
                    return null;
                }
                LOGGER.info("[HuhuTalk] 已解析手机动画文件: {} -> {}", ANIMATION_FILE, file.animations().keySet());
                parsedCache = file;
                return file;
            } catch (IOException e) {
                LOGGER.error("[HuhuTalk] 打不开手机动画文件: {}", ANIMATION_FILE, e);
                return null;
            } catch (Exception e) {
                LOGGER.error("[HuhuTalk] 解析手机动画文件失败: {}", ANIMATION_FILE, e);
                return null;
            }
        }
    }
}
