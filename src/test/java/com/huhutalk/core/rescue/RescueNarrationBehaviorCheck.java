package com.huhutalk.core.rescue;

import com.huhutalk.core.chat.MaidNarrativeBus;

import java.util.UUID;

/**
 * 无 JUnit 的救援叙事偏好检查（由上游直接用 javac/java 运行 main）。
 *
 * <p>只验证真实策略 {@link RescueSettings#shouldNarrate(UUID, MaidNarrativeBus.Kind)}
 * 与 {@link RescueSettings} 的会话状态：默认开启、关闭时三种救援事件全部禁播而其他事件保留、
 * 开关切换后同一种类立即生效、两个玩家互不影响、null UUID 下救援事件禁播、clear / clearAll 回到默认。
 *
 * <p><b>这不是游戏内复活验收</b>：实际死亡、药水消耗、死亡点复活与队列行为仍需在隔离客户端测试。
 */
public final class RescueNarrationBehaviorCheck {

    private static final UUID OWNER_A =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_B =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final MaidNarrativeBus.Kind[] RESCUE_KINDS = {
            MaidNarrativeBus.Kind.RESCUE,
            MaidNarrativeBus.Kind.RESCUE_LOW_POTION,
            MaidNarrativeBus.Kind.RESCUE_STREAK_SUMMARY
    };

    private static final MaidNarrativeBus.Kind[] OTHER_KINDS = {
            MaidNarrativeBus.Kind.SUPPLY_MODE_SWITCH,
            MaidNarrativeBus.Kind.SUPPLY_USED_UP,
            MaidNarrativeBus.Kind.SUPPLY_DIED
    };

    private static int passed;

    private RescueNarrationBehaviorCheck() {
    }

    public static void main(String[] args) {
        try {
            RescueSettings.clearAll();
            defaultEnabledChecks();
            disabledChecks();
            toggleChecks();
            nullOwnerChecks();
            ownerIsolationChecks();
            clearChecks();
            System.out.println("ALL PASS (" + passed + " checks)");
        } catch (Throwable throwable) {
            System.err.println("FAIL: " + throwable.getMessage());
            throwable.printStackTrace();
            System.exit(1);
        }
    }

    /** 没有记录时默认允许三种救援叙事，且 isEnabled 兼容返回 true。 */
    private static void defaultEnabledChecks() {
        RescueSettings.clearAll();
        checkEquals("fresh owner isEnabled defaults to true", true,
                RescueSettings.isEnabled(OWNER_A));
        for (MaidNarrativeBus.Kind kind : RESCUE_KINDS) {
            checkEquals("fresh owner narrates " + kind, true,
                    RescueSettings.shouldNarrate(OWNER_A, kind));
        }
        for (MaidNarrativeBus.Kind kind : OTHER_KINDS) {
            checkEquals("fresh owner narrates non-rescue " + kind, true,
                    RescueSettings.shouldNarrate(OWNER_A, kind));
        }
    }

    /** 关闭后只禁三种救援事件，其他事件一律保留。 */
    private static void disabledChecks() {
        RescueSettings.clearAll();
        RescueSettings.set(OWNER_A, false);
        checkEquals("disabled owner isEnabled is false", false,
                RescueSettings.isEnabled(OWNER_A));
        for (MaidNarrativeBus.Kind kind : RESCUE_KINDS) {
            checkEquals("disabled owner suppresses " + kind, false,
                    RescueSettings.shouldNarrate(OWNER_A, kind));
        }
        for (MaidNarrativeBus.Kind kind : OTHER_KINDS) {
            checkEquals("disabled owner keeps non-rescue " + kind, true,
                    RescueSettings.shouldNarrate(OWNER_A, kind));
        }
    }

    /** 开关切换后同一 kind 立即改判：不依赖提交时快照，关后已排队事件也会按当前偏好丢弃。 */
    private static void toggleChecks() {
        RescueSettings.clearAll();
        for (MaidNarrativeBus.Kind kind : RESCUE_KINDS) {
            RescueSettings.set(OWNER_A, true);
            checkEquals("toggle on allows " + kind, true,
                    RescueSettings.shouldNarrate(OWNER_A, kind));
            RescueSettings.set(OWNER_A, false);
            checkEquals("on->off suppresses " + kind + " immediately", false,
                    RescueSettings.shouldNarrate(OWNER_A, kind));
            RescueSettings.set(OWNER_A, true);
            checkEquals("off->on allows " + kind + " immediately", true,
                    RescueSettings.shouldNarrate(OWNER_A, kind));
        }

        // 救援叙事关闭时，混在队列里的普通补给/模式事件仍必须放行。
        RescueSettings.set(OWNER_A, false);
        for (MaidNarrativeBus.Kind kind : OTHER_KINDS) {
            checkEquals("disabled owner keeps mixed non-rescue " + kind, true,
                    RescueSettings.shouldNarrate(OWNER_A, kind));
        }
    }

    /** UUID 缺失（玩家引用已失效）时救援事件一律不播，其他事件仍保留。 */
    private static void nullOwnerChecks() {
        RescueSettings.clearAll();
        for (MaidNarrativeBus.Kind kind : RESCUE_KINDS) {
            checkEquals("null owner suppresses " + kind, false,
                    RescueSettings.shouldNarrate(null, kind));
        }
        for (MaidNarrativeBus.Kind kind : OTHER_KINDS) {
            checkEquals("null owner keeps non-rescue " + kind, true,
                    RescueSettings.shouldNarrate(null, kind));
        }
    }

    /** 偏好按 UUID 隔离：改一个玩家不能影响另一个。 */
    private static void ownerIsolationChecks() {
        RescueSettings.clearAll();
        RescueSettings.set(OWNER_A, false);
        RescueSettings.set(OWNER_B, true);
        checkEquals("owner A stays off", false,
                RescueSettings.shouldNarrate(OWNER_A, MaidNarrativeBus.Kind.RESCUE));
        checkEquals("owner B stays on", true,
                RescueSettings.shouldNarrate(OWNER_B, MaidNarrativeBus.Kind.RESCUE));

        RescueSettings.set(OWNER_A, true);
        checkEquals("turning A on does not change B", true,
                RescueSettings.shouldNarrate(OWNER_B, MaidNarrativeBus.Kind.RESCUE));

        RescueSettings.set(OWNER_B, false);
        checkEquals("turning B off does not change A", true,
                RescueSettings.shouldNarrate(OWNER_A, MaidNarrativeBus.Kind.RESCUE));
        checkEquals("owner B is off", false,
                RescueSettings.shouldNarrate(OWNER_B, MaidNarrativeBus.Kind.RESCUE));
    }

    /** clear 只移除单个玩家；clearAll 全清后回到默认 true。 */
    private static void clearChecks() {
        RescueSettings.clearAll();
        RescueSettings.set(OWNER_A, false);
        RescueSettings.set(OWNER_B, false);

        RescueSettings.clear(OWNER_A);
        checkEquals("clear(A) restores A default", true,
                RescueSettings.shouldNarrate(OWNER_A, MaidNarrativeBus.Kind.RESCUE));
        checkEquals("clear(A) leaves B off", false,
                RescueSettings.shouldNarrate(OWNER_B, MaidNarrativeBus.Kind.RESCUE));

        RescueSettings.clearAll();
        checkEquals("clearAll restores A default", true,
                RescueSettings.shouldNarrate(OWNER_A, MaidNarrativeBus.Kind.RESCUE));
        checkEquals("clearAll restores B default", true,
                RescueSettings.shouldNarrate(OWNER_B, MaidNarrativeBus.Kind.RESCUE));
        for (MaidNarrativeBus.Kind kind : RESCUE_KINDS) {
            checkEquals("clearAll restores " + kind, true,
                    RescueSettings.shouldNarrate(OWNER_A, kind));
        }
    }

    private static void checkEquals(String label, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            passed++;
            return;
        }
        throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
    }
}
