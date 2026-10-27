package com.huhutalk.network.transport;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * 本项目自己的网络负载标记接口。
 *
 * <p>它不是 Minecraft/Forge 原生 {@code CustomPacketPayload}。保留这个简单类名，
 * 是为了让原有 36 个包的字段声明和 {@code Type} 内部类写法只需替换 import，
 * 而不必把 36 个业务包改成另一套描述方式。</p>
 */
public interface CustomPacketPayload {
    Type<? extends CustomPacketPayload> type();

    /**
     * 负载类型标识。真正发送时，{@code PacketTransport} 只按这个 id 查登记表，
     * 不为每个 Type 额外生成 Class 反射或独立通道。
     */
    final class Type<T extends CustomPacketPayload> {
        private final ResourceLocation id;

        public Type(ResourceLocation id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        public ResourceLocation id() {
            return id;
        }

        @Override
        public String toString() {
            return id.toString();
        }
    }
}
