package cn.studykid.multiagent.application.cap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * <b>临时</b>内容指纹（P0 占位实现 · P1 将被 §6.4 真实算法替换）。
 *
 * <p><b>为什么 P0 需要一个「临时」指纹</b>：
 * {@code cap_*_def.config_fingerprint} 与
 * {@code execution_capability_snapshot.config_fingerprint} 是<b>非空</b>列，
 * 且 P0 的双写比对工具需要「同一份输入 → 同一个指纹」才能判定一致性。
 * 但 §6.4 的真实闭包指纹算法（P1 实现）要覆盖
 * Agent/Skill/Tool/MCP 的 {@code (id,version)}、riskCeiling、allowlist、
 * paramPolicies、MCP 固定清单、ForcedSafety 摘要，且**凭据材料永不入指纹**——
 * 这些输入在 P0 还不存在（Tool/Skill/MCP 定义与发布头属 P1/P5）。
 *
 * <p>⚠️ <b>本指纹带 {@code pending_v1:} 前缀</b>，与 P1 的真实算法输出
 * <b>物理隔离</b>。这样做的目的是让「临时值」在数据里<b>一眼可辨</b>：
 * 任何人看到 {@code pending_v1:...} 就知道这是占位值，
 * 而不是误以为它已经是可用于失效判定的真实指纹。
 * P1 上线时必须重算并迁移（列入 P1 的迁移项）。
 *
 * <p><b>确定性要求</b>：同样输入必须得到同样输出，否则双写比对工具会把
 * 「投影正确」误判成「内容不一致」。故此处做<b>规范化</b>：
 * 字段顺序固定、列表按字典序排序、null 与空列表归一。
 */
public final class ProvisionalFingerprint {

    /**
     * 占位指纹前缀。
     *
     * <p>⚠️ 与 P1 真实算法输出<b>不共用前缀</b>，避免占位值被误当成真实指纹
     * 参与失效判定（S5a 会误判为「指纹变化」而全量拒绝）。
     */
    public static final String PREFIX = "pending_v1:";

    private ProvisionalFingerprint() {
    }

    /**
     * 计算临时指纹。
     *
     * @param parts 参与指纹的字段值；<b>顺序即规范</b>，调用方须按固定顺序传入
     * @return {@code pending_v1:} 前缀 + SHA-256 十六进制摘要
     */
    public static String of(List<String> parts) {
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) {
            // 分隔符不可省略：否则 ["ab","c"] 与 ["a","bc"] 会得到同一摘要
            canonical.append(part == null ? "\u0000" : part).append('\u001F');
        }
        return PREFIX + sha256Hex(canonical.toString());
    }

    /**
     * 规范化字符串列表：去空白、丢弃空项、按字典序排序。
     *
     * <p>⚠️ <b>排序是必要的</b>：{@code enabledSkills} 的<b>顺序</b>不携带
     * 权限语义（显式列表的语义是「包含」，不是「顺序」），
     * 但如果指纹对顺序敏感，同一份配置换个书写顺序就会算出不同指纹，
     * 双写比对工具会报出大量假差异。
     */
    public static String canonicalList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList()
                .toString();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // ⚠️ SHA-256 是 JDK 强制支持的算法，走到这里说明运行环境异常。
            //    抛异常而不是返回固定值 —— 返回固定值会让所有指纹相同，
            //    「指纹相同即内容相同」的判定将永远成立，失效检测彻底失效。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
