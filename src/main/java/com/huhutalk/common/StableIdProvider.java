package com.huhutalk.common;

/**
 * Duck interface：由 EntityMaidMixin 注入到 {@code EntityMaid}。
 * <p>
 * 本接口必须放在普通包（而非 mixin package）内，否则 Mixin 类加载器
 * 会拒绝 mixin 类对它的直接引用。
 * 外部通过 {@code if (maid instanceof StableIdProvider p)} 安全读写稳定 ID。
 */
public interface StableIdProvider {
    String getStableId();

    void setStableId(String id);
}