package cn.studykid.multiagent.infrastructure.persistence.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import cn.studykid.multiagent.core.security.ScopeFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SqlScopeTranslator —— ScopeFilter → SQL 谓词")
class SqlScopeTranslatorTest {

    private final SqlScopeTranslator translator = new SqlScopeTranslator("shared-tenant");

    @Test
    @DisplayName("TenantOnly → 等值谓词")
    void tenantOnly() {
        SqlFragment f = translator.render(new ScopeFilter.TenantOnly("t-1"));
        assertEquals("tenant_id = :__scopeTenant", f.sql());
        assertEquals(Map.of("__scopeTenant", "t-1"), f.params());
    }

    @Test
    @DisplayName("TenantWithShared → IN 谓词（含哨兵）")
    void tenantWithShared() {
        SqlFragment f = translator.render(new ScopeFilter.TenantWithShared("t-1"));
        assertEquals("tenant_id IN (:__scopeTenant, :__scopeShared)", f.sql());
        assertEquals(Map.of("__scopeTenant", "t-1", "__scopeShared", "shared-tenant"), f.params());
    }

    @Test
    @DisplayName("Unrestricted → TRUE")
    void unrestricted() {
        SqlFragment f = translator.render(new ScopeFilter.Unrestricted("ops"));
        assertEquals(SqlFragment.TRUE, f);
    }

    @Test
    @DisplayName("None → FALSE（恒假 / fail-closed）")
    void none() {
        SqlFragment f = translator.render(new ScopeFilter.None());
        assertEquals(SqlFragment.FALSE, f);
    }

    @Test
    @DisplayName("哨兵租户标识为空 → 拒绝构造")
    void blankSharedRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SqlScopeTranslator(" "));
    }
}
