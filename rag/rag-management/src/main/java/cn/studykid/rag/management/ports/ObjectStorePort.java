package cn.studykid.rag.management.ports;

import org.jspecify.annotations.NonNull;

/**
 * 对象存储端口（语料物理载体落桶）。
 *
 * <p><b>⚠️ 为什么需要它</b>：{@code rag_document.object_ref} 原本假设
 * 「正文已由外部放进对象存储、前端只是粘贴 locator」。但「录入文档」交互要求
 * 由后端把上传字节落桶并产出定位符——否则用户根本拿不到一个合法的 objectRef。
 *
 * <p><b>⚠️ 占位实现 = 本地目录</b>：当前 {@code LocalObjectStoreAdapter} 把字节写到
 * 本地文件系统（配置项 {@code rag.corpus-bucket.base-dir}）。它是<b>落桶语义的最小实现</b>，
 * 真实部署应替换为 MinIO / S3 适配器（同一接口，不改调用方）。
 *
 * <p><b>⚠️ 服务端权威哈希</b>：{@link #sha256(byte[])} 由服务端重算内容摘要，
 * <b>绝不</b>信任前端传入的哈希——前端算的 SHA-256 仅用于即时展示，
 * 落库必须以服务端重算值为准（防篡改 / 防哈希被静默替换）。
 */
public interface ObjectStorePort {

    /**
     * 存储字节内容，返回对象定位符（objectRef）。
     *
     * <p>fail-closed：存储失败必须抛异常，<b>绝不</b>返回 null 或空串——
     * 空 objectRef 会绕开「检测语料被静默替换」这条防线。
     *
     * @param logicalKey 逻辑键（建议形如 {@code <tenantId>/<sourceId>/<uuid>.<ext>}），仅用于落盘路径
     * @param content    文件字节
     * @return 对象定位符（相对键或完整 URI，由实现定义）
     */
    @NonNull String store(@NonNull String logicalKey, @NonNull byte[] content);

    /**
     * 计算内容 SHA-256（小写十六进制，64 位）。服务端权威哈希，不信任前端传入值。
     *
     * @param content 文件字节
     * @return 64 位十六进制摘要
     */
    @NonNull String sha256(@NonNull byte[] content);

    /**
     * 读取对象内容（V1.0.2 S2-3：摄取流水线需要拿回落桶的原始字节去解析）。
     *
     * <p><b>⚠️ 为什么落桶之后还需要读</b>：上传时只保存 {@code object_ref}，
     * 异步 worker 在另一个进程里执行解析，必须凭 objectRef 把原始字节读回来。
     * 没有本方法，摄取链在「落桶 → 解析」之间就断了——
     * 离线脚本时代的断裂正是由此而来。
     *
     * <p>⚠️ fail-closed：对象不存在或读取失败必须抛异常，
     * <b>不得</b>返回空数组——空数组会被解析器当「空文档」处理，
     * 最终以「录入成功但 0 片段」的形态静默失败。
     *
     * @param objectRef 对象定位符（{@link #store} 的返回值）
     * @return 原始字节
     */
    @NonNull byte[] read(@NonNull String objectRef);

    /**
     * 通道是否可用（本地桩恒 true；远端未配置时应返回 false 以便上层 fail-closed）。
     */
    boolean isAvailable();
}
