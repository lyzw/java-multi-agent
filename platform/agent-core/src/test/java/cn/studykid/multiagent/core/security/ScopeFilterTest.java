package cn.studykid.multiagent.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ScopeFilter —— 密封枢纽对象")
class ScopeFilterTest {

    @Test
    @DisplayName("NONE 常量是 None 子类型的实例（密封穷尽性可满足）")
    void noneConstantIsNoneSubtype() {
        assertInstanceOf(ScopeFilter.None.class, ScopeFilter.NONE);
    }

    @Test
    @DisplayName("四种形态均可在 switch 中穷尽匹配（无 default 也能编译）")
    void exhaustiveSwitchOverAllFourShapes() {
        ScopeFilter[] all = {
                new ScopeFilter.TenantOnly("t-1"),
                new ScopeFilter.TenantWithShared("t-1"),
                new ScopeFilter.Unrestricted("ops"),
                new ScopeFilter.None()
        };
        for (ScopeFilter f : all) {
            String kind = switch (f) {
                case ScopeFilter.TenantOnly ignored -> "TenantOnly";
                case ScopeFilter.TenantWithShared ignored -> "TenantWithShared";
                case ScopeFilter.Unrestricted ignored -> "Unrestricted";
                case ScopeFilter.None ignored -> "None";
            };
            assertTrue(kind != null && !kind.isBlank());
        }
        assertEquals("None", classify(new ScopeFilter.None()));
    }

    private static String classify(ScopeFilter f) {
        return switch (f) {
            case ScopeFilter.TenantOnly ignored -> "TenantOnly";
            case ScopeFilter.TenantWithShared ignored -> "TenantWithShared";
            case ScopeFilter.Unrestricted ignored -> "Unrestricted";
            case ScopeFilter.None ignored -> "None";
        };
    }
}
