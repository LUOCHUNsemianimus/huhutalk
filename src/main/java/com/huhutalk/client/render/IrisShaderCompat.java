package com.huhutalk.client.render;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Iris 光影状态的最小兼容层。
 *
 * <h2>为什么要单独一层</h2>
 * 自拍 pass 需要知道 Iris 是否正在使用光影包，以决定走「借用真实主靶 + 中心裁切」还是原
 * 400x600 直接渲染路径（窗口像素不足时也回退原路径）。Iris 不是本模组的编译期依赖，因此只能反射公开 API
 * {@code net.irisshaders.iris.api.v0.IrisApi#isShaderPackInUse()}。
 *
 * <h2>边界</h2>
 * <ul>
 *   <li>类或 API 不存在（未安装 Iris、版本无该 API）时返回 false，<b>不当作错误、不刷日志</b>；</li>
 *   <li>{@code Class.forName}/{@code getMethod} 只在首次调用时解析并缓存，热路径不重复查找；</li>
 *   <li>API 存在但反射调用失败时只 {@code warn} 一次，并永久回退原渲染路径；</li>
 *   <li>{@link InvocationTargetException} 里的 {@link Error} 原样抛出，不吞致命错误。</li>
 * </ul>
 */
final class IrisShaderCompat {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";

    private static boolean resolved;
    private static boolean unavailable;
    private static Method getInstance;
    private static Method isShaderPackInUse;
    private static Object apiInstance;
    /** 调用失败后永久回退原路径，避免每帧重复抛异常/刷日志。 */
    private static boolean callFailed;

    private IrisShaderCompat() {
    }

    /**
     * 当前是否正在使用 Iris 光影包。无 Iris 时返回 false。
     * <p>
     * 只在渲染线程的光影状态检查里调用；API 存在但调用失败时回退原 400x600 路径并明确告警一次。
     */
    static boolean isShaderPackInUse() {
        if (callFailed) {
            return false;
        }
        resolve();
        if (unavailable) {
            return false;
        }
        try {
            Object api = apiInstance;
            if (api == null) {
                api = getInstance.invoke(null);
                if (api == null) {
                    callFailed = true;
                    LOGGER.warn("[HuhuTalk/Selfie] IrisApi.getInstance() 返回 null，自拍回退原 400x600 离屏路径");
                    return false;
                }
                apiInstance = api;
            }
            return Boolean.TRUE.equals(isShaderPackInUse.invoke(api));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error error) {
                throw error; // 不吞致命 Error
            }
            callFailed = true;
            LOGGER.warn("[HuhuTalk/Selfie] Iris 光影状态查询失败，自拍回退原 400x600 离屏路径",
                    cause != null ? cause : e);
            return false;
        } catch (ReflectiveOperationException | LinkageError | SecurityException e) {
            callFailed = true;
            LOGGER.warn("[HuhuTalk/Selfie] Iris 光影状态查询失败，自拍回退原 400x600 离屏路径", e);
            return false;
        }
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> apiClass = Class.forName(API_CLASS, false, IrisShaderCompat.class.getClassLoader());
            getInstance = apiClass.getMethod("getInstance");
            isShaderPackInUse = apiClass.getMethod("isShaderPackInUse");
        } catch (ClassNotFoundException e) {
            // 未安装 Iris / 该版本无此 API：不是错误，静默回退原路径
            unavailable = true;
        } catch (ReflectiveOperationException | LinkageError | SecurityException e) {
            // 类存在但公开方法解析不了：回退原路径，并明确告警一次
            unavailable = true;
            LOGGER.warn("[HuhuTalk/Selfie] Iris API 存在但无法解析，自拍回退原 400x600 离屏路径", e);
        }
    }
}
