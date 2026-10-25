package cn.studykid.multiagent.application.orchestration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 委派调用标识的<b>确定性派生</b>（多级 Agent v1.1 · MA-T10）。
 *
 * <p>⚠️ <b>幂等的前提是标识可重算</b>：同一处委派（同一 Run + 同一父调用 + 同一模型
 * tool call）在重复投递、Worker 重启、接管重放时必须算出<b>同一个</b> invocationId，
 * 否则「重复消息不重复创建逻辑调用」无从谈起 —— 那只会变成「每次重放都新建一次调用」。
 *
 * <p>⚠️ <b>不用随机 UUID</b>：随机 id 只能防「同一 id 重复插入」，
 * 防不住「同一逻辑调用被创建两次」。
 */
public final class DelegationInvocationId {

    private DelegationInvocationId() {
    }

    /**
     * 派生委派调用标识。
     *
     * @param runId              根 Run 标识
     * @param parentInvocationId 父调用标识；根调用为 {@code null}
     * @param toolCallId         模型 tool call 的标识（同一轮内的稳定标识）
     * @return UUID 形态的调用标识
     */
    public static @NonNull String derive(@NonNull String runId,
                                          @Nullable String parentInvocationId,
                                          @Nullable String toolCallId) {
        String material = runId + '|' + (parentInvocationId == null ? "-" : parentInvocationId)
                + '|' + (toolCallId == null ? "-" : toolCallId);
        byte[] digest = sha256(material);
        // ⚠️ 落进 UUID 列 → 必须满足 UUID 的 version/variant 位布局
        digest[6] = (byte) ((digest[6] & 0x0F) | 0x40);
        digest[8] = (byte) ((digest[8] & 0x3F) | 0x80);
        String hex = HexFormat.of().formatHex(digest);
        return hex.substring(0, 8) + '-' + hex.substring(8, 12) + '-' + hex.substring(12, 16)
                + '-' + hex.substring(16, 20) + '-' + hex.substring(20, 32);
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // ⚠️ SHA-256 是 JDK 强制支持的算法；走不到这里说明运行环境异常。
            //    抛异常而不是返回固定值 —— 固定值会让所有委派共享同一个 id，
            //    「幂等」将退化为「永远只允许一次委派」。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
