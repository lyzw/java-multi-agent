package cn.studykid.multiagent.core.cap;

import org.jspecify.annotations.NonNull;

/**
 * 原子发布结果（§6.3 · W-PERM-P5）。
 *
 * @param release  新建的发布头（其 {@code configPublishSeq} 由 DB 序列分配）
 * @param pointer  移动后的当前指针
 * @param attempts 实际尝试次数（1 = 首次 CAS 即成功；>1 = 发生过并发竞争并变基重试）
 */
public record ConfigPublishResult(
        @NonNull ConfigRelease release,
        @NonNull ConfigPointer pointer,
        int attempts) {

    public ConfigPublishResult {
        if (release == null) {
            throw new IllegalArgumentException("release 不能为空");
        }
        if (pointer == null) {
            throw new IllegalArgumentException("pointer 不能为空");
        }
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts 必须为正数，收到: " + attempts);
        }
    }

    /**
     * @return 本次发布的发布序号
     */
    public long publishSeq() {
        return release.configPublishSeq();
    }
}
