package com.huhutalk.network.transport;

import com.huhutalk.network.transport.PacketFragments.Fragment;
import com.huhutalk.network.transport.PacketFragments.Reassembler;
import com.huhutalk.network.transport.PacketFragments.Result;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Forge 1.20.1 原生 {@link SimpleChannel} 承载的共享网络适配层。
 *
 * <p>通道上只注册一个 {@link WirePacket}。36 个业务包按 {@link ResourceLocation}
 * 登记 type/codec/方向/handler；发送时先把业务负载完整编码成 byte[]，再切成
 * 24000 字节以内的片。接收时先按当前 {@link NetworkEvent.Context} 判断方向、
 * 从 ctx 取服务端玩家，然后按 (sender, messageId) 重组，完整且无尾随字节后才调用
 * 原 handler。</p>
 *
 * <p>公共类不 import 任何 {@code net.minecraft.client.*}；客户端玩家 Supplier
 * 由仅客户端注册的 {@code ClientPayloadSetup#registerHandlers()} 设置。</p>
 */
public final class PacketTransport {
    public static final ResourceLocation CHANNEL_NAME = new ResourceLocation("huhutalk", "main");
    public static final String PROTOCOL_VERSION = "forge1201-v1";

    private static final Logger LOGGER = LoggerFactory.getLogger("huhutalk-network");
    private static final Object CLIENT_KEY = new Object();
    private static final Reassembler REASSEMBLER = new Reassembler();

    private static final Map<ResourceLocation, Descriptor<?>> DESCRIPTORS = new LinkedHashMap<>();
    private static final List<ResourceLocation> ORDER = new ArrayList<>();

    private static SimpleChannel channel;
    private static PayloadRegistrar registrar;
    private static volatile Supplier<Player> clientPlayerSupplier;

    private PacketTransport() {
    }

    /**
     * 在模组构造期调用。只登记底层通用 WirePacket；36 个业务描述由
     * {@link PayloadRegistrar} 在共同端按固定顺序登记。
     */
    public static synchronized PayloadRegistrar initCommon() {
        if (channel == null) {
            channel = NetworkRegistry.newSimpleChannel(
                    CHANNEL_NAME,
                    () -> PROTOCOL_VERSION,
                    PROTOCOL_VERSION::equals,
                    PROTOCOL_VERSION::equals);
            channel.registerMessage(0, WirePacket.class, WirePacket::write, WirePacket::read,
                    PacketTransport::onWirePacket);
            registrar = new PayloadRegistrar();
        }
        return registrar;
    }

    public static synchronized PayloadRegistrar registrar() {
        if (registrar == null) {
            throw new IllegalStateException("PacketTransport.initCommon() has not been called");
        }
        return registrar;
    }

    static synchronized <T extends CustomPacketPayload> void registerDescriptor(
            CustomPacketPayload.Type<T> type,
            Direction direction,
            StreamCodec<FriendlyByteBuf, T> codec,
            PacketHandler<T> handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(codec, "codec");
        ResourceLocation id = type.id();
        Descriptor<?> existing = DESCRIPTORS.get(id);
        if (existing != null && existing.direction != direction) {
            throw new IllegalStateException("payload id registered for both directions: " + id);
        }
        if (existing == null) {
            ORDER.add(id);
        }
        DESCRIPTORS.put(id, new Descriptor<>(type, direction, codec, handler));
    }

    public static List<ResourceLocation> registeredOrder() {
        synchronized (PacketTransport.class) {
            return List.copyOf(ORDER);
        }
    }

    /** 仅客户端注册时调用，设置当前客户端的玩家来源。 */
    public static void setClientPlayerSupplier(Supplier<Player> supplier) {
        clientPlayerSupplier = supplier;
    }

    public static void sendToServer(CustomPacketPayload payload) {
        ensureChannel();
        send(payload, Direction.TO_SERVER, wire -> channel.sendToServer(wire));
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        Objects.requireNonNull(player, "player");
        ensureChannel();
        send(payload, Direction.TO_CLIENT,
                wire -> channel.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player), wire));
    }

    public static void sendToAllPlayers(CustomPacketPayload payload) {
        ensureChannel();
        send(payload, Direction.TO_CLIENT,
                wire -> channel.send(net.minecraftforge.network.PacketDistributor.ALL.noArg(), wire));
    }

    private static void ensureChannel() {
        if (channel == null) {
            initCommon();
        }
    }

    private static void send(CustomPacketPayload payload, Direction required, Consumer<WirePacket> sender) {
        Objects.requireNonNull(payload, "payload");
        byte[] bytes;
        try {
            bytes = encodePayload(payload, required);
        } catch (RuntimeException exception) {
            LOGGER.warn("[HuhuTalk] refused to send payload {}: {}", payload.type().id(), exception.toString());
            LOGGER.debug("[HuhuTalk] payload encode failure", exception);
            return;
        }

        List<Fragment> fragments;
        try {
            fragments = PacketFragments.split(payload.type().id().toString(), UUID.randomUUID(), bytes);
        } catch (RuntimeException exception) {
            LOGGER.warn("[HuhuTalk] refused to fragment payload {}: {}",
                    payload.type().id(), exception.toString());
            return;
        }

        for (Fragment fragment : fragments) {
            WirePacket wire = new WirePacket(
                    new ResourceLocation(payload.type().id().getNamespace(), payload.type().id().getPath()),
                    fragment.messageId(), fragment.totalLength(), fragment.partIndex(),
                    fragment.partCount(), fragment.data());
            try {
                sender.accept(wire);
            } catch (RuntimeException exception) {
                LOGGER.warn("[HuhuTalk] failed to send fragment {}/{} of {}: {}",
                        fragment.partIndex(), fragment.partCount(), payload.type().id(), exception.toString());
                LOGGER.debug("[HuhuTalk] fragment send failure", exception);
                return;
            }
        }
    }

    private static byte[] encodePayload(CustomPacketPayload payload, Direction required) {
        Descriptor<?> descriptor = DESCRIPTORS.get(payload.type().id());
        if (descriptor == null) {
            throw new IllegalArgumentException("unregistered payload id " + payload.type().id());
        }
        if (descriptor.direction != required) {
            throw new IllegalArgumentException("payload " + payload.type().id()
                    + " is registered for " + descriptor.direction + ", not " + required);
        }
        return encodeCaptured(descriptor, payload);
    }

    private static <T extends CustomPacketPayload> byte[] encodeCaptured(
            Descriptor<T> descriptor, CustomPacketPayload payload) {
        T typed = castPayload(payload);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            descriptor.codec.encode(buffer, typed);
            int size = buffer.readableBytes();
            if (size > PacketFragments.MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException("encoded payload exceeds " + PacketFragments.MAX_TOTAL_BYTES
                        + " bytes: " + size);
            }
            byte[] bytes = new byte[size];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> T castPayload(CustomPacketPayload payload) {
        return (T) payload;
    }

    private static void onWirePacket(WirePacket wire, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier == null ? null : contextSupplier.get();
        if (context == null) {
            LOGGER.warn("[HuhuTalk] dropped wire packet without a network context");
            return;
        }
        try {
            context.enqueueWork(() -> handleWire(wire, context));
        } finally {
            context.setPacketHandled(true);
        }
    }

    private static void handleWire(WirePacket wire, NetworkEvent.Context context) {
        NetworkDirection networkDirection = context.getDirection();
        Direction expected;
        Object senderKey;
        if (networkDirection == NetworkDirection.PLAY_TO_SERVER) {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                LOGGER.warn("[HuhuTalk] dropped serverbound packet without an authenticated sender");
                return;
            }
            expected = Direction.TO_SERVER;
            senderKey = sender.getUUID();
        } else if (networkDirection == NetworkDirection.PLAY_TO_CLIENT) {
            expected = Direction.TO_CLIENT;
            senderKey = CLIENT_KEY;
        } else {
            LOGGER.warn("[HuhuTalk] dropped wire packet on unsupported direction {}", networkDirection);
            return;
        }

        Descriptor<?> descriptor = DESCRIPTORS.get(wire.payloadId());
        if (descriptor == null) {
            LOGGER.warn("[HuhuTalk] dropped packet for unregistered payload id {}", wire.payloadId());
            return;
        }
        if (descriptor.direction != expected) {
            LOGGER.warn("[HuhuTalk] dropped packet {} on wrong direction {}", wire.payloadId(), networkDirection);
            return;
        }

        Fragment fragment = new Fragment(wire.payloadId().toString(), wire.messageId(),
                wire.totalLength(), wire.partIndex(), wire.partCount(), wire.data());
        Result result = REASSEMBLER.accept(senderKey, fragment, System.nanoTime() / 1_000_000L);
        switch (result.status()) {
            case COMPLETE -> dispatch(descriptor, result.payload(), context);
            case REJECTED -> LOGGER.warn("[HuhuTalk] rejected packet {}: {}", wire.payloadId(), result.reason());
            case ACCEPTED, DUPLICATE -> {
                // 等待后续分片；重复片不增加缓冲，也不重复调用业务。
            }
        }
    }

    private static void dispatch(Descriptor<?> descriptor, byte[] payloadBytes, NetworkEvent.Context context) {
        dispatchCaptured(descriptor, payloadBytes, context);
    }

    private static <T extends CustomPacketPayload> void dispatchCaptured(
            Descriptor<T> descriptor, byte[] payloadBytes, NetworkEvent.Context context) {
        if (descriptor.handler == null) {
            LOGGER.warn("[HuhuTalk] no handler registered yet for payload {}", descriptor.id);
            return;
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payloadBytes));
        T payload;
        try {
            payload = descriptor.codec.decode(buffer);
            if (payload == null) {
                LOGGER.warn("[HuhuTalk] codec decoded null payload for {}", descriptor.id);
                return;
            }
            if (buffer.isReadable()) {
                LOGGER.warn("[HuhuTalk] codec left {} trailing bytes for {}",
                        buffer.readableBytes(), descriptor.id);
                return;
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("[HuhuTalk] malformed payload {}: {}", descriptor.id, exception.toString());
            LOGGER.debug("[HuhuTalk] payload decode failure", exception);
            return;
        } finally {
            buffer.release();
        }
        descriptor.handler.handle(payload, new PayloadContext(context));
    }

    /** 处理来自当前 ctx 的反向回复；使用同一连接/方向，不自行推断 owner。 */
    private static void reply(NetworkEvent.Context context, CustomPacketPayload payload) {
        Direction replyDirection = context.getDirection() == NetworkDirection.PLAY_TO_SERVER
                ? Direction.TO_CLIENT : Direction.TO_SERVER;
        byte[] bytes;
        try {
            bytes = encodePayload(payload, replyDirection);
        } catch (RuntimeException exception) {
            LOGGER.warn("[HuhuTalk] refused to reply with payload {}: {}",
                    payload.type().id(), exception.toString());
            return;
        }
        List<Fragment> fragments;
        try {
            fragments = PacketFragments.split(payload.type().id().toString(), UUID.randomUUID(), bytes);
        } catch (RuntimeException exception) {
            LOGGER.warn("[HuhuTalk] refused to fragment reply {}: {}",
                    payload.type().id(), exception.toString());
            return;
        }
        for (Fragment fragment : fragments) {
            WirePacket wire = new WirePacket(payload.type().id(), fragment.messageId(),
                    fragment.totalLength(), fragment.partIndex(), fragment.partCount(), fragment.data());
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            boolean handedOff = false;
            try {
                channel.encodeMessage(wire, buffer);
                if (buffer.readableBytes() > WirePacket.MAX_WIRE_BYTES) {
                    LOGGER.warn("[HuhuTalk] reply wire packet exceeds {} bytes: {}",
                            WirePacket.MAX_WIRE_BYTES, buffer.readableBytes());
                    return;
                }
                context.getPacketDispatcher().sendPacket(CHANNEL_NAME, buffer);
                handedOff = true;
            } catch (RuntimeException exception) {
                LOGGER.warn("[HuhuTalk] failed to send reply fragment of {}: {}",
                        payload.type().id(), exception.toString());
                return;
            } finally {
                if (!handedOff) {
                    buffer.release();
                }
            }
        }
    }

    /** 服务端/客户端 tick 调用；清理超过 30 秒的分组并释放预算。 */
    public static void tick() {
        int removed = REASSEMBLER.expire(System.nanoTime() / 1_000_000L);
        if (removed > 0) {
            LOGGER.debug("[HuhuTalk] expired {} partial network payloads", removed);
        }
    }

    public static void clearServerPlayer(UUID playerId) {
        if (playerId != null) {
            REASSEMBLER.clearSender(playerId);
        }
    }

    public static void resetClient() {
        REASSEMBLER.clearSender(CLIENT_KEY);
    }

    public static void clearAll() {
        REASSEMBLER.clearAll();
    }

    public enum Direction {
        TO_SERVER,
        TO_CLIENT
    }

    private static final class Descriptor<T extends CustomPacketPayload> {
        private final ResourceLocation id;
        private final Direction direction;
        private final StreamCodec<FriendlyByteBuf, T> codec;
        private final PacketHandler<T> handler;

        private Descriptor(CustomPacketPayload.Type<T> type, Direction direction,
                           StreamCodec<FriendlyByteBuf, T> codec, PacketHandler<T> handler) {
            this.id = type.id();
            this.direction = direction;
            this.codec = codec;
            this.handler = handler;
        }
    }

    private static final class PayloadContext implements IPayloadContext {
        private final NetworkEvent.Context context;

        private PayloadContext(NetworkEvent.Context context) {
            this.context = context;
        }

        @Override
        public Player player() {
            if (context.getDirection() == NetworkDirection.PLAY_TO_SERVER) {
                return context.getSender();
            }
            Supplier<Player> supplier = clientPlayerSupplier;
            return supplier == null ? null : supplier.get();
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> enqueueWork(Runnable runnable) {
            return context.enqueueWork(runnable);
        }

        @Override
        public void reply(CustomPacketPayload payload) {
            PacketTransport.reply(context, payload);
        }
    }
}
