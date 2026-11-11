package cn.studykid.multiagent.context;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.studykid.multiagent.context.RagHitQuota.Candidate;
import cn.studykid.multiagent.context.RagHitQuota.KeptHit;
import cn.studykid.multiagent.context.RagHitQuota.Options;
import cn.studykid.multiagent.context.RagHitQuota.RefHit;
import cn.studykid.multiagent.context.RagHitQuota.RefTokens;
import cn.studykid.multiagent.context.RagHitQuota.Result;
import cn.studykid.multiagent.context.RagHitQuota.TextTokens;

/**
 * RAG 命中配额内核行为测试（M10-a，《方案》§4.1 / §5.1 / §6.4）。
 *
 * <p>⚠️ 计数口径故意用 {@code String::length}（ASCII 1 字符 = 1 token），
 * 使每个断言的 token 数可手算、可反推，避免「用被测同一套启发式估算」的自证循环。
 *
 * <p>⚠️ 每个硬不变量都配「撤掉修复点即红」的反向验证锚点（RV-91 起），
 * 并在 {@code ContextCompressionM10GuardTest} 用源码锚点二次锁死。
 */
class RagHitQuotaTest {

    /** 正文计数：1 字符 = 1 token；省略号也算 1 字符。 */
    private static final TextTokens LEN = String::length;
    /** 引用行按 id+locator+标点计 token（可控用于测硬顶）。 */
    private static final RefTokens REF_BY_LEN = (id, loc) -> id.length() + loc.length() + 3;
    /** 固定便宜引用（测动态 top-k）。 */
    private static final RefTokens REF_1 = (id, loc) -> 1;

    private static Candidate hit(String id, String text, double score, String loc, int ov) {
        return new Candidate(id, text, score, loc, ov);
    }

    private static Options options(int budget, int maxHits, int maxHitTokens, int minKept) {
        return new Options(budget, maxHits, maxHitTokens, minKept);
    }

    // ── ① 去重 + beforeTokens 全量口径 ────────────────────────────────

    @Test
    @DisplayName("✅ RV-91：去重按 chunkId 保留高分，beforeTokens 按含重复全量统计")
    void dedupKeepsBestAndBeforeCountsRawHits() {
        List<Candidate> hits = List.of(
                hit("a", "xxxx", 0.9, "L1", 0),
                hit("a", "y", 0.5, "L2", 0));

        Result r = RagHitQuota.allocate(hits, options(100, 8, 100, 1), LEN, REF_BY_LEN);

        assertThat(r.kept()).hasSize(1);
        assertThat(r.kept().get(0).text()).isEqualTo("xxxx");
        assertThat(r.duplicateCount()).isEqualTo(1);
        assertThat(r.beforeTokens()).isEqualTo(5);   // 4 + 1，含重复
        assertThat(r.afterTokens()).isEqualTo(4);
        assertThat(r.savedTokens()).isEqualTo(1);     // 去重也体现为严格净省
    }

    @Test
    @DisplayName("✅ 去重 tie-break 到 text：同 chunkId/score/locator 时保留字典序最小正文")
    void dedupTieBreakIsDeterministicOnText() {
        List<Candidate> hits = List.of(
                hit("a", "bbb", 0.9, "L", 0),
                hit("a", "aaa", 0.9, "L", 0));

        Result r = RagHitQuota.allocate(hits, options(100, 8, 100, 1), LEN, REF_BY_LEN);

        assertThat(r.kept()).hasSize(1);
        assertThat(r.kept().get(0).text()).isEqualTo("aaa");
    }

    // ── ② 确定性二次排序 ──────────────────────────────────────────────

    @Test
    @DisplayName("✅ RV-92：输出顺序与输入顺序无关（全序 tie-break 到 chunkId）")
    void orderIsIndependentOfInputOrder() {
        // b 与 c 同分、同 locator，只有 chunkId 不同 → 必须靠 chunkId tie-break 定序。
        // ⚠️ 这是刻意造的「挤出压力」：撤掉 .thenComparing(Candidate::chunkId) 后，
        //    正/逆序输入会得到不同保留顺序，本用例立即红（防止 tie-break 变死代码）。
        Candidate a = hit("a", "aa", 0.5, "z", 0);
        // b 与 c 同分、同 locator、同正文、同 overhead —— 只有 chunkId 不同。
        // ⚠️ text 也相同是刻意的：撤掉 .thenComparing(Candidate::chunkId) 后
        //    comparator 无法区分二者，只能退回输入顺序 → 正/逆序结果不同，本用例立即红。
        Candidate b = hit("b", "xx", 0.5, "a", 0);
        Candidate c = hit("c", "xx", 0.5, "a", 0);
        Options opt = options(100, 8, 100, 1);

        Result forward = RagHitQuota.allocate(List.of(a, b, c), opt, LEN, REF_BY_LEN);
        Result reverse = RagHitQuota.allocate(List.of(c, b, a), opt, LEN, REF_BY_LEN);

        // locator 升序 + chunkId 升序：b(a) → c(a) → a(z)
        assertThat(forward.kept()).extracting(KeptHit::chunkId).containsExactly("b", "c", "a");
        assertThat(reverse.kept()).extracting(KeptHit::chunkId).containsExactly("b", "c", "a");
        assertThat(forward.kept()).extracting(KeptHit::chunkId)
                .isEqualTo(reverse.kept().stream().map(KeptHit::chunkId).toList());
    }

    @Test
    @DisplayName("✅ score 降序优先于 locator 升序")
    void scoreDescWinsOverLocatorAsc() {
        Candidate lowScoreEarlyLoc = hit("b", "bb", 0.1, "a", 0);
        Candidate highScoreLateLoc = hit("a", "aa", 0.9, "z", 0);

        Result r = RagHitQuota.allocate(
                List.of(lowScoreEarlyLoc, highScoreLateLoc), options(100, 8, 100, 1), LEN, REF_BY_LEN);

        assertThat(r.kept()).extracting(KeptHit::chunkId).containsExactly("a", "b");
    }

    // ── ③ 动态 top-k + ④ 单块截断 ────────────────────────────────────

    @Test
    @DisplayName("✅ RV-93：top-k 由 token 预算驱动，非固定条数；放不下降级引用且严格净省")
    void topKIsDrivenByTokenBudget() {
        List<Candidate> hits = List.of(
                hit("a", "aaaaaaaaaa", 0.9, "L1", 0),
                hit("b", "bbbbbbbbbb", 0.8, "L2", 0),
                hit("c", "cccccccccc", 0.7, "L3", 0),
                hit("d", "dddddddddd", 0.6, "L4", 0),
                hit("e", "eeeeeeeeee", 0.5, "L5", 0));

        // 预算 25 只够 2 条全文（20）；minKept=1000 禁止单块截断；
        // 余下 5 token 按每条 1 token 的引用放 3 条，仍 <= 25。
        Result r = RagHitQuota.allocate(hits, options(25, 8, 1000, 1000), LEN, REF_1);

        assertThat(r.kept()).hasSize(2);
        assertThat(r.kept()).extracting(KeptHit::chunkId).containsExactly("a", "b");
        assertThat(r.refs()).extracting(RefHit::chunkId).containsExactly("c", "d", "e");
        assertThat(r.afterTokens()).isLessThanOrEqualTo(25);
        assertThat(r.savedTokens()).isGreaterThan(0);
        assertThat(r.savedTokens()).isEqualTo(r.beforeTokens() - r.afterTokens());
    }

    @Test
    @DisplayName("✅ RV-94：单块正文按 token 截断到 maxHitTokens，含省略号与 overhead")
    void singleBlockTruncatedToMaxHitTokens() {
        // full = ov(2) + 10 = 12 > maxHitTokens(6)；cap=6，bodyCap=6-2=4
        // "abcdefghij" 截到 "abc…"（4 token），cutTokens = 2 + 4 = 6 <= cap
        Candidate c = hit("a", "abcdefghij", 0.9, "L", 2);

        Result r = RagHitQuota.allocate(List.of(c), options(100, 8, 6, 1), LEN, REF_BY_LEN);

        assertThat(r.kept()).hasSize(1);
        KeptHit kept = r.kept().get(0);
        assertThat(kept.text()).isEqualTo("abc…");
        assertThat(kept.tokens()).isEqualTo(6);
        assertThat(kept.truncated()).isTrue();
        assertThat(r.truncated()).isTrue();
        assertThat(r.beforeTokens()).isEqualTo(12);
        assertThat(r.afterTokens()).isEqualTo(6);
        assertThat(r.savedTokens()).isEqualTo(6);
    }

    @Test
    @DisplayName("✅ RV-95：截断不产生空正文块（连省略号都放不下则降级引用）")
    void truncationNeverEmitsEmptyText() {
        // full = ov(1) + 2 = 3 > maxHitTokens(2)；cap=2，bodyCap=2-1=1
        // "ab" 截到 bodyCap=1 时 "a…"=2 > 1 → best="" → 不 kept，降级引用
        Candidate c = hit("a", "ab", 0.9, "L", 1);

        Result r = RagHitQuota.allocate(List.of(c), options(100, 8, 2, 1), LEN, REF_1);

        assertThat(r.kept()).isEmpty();
        assertThat(r.refs()).hasSize(1);
        assertThat(r.refs().get(0).chunkId()).isEqualTo("a");
        assertThat(r.savedTokens()).isGreaterThan(0);
    }

    @Test
    @DisplayName("✅ overheadTokens 计入全文 token（撤掉即让预算低估）")
    void overheadCountedInFullTokens() {
        Candidate c = hit("a", "ab", 0.9, "L", 7); // full = 7 + 2 = 9

        Result r = RagHitQuota.allocate(List.of(c), options(100, 8, 100, 1), LEN, REF_BY_LEN);

        assertThat(r.kept()).hasSize(1);
        assertThat(r.kept().get(0).tokens()).isEqualTo(9);
        assertThat(r.beforeTokens()).isEqualTo(9);
        assertThat(r.afterTokens()).isEqualTo(9);
    }

    // ── ⑤ 引用降级 + 三条硬不变量 ────────────────────────────────────

    @Test
    @DisplayName("✅ RV-96：放不下降级为 chunkId+locator 引用，严格净省，不携带正文")
    void overflowDowngradesToReferenceAndStrictlySaves() {
        // full = 8；引用 = "chunk-7"(7) + "doc.md#42"(9) + 3 = 19 > 原文 8 → 会被丢弃。
        // 故本例用便宜引用 REF_1，专测「放不下降级为引用 + 严格净省 + 不携带正文」。
        // 「引用比原文贵则丢弃」由 RV-97 单独覆盖。
        Candidate c = hit("chunk-7", "abcdefgh", 0.9, "doc.md#42", 0); // full = 8

        Result r = RagHitQuota.allocate(List.of(c), options(3, 8, 100, 100), LEN, REF_1);

        assertThat(r.kept()).isEmpty();
        assertThat(r.refs()).hasSize(1);
        RefHit ref = r.refs().get(0);
        assertThat(ref.chunkId()).isEqualTo("chunk-7");
        assertThat(ref.locator()).isEqualTo("doc.md#42");
        assertThat(ref.tokens()).isEqualTo(1);
        assertThat(r.afterTokens()).isLessThanOrEqualTo(3);
        assertThat(r.beforeTokens()).isEqualTo(8);
        assertThat(r.savedTokens()).isEqualTo(7);
    }

    @Test
    @DisplayName("✅ RV-97：引用比原文贵则丢弃而非降级——绝不静默撑爆上下文")
    void refMoreExpensiveThanFullIsDropped() {
        List<Candidate> hits = List.of(
                hit("a", "x", 0.9, "L1", 0),  // full = 1，先占满 maxHits
                hit("b", "y", 0.8, "L2", 0)); // full = 1，降级候选
        RefTokens expensiveRef = (id, loc) -> 200; // 引用 200 >> 原文 1
        // ⚠️ 预算给足 500，排除「因预算不足而丢弃」的干扰（否则即使撤掉
        //    refCost < full 闸门，200 > 剩余预算仍会丢弃，形成假绿）。
        //    预算充足时唯一拦截点只剩「refCost < full」该闸门：
        //    撤掉它 → 贵引用被保留 → refs 非空、after=201 → 本用例红。
        Options opt = options(500, 1, 100, 1);

        Result r = RagHitQuota.allocate(hits, opt, LEN, expensiveRef);

        assertThat(r.kept()).hasSize(1);
        assertThat(r.refs()).isEmpty();               // ⛔ 不得留一个比原文贵的引用
        assertThat(r.droppedCount()).isEqualTo(1);
        assertThat(r.afterTokens()).isEqualTo(1);
        assertThat(r.savedTokens()).isEqualTo(1);
    }

    @Test
    @DisplayName("✅ RV-98：afterTokens 硬顶 tokenBudget，引用也扣剩余预算")
    void afterNeverExceedsBudgetAndRefsConsumeRemaining() {
        List<Candidate> hits = List.of(
                hit("a", "xxx", 0.9, "L1", 0), // full 3
                hit("b", "xxx", 0.8, "L2", 0), // full 3
                hit("c", "xxx", 0.7, "L3", 0)); // full 3
        // 预算 4：第一条 kept(3) 余 1；第二/三条引用各 1 token，只装得下一条（余 1→0），
        // 第三条连引用都放不下 → 丢弃。⚠️ 刻意造的挤出压力：若引用不扣 remaining，
        // 两条引用都会被当成「装得下」，after=5 > budget=4，硬顶校验抛异常即红。
        Result r = RagHitQuota.allocate(hits, options(4, 1, 100, 100), LEN, REF_1);

        assertThat(r.kept()).hasSize(1);
        assertThat(r.refs()).hasSize(1);
        assertThat(r.droppedCount()).isEqualTo(1);
        assertThat(r.afterTokens()).isEqualTo(4);
        assertThat(r.afterTokens()).isLessThanOrEqualTo(4);
        assertThat(r.beforeTokens()).isEqualTo(9);
        assertThat(r.savedTokens()).isEqualTo(5);
    }

    @Test
    @DisplayName("✅ 预算太小连引用都放不下时全部丢弃，after 仍守住硬顶")
    void budgetTooSmallDropsEvenReferences() {
        List<Candidate> hits = List.of(
                hit("a", "xxx", 0.9, "L1", 0),
                hit("b", "xxx", 0.8, "L2", 0));
        Result r = RagHitQuota.allocate(hits, options(4, 1, 100, 100), LEN, REF_BY_LEN);

        assertThat(r.kept()).hasSize(1);
        assertThat(r.refs()).isEmpty();       // refCost=2+1+3=6 > 剩余 1
        assertThat(r.droppedCount()).isEqualTo(1);
        assertThat(r.afterTokens()).isEqualTo(3);
        assertThat(r.afterTokens()).isLessThanOrEqualTo(4);
        assertThat(r.savedTokens()).isGreaterThan(0);
    }

    @Test
    @DisplayName("✅ maxHits 限制全文块条数，其余降级引用")
    void maxHitsCapsFullTextCount() {
        List<Candidate> hits = List.of(
                hit("a", "xx", 0.9, "L1", 0),
                hit("b", "xx", 0.8, "L2", 0),
                hit("c", "xx", 0.7, "L3", 0));

        Result r = RagHitQuota.allocate(hits, options(100, 1, 100, 100), LEN, REF_1);

        assertThat(r.kept()).hasSize(1);
        assertThat(r.refs()).hasSize(2);
        assertThat(r.droppedCount()).isZero();
        assertThat(r.savedTokens()).isGreaterThan(0);
    }

    // ── 无裁剪 / 空输入 ────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 预算充足时不制造伪节省：kept 逐字保留，saved=0")
    void noCompactionKeepsVerbatimAndSavesNothing() {
        Candidate c = hit("a", "hello", 0.9, "L", 0);
        Result r = RagHitQuota.allocate(List.of(c), options(100, 8, 100, 1), LEN, REF_BY_LEN);

        assertThat(r.kept()).hasSize(1);
        assertThat(r.kept().get(0).text()).isEqualTo("hello");
        assertThat(r.kept().get(0).truncated()).isFalse();
        assertThat(r.refs()).isEmpty();
        assertThat(r.truncated()).isFalse();
        assertThat(r.beforeTokens()).isEqualTo(r.afterTokens());
        assertThat(r.savedTokens()).isZero();
    }

    @Test
    @DisplayName("✅ 空输入返回全零、不可变空结果")
    void emptyHitsReturnsZeroResult() {
        Result r = RagHitQuota.allocate(List.of(), options(100, 8, 100, 1), LEN, REF_BY_LEN);

        assertThat(r.kept()).isEmpty();
        assertThat(r.refs()).isEmpty();
        assertThat(r.beforeTokens()).isZero();
        assertThat(r.afterTokens()).isZero();
        assertThat(r.savedTokens()).isZero();
        assertThat(r.droppedCount()).isZero();
        assertThat(r.duplicateCount()).isZero();
        assertThat(r.truncated()).isFalse();
    }

    // ── fail-closed ────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ RV-99：非法 Options 一律 fail-closed")
    void invalidOptionsFailClosed() {
        assertThatThrownBy(() -> new Options(0, 8, 100, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Options(100, 0, 100, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Options(100, 8, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Options(100, 8, 100, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Options(100, 8, 100, 101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("✅ RV-100：null 参数、null 元素、非法 Candidate 一律 fail-closed")
    void nullAndBadCandidatesFailClosed() {
        Options opt = options(100, 8, 100, 1);
        Candidate ok = hit("a", "x", 0.9, "L", 0);

        assertThatThrownBy(() -> RagHitQuota.allocate(null, opt, LEN, REF_BY_LEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RagHitQuota.allocate(List.of(ok), null, LEN, REF_BY_LEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RagHitQuota.allocate(List.of(ok), opt, null, REF_BY_LEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RagHitQuota.allocate(List.of(ok), opt, LEN, null))
                .isInstanceOf(IllegalArgumentException.class);
        // ⚠️ 用 Arrays.asList 而非 List.of：后者在「构造列表」时就抛 NPE，
        //    根本走不到 allocate 的元素校验，会造出「测试通过但守卫没被验证」的假绿。
        assertThatThrownBy(() -> RagHitQuota.allocate(Arrays.asList(ok, null), opt, LEN, REF_BY_LEN))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> hit("", "x", 0.9, "L", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hit("a", null, 0.9, "L", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hit("a", "x", 0.9, "", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hit("a", "x", 0.9, "L", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hit("a", "x", Double.NaN, "L", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hit("a", "x", Double.POSITIVE_INFINITY, "L", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
