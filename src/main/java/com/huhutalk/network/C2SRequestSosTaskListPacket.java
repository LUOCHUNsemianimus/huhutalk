package com.huhutalk.network;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 → 服务端：请求当前可用的女仆工作模式列表。
 * <p>服务端枚举所有非隐藏任务（{@link TaskManager#getTaskIndex()}）回推，
 * 客户端在 SOS 绑定页据此渲染工作模式选择器。兼容新增攻击模式的 mod。
 */
public record C2SRequestSosTaskListPacket() implements CustomPacketPayload {
    public static final Type<C2SRequestSosTaskListPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "request_sos_task_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRequestSosTaskListPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> {}, buf -> new C2SRequestSosTaskListPacket());

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SRequestSosTaskListPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            List<String> uids = new ArrayList<>();
            for (IMaidTask task : TaskManager.getTaskIndex()) {
                // 跳过隐藏任务；个别任务在 null 女仆下 isHidden 会抛异常，防御性兜底
                try {
                    if (task.isHidden(null)) {
                        continue;
                    }
                } catch (RuntimeException e) {
                    // 保守视为不隐藏（保留在可选列表）
                }
                uids.add(task.getUid().toString());
            }
            context.reply(new S2CSosTaskListPacket(uids));
        });
    }
}
