package com.huhutalk.network.transport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * 纯 Java 行为检查，不依赖 Minecraft/Forge/Netty。
 *
 * <p>上游可直接用 {@code java -cp <test classes> com.huhutalk.network.transport.PacketFragmentsBehaviorCheck}
 * 运行。退出码 0 表示全部断言通过。</p>
 */
public final class PacketFragmentsBehaviorCheck {
    private static int checks;

    private PacketFragmentsBehaviorCheck() {
    }

    public static void main(String[] args) {
        try {
            oneMegabyteOutOfOrderRoundTrip();
            differentSendersAreIsolated();
            missingFragmentDoesNotDeliver();
            identicalDuplicateDoesNotGrowBudget();
            conflictingDuplicateReleasesGroup();
            metadataConflictReleasesGroup();
            activeGroupLimitPerSender();
            globalByteBudget();
            zeroLengthPayload();
            clearSenderAndClearAll();
            invalidMetadataRejectedBeforeAllocation();
            invalidFragmentReleasesOnlyMatchingGroup();
            oversizedPayloadCannotBeSplit();
            System.out.println("PacketFragmentsBehaviorCheck OK (" + checks + " checks)");
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            System.exit(1);
        }
    }

    private static void oneMegabyteOutOfOrderRoundTrip() {
        byte[] payload = payload(1_000_000, 17);
        UUID messageId = UUID.randomUUID();
        List<PacketFragments.Fragment> fragments =
                PacketFragments.split("huhutalk:maid_image_chat", messageId, payload);

        check(fragments.size() == 42, "1MB image should split into 42 fragments, got " + fragments.size());
        for (PacketFragments.Fragment fragment : fragments) {
            check(fragment.totalLength() == payload.length, "total length must be preserved");
            check(fragment.partCount() == fragments.size(), "part count must be consistent");
            check(fragment.data().length <= PacketFragments.MAX_FRAGMENT_BYTES,
                    "fragment exceeds 24000 bytes");
            check(fragment.data().length
                            == PacketFragments.expectedPartLength(payload.length, fragment.partIndex()),
                    "fragment length must match its index");
        }

        List<PacketFragments.Fragment> shuffled = new ArrayList<>(fragments);
        Collections.shuffle(shuffled, new Random(0x5EED));
        PacketFragments.Reassembler reassembler = new PacketFragments.Reassembler();
        PacketFragments.Result last = null;
        for (PacketFragments.Fragment fragment : shuffled) {
            last = reassembler.accept("player-a", fragment, 1_000L);
        }
        check(last != null && last.complete(), "out-of-order fragments should complete");
        check(Arrays.equals(last.payload(), payload), "reassembled bytes must equal the original");
        check(reassembler.activeAssemblies() == 0, "completed group must leave no active assembly");
        check(reassembler.bufferedBytes() == 0L, "completed group must release all budget");
    }

    private static void differentSendersAreIsolated() {
        byte[] payloadA = payload(50_000, 1);
        byte[] payloadB = payload(50_000, 2);
        UUID sameMessageId = UUID.randomUUID();
        List<PacketFragments.Fragment> a =
                PacketFragments.split("huhutalk:a", sameMessageId, payloadA);
        List<PacketFragments.Fragment> b =
                PacketFragments.split("huhutalk:b", sameMessageId, payloadB);

        PacketFragments.Reassembler reassembler = new PacketFragments.Reassembler();
        PacketFragments.Result lastA = null;
        PacketFragments.Result lastB = null;
        for (int i = 0; i < a.size(); i++) {
            lastA = reassembler.accept("player-a", a.get(i), 2_000L);
            lastB = reassembler.accept("player-b", b.get(i), 2_000L);
        }
        check(lastA != null && lastA.complete(), "player A should complete");
        check(lastB != null && lastB.complete(), "player B should complete");
        check(Arrays.equals(lastA.payload(), payloadA), "player A payload must not mix with B");
        check(Arrays.equals(lastB.payload(), payloadB), "player B payload must not mix with A");
    }

    private static void missingFragmentDoesNotDeliver() {
        byte[] payload = payload(50_000, 3);
        List<PacketFragments.Fragment> fragments =
                PacketFragments.split("huhutalk:missing", UUID.randomUUID(), payload);
        PacketFragments.Reassembler reassembler = new PacketFragments.Reassembler();

        check(reassembler.accept("p", fragments.get(0), 3_000L).status()
                == PacketFragments.Status.ACCEPTED, "first part accepted");
        check(reassembler.accept("p", fragments.get(2), 3_000L).status()
                == PacketFragments.Status.ACCEPTED, "last part accepted without the middle");
        check(reassembler.activeAssemblies() == 1, "incomplete group stays active");
        check(reassembler.accept("p", fragments.get(0), 3_000L).status()
                == PacketFragments.Status.DUPLICATE, "duplicate still does not complete");

        int expired = reassembler.expire(3_000L + PacketFragments.TIMEOUT_MILLIS);
        check(expired == 1, "timeout should remove the incomplete group");
        check(reassembler.activeAssemblies() == 0, "timeout leaves no active group");
        check(reassembler.bufferedBytes() == 0L, "timeout releases budget");
    }

    private static void identicalDuplicateDoesNotGrowBudget() {
        byte[] payload = payload(50_000, 4);
        List<PacketFragments.Fragment> fragments =
                PacketFragments.split("huhutalk:dup", UUID.randomUUID(), payload);
        PacketFragments.Reassembler reassembler = new PacketFragments.Reassembler();
        check(reassembler.accept("p", fragments.get(0), 4_000L).status()
                == PacketFragments.Status.ACCEPTED, "first part accepted");
        long bytesAfterFirst = reassembler.bufferedBytes();
        check(reassembler.accept("p", fragments.get(0), 4_000L).status()
                == PacketFragments.Status.DUPLICATE, "identical duplicate is not an error");
        check(reassembler.bufferedBytes() == bytesAfterFirst, "duplicate must not increase budget");

        PacketFragments.Result result = null;
        for (int i = 1; i < fragments.size(); i++) {
            result = reassembler.accept("p", fragments.get(i), 4_000L);
        }
        check(result != null && result.complete(), "group completes after duplicate");
        check(Arrays.equals(result.payload(), payload), "payload survives duplicate");
    }

    private static void conflictingDuplicateReleasesGroup() {
        byte[] payload = payload(50_000, 5);
        List<PacketFragments.Fragment> fragments =
                PacketFragments.split("huhutalk:conflict", UUID.randomUUID(), payload);
        PacketFragments.Fragment first = fragments.get(0);
        PacketFragments.Reassembler reassembler = new PacketFragments.Reassembler();
        check(reassembler.accept("p", first, 5_000L).status()
                == PacketFragments.Status.ACCEPTED, "first part accepted");

        byte[] different = first.data().clone();
        different[0] ^= 0x7F;
        PacketFragments.Fragment conflict = new PacketFragments.Fragment(
                first.payloadId(), first.messageId(), first.totalLength(),
                first.partIndex(), first.partCount(), different);
        PacketFragments.Result rejected = reassembler.accept("p", conflict, 5_000L);
        check(rejected.status() == PacketFragments.Status.REJECTED, "conflicting duplicate rejected");
        check(reassembler.activeAssemblies() == 0, "conflicting group is released");
        check(reassembler.bufferedBytes() == 0L, "conflicting group releases budget");
    }

    private static void metadataConflictReleasesGroup() {
        byte[] payload = payload(50_000, 6);
        List<PacketFragments.Fragment> fragments =
                PacketFragments.split("huhutalk:meta-a", UUID.randomUUID(), payload);
        PacketFragments.Reassembler reassembler = new PacketFragments.Reassembler();
        check(reassembler.accept("p", fragments.get(0), 6_000L).status()
                == PacketFragments.Status.ACCEPTED, "first part accepted");

        PacketFragments.Fragment sameLengthOtherPayload = new PacketFragments.Fragment(
                "huhutalk:meta-b", fragments.get(1).messageId(), fragments.get(1).totalLength(),
                fragments.get(1).partIndex(), fragments.get(1).partCount(), fragments.get(1).data());
        PacketFragments.Result rejected = reassembler.accept("p", sameLengthOtherPayload, 6_000L);
        check(rejected.status() == PacketFragments.Status.REJECTED, "metadata conflict rejected");
        check(reassembler.activeAssemblies() == 0, "metadata conflict releases the group");
        check(reassembler.bufferedBytes() == 0L, "metadata conflict releases budget");
    }

    private static void activeGroupLimitPerSender() {
        PacketFragments.Reassembler reassembler = new PacketFragments.Reassembler();
        for (int i = 0; i < PacketFragments.MAX_ACTIVE_PER_SENDER; i++) {
            List<PacketFragments.Fragment> fragments = PacketFragments.split(
                    "huhutalk:active-" + i, UUID.randomUUID(), payload(50_000, 10 + i));
            check(reassembler.accept("p", fragments.get(0), 7_000L).status()
                    == PacketFragments.Status.ACCEPTED, "active group " + i + " accepted");
        }
        List<PacketFragments.Fragment> extra = PacketFragments.split(
                "huhutalk:active-extra", UUID.randomUUID(), payload(50_000, 20));
        check(reassembler.accept("p", extra.get(0), 7_000L).status()
                == PacketFragments.Status.REJECTED, "third active group rejected");
        check(reassembler.activeForSender("p") == PacketFragments.MAX_ACTIVE_PER_SENDER,
                "active count stays at limit");
        reassembler.clearAll();
    }

    private static void globalByteBudget() {
        PacketFragments.Reassembler reassembler = new PacketFragments.Reassembler();
        int senders = (int) (PacketFragments.MAX_GLOBAL_BYTES / PacketFragments.MAX_TOTAL_BYTES);
        for (int i = 0; i < senders; i++) {
            List<PacketFragments.Fragment> fragments = PacketFragments.split(
                    "huhutalk:global-" + i, UUID.randomUUID(), new byte[PacketFragments.MAX_TOTAL_BYTES]);
            check(reassembler.accept("sender-" + i, fragments.get(0), 8_000L).status()
                    == PacketFragments.Status.ACCEPTED, "global sender " + i + " accepted");
        }
        check(reassembler.bufferedBytes() == PacketFragments.MAX_GLOBAL_BYTES,
                "global budget should be fully reserved");
        check(reassembler.senderBudgetCount() == senders,
                "each accepted sender owns one budget entry");
        List<PacketFragments.Fragment> extra = PacketFragments.split(
                "huhutalk:global-extra", UUID.randomUUID(), new byte[PacketFragments.MAX_TOTAL_BYTES]);
        check(reassembler.accept("sender-extra", extra.get(0), 8_000L).status()
                == PacketFragments.Status.REJECTED, "global over-budget sender rejected");
        check(reassembler.senderBudgetCount() == senders,
                "rejected sender must not leave an empty budget entry");
        check(reassembler.activeForSender("sender-extra") == 0,
                "rejected sender has no active group");
        reassembler.clearAll();
        check(reassembler.bufferedBytes() == 0L, "clearAll releases global budget");
    }

    private static void zeroLengthPayload() {
        List<PacketFragments.Fragment> fragments =
                PacketFragments.split("huhutalk:empty", UUID.randomUUID(), new byte[0]);
        check(fragments.size() == 1, "zero-length payload has one fragment");
        check(fragments.get(0).data().length == 0, "zero-length fragment is empty");
        PacketFragments.Reassembler reassembler = new PacketFragments.Reassembler();
        PacketFragments.Result result = reassembler.accept("p", fragments.get(0), 9_000L);
        check(result.complete(), "zero-length payload completes");
        check(result.payload().length == 0, "zero-length result is empty");
    }

    private static void clearSenderAndClearAll() {
        PacketFragments.Reassembler reassembler = new PacketFragments.Reassembler();
        List<PacketFragments.Fragment> a =
                PacketFragments.split("huhutalk:clear-a", UUID.randomUUID(), payload(50_000, 30));
        List<PacketFragments.Fragment> b =
                PacketFragments.split("huhutalk:clear-b", UUID.randomUUID(), payload(50_000, 31));
        reassembler.accept("a", a.get(0), 10_000L);
        reassembler.accept("b", b.get(0), 10_000L);
        check(reassembler.clearSender("a") == 1, "clearSender removes only sender a");
        check(reassembler.activeForSender("a") == 0, "sender a has no active group");
        check(reassembler.activeForSender("b") == 1, "sender b is untouched");
        reassembler.clearAll();
        check(reassembler.activeAssemblies() == 0, "clearAll removes all groups");
        check(reassembler.bufferedBytes() == 0L, "clearAll resets global bytes");
    }

    private static void invalidMetadataRejectedBeforeAllocation() {
        PacketFragments.Reassembler reassembler = new PacketFragments.Reassembler();
        UUID id = UUID.randomUUID();
        check(reassembler.accept("p", new PacketFragments.Fragment(
                "x", id, 50_000, 0, 1, new byte[50_000]), 11_000L).status()
                == PacketFragments.Status.REJECTED, "wrong part count rejected");
        check(reassembler.accept("p", new PacketFragments.Fragment(
                "x", id, 50_000, 0, 3, new byte[1]), 11_000L).status()
                == PacketFragments.Status.REJECTED, "wrong fragment length rejected");
        check(reassembler.accept("p", new PacketFragments.Fragment(
                "x", id, PacketFragments.MAX_TOTAL_BYTES + 1, 0,
                PacketFragments.expectedPartCount(PacketFragments.MAX_TOTAL_BYTES + 1),
                new byte[PacketFragments.MAX_FRAGMENT_BYTES]), 11_000L).status()
                == PacketFragments.Status.REJECTED, "oversized total length rejected");
        check(reassembler.activeAssemblies() == 0, "rejected metadata allocates nothing");
        check(reassembler.bufferedBytes() == 0L, "rejected metadata reserves no budget");
    }

    private static void invalidFragmentReleasesOnlyMatchingGroup() {
        UUID sharedMessageId = UUID.randomUUID();
        PacketFragments.Reassembler reassembler = new PacketFragments.Reassembler();
        List<PacketFragments.Fragment> mine = PacketFragments.split(
                "huhutalk:invalid-a", sharedMessageId, payload(50_000, 40));
        List<PacketFragments.Fragment> other = PacketFragments.split(
                "huhutalk:invalid-b", UUID.randomUUID(), payload(50_000, 41));
        check(reassembler.accept("p", mine.get(0), 12_000L).status()
                == PacketFragments.Status.ACCEPTED, "sender p first part accepted");
        check(reassembler.accept("q", other.get(0), 12_000L).status()
                == PacketFragments.Status.ACCEPTED, "sender q first part accepted");

        PacketFragments.Fragment badIndex = new PacketFragments.Fragment(
                mine.get(0).payloadId(), sharedMessageId, mine.get(0).totalLength(),
                mine.get(0).partCount(), mine.get(0).partCount(), new byte[0]);
        check(reassembler.accept("p", badIndex, 12_000L).status()
                == PacketFragments.Status.REJECTED, "out-of-range part index rejected");
        check(reassembler.activeForSender("p") == 0,
                "invalid index releases the matching existing group");
        check(reassembler.activeForSender("q") == 1,
                "invalid index leaves other senders untouched");
        check(reassembler.bufferedBytes() == 50_000L,
                "only the matching group's budget is released");

        check(reassembler.accept("p", mine.get(0), 12_000L).status()
                == PacketFragments.Status.ACCEPTED, "sender p group recreated");
        PacketFragments.Fragment badLength = new PacketFragments.Fragment(
                mine.get(1).payloadId(), sharedMessageId, mine.get(1).totalLength(),
                mine.get(1).partIndex(), mine.get(1).partCount(), new byte[1]);
        check(reassembler.accept("p", badLength, 12_000L).status()
                == PacketFragments.Status.REJECTED, "wrong fragment length rejected");
        check(reassembler.activeForSender("p") == 0,
                "invalid length releases the matching existing group");
        check(reassembler.activeForSender("q") == 1,
                "invalid length leaves other senders untouched");
        check(reassembler.bufferedBytes() == 50_000L,
                "other sender keeps its reserved budget");
    }

    private static void oversizedPayloadCannotBeSplit() {
        boolean threw = false;
        try {
            PacketFragments.split("x", UUID.randomUUID(), new byte[PacketFragments.MAX_TOTAL_BYTES + 1]);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check(threw, "split must reject payload above 2 MiB");
    }

    private static byte[] payload(int size, int seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
