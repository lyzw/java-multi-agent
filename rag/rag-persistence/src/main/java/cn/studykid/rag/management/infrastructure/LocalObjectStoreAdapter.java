package cn.studykid.rag.management.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.management.ports.ObjectStorePort;

/**
 * 本地文件系统对象存储桩（RAG-D02 §3.4 / 摄入链 G1 占位同口径）。
 *
 * <p><b>⚠️ 这是「落桶语义的最小实现」，不是生产存储</b>：把字节写到本地目录，
 * 返回相对路径作为 objectRef。真实部署应替换为 MinIO / S3 适配器
 * （实现同一 {@link ObjectStorePort}，调用方零改动）。
 *
 * <p><b>⚠️ 路径穿越防护</b>：逻辑键经 {@link #safeResolve(String)} 归一化并强制
 * 落在 {@code baseDir} 之内——否则 {@code ../} 类的键会逃出语料根目录。
 * 该检查失败即抛 {@link IllegalArgumentException}（最终映射为 400），
 * 不让恶意键静默落盘到任意位置。
 *
 * <p><b>⚠️ 服务端权威哈希</b>：{@link #sha256(byte[])} 用 JDK 内置
 * {@code MessageDigest("SHA-256")} 重算，不依赖前端传入值。
 */
public class LocalObjectStoreAdapter implements ObjectStorePort {

    /** 允许的载体扩展名白名单（落盘后缀只取这些，其它一律按二进制 .bin 处理）。 */
    private static final java.util.Set<String> SAFE_EXT =
            java.util.Set.of(".pdf", ".epub", ".html", ".htm", ".txt", ".md", ".json", ".xml", ".png", ".jpg", ".jpeg");

    private final Path baseDir;

    /**
     * @param baseDir 语料根目录（相对路径按进程工作目录解析为绝对路径）
     */
    public LocalObjectStoreAdapter(@NonNull String baseDir) {
        // ⚠️ 立刻绝对化 + 归一：后续所有相对解析都以它为锚，
        // 路径穿越检查才有确定的「根」可比对。
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
    }

    @Override
    public @NonNull String store(@NonNull String logicalKey, @NonNull byte[] content) {
        Path target = safeResolve(logicalKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new IllegalStateException("语料对象落盘失败（key=" + logicalKey + "）", e);
        }
        // objectRef = 相对 baseDir 的归一化路径（统一用 '/' 分隔，跨平台一致）。
        return baseDir.relativize(target).toString().replace('\\', '/');
    }

    @Override
    public byte @NonNull [] read(@NonNull String objectRef) {
        if (objectRef == null || objectRef.isBlank()) {
            throw new IllegalArgumentException("objectRef 不能为空");
        }
        Path target = safeResolve(objectRef);
        try {
            // ⚠️ 存在性先判：Files.readAllBytes 对不存在的文件抛 NoSuchFileException，
            //    消息里不含逻辑键，排障时看不出「是哪本书丢了」。
            if (!Files.isRegularFile(target)) {
                throw new IllegalStateException("对象不存在或不是普通文件：" + objectRef);
            }
            return Files.readAllBytes(target);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取对象失败：" + objectRef, e);
        }
    }

    @Override
    public @NonNull String sha256(@NonNull byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制算法，走到这里即环境损坏。
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    /**
     * 把逻辑键解析成绝对落盘路径，并强制校验不逃出 {@code baseDir}。
     *
     * @param logicalKey 逻辑键（形如 {@code tenant/source/uuid.pdf}）
     * @return 绝对、归一化、位于 baseDir 内的路径
     * @throws IllegalArgumentException 键疑似路径穿越时
     */
    private Path safeResolve(String logicalKey) {
        if (logicalKey == null || logicalKey.isBlank()) {
            throw new IllegalArgumentException("对象定位键不能为空");
        }
        // ⚠️ 拒绝任何形式的绝对路径或上级引用，从源头消除穿越。
        if (logicalKey.startsWith("/") || logicalKey.startsWith("\\")
                || logicalKey.contains("..")) {
            throw new IllegalArgumentException("非法的对象定位键（疑似路径穿越）：" + logicalKey);
        }
        Path normalized = baseDir.resolve(logicalKey).normalize();
        if (!normalized.startsWith(baseDir)) {
            throw new IllegalArgumentException("非法的对象定位键（疑似路径穿越）：" + logicalKey);
        }
        return normalized;
    }

    /**
     * 从文件名派生安全落盘后缀（仅取白名单内的小写扩展名，避免注入）。
     *
     * @param fileName 原始文件名（可为空）
     * @return 形如 {@code .pdf} 的后缀；不在白名单返回 {@code .bin}
     */
    static String safeExtension(@NonNull String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return ".bin";
        }
        String ext = fileName.substring(dot).toLowerCase();
        return SAFE_EXT.contains(ext) ? ext : ".bin";
    }
}
