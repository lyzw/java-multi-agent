package cn.studykid.multiagent.infrastructure.agentchataudit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 确定性采样器（按 auditId 哈希，保证同一请求全链路一致抽样）。
 *
 * <p><b>⚠️ 为什么不用 {@code String.hashCode()}</b>：它只有 32 位，且分布受字符串结构影响
 * （同一前缀的 traceId 容易挤进同一段桶）。采样率 0.05 时桶内聚集会让实际采样量
 * 与名义值差出数倍——而「采样率」在评估场景里是要写进报告的口径，不能有偏。
 * 这里取 SHA-256 前 8 字节作无符号数再缩放，均匀且可复现。
 *
 * <p><b>⚠️ 无随机源</b>：同一 auditId 在任何实例、任何时刻结论相同，
 * 这是「按 traceId 抽样可复现」的前提。
 */
public final class SamplingDecider {

    private final double rate;

    public SamplingDecider(double rate) {
        this.rate = rate;
    }

    /** @return 本次是否录制 */
    public boolean shouldRecord(@Nullable String auditId) {
        return shouldRecord(rate, auditId);
    }

    /**
     * 静态判定（调用点不必持有实例）。
     *
     * @param rate    [0,1]；&ge;1 全量，&le;0 全不采
     * @param auditId 采样键（traceId / auditId）
     */
    public static boolean shouldRecord(double rate, @Nullable String auditId) {
        if (rate >= 1.0d) {
            return true;
        }
        if (rate <= 0.0d || auditId == null || auditId.isBlank()) {
            return false;
        }
        return bucket(auditId) < rate;
    }

    /** auditId → [0,1) 的确定性桶位。 */
    static double bucket(@NonNull String auditId) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(auditId.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 属 JLS 强制支持的算法；被裁剪的环境宁可全量采样
            // （记多了可以删，记漏了无法补）。
            return 0.0d;
        }
        long bits = 0L;
        for (int i = 0; i < 8; i++) {
            bits = (bits << 8) | (digest[i] & 0xFFL);
        }
        // 取 63 位映射到 [0,1)：先无符号右移 1 位（去掉符号位），再除以 2^63-1。
        // ⚠️ 除数必须是 2^63-1（Long.MAX_VALUE）：写成 1L<<62 会让值域变成 [0,2)，
        //    于是「rate=0.1」实际只采到 5%——采样率与报告口径直接差一倍，且无任何报错。
        return (bits >>> 1) / (double) Long.MAX_VALUE;
    }
}
