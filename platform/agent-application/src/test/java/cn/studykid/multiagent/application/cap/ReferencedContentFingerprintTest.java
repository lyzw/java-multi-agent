package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.orchestration.RoutingStrategy;

/**
 * 被引用行内容指纹入闭包的测试（第二十四批 · C24-1 · A4）。
 *
 * <p>⚠️ <b>本类要证明的是一道「第二道防线」</b>：
 * 编排绑定原本只固化<b>固定版本引用</b>（{@code id@version}），
 * 而「同一版本行被 DB 侧就地改写（运维直接 UPDATE）」这一支**没有任何防线** ——
 * 版本号没变、引用没变、编排声明没变，只有被引用行自己的内容变了。
 * 「版本行不可变」只是<b>约定</b>，不是机制。
 * 现在被引用行的<b>内容指纹</b>被承载进闭包绑定 → 指纹变 → 在途 Run 失效。
 *
 * <p>⚠️ <b>本类同时钉住「不该失效的别失效」</b>：
 * 没有被引用行的闭包，其指纹<b>必须完全不变</b> ——
 * 否则就是「无谓地让在途 Run 集体失效」，那不是 fail-closed，只是返工。
 */
class ReferencedContentFingerprintTest {

    private static final String TENANT = "tenant-ref";

    private static final String WITH_CONTRACT = """
            {"mode":"SEQUENTIAL","resultContractRef":"contract@1"}
            """;

    private static final String WITH_POLICY = """
            {"mode":"ROUTER","childAgentRefs":[{"id":"child-agent","version":1}],
             "routingPolicyRef":"policy@1"}
            """;

    // ── M-1 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("M-1 ✅ 被引用行的内容指纹被承载进闭包绑定与闭包指纹")
    void referencedContentFingerprintIsCarriedIntoClosure() {
        CapFixture fixture = fixtureWithContract("fp-contract-1");

        EffectiveClosure closure = resolver(fixture).resolve(TENANT, "agent-a", 1);

        assertThat(closure.orchestration().resultContractFingerprint())
                .as("被引用契约行的内容指纹必须被承载")
                .isEqualTo("fp-contract-1");
        assertThat(ClosureFingerprint.canonicalJson(closure))
                .as("内容指纹必须进入闭包指纹的哈希输入")
                .contains("referencedContent")
                .contains("fp-contract-1");
    }

    // ── M-2：本类的核心命题 ──────────────────────────────────────────

    @Test
    @DisplayName("M-2 ⛔⚠️ 同一版本行被就地改写 → 闭包指纹必变（第二道防线真的挡住了）")
    void inPlaceRewriteOfReferencedRowChangesClosureFingerprint() {
        // ⚠️ 版本号没变、引用没变（还是 contract@1）、编排声明也没变 ——
        //    只有被引用行自己的内容被就地改写（运维直接 UPDATE）。
        //    修复前这里两个指纹完全相同 → 在途 Run 不失效 → 旧审批被继续复用。
        CapFixture before = fixtureWithContract("fp-contract-before");
        CapFixture after = fixtureWithContract("fp-contract-after");

        String beforeFingerprint = ClosureFingerprint.of(
                resolver(before).resolve(TENANT, "agent-a", 1));
        String afterFingerprint = ClosureFingerprint.of(
                resolver(after).resolve(TENANT, "agent-a", 1));

        assertThat(beforeFingerprint).isNotEqualTo(afterFingerprint);
    }

    @Test
    @DisplayName("M-2b ⛔⚠️ 被引用的路由策略行被就地改写 → 闭包指纹必变")
    void inPlaceRewriteOfRoutingPolicyChangesClosureFingerprint() {
        CapFixture before = new CapFixture()
                .withRoutingPolicy(TENANT, "policy", 1, RoutingStrategy.EXPLICIT_TARGETS,
                        List.of("child-agent"), true)
                .withAgent(TENANT, "agent-a", 1, List.of(), "{}", WITH_POLICY)
                .withAgent(TENANT, "child-agent", 1, List.of(), "{}", "{}");
        CapFixture after = new CapFixture()
                .withRoutingPolicy(TENANT, "policy", 1, RoutingStrategy.EXPLICIT_TARGETS,
                        List.of("child-agent"), true)
                .withAgent(TENANT, "agent-a", 1, List.of(), "{}", WITH_POLICY)
                .withAgent(TENANT, "child-agent", 1, List.of(), "{}", "{}");
        // ⚠️ 直接改同一版本行的内容指纹（模拟 DB 侧就地 UPDATE）
        before.routingPolicies.put(TENANT + "/policy@1",
                new cn.studykid.multiagent.core.cap.CapRoutingPolicyDef(
                        TENANT, "policy", 1, RoutingStrategy.EXPLICIT_TARGETS,
                        List.of("child-agent"), "fp-policy-before", 1,
                        java.time.Instant.EPOCH, java.time.Instant.EPOCH));
        after.routingPolicies.put(TENANT + "/policy@1",
                new cn.studykid.multiagent.core.cap.CapRoutingPolicyDef(
                        TENANT, "policy", 1, RoutingStrategy.EXPLICIT_TARGETS,
                        List.of("child-agent"), "fp-policy-after", 1,
                        java.time.Instant.EPOCH, java.time.Instant.EPOCH));

        String beforeFingerprint = ClosureFingerprint.of(
                resolver(before).resolve(TENANT, "agent-a", 1));
        String afterFingerprint = ClosureFingerprint.of(
                resolver(after).resolve(TENANT, "agent-a", 1));

        assertThat(beforeFingerprint)
                .as("⛔ 被引用的路由策略行被就地改写却指纹不变：路由规则漂移不可察")
                .isNotEqualTo(afterFingerprint);
    }

    // ── M-3 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("M-3 ✅ 没有被引用行的闭包 → 指纹完全不变（不无故让在途 Run 失效）")
    void closureWithoutReferencedRowsKeepsFingerprintStable() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "agent-a", 1, List.of(), "{}", "{}");

        EffectiveClosure closure = resolver(fixture).resolve(TENANT, "agent-a", 1);

        assertThat(closure.orchestration().hasReferencedRows()).isFalse();
        assertThat(ClosureFingerprint.canonicalJson(closure))
                .as("没有被引用行 → 不写入 referencedContent 段")
                .doesNotContain("referencedContent");
    }

    // ── M-4 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("M-4 ⛔ 被引用行解析不出 → fail-closed（拒绝构建闭包，绝不「没有指纹就放行」）")
    void unresolvableReferencedRowFailsClosed() {
        CapFixture fixture = new CapFixture()
                // ⚠️ 声明了 contract@1，但**没有任何契约行**
                .withAgent(TENANT, "agent-a", 1, List.of(), "{}", WITH_CONTRACT);

        assertThatThrownBy(() -> resolver(fixture).resolve(TENANT, "agent-a", 1))
                .as("⛔ 引用了却解析不出：悄悄放弃这道防线 = 防线看起来在、其实不在")
                .isInstanceOf(ClosureResolutionException.class);
    }

    @Test
    @DisplayName("M-4b ⛔ 被引用行存在但未发布 → fail-closed（草稿不可被引用）")
    void unpublishedReferencedRowFailsClosed() {
        CapFixture fixture = new CapFixture()
                .withContract(TENANT, "contract", 1, "{}", false, "fp-contract-1")
                .withAgent(TENANT, "agent-a", 1, List.of(), "{}", WITH_CONTRACT);

        assertThatThrownBy(() -> resolver(fixture).resolve(TENANT, "agent-a", 1))
                .as("⛔ 未发布的契约被当成了可用内容")
                .isInstanceOf(ClosureResolutionException.class);
    }

    // ── M-5 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("M-5 ⛔ 解析能力未装配 → fail-closed（不是「没有解析器就当没有指纹」）")
    void missingResolverFailsClosed() {
        CapFixture fixture = fixtureWithContract("fp-contract-1");
        PermissionResolver resolver = new PermissionResolver(
                fixture.agentDefs(), fixture.skillDefs(), fixture.toolDefs(),
                fixture.serverDefs(), fixture.publishedVersions(), fixture.manifest());

        assertThatThrownBy(() -> resolver.resolve(TENANT, "agent-a", 1))
                .as("⛔ 未装配解析能力却照样建闭包：第二道防线被静默放弃")
                .isInstanceOf(ClosureResolutionException.class);
    }

    @Test
    @DisplayName("M-5b ✅ 未装配解析能力 + 无被引用行 → 正常构建（LEAF 场景零额外读）")
    void missingResolverIsFineWithoutReferencedRows() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "agent-a", 1, List.of(), "{}", "{}");
        PermissionResolver resolver = new PermissionResolver(
                fixture.agentDefs(), fixture.skillDefs(), fixture.toolDefs(),
                fixture.serverDefs(), fixture.publishedVersions(), fixture.manifest());

        EffectiveClosure closure = resolver.resolve(TENANT, "agent-a", 1);
        assertThat(closure.agentId()).isEqualTo("agent-a");
    }

    // ── M-6：缓存不得架空这道防线 ────────────────────────────────────

    @Test
    @DisplayName("M-6 ⛔⚠️ 被引用行被改写 → 闭包缓存必须作废（否则防线被缓存架空）")
    void cacheIsInvalidatedWhenReferencedRowChanges() {
        CapFixture fixture = fixtureWithContract("fp-contract-1");
        PermissionResolver delegate = resolver(fixture);
        CachingClosureResolver caching = new CachingClosureResolver(delegate,
                fixture.publishedVersions(), fixture.toolDefs(), fixture.agentDefs(),
                fixture.manifest(), CachingClosureResolver.DEFAULT_MAX_ENTRIES,
                fixture.referencedContent());

        String before = ClosureFingerprint.of(caching.resolve(TENANT, "agent-a", 1));

        // ⚠️ 同一版本行被就地改写（版本号、引用、编排声明全都没变）
        fixture.resultContracts.put(TENANT + "/contract@1",
                new cn.studykid.multiagent.core.cap.CapResultContractDef(
                        TENANT, "contract", 1, "{}", "fp-contract-2", 1,
                        java.time.Instant.EPOCH, java.time.Instant.EPOCH));

        String after = ClosureFingerprint.of(caching.resolve(TENANT, "agent-a", 1));

        assertThat(after)
                .as("⛔ 缓存继续供出旧闭包：被引用行改了，指纹却不变 → 第二道防线形同虚设")
                .isNotEqualTo(before);
    }

    @Test
    @DisplayName("M-7 ⛔ 缓存的解析器未装配 → 含被引用行的条目一律作废（缓存层不得 fail-open）")
    void cacheWithoutResolverInvalidatesReferencedEntries() {
        CapFixture fixture = fixtureWithContract("fp-contract-1");
        PermissionResolver delegate = resolver(fixture);

        // ⚠️ **对照组**：缓存层**装了**解析器 → 第二次解析命中缓存
        CachingClosureResolver withResolver = new CachingClosureResolver(delegate,
                fixture.publishedVersions(), fixture.toolDefs(), fixture.agentDefs(),
                fixture.manifest(), CachingClosureResolver.DEFAULT_MAX_ENTRIES,
                fixture.referencedContent());
        withResolver.resolve(TENANT, "agent-a", 1);
        withResolver.resolve(TENANT, "agent-a", 1);
        assertThat(withResolver.hitCount())
                .as("对照组：装了解析器时第二次解析应命中缓存")
                .isPositive();

        // ⚠️ **实验组**：缓存层**没装**解析器 → 含被引用行的条目一律作废
        CachingClosureResolver withoutResolver = new CachingClosureResolver(delegate,
                fixture.publishedVersions(), fixture.toolDefs(), fixture.agentDefs(),
                fixture.manifest());
        withoutResolver.resolve(TENANT, "agent-a", 1);
        withoutResolver.resolve(TENANT, "agent-a", 1);
        assertThat(withoutResolver.hitCount())
                .as("⛔ 没有解析器却仍供出缓存命中 = 缓存层的 fail-open："
                        + "被引用行改了也不知道，第二道防线形同虚设")
                .isZero();
        assertThat(withoutResolver.missCount())
                .as("每一次都应回源重解析")
                .isPositive();
    }

    // ── 夹具 ────────────────────────────────────────────────────────

    private static CapFixture fixtureWithContract(@NonNull String fingerprint) {
        return new CapFixture()
                .withContract(TENANT, "contract", 1, "{}", true, fingerprint)
                .withAgent(TENANT, "agent-a", 1, List.of(), "{}", WITH_CONTRACT);
    }

    private static PermissionResolver resolver(CapFixture fixture) {
        return new PermissionResolver(fixture.agentDefs(), fixture.skillDefs(),
                fixture.toolDefs(), fixture.serverDefs(), fixture.publishedVersions(),
                fixture.manifest(), fixture.referencedContent());
    }
}
