package cn.studykid.rag.management.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.management.ports.DictionaryPort;
import cn.studykid.rag.management.ports.DictionaryPort.DictEntry;
import cn.studykid.rag.management.ports.DictionaryPort.Scope;

/**
 * 「全局项 + 租户覆盖项」合并规则的单元测试。
 *
 * <p><b>本测试只测纯函数</b>：给定一批行，合并结果是什么。
 * 「谁能看见哪些行」是数据库的事，分别由
 * {@code scripts/rag-dict-scope-counterproof.sh}（策略层，直连 psql 注入越权语句）
 * 与 {@code JdbcDictionaryRepositoryIT}（应用层，真库读写）覆盖。
 * 三者测的是不同层，缺一不可：
 * 只测合并函数会把「RLS 其实没放行全局行」测成绿，
 * 只测 RLS 会把「SQL 里的等值谓词把全局行滤光了」测成绿。
 *
 * <p>⚠️ 合并<b>与入参顺序无关</b>：谁胜出由 scope 决定，不由到达先后决定。
 * 早先的实现依赖「全局行在前」的 ORDER BY + put 覆盖，
 * 而 PG 的 {@code ORDER BY (tenant_id IS NULL)} 把 FALSE 排在 TRUE 前，
 * 真实顺序恰好相反 → 全局项覆盖了租户项，
 * 被 {@code JdbcDictionaryRepositoryIT.tenantOverrideIsLocalOnly} 抓到。
 * {@link #overrideWinsRegardlessOfRowOrder()} 就是钉住这个回归的。
 */
class DictionaryGlobalScopeMergeTest {

    private static DictEntry global(String code, String label, int order) {
        return new DictEntry(DictionaryPort.TYPE_SOURCE_TYPE, code, label, order, true, Scope.PLATFORM);
    }

    private static DictEntry tenant(String code, String label, int order) {
        return new DictEntry(DictionaryPort.TYPE_SOURCE_TYPE, code, label, order, true, Scope.TENANT);
    }

    @Test
    @DisplayName("无覆盖时：全局项与租户私有项并存，按 sort_order 升序输出")
    void keepsBothWithoutOverride() {
        List<DictEntry> merged = JdbcDictionaryRepository.mergeOverride(List.of(
                global("TEXTBOOK", "教材", 10),
                global("PAPER", "论文", 30),
                tenant("WORKBOOK", "校本教辅", 20)));

        assertEquals(List.of("TEXTBOOK", "WORKBOOK", "PAPER"),
                merged.stream().map(DictEntry::code).toList(),
                "合并后必须整体按 sort_order 重排，而不是沿用行的到达顺序");
    }

    @Test
    @DisplayName("同 code 覆盖：租户项胜出，且只剩一条")
    void tenantOverridesGlobalOnSameCode() {
        List<DictEntry> merged = JdbcDictionaryRepository.mergeOverride(List.of(
                global("TEXTBOOK", "教材（平台）", 10),
                tenant("TEXTBOOK", "教材（本校）", 10)));

        assertEquals(1, merged.size(), "同 code 的全局项与租户项必须塌成一条");
        DictEntry kept = merged.get(0);
        assertEquals("教材（本校）", kept.label(), "租户标签应覆盖平台标签");
        assertEquals(Scope.TENANT, kept.scope(), "覆盖后这一行应显示为租户可维护");
    }

    @Test
    @DisplayName("回归钉：无论全局行在前还是在后，租户覆盖项都胜出")
    void overrideWinsRegardlessOfRowOrder() {
        List<DictEntry> globalFirst = List.of(
                global("TEXTBOOK", "教材（平台）", 10),
                tenant("TEXTBOOK", "教材（本校）", 10));
        List<DictEntry> tenantFirst = List.of(
                tenant("TEXTBOOK", "教材（本校）", 10),
                global("TEXTBOOK", "教材（平台）", 10));

        // 两种到达顺序必须给出同一个结果——SQL 的 ORDER BY 方向不该承载业务规则。
        assertEquals("教材（本校）",
                JdbcDictionaryRepository.mergeOverride(globalFirst).get(0).label());
        assertEquals("教材（本校）",
                JdbcDictionaryRepository.mergeOverride(tenantFirst).get(0).label());
        assertEquals(JdbcDictionaryRepository.mergeOverride(globalFirst),
                JdbcDictionaryRepository.mergeOverride(tenantFirst));
    }

    @Test
    @DisplayName("覆盖后重排用的是租户的 sort_order，不是全局的")
    void overrideAdoptsTenantSortOrder() {
        List<DictEntry> merged = JdbcDictionaryRepository.mergeOverride(List.of(
                global("TEXTBOOK", "教材（平台）", 10),
                global("PAPER", "论文（平台）", 20),
                tenant("TEXTBOOK", "教材（本校）", 90)));

        assertEquals(List.of("PAPER", "TEXTBOOK"),
                merged.stream().map(DictEntry::code).toList(),
                "租户把 TEXTBOOK 排到最后，覆盖项的排序参数要生效");
    }

    @Test
    @DisplayName("未被覆盖的全局项保持 PLATFORM 作用域（前端据此隐藏编辑按钮）")
    void untouchedGlobalsStayPlatformScoped() {
        List<DictEntry> merged = JdbcDictionaryRepository.mergeOverride(List.of(
                global("TEXTBOOK", "教材（平台）", 10),
                global("PAPER", "论文（平台）", 20),
                tenant("TEXTBOOK", "教材（本校）", 10)));

        DictEntry paper = merged.stream()
                .filter(e -> "PAPER".equals(e.code())).findFirst().orElseThrow();
        assertEquals(Scope.PLATFORM, paper.scope());
        assertEquals(true, paper.global(), "global() 是前端判断只读的依据");
    }

    @Test
    @DisplayName("空输入得到空输出（不抛异常）")
    void emptyStaysEmpty() {
        assertEquals(List.of(), JdbcDictionaryRepository.mergeOverride(List.of()));
    }
}
