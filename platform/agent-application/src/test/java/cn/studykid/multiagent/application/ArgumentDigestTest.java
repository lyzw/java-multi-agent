package cn.studykid.multiagent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M0-3 回归测试：参数摘要必须<b>稳定</b>、<b>不含原文</b>、<b>与 CAP 同源</b>。
 *
 * <p><b>缺陷背景</b>：闸门此前用 {@code String.valueOf(call.arguments())}，
 * CAP 侧用 {@code ArgsNormalizer.hash} —— 两套口径。
 * 前者既依赖 {@code HashMap} 遍历顺序（不是契约保证），
 * 又把<b>参数原文</b>（含学生提交的解答）送进审批台。
 *
 * <p><b>断言要点</b>：同参同摘要、顺序不同同摘要、摘要不含原文、
 * 改一个值摘要就变（防篡改绑定有效）。
 */
class ArgumentDigestTest {

    @Test
    @DisplayName("✅ 同参两次调用产同一摘要（审批绑定与实际执行必须算出同一个值）")
    void sameArgumentsProduceSameDigest() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("studentId", "s-1");
        a.put("knowledgePoint", "二次函数");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("studentId", "s-1");
        b.put("knowledgePoint", "二次函数");

        assertThat(ArgumentDigest.of(a)).isEqualTo(ArgumentDigest.of(b));
    }

    @Test
    @DisplayName("✅ 参数顺序不同 → 同一摘要（键序不是契约保证）")
    void argumentOrderDoesNotChangeDigest() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("studentId", "s-1");
        a.put("knowledgePoint", "二次函数");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("knowledgePoint", "二次函数");
        b.put("studentId", "s-1");

        assertThat(ArgumentDigest.of(a)).isEqualTo(ArgumentDigest.of(b));
    }

    @Test
    @DisplayName("⚠️ 合规红线：摘要**不含参数原文**（审批台只存摘要）")
    void digestDoesNotLeakRawArguments() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("studentId", "s-1");
        args.put("answer", "我把答案写在这里");

        String digest = ArgumentDigest.of(args);

        assertThat(digest).doesNotContain("我把答案写在这里").doesNotContain("s-1");
        assertThat(digest)
                .as("摘要须带版本前缀，便于日后换算法时识别旧值")
                .startsWith("args_norm_v1:");
    }

    @Test
    @DisplayName("✅ 改一个参数值 → 摘要变化（防篡改绑定有效）")
    void changedArgumentChangesDigest() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("amount", 10);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("amount", 11);

        assertThat(ArgumentDigest.of(a)).isNotEqualTo(ArgumentDigest.of(b));
    }

    @Test
    @DisplayName("✅ 数字 10 与字符串 \"10\" → 不同摘要（类型不能被抹平）")
    void numberAndStringAreDistinct() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("amount", 10);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("amount", "10");

        assertThat(ArgumentDigest.of(a)).isNotEqualTo(ArgumentDigest.of(b));
    }
}
