package com.huhutalk.core.archive;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 归档 PNG 附件的内容寻址存储。
 *
 * <p>布局为 {@code <archiveRoot>/assets/<sha256>.png}。写入前先限制字节数、校验
 * PNG 签名与 IHDR 尺寸，再用 SHA-256 命名；同一内容只保留一份。读取时会重新核对
 * 文件大小、hash 与尺寸。已存在的同 hash 文件必须通过校验且字节一致，否则显式失败，
 * 绝不覆盖损坏资产。</p>
 *
 * <p>hash 只接受 64 位小写十六进制，路径不能由客户端任意传入。本类不持有静态
 * server，也不负责自动 GC；附件生命周期与消息快照分离。</p>
 */
public final class ArchiveAssetStore {
    private static final Logger LOG = Logger.getLogger("huhutalk.archive");
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final String ASSET_DIR = "assets";
    private static final int PNG_IHDR_LENGTH = 13;

    public static final int MAX_ASSET_BYTES = 1_000_000;
    public static final int MAX_IMAGE_SIDE = 4096;
    public static final long MAX_IMAGE_PIXELS = 4_194_304L;

    private final Path root;
    private final Path assetsDir;

    public ArchiveAssetStore(Path archiveRoot) {
        if (archiveRoot == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "archive asset root must not be null");
        }
        this.root = archiveRoot.toAbsolutePath().normalize();
        this.assetsDir = root.resolve(ASSET_DIR).normalize();
        if (!assetsDir.startsWith(root)) {
            throw new ArchiveException(ArchiveException.Code.INVALID_IDENTIFIER,
                    "asset directory escapes archive root: " + assetsDir);
        }
    }

    public Path root() {
        return root;
    }

    public Path assetsDir() {
        return assetsDir;
    }

    /**
     * 保存一张 PNG，返回其 SHA-256。相同字节不会重复写入。
     */
    public String store(byte[] pngBytes) {
        if (pngBytes == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "asset bytes must not be null");
        }
        if (pngBytes.length == 0) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "asset bytes must not be empty");
        }
        if (pngBytes.length > MAX_ASSET_BYTES) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "asset exceeds " + MAX_ASSET_BYTES + " bytes");
        }
        validatePng(pngBytes, ArchiveException.Code.VALIDATION);
        String hash = sha256Hex(pngBytes);
        Path file = assetFile(hash);
        if (Files.exists(file)) {
            verifyExistingAsset(file, hash, pngBytes);
            return hash;
        }
        writeNewAsset(file, pngBytes);
        return hash;
    }

    /**
     * 按 hash 读取并重新验证资产，返回全新的字节数组。
     */
    public byte[] read(String hash) {
        String validated = requireHash(hash);
        Path file = assetFile(validated);
        if (Files.notExists(file)) {
            throw new ArchiveException(ArchiveException.Code.NOT_FOUND,
                    "archive asset does not exist: " + validated);
        }
        if (!Files.isRegularFile(file)) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "archive asset is not a regular file: " + file);
        }
        try {
            long size = Files.size(file);
            if (size <= 0L || size > MAX_ASSET_BYTES) {
                throw new ArchiveException(ArchiveException.Code.CORRUPT,
                        "archive asset has invalid size: " + size);
            }
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > MAX_ASSET_BYTES) {
                throw new ArchiveException(ArchiveException.Code.CORRUPT,
                        "archive asset exceeds " + MAX_ASSET_BYTES + " bytes");
            }
            if (!sha256Hex(bytes).equals(validated)) {
                throw new ArchiveException(ArchiveException.Code.CORRUPT,
                        "archive asset hash does not match its name: " + validated);
            }
            validatePng(bytes, ArchiveException.Code.CORRUPT);
            return bytes;
        } catch (IOException exception) {
            throw new ArchiveException(ArchiveException.Code.IO,
                    "cannot read archive asset: " + file, exception);
        }
    }

    public boolean exists(String hash) {
        return Files.isRegularFile(assetFile(requireHash(hash)));
    }

    /** 供后续迁移打包列出快照实际引用的 hash，不读取也不删除文件。 */
    public static List<String> referencedHashes(ArchiveSnapshot snapshot) {
        if (snapshot == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "snapshot must not be null");
        }
        TreeSet<String> hashes = new TreeSet<>();
        for (ArchiveRecord record : snapshot.records()) {
            for (String ref : record.attachmentRefs()) {
                if (HASH.matcher(ref).matches()) {
                    hashes.add(ref);
                }
            }
        }
        return List.copyOf(new ArrayList<>(hashes));
    }

    static String requireHash(String hash) {
        if (hash == null || !HASH.matcher(hash).matches()) {
            throw new ArchiveException(ArchiveException.Code.INVALID_IDENTIFIER,
                    "asset hash must be 64 lowercase hex characters: " + hash);
        }
        return hash;
    }

    private Path assetFile(String hash) {
        String validated = requireHash(hash);
        Path file = assetsDir.resolve(validated + ".png").normalize();
        if (!file.startsWith(assetsDir)) {
            throw new ArchiveException(ArchiveException.Code.INVALID_IDENTIFIER,
                    "asset path escapes asset directory: " + file);
        }
        return file;
    }

    private void verifyExistingAsset(Path file, String hash, byte[] incoming) {
        if (!Files.isRegularFile(file)) {
            throw new ArchiveException(ArchiveException.Code.CONFLICT,
                    "asset path is not a regular file, refusing to overwrite: " + file);
        }
        try {
            long size = Files.size(file);
            if (size <= 0L || size > MAX_ASSET_BYTES) {
                throw new ArchiveException(ArchiveException.Code.CONFLICT,
                        "existing asset has invalid size, refusing to overwrite: " + file);
            }
            byte[] existing = Files.readAllBytes(file);
            if (!sha256Hex(existing).equals(hash)) {
                throw new ArchiveException(ArchiveException.Code.CONFLICT,
                        "existing asset hash mismatch, refusing to overwrite: " + file);
            }
            validatePng(existing, ArchiveException.Code.CONFLICT);
            if (!Arrays.equals(existing, incoming)) {
                throw new ArchiveException(ArchiveException.Code.CONFLICT,
                        "existing asset differs from incoming bytes, refusing to overwrite: " + file);
            }
        } catch (IOException exception) {
            throw new ArchiveException(ArchiveException.Code.IO,
                    "cannot verify existing archive asset: " + file, exception);
        }
    }

    private void writeNewAsset(Path file, byte[] pngBytes) {
        Path temp = null;
        try {
            Files.createDirectories(assetsDir);
            temp = Files.createTempFile(assetsDir, ".asset-", ".tmp");
            Files.write(temp, pngBytes, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                LOG.log(Level.WARNING,
                        "Atomic asset move unsupported; falling back to regular move: " + file,
                        exception);
                Files.move(temp, file);
            }
        } catch (IOException exception) {
            throw new ArchiveException(ArchiveException.Code.IO,
                    "cannot write archive asset: " + file, exception);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException exception) {
                    LOG.log(Level.WARNING, "cannot delete archive asset temp file: " + temp, exception);
                }
            }
        }
    }

    /** 客户端/会话校验复用同一 SHA-256 实现，避免复制安全逻辑。 */
    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(Character.forDigit((value >>> 4) & 0x0F, 16));
                builder.append(Character.forDigit(value & 0x0F, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new ArchiveException(ArchiveException.Code.IO,
                    "SHA-256 is not available", exception);
        }
    }

    /**
     * 客户端解码前复用同一 PNG 头/尺寸预算校验；只解析签名与 IHDR，不做无界解压。
     */
    public static void validatePngBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "asset bytes must not be null or empty");
        }
        if (bytes.length > MAX_ASSET_BYTES) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "asset exceeds " + MAX_ASSET_BYTES + " bytes");
        }
        validatePng(bytes, ArchiveException.Code.VALIDATION);
    }

    /** 只解析签名与 IHDR，为避免无界解压不完整解码图片。 */
    private static void validatePng(byte[] bytes, ArchiveException.Code failureCode) {
        if (bytes.length < PNG_SIGNATURE.length + 8 + PNG_IHDR_LENGTH + 4) {
            throw new ArchiveException(failureCode, "asset is too short to be a PNG");
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                throw new ArchiveException(failureCode, "asset does not start with a PNG signature");
            }
        }
        if (readIntBigEndian(bytes, 8) != PNG_IHDR_LENGTH) {
            throw new ArchiveException(failureCode, "PNG first chunk is not a valid IHDR");
        }
        if (bytes[12] != 'I' || bytes[13] != 'H' || bytes[14] != 'D' || bytes[15] != 'R') {
            throw new ArchiveException(failureCode, "PNG first chunk is not IHDR");
        }
        int width = readIntBigEndian(bytes, 16);
        int height = readIntBigEndian(bytes, 20);
        if (width <= 0 || height <= 0) {
            throw new ArchiveException(failureCode, "PNG dimensions must be positive");
        }
        if (width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE) {
            throw new ArchiveException(failureCode,
                    "PNG side must not exceed " + MAX_IMAGE_SIDE + " pixels");
        }
        if ((long) width * (long) height > MAX_IMAGE_PIXELS) {
            throw new ArchiveException(failureCode,
                    "PNG pixel count must not exceed " + MAX_IMAGE_PIXELS);
        }
    }

    private static int readIntBigEndian(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }
}
