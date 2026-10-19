package cn.studykid.multiagent.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.core.tenant.TenantMismatchException;

/**
 * 租户上下文与授权契约测试（P1-04 / ADR-006 / FR-010）。
 *
 * <p>核心断言：<b>tenant_id 的可信来源</b>。若上下文可被客户端输入污染，
 * 则 RLS 之外的所有授权判断都失去意义（P0-D02 NFR-001）。
 */
class TenantContextTest {

    private static TenantContext ctx(String tenantId, String principal, String... roles) {
        return new TenantContext(tenantId, principal, Set.of(roles), 0);
    }

    @Nested
    @DisplayName("上下文构造：非法输入一律拒绝")
    class Construction {

        @Test
        @DisplayName("tenantId 为空或空白 → 拒绝")
        void rejectsBlankTenantId() {
            assertThatThrownBy(() -> ctx(null, "p1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tenantId");
            assertThatThrownBy(() -> ctx("", "p1"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ctx("   ", "p1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("principalId 为空 → 拒绝（无主体的上下文无审计意义）")
        void rejectsBlankPrincipal() {
            assertThatThrownBy(() -> ctx("t-a", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("principalId");
        }

        @Test
        @DisplayName("authzVersion 为负 → 拒绝")
        void rejectsNegativeAuthzVersion() {
            assertThatThrownBy(() -> new TenantContext("t-a", "p1", Set.of(), -1L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("roles 为 null 视为空集，不抛异常")
        void nullRolesBecomeEmptySet() {
            TenantContext c = new TenantContext("t-a", "p1", null, 0);
            assertThat(c.roles()).isEmpty();
        }
    }

    @Nested
    @DisplayName("不可变性：请求处理中途不可被篡改")
    class Immutability {

        @Test
        @DisplayName("roles 集合不可修改")
        void rolesAreImmutable() {
            TenantContext c = ctx("t-a", "p1", "VIEWER");
            assertThatThrownBy(() -> c.roles().add("TENANT_ADMIN"))
                    .as("角色集合必须不可变，否则可在校验后提权")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("记录类天然不可变，字段无 setter")
        void isRecord() {
            assertThat(TenantContext.class.isRecord()).isTrue();
        }
    }

    @Nested
    @DisplayName("⚠️ 租户一致性：跨租户访问必须显式失败")
    class TenantConsistency {

        @Test
        @DisplayName("同租户校验通过")
        void sameTenantPasses() {
            TenantContext c = ctx("t-a", "p1");
            c.requireSameTenant("t-a");   // 不应抛异常
        }

        @Test
        @DisplayName("跨租户访问抛 TenantMismatchException")
        void crossTenantThrows() {
            TenantContext c = ctx("t-a", "p1");
            assertThatThrownBy(() -> c.requireSameTenant("t-b"))
                    .isInstanceOf(TenantMismatchException.class)
                    .hasMessageContaining("t-a")
                    .hasMessageContaining("t-b");
        }

        @Test
        @DisplayName("异常携带双方租户 ID，便于审计定位")
        void exceptionCarriesBothTenants() {
            TenantMismatchException e = new TenantMismatchException("t-a", "t-b");
            assertThat(e.actualTenantId()).isEqualTo("t-a");
            assertThat(e.expectedTenantId()).isEqualTo("t-b");
        }
    }

    @Nested
    @DisplayName("授权撤销：authzVersion 递增以拒绝陈旧上下文")
    class AuthzRevocation {

        @Test
        @DisplayName("撤销后版本号更高，在途旧上下文可被识别")
        void versionDistinguishesStaleContext() {
            TenantContext stale = new TenantContext("t-a", "p1", Set.of("AGENT_AUTHOR"), 1L);
            TenantContext fresh = new TenantContext("t-a", "p1", Set.of(), 2L);

            assertThat(stale.authzVersion())
                    .as("旧上下文版本应低于撤销后的版本")
                    .isLessThan(fresh.authzVersion());
            assertThat(stale.hasRole("AGENT_AUTHOR"))
                    .as("陈旧上下文仍持旧角色，故必须比对版本号而非仅看角色")
                    .isTrue();
            assertThat(fresh.hasRole("AGENT_AUTHOR")).isFalse();
        }
    }

    @Nested
    @DisplayName("审计表示：不含敏感信息")
    class AuditRepresentation {

        @Test
        @DisplayName("auditString 含租户与主体，不含 Prompt 或业务数据")
        void auditStringIsSafe() {
            TenantContext c = ctx("t-a", "principal-1", "VIEWER");
            String s = c.auditString();
            assertThat(s).contains("t-a").contains("principal-1").contains("VIEWER");
            assertThat(s.toLowerCase()).doesNotContain("prompt").doesNotContain("password");
        }
    }

    @Nested
    @DisplayName("角色判定")
    class Roles {

        @Test
        @DisplayName("hasRole 精确匹配")
        void hasRole() {
            TenantContext c = ctx("t-a", "p1", "TENANT_ADMIN", "VIEWER");
            assertThat(c.hasRole("TENANT_ADMIN")).isTrue();
            assertThat(c.hasRole("VIEWER")).isTrue();
            assertThat(c.hasRole("AGENT_AUTHOR")).isFalse();
        }

        @Test
        @DisplayName("角色集合内容正确")
        void rolesContent() {
            assertThat(ctx("t-a", "p1", "A", "B").roles()).containsExactlyInAnyOrder("A", "B");
        }
    }
}