package cn.studykid.rag.infrastructure.retirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.scope.RlsDependencyProbe;
import cn.studykid.rag.core.scope.RlsDependencyProbe.Batch;

/**
 * M4-CENTRAL 退役反向验证守卫（源码级，默认跳过，退役后 {@code -Dm4.central.retired=true} 激活）。
 *
 * <p>CENTRAL 是跨批的中央绑定器 {@code RagSessionScope}，<b>无表级 RLS 可退</b>
 * （表级策略已由 R1/R2/R3 三批全退）。本批是<b>纯代码</b>收口：移除两处
 * {@code set_config('app.tenant_id', ...)} 与 {@code RlsDependencyProbe.record(CENTRAL)}，
 * 使 {@code rlsDependent} 达成 0（M4 真出口）。
 *
 * <p><b>反向验证</b>：本守卫断言「修复点已彻底移除」—— 只要 {@code RagSessionScope} 仍残留
 * GUC 绑定或探针记录，即说明 CENTRAL 未真正退役（rlsDependent 未归 0），守卫转红。
 *
 * <p>与 deploy/sql/rag/17_v1_0_4_r4_central_rls_retirement.sql（纯代码变更清单、零 DDL）配套。
 */
class M4CentralRetirementGuard {

    private static final String SESSION_SCOPE =
            "src/main/java/cn/studykid/rag/management/infrastructure/RagSessionScope.java";

    @BeforeAll
    static void gate() {
        // ⛔ 硬门禁：仅当 CENTRAL（即 R1/R2/R3 全批）真正退役后激活。
        Assumptions.assumeTrue(System.getProperty("m4.central.retired") != null,
                "skipped: CENTRAL 尚未退役（需 -Dm4.central.retired=true，且 R1/R2/R3 已全部退役）");
    }

    @Test
    @DisplayName("⛔ 中央绑定器 RagSessionScope 不得再含 set_config('app.tenant_id' 兼容层")
    void centralBinderHasNoGucBinding() {
        String src = stripComments(readSource(SESSION_SCOPE));
        assertThat(src)
                .as("RagSessionScope 仍残留 set_config('app.tenant_id'：CENTRAL 未退役；"
                        + "撤掉这两处即 R4 的「修复点」，残留即说明隔离仍依赖 GUC（rlsDependent≠0）")
                .doesNotContain("set_config('app.tenant_id'");
    }

    @Test
    @DisplayName("⛔ 中央绑定器不得再记录 RlsDependencyProbe.record(CENTRAL)")
    void centralBinderNoProbeRecord() {
        String src = stripComments(readSource(SESSION_SCOPE));
        assertThat(src)
                .as("RagSessionScope 仍调用 RlsDependencyProbe.record(CENTRAL)：中央绑定器 live 信号未清零")
                .doesNotContain("RlsDependencyProbe.record(RlsDependencyProbe.Batch.CENTRAL)")
                .doesNotContain("RlsDependencyProbe.record(Batch.CENTRAL)");
    }

    @Test
    @DisplayName("✅ rlsDependent(CENTRAL) 归 0 且 expectedTotal 归 0（M4 真出口）")
    void rlsDependentReachesZero() {
        assertEquals(0, RlsDependencyProbe.EXPECTED.get(Batch.CENTRAL),
                "RlsDependencyProbe.EXPECTED[CENTRAL] 必须随 R4 代码移除递减至 0");
        assertEquals(0, RlsDependencyProbe.expectedTotal(),
                "rlsDependent 总数必须归 0（R1+R2+R3+CENTRAL 全部退役）—— 这是 M4 的真出口");
    }

    private static String readSource(String rel) {
        Path path = Path.of(rel);
        try {
            assertThat(Files.exists(path))
                    .as("找不到 %s（工作目录应为 rag-persistence 模块）", rel).isTrue();
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("读取失败：" + rel, e);
        }
    }

    private static String stripComments(String code) {
        return code
                .replaceAll("/\\*.*?\\*/", " ")
                .replaceAll("--[^\\n]*", " ")
                .replaceAll("//[^\\n]*", " ");
    }
}
