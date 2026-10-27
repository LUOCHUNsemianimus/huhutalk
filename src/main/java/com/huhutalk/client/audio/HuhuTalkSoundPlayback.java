package com.huhutalk.client.audio;

import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.init.HuhuTalkSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;

/**
 * HuhuTalk UI 音效的客户端播放实现。
 * <p>
 * 本类持有所有对 {@link Minecraft} / {@link SimpleSoundInstance} 及客户端设置类的引用，
 * 由 {@link HuhuTalkSounds} 的公开方法在确认当前为客户端后转发调用，避免专用服加载客户端类型。
 */
public final class HuhuTalkSoundPlayback {

    /** 界面外通知音的最小触发间隔（毫秒）：每 1.5 秒最多响一次，冷却期间重复直接忽略 */
    private static final long OUTER_NOTIFY_COOLDOWN_MS = 1500L;

    /** 上次界面外通知音播放时间戳 */
    private static volatile long lastOuterNotifyTime = 0L;

    private HuhuTalkSoundPlayback() {
    }

    /** 播放一个本模组的 UI 音效。调用方必须已确认当前为客户端。 */
    public static void play(SoundEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.getSoundManager() == null) {
            return;
        }
        mc.getSoundManager().play(SimpleSoundInstance.forUI(event, 1.0f, 1.0f));
    }

    /** 播放按键音（不受 DND 影响）。 */
    public static void playVoiceHold() {
        play(HuhuTalkSounds.VOICE_HOLD.get());
    }

    /** 播放发送失败音（受 DND 影响，开启免打扰时静音）。 */
    public static void playSendFail() {
        if (HuhuTalkClientSettings.isDndEnabled()) {
            return;
        }
        play(HuhuTalkSounds.SEND_FAIL.get());
    }

    /** 播放界面内接收消息音（预留；受 DND 影响）。 */
    public static void playMsgReceiveUi() {
        if (HuhuTalkClientSettings.isDndEnabled()) {
            return;
        }
        play(HuhuTalkSounds.MSG_RECEIVE_UI.get());
    }

    /** 播放界面外接收消息音（预留；受 DND 影响），带 1.5s 限频。 */
    public static void playMsgReceiveOuter() {
        if (HuhuTalkClientSettings.isDndEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastOuterNotifyTime < OUTER_NOTIFY_COOLDOWN_MS) {
            return;
        }
        lastOuterNotifyTime = now;
        play(HuhuTalkSounds.MSG_RECEIVE_OUTER.get());
    }
}
