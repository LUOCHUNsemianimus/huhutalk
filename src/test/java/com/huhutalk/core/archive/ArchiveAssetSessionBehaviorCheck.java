package com.huhutalk.core.archive;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * 不依赖 JUnit 的档案图片会话行为检查。覆盖 hash/世界/请求匹配、单 pending、
 * 关闭/重开/连接重置后的迟到响应、错误状态不带字节、预算与重试不自动循环。
 * 由上游直接用 javac/java 运行 main。
 */
public final class ArchiveAssetSessionBehaviorCheck {
    private static final String MAID = "maid-asset-1";
    private static final String MAID_2 = "maid-asset-2";
    private static final String WORLD = "world-asset-1";
    private static final String WORLD_2 = "world-asset-2";

    private static int passed;

    private ArchiveAssetSessionBehaviorCheck() {
    }

    public static void main(String[] args) throws Exception {
        byte[] png = png(4, 4, 0xFF336699);
        byte[] otherPng = png(5, 5, 0xFFAA0000);
        String hash = ArchiveAssetStore.sha256Hex(png);
        String otherHash = ArchiveAssetStore.sha256Hex(otherPng);

        matchingAndSinglePending(hash, png, otherPng, otherHash);
        errorsAndRetry(hash);
        failurePaths(png, hash, otherHash);
        budgetAndPngValidation(png, hash);
        requestValidation(hash);
        System.out.println("ALL PASS (" + passed + " checks)");
    }

    private static void matchingAndSinglePending(String hash, byte[] png, byte[] otherPng,
                                                 String otherHash) {
        ArchiveAssetSession session = new ArchiveAssetSession();
        ArchiveAssetRequest first = session.open(MAID, WORLD, hash).orElseThrow();
        check(session.isLoading(), "open leaves one pending");
        check(session.open(MAID, WORLD, hash).isPresent(), "second open replaces pending");
        ArchiveAssetResponse wrong = new ArchiveAssetResponse(first.requestId(), MAID, WORLD,
                ArchiveAssetStatus.OK, hash, png);
        check(session.accept(wrong) == ArchiveAssetSession.Apply.IGNORED,
                "replaced request id is ignored");

        ArchiveAssetRequest current = session.pendingRequest();
        ArchiveAssetResponse matching = new ArchiveAssetResponse(current.requestId(), MAID, WORLD,
                ArchiveAssetStatus.OK, hash, png);
        check(session.accept(matching) == ArchiveAssetSession.Apply.APPLIED,
                "matching response applies");
        check(!session.isLoading(), "applied response clears pending");
        check(session.response() == matching, "session keeps applied response");
        check(!session.canRetry(), "successful response cannot retry");
        check(session.retry(hash).isEmpty(), "retry after success is refused");

        ArchiveAssetSession wrongStable = new ArchiveAssetSession();
        ArchiveAssetRequest r = wrongStable.open(MAID, WORLD, hash).orElseThrow();
        check(wrongStable.accept(new ArchiveAssetResponse(r.requestId(), MAID_2, WORLD,
                        ArchiveAssetStatus.OK, hash, png)) == ArchiveAssetSession.Apply.IGNORED,
                "wrong stable id is ignored");
        check(wrongStable.isLoading(), "ignored response leaves pending");

        check(wrongStable.accept(new ArchiveAssetResponse(r.requestId(), MAID, WORLD_2,
                        ArchiveAssetStatus.OK, hash, png)) == ArchiveAssetSession.Apply.IGNORED,
                "wrong world is ignored");
        check(wrongStable.accept(new ArchiveAssetResponse(r.requestId(), MAID, WORLD,
                        ArchiveAssetStatus.OK, otherHash, otherPng))
                == ArchiveAssetSession.Apply.IGNORED, "wrong hash is ignored");
        check(wrongStable.accept(new ArchiveAssetResponse(r.requestId(), MAID, WORLD,
                        ArchiveAssetStatus.OK, hash, png))
                == ArchiveAssetSession.Apply.APPLIED, "correct response still applies");

        // 关闭后迟到响应被丢弃；重开后旧 requestId 仍无效。
        ArchiveAssetSession closed = new ArchiveAssetSession();
        ArchiveAssetRequest closedReq = closed.open(MAID, WORLD, hash).orElseThrow();
        closed.close();
        check(closed.accept(new ArchiveAssetResponse(closedReq.requestId(), MAID, WORLD,
                        ArchiveAssetStatus.OK, hash, png)) == ArchiveAssetSession.Apply.IGNORED,
                "closed session ignores late response");
        ArchiveAssetRequest reopened = closed.open(MAID, WORLD, hash).orElseThrow();
        check(closed.accept(new ArchiveAssetResponse(closedReq.requestId(), MAID, WORLD,
                        ArchiveAssetStatus.OK, hash, png)) == ArchiveAssetSession.Apply.IGNORED,
                "old request id ignored after reopen");
        check(closed.accept(new ArchiveAssetResponse(reopened.requestId(), MAID, WORLD,
                        ArchiveAssetStatus.OK, hash, png))
                == ArchiveAssetSession.Apply.APPLIED, "reopened matching response applies");

        // 连接重置：代次增加，迟到响应丢弃。
        int generation = closed.generation();
        closed.onConnectionReset();
        check(closed.generation() == generation + 1, "connection reset increments generation");
        check(closed.accept(new ArchiveAssetResponse(reopened.requestId(), MAID, WORLD,
                        ArchiveAssetStatus.OK, hash, png)) == ArchiveAssetSession.Apply.IGNORED,
                "response from old generation is ignored");
    }

    private static void errorsAndRetry(String hash) {
        for (ArchiveAssetStatus status : new ArchiveAssetStatus[]{
                ArchiveAssetStatus.MISSING, ArchiveAssetStatus.NO_PERMISSION,
                ArchiveAssetStatus.STALE_WORLD, ArchiveAssetStatus.RATE_LIMITED,
                ArchiveAssetStatus.ERROR}) {
            ArchiveAssetSession session = new ArchiveAssetSession();
            ArchiveAssetRequest request = session.open(MAID, WORLD, hash).orElseThrow();
            ArchiveAssetResponse response = ArchiveAssetResponse.error(request.requestId(),
                    MAID, WORLD, status, hash);
            check(response.data().length == 0, status + " carries no bytes");
            check(session.accept(response) == ArchiveAssetSession.Apply.APPLIED,
                    status + " response applies");
            check(session.canRetry(), status + " allows an explicit retry");
            ArchiveAssetRequest retried = session.retry(hash).orElseThrow();
            check(!retried.requestId().equals(request.requestId()),
                    status + " retry creates a new request id");
            check(session.isLoading(), status + " retry leaves one pending");
            check(session.retry(hash).isEmpty(), status + " never queues a second retry");
        }
    }

    private static void failurePaths(byte[] png, String hash, String otherHash) {
        // 1. 匹配当前 pending 的畸形回包 → 清空字节的可重试 ERROR；一次点击产生一个新请求。
        ArchiveAssetSession malformed = new ArchiveAssetSession();
        ArchiveAssetRequest request = malformed.open(MAID, WORLD, hash).orElseThrow();
        check(malformed.retry(hash).isEmpty(), "pending retry is refused");
        check(malformed.failPending(request.requestId(), MAID, WORLD, hash)
                == ArchiveAssetSession.Apply.APPLIED, "matching malformed response ends pending");
        check(!malformed.isLoading(), "malformed response clears pending");
        check(malformed.response().data().length == 0, "malformed response clears bytes");
        check(malformed.canRetry(), "malformed response becomes retryable");
        check(malformed.retry(otherHash).isEmpty(), "malformed retry only original hash");
        ArchiveAssetRequest retried = malformed.retry(hash).orElseThrow();
        check(!retried.requestId().equals(request.requestId()),
                "malformed retry creates a new request id");
        check(malformed.isLoading(), "malformed retry leaves one pending");
        check(malformed.retry(hash).isEmpty(), "malformed retry never queues twice");

        // 2. 身份字段不匹配的畸形回包不得结束原 pending。
        ArchiveAssetSession mismatch = new ArchiveAssetSession();
        ArchiveAssetRequest pending = mismatch.open(MAID, WORLD, hash).orElseThrow();
        check(mismatch.failPending("req-unknown", MAID, WORLD, hash)
                == ArchiveAssetSession.Apply.IGNORED,
                "wrong request id malformed response ignored");
        check(mismatch.failPending(pending.requestId(), MAID_2, WORLD, hash)
                == ArchiveAssetSession.Apply.IGNORED,
                "wrong stable id malformed response ignored");
        check(mismatch.failPending(pending.requestId(), MAID, WORLD_2, hash)
                == ArchiveAssetSession.Apply.IGNORED,
                "wrong world malformed response ignored");
        check(mismatch.failPending(pending.requestId(), MAID, WORLD, otherHash)
                == ArchiveAssetSession.Apply.IGNORED,
                "wrong hash malformed response ignored");
        check(mismatch.isLoading(), "mismatched malformed responses keep pending");
        check(mismatch.accept(new ArchiveAssetResponse(pending.requestId(), MAID, WORLD,
                        ArchiveAssetStatus.OK, hash, png)) == ArchiveAssetSession.Apply.APPLIED,
                "original pending still applies after mismatches");

        // 3. 已接受 OK 但本地解码失败 → 同身份 ERROR、清字节、可显式重试。
        ArchiveAssetSession decode = new ArchiveAssetSession();
        ArchiveAssetRequest decodeReq = decode.open(MAID, WORLD, hash).orElseThrow();
        ArchiveAssetResponse accepted = new ArchiveAssetResponse(decodeReq.requestId(), MAID, WORLD,
                ArchiveAssetStatus.OK, hash, png);
        check(decode.accept(accepted) == ArchiveAssetSession.Apply.APPLIED,
                "OK response accepted before local decode");
        check(!decode.canRetry(), "accepted OK is not retryable before local failure");
        check(decode.markDecodeFailure(accepted) == ArchiveAssetSession.Apply.APPLIED,
                "local decode failure marks ERROR");
        check(decode.canRetry(), "local decode failure becomes retryable");
        check(decode.response().data().length == 0, "local decode failure clears bytes");
        check(decode.retry(otherHash).isEmpty(), "local decode retry only original hash");
        ArchiveAssetRequest decodeRetry = decode.retry(hash).orElseThrow();
        check(!decodeRetry.requestId().equals(decodeReq.requestId()),
                "local decode retry creates a new request id");
        check(decode.markDecodeFailure(accepted) == ArchiveAssetSession.Apply.IGNORED,
                "old decode failure cannot clear retried pending");
        check(decode.retry(hash).isEmpty(), "local decode retry never queues twice");

        // 4. close/reopen 后旧请求的迟到失败不生效。
        ArchiveAssetSession stale = new ArchiveAssetSession();
        ArchiveAssetRequest staleReq = stale.open(MAID, WORLD, hash).orElseThrow();
        ArchiveAssetResponse staleOk = new ArchiveAssetResponse(staleReq.requestId(), MAID, WORLD,
                ArchiveAssetStatus.OK, hash, png);
        check(stale.accept(staleOk) == ArchiveAssetSession.Apply.APPLIED,
                "stale fixture response applies");
        stale.close();
        check(stale.retry(hash).isEmpty(), "closed session refuses retry");
        ArchiveAssetRequest reopened = stale.open(MAID, WORLD, hash).orElseThrow();
        check(stale.markDecodeFailure(staleOk) == ArchiveAssetSession.Apply.IGNORED,
                "old decode failure after reopen is ignored");
        check(stale.failPending(staleReq.requestId(), MAID, WORLD, hash)
                == ArchiveAssetSession.Apply.IGNORED,
                "old malformed response after reopen is ignored");
        check(stale.isLoading(), "reopened pending survives stale failures");
        check(stale.accept(new ArchiveAssetResponse(reopened.requestId(), MAID, WORLD,
                        ArchiveAssetStatus.OK, hash, png)) == ArchiveAssetSession.Apply.APPLIED,
                "reopened response still applies");
    }

    private static void budgetAndPngValidation(byte[] png, String hash) {
        expectArchive("null bytes rejected", ArchiveException.Code.VALIDATION,
                () -> ArchiveAssetStore.validatePngBytes(null));
        expectArchive("oversized bytes rejected", ArchiveException.Code.VALIDATION,
                () -> ArchiveAssetStore.validatePngBytes(
                        new byte[ArchiveAssetStore.MAX_ASSET_BYTES + 1]));
        byte[] badPng = Arrays.copyOf(png, png.length);
        badPng[0] = 0;
        expectArchive("bad PNG header rejected", ArchiveException.Code.VALIDATION,
                () -> ArchiveAssetStore.validatePngBytes(badPng));

        expectArchive("hash mismatch response rejected", ArchiveException.Code.CORRUPT,
                () -> new ArchiveAssetResponse("req-budget-1", MAID, WORLD,
                        ArchiveAssetStatus.OK, "0".repeat(64), png));
        expectArchive("oversized response rejected", ArchiveException.Code.VALIDATION,
                () -> new ArchiveAssetResponse("req-budget-2", MAID, WORLD,
                        ArchiveAssetStatus.OK, hash,
                        new byte[ArchiveAssetStore.MAX_ASSET_BYTES + 1]));
        expectArchive("error response with bytes rejected", ArchiveException.Code.VALIDATION,
                () -> new ArchiveAssetResponse("req-budget-3", MAID, WORLD,
                        ArchiveAssetStatus.ERROR, hash, png));

        ArchiveAssetResponse ok = new ArchiveAssetResponse("req-copy", MAID, WORLD,
                ArchiveAssetStatus.OK, hash, png);
        byte[] copy = ok.data();
        copy[0] = 0;
        check(Arrays.equals(png, ok.data()), "response data is defensively copied");
    }

    private static void requestValidation(String hash) {
        expectArchive("empty world rejected", ArchiveException.Code.VALIDATION,
                () -> new ArchiveAssetRequest("req-1", MAID, "", hash));
        expectArchive("bad hash rejected", ArchiveException.Code.INVALID_IDENTIFIER,
                () -> new ArchiveAssetRequest("req-2", MAID, WORLD, "../escape"));
        expectArchive("unsafe request id rejected", ArchiveException.Code.INVALID_IDENTIFIER,
                () -> new ArchiveAssetRequest("bad id!", MAID, WORLD, hash));
        expectArchive("unknown response status rejected", ArchiveException.Code.CORRUPT,
                () -> ArchiveAssetStatus.fromJson("HACKED"));
    }

    private static byte[] png(int width, int height, int argb) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    image.setRGB(x, y, argb);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            byte[] bytes = output.toByteArray();
            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
                if (ImageIO.read(input) == null) {
                    throw new AssertionError("fixture is not a readable PNG");
                }
            }
            return bytes;
        } catch (IOException exception) {
            throw new AssertionError("cannot create PNG fixture", exception);
        }
    }

    private static void expectArchive(String name, ArchiveException.Code expected, Runnable action) {
        try {
            action.run();
            throw new AssertionError(name + " expected ArchiveException " + expected);
        } catch (ArchiveException exception) {
            if (exception.code() != expected) {
                throw new AssertionError(name + " expected " + expected + " but got "
                        + exception.code() + ": " + exception.getMessage(), exception);
            }
            check(name);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        check(message);
    }

    private static void check(String name) {
        passed++;
        System.out.println("PASS - " + name);
    }
}
