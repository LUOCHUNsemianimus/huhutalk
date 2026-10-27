package com.huhutalk.init;

import com.huhutalk.HuhuTalk;
import com.huhutalk.client.audio.HuhuTalkSoundPlayback;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 本模组音效注册与公开播放入口。
 * <p>
 * 提供狐狐手机的 UI 提示音：按住语音按键音、发送失败音、以及（预留）消息接收音。
 * 类本身保持双侧安全：只持有原版音效注册，播放实现放在客户端专用类中。
 * <p>
 * <b>DND（消息免打扰）联动</b>：接收音 / 发送失败音受客户端设置控制，
 * 开启免打扰时静音；按键音不受影响（玩家按下说话键的即时反馈不应被静音）。
 * <p>
 * 接收音（界面内外）已预留注册与常量，本次先不接入实际调用点，后续实现消息接收时启用。
 */
public final class HuhuTalkSounds {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, HuhuTalk.MOD_ID);

    /** 按住语音按钮 / 快捷语音输入键的按键音（不受 DND 影响） */
    public static final RegistryObject<SoundEvent> VOICE_HOLD = register("voice_hold");
    /** 消息发送失败（红色感叹号）音（受 DND 影响） */
    public static final RegistryObject<SoundEvent> SEND_FAIL = register("send_fail");
    /** 界面内接收消息音（预留，受 DND 影响） */
    public static final RegistryObject<SoundEvent> MSG_RECEIVE_UI = register("msg_receive_ui");
    /** 界面外（非聊天页）接收消息音（预留，受 DND 影响） */
    public static final RegistryObject<SoundEvent> MSG_RECEIVE_OUTER = register("msg_receive_outer");

    private HuhuTalkSounds() {
    }

    private static RegistryObject<SoundEvent> register(String name) {
        ResourceLocation loc = new ResourceLocation(HuhuTalk.MOD_ID, name);
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(loc));
    }

    /** 播放一个本模组的 UI 音效（仅在客户端可用）。 */
    public static void play(SoundEvent event) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        HuhuTalkSoundPlayback.play(event);
    }

    /** 播放按键音（不受 DND 影响） */
    public static void playVoiceHold() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        HuhuTalkSoundPlayback.playVoiceHold();
    }

    /** 播放发送失败音（受 DND 影响，开启免打扰时静音） */
    public static void playSendFail() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        HuhuTalkSoundPlayback.playSendFail();
    }

    /** 播放界面内接收消息音（预留；受 DND 影响） */
    public static void playMsgReceiveUi() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        HuhuTalkSoundPlayback.playMsgReceiveUi();
    }

    /** 播放界面外接收消息音（预留；受 DND 影响），带 1.5s 限频，冷却期间重复调用直接忽略 */
    public static void playMsgReceiveOuter() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        HuhuTalkSoundPlayback.playMsgReceiveOuter();
    }
}
