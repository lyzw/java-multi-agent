package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.bootstrap.config.AuditTamperProofGuard.Assessment;

/**
 * P2-2 审计防篡改漂移守卫（纯判定逻辑）。
 *
 * <p>⚠️ <b>本类要钉住的判据</b>：无论环境怎么变，
 * 「应用角色能删审计」这件事<b>绝不可以是静默的</b>。
 * 它要么被判定为告警（默认），要么在声明了要求隔离的环境里直接拒绝启动 ——
 * 绝不能因为日志级别、配置顺序或空值而悄悄消失。
 */
class AuditTamperProofGuardTest {

    @Test
    @DisplayName("✅ 应用角色无 DELETE → 通过，不报不噪")
    void isolatedRolePassesSilently() {
        Assessment a = AuditTamperProofGuard.assess("audit_record", false, true);

        assertThat(a.fatal()).isFalse();
        assertThat(a.warn()).isFalse();
        assertThat(a.message()).contains("audit_record").contains("分离");
    }

    @Test
    @DisplayName("⚠️ 未隔离 + 未声明要求 → 必须告警（绝不可静默通过）")
    void nonIsolatedWithoutRequirementWarns() {
        Assessment a = AuditTamperProofGuard.assess("audit_record", true, false);

        assertThat(a.fatal()).isFalse();
        assertThat(a.warn()).as("此类缺口正是「配了等于没配且没有报错」的形态，必须可见")
                .isTrue();
        assertThat(a.message()).contains("04_retention_role.sql");
    }

    @Test
    @DisplayName("⛔ 未隔离 + 已声明要求 → 拒绝启动（隔离被回滚必须 fail-closed）")
    void nonIsolatedWithRequirementIsFatal() {
        Assessment a = AuditTamperProofGuard.assess("audit_record", true, true);

        assertThat(a.fatal()).isTrue();
        assertThat(a.message())
                .contains("03_platform_roles.sql")
                .as("报错必须指出最可能的真因，否则运维只会看到一条启动失败")
                .contains("拒绝启动");
    }

    @Test
    @DisplayName("⛔💥 已隔离又被授权脚本覆盖 → 启动失败，且报错带得上「怎么修」")
    void regressionToSharedCredentialIsFatal() {
        // 场景：04 脚本执行过（故 require=true），随后有人重跑 GRANT ALL TABLES
        assertThatThrownBy(() -> {
            Assessment a = AuditTamperProofGuard.assess("erasure_certificate", true, true);
            if (a.fatal()) {
                throw new IllegalStateException(a.message());
            }
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("erasure_certificate")
                .hasMessageContaining("重跑");
    }
}
