package com.huhutalk.init;

import com.huhutalk.HuhuTalk;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组音效注册与播放工具。
 * <p>
 * 提供狐狐手机的 UI 提示音：按住语音按键音、发送失败音、以及（预留）消息接收音。
 * 客户端专用——GUI 音效用 {@link SimpleSoundInstance#forUI} 播放，无需三维位置。
 * <p>
 * <b>DND（消息免打扰）联动</b>：接收音 / 发送失败音受 {@link HuhuTalkClientSettings#isDndEnabled()} 控制，
 * 开启免打扰时静音；按键音不受影响（玩家按下说话键的即时反馈不应被静音）。
 * <p>
 * 接收音（界面内外）已预留注册与常量，本次先不接入实际调用点，后续实现消息接收时启用。
 */
public final class HuhuTalkSounds {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, HuhuTalk.MOD_ID);

    /** 按住语音按钮 / 快捷语音输入键的按键音（不受 DND 影响） */
    public static final DeferredHolder<SoundEvent, SoundEvent> VOICE_HOLD = register("voice_hold");
    /** 消息发送失败（红色感叹号）音（受 DND 影响） */
    public static final DeferredHolder<SoundEvent, SoundEvent> SEND_FAIL = register("send_fail");
    /** 界面内接收消息音（预留，受 DND 影响） */
    public static final DeferredHolder<SoundEvent, SoundEvent> MSG_RECEIVE_UI = register("msg_receive_ui");
    /** 界面外（非聊天页）接收消息音（预留，受 DND 影响） */
    public static final DeferredHolder<SoundEvent, SoundEvent> MSG_RECEIVE_OUTER = register("msg_receive_outer");

    private HuhuTalkSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(HuhuTalk.MOD_ID, name);
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(loc));
    }

    /** 播放一个本模组的 UI 音效（仅在客户端可用）。 */
    public static void play(SoundEvent event) {
        if (FMLEnvironment.dist.isDedicatedServer()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.getSoundManager() == null) {
            return;
        }
        mc.getSoundManager().play(SimpleSoundInstance.forUI(event, 1.0f, 1.0f));
    }

    /** 播放按键音（不受 DND 影响） */
    public static void playVoiceHold() {
        play(VOICE_HOLD.get());
    }

    /** 播放发送失败音（受 DND 影响，开启免打扰时静音） */
    public static void playSendFail() {
        if (HuhuTalkClientSettings.isDndEnabled()) {
            return;
        }
        play(SEND_FAIL.get());
    }

    /** 播放界面内接收消息音（预留；受 DND 影响） */
    public static void playMsgReceiveUi() {
        if (HuhuTalkClientSettings.isDndEnabled()) {
            return;
        }
        play(MSG_RECEIVE_UI.get());
    }

    /** 界面外通知音的最小触发间隔（毫秒）：每 1.5 秒最多响一次，冷却期间重复直接忽略 */
    private static final long OUTER_NOTIFY_COOLDOWN_MS = 1500L;

    /** 上次界面外通知音播放时间戳 */
    private static volatile long lastOuterNotifyTime = 0L;

    /** 播放界面外接收消息音（预留；受 DND 影响），带 1.5s 限频，冷却期间重复调用直接忽略 */
    public static void playMsgReceiveOuter() {
        if (HuhuTalkClientSettings.isDndEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastOuterNotifyTime < OUTER_NOTIFY_COOLDOWN_MS) {
            return;
        }
        lastOuterNotifyTime = now;
        play(MSG_RECEIVE_OUTER.get());
    }
}
