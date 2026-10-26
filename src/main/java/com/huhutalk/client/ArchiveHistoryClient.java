package com.huhutalk.client;

import com.huhutalk.core.archive.ArchiveHistoryRecord;
import com.huhutalk.core.archive.ArchiveHistoryRequest;
import com.huhutalk.core.archive.ArchiveHistoryResponse;
import com.huhutalk.core.archive.ArchiveHistorySession;
import com.huhutalk.core.archive.ArchiveHistoryStatus;
import com.huhutalk.network.C2SRequestArchiveHistoryPacket;
import com.huhutalk.phone.PhoneOverlay;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 手机聊天档案的客户端控制器。
 *
 * <p>只缓存当前页与已访问 cursor（由 {@link ArchiveHistorySession} 管理），
 * <b>不读写 {@link ChatHistoryStorage}</b>，也不改写草稿、图片、物品、表情、
 * 错误或离线消息。异步响应到达客户端主线程后先经过会话状态机校验，再请求重绘。</p>
 */
public final class ArchiveHistoryClient {
    private static final ArchiveHistorySession SESSION = new ArchiveHistorySession();

    private ArchiveHistoryClient() {
    }

    /** 登录、登出或切换世界时作废旧代次，迟到响应不再生效。 */
    public static void onConnectionReset() {
        SESSION.onConnectionReset();
    }

    public static void open(String stableId) {
        SESSION.open(stableId).ifPresent(ArchiveHistoryClient::send);
        PhoneOverlay.requestRedraw();
    }

    public static void close(String stableId) {
        SESSION.close(stableId);
        PhoneOverlay.requestRedraw();
    }

    public static void refresh() {
        SESSION.refresh().ifPresent(ArchiveHistoryClient::send);
        PhoneOverlay.requestRedraw();
    }

    public static void requestOlder() {
        SESSION.older().ifPresent(ArchiveHistoryClient::send);
        PhoneOverlay.requestRedraw();
    }

    public static void requestNewer() {
        SESSION.newer().ifPresent(ArchiveHistoryClient::send);
        PhoneOverlay.requestRedraw();
    }

    /** 由客户端主线程的网络处理器调用；只有匹配当前请求的响应会被应用。 */
    public static void onResponse(ArchiveHistoryResponse response) {
        if (SESSION.accept(response) == ArchiveHistorySession.Apply.APPLIED) {
            PhoneOverlay.requestRedraw();
        }
    }

    public static boolean isLoading() {
        return SESSION.isLoading();
    }

    public static boolean hasOlder() {
        return SESSION.hasOlder();
    }

    public static boolean hasNewer() {
        return SESSION.hasNewer();
    }

    public static List<ArchiveHistoryRecord> records() {
        return SESSION.records();
    }

    public static ArchiveHistoryStatus status() {
        return SESSION.status();
    }

    public static String activeStableId() {
        return SESSION.stableId();
    }

    private static void send(ArchiveHistoryRequest request) {
        PacketDistributor.sendToServer(new C2SRequestArchiveHistoryPacket(
                request.requestId(), request.maidStableId(), request.expectedServerWorldId(),
                request.beforeSequence(), request.upperSequence(), request.limit()));
    }
}
