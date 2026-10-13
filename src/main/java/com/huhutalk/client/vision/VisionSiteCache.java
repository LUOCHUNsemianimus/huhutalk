package com.huhutalk.client.vision;

import com.huhutalk.network.S2CVisionSitesPacket;

import java.util.List;

/**
 * 客户端缓存的「识图平台列表」快照（来自 {@link S2CVisionSitesPacket}）。
 *
 * <h2>为什么不缓存密钥</h2>
 * 服务端<b>根本不下发密钥</b>（见该包的类注释），所以这里只有"配没配"这一个布尔量。
 * 「像密码一样不可读」因此不只是 UI 层的掩码——密钥压根没到过客户端。
 *
 * <h2>生命周期</h2>
 * 每次打开「识图模型」页都会重新向服务端拉一次，所以这里不需要失效策略；
 * 断线时保留旧快照也无害（渲染用，不参与任何判定）。
 */
public final class VisionSiteCache {

    private static volatile List<S2CVisionSitesPacket.SiteEntry> sites = List.of();
    private static volatile boolean canEdit = false;

    private VisionSiteCache() {
    }

    /** 收到服务端快照时写入。 */
    public static void set(List<S2CVisionSitesPacket.SiteEntry> entries, boolean editable) {
        sites = entries == null ? List.of() : List.copyOf(entries);
        canEdit = editable;
    }

    /** 服务器上现有的识图平台（按服务端给好的顺序）。 */
    public static List<S2CVisionSitesPacket.SiteEntry> sites() {
        return sites;
    }

    /** 当前玩家是否有权修改服务器上 AI 站点配置。 */
    public static boolean canEdit() {
        return canEdit;
    }

    /** 是否还没拉到过任何快照（用于"正在加载"提示）。 */
    public static boolean isEmpty() {
        return sites.isEmpty();
    }

    /** 退出服务器时清空，避免上一个服务器的站点列表残留到下一个。 */
    public static void clear() {
        sites = List.of();
        canEdit = false;
    }
}
