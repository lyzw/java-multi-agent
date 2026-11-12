package cn.studykid.multiagent.bootstrap.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.context.GlobalContextPolicy;
import cn.studykid.multiagent.context.RagHitQuota;
import cn.studykid.multiagent.context.RagHitQuota.Candidate;
import cn.studykid.multiagent.context.RagHitQuota.KeptHit;
import cn.studykid.multiagent.context.RagHitQuota.Options;
import cn.studykid.multiagent.context.RagHitQuota.RefHit;
import cn.studykid.multiagent.context.RagHitQuota.RefTokens;
import cn.studykid.multiagent.context.RagHitQuota.Result;
import cn.studykid.multiagent.context.RagHitQuota.TextTokens;
import cn.studykid.multiagent.ports.agentchat.RagHitQuotaPort;
import cn.studykid.multiagent.ports.agentchat.RagHitQuotaPort.Allocation;
import cn.studykid.multiagent.ports.agentchat.RagHitQuotaPort.Hit;
import cn.studykid.multiagent.ports.agentchat.RagHitQuotaPort.KeptRagHit;
import cn.studykid.multiagent.ports.agentchat.RagHitQuotaPort.RefRagHit;
import cn.studykid.multiagent.ports.context.ContextBlocks;
import cn.studykid.multiagent.ports.context.TokenCounter;

/**
 * L2 SSE RAG 配额裁剪的<b>唯一组合根适配实现</b>（ADR-013 D51 / S3-M10 接线）。
 *
 * <p>⚠️ <b>只做接线与计数口径注入，不做第二套配额算法</b>：去重 / 确定性排序 / 动态 top-k /
 * 单块截断 / 降级引用的五动作与三硬不变量全部委托 {@link RagHitQuota#allocate} 纯函数；
 * 本类只做三件事：
 * <ol>
 *   <li>把中性 {@link Hit} 映射为算法层 {@link Candidate}，并按生产 {@link TokenCounter}
 *       算出每条命中的 {@code overheadTokens}（元数据 JSON，见 {@link #overheadTokensOf}）；</li>
 *   <li>把生产 {@link TokenCounter} 包装成 {@link TextTokens} / {@link RefTokens} 注入算法层——
 *       两个函数与 worker 渲染共用 {@link RagHitQuotaPort} 的冻结 JSON 口径，杜绝「算 A 发 B」；</li>
 *   <li>把算法层 {@link Result} 逐字段映射回中性 {@link Allocation}，worker 只渲染、不重算。</li>
 * </ol>
 *
 * <p>⚠️ <b>关闭态零副作用短路</b>：{@code !global.enabled()} 时<b>第一行</b>返回 {@code null}——
 * 零配额解析、零 token 计数、零候选构造。编排器收到 null 走 legacy {@code serializeHits}，
 * 拿到 legacy 固定口径的<b>同一批</b>命中，请求字节与影子期逐位一致（[D11] / G1）。
 * ⛔ 本类不重复任何 enabled 判定真相（与 M7 统一装配共用同一个 {@link GlobalContextPolicy} Bean）。
 *
 * <p>⚠️ <b>组件级 fail-soft</b>：RAG 是可降级的组件能力。空命中 / 算法拒绝 / 非法候选 /
 * 计数异常一律静默返回 {@code null}（→ legacy 注入），⛔ 绝不把配额失败变成 error 帧或
 * FAILED 审计，也绝不吞掉主流程异常。与装配入口的 fail-closed（会拒绝对话）边界不同。
 *
 * <p>⚠️ 本批只在内存裁剪命中列表，⛔ 不写 DB / 不写 outbox / 不发 MQ / 不接观测链。
 */
public final class RagHitQuotaAdapter implements RagHitQuotaPort {

    private static final Logger log = LoggerFactory.getLogger(RagHitQuotaAdapter.class);

    private final @NonNull GlobalContextPolicy global;
    private final @NonNull TokenCounter counter;
    private final @NonNull Options options;

    /**
     * 唯一构造：配额四项参数由 {@code RagHitQuotaConfiguration} 从 yml 传入，本类不提供
     * {@code Options.defaults()} 旁路——否则「改了 env 却没生效」不会报错、只会静默失真。
     */
    public RagHitQuotaAdapter(@NonNull GlobalContextPolicy global,
                              @NonNull TokenCounter counter,
                              int tokenBudget,
                              int maxHits,
                              int maxHitTokens,
                              int minKeptTokens) {
        this.global = Objects.requireNonNull(global, "global 不得为空");
        this.counter = Objects.requireNonNull(counter, "counter 不得为空");
        this.options = new Options(tokenBudget, maxHits, maxHitTokens, minKeptTokens);
    }

    @Override
    public @Nullable Allocation applyQuota(@NonNull Input input) {
        Objects.requireNonNull(input, "RagHitQuotaPort.Input 不得为空");
        // ── [D11] 关闭态：零副作用短路 → 调用方走 legacy 逐位等价 ──────────
        if (!global.enabled()) {
            return null;
        }
        if (input.hits().isEmpty()) {
            return null;   // 零命中：不应注入空围栏，交给调用方按现有零命中语义处理
        }
        try {
            // 生产 token 计数口径：与 worker 的载荷渲染共用 RagHitQuotaPort 冻结 JSON 形状。
            TextTokens textTokens = text -> countSegment(text);
            RefTokens refTokens = (chunkId, locator) ->
                    countSegment(RagHitQuotaPort.refJson(new RefRagHit(chunkId, locator, 0)));

            List<Candidate> candidates = new ArrayList<>(input.hits().size());
            for (Hit hit : input.hits()) {
                candidates.add(new Candidate(
                        hit.chunkId(), hit.text(), hit.score(), hit.locator(),
                        overheadTokensOf(hit)));
            }

            Result result = RagHitQuota.allocate(candidates, options, textTokens, refTokens);
            if (result.savedTokens() != result.beforeTokens() - result.afterTokens()) {
                throw new IllegalStateException("RagHitQuota 净省口径不闭合");
            }

            // 逐字段映射回中性 DTO：kept / refs 都带 chunkId+locator，供 worker 渲染引用行。
            List<KeptRagHit> kept = new ArrayList<>(result.kept().size());
            for (KeptHit k : result.kept()) {
                // 不重算算法层的排序：用 tokens - 正文 token 反推该候选取用的 overhead，
                // 再从原始命中里找回同 chunkId 且 overhead 相同的那一条元数据。
                int overhead = Math.max(0, k.tokens() - textTokens.count(k.text()));
                Hit original = originalOf(input.hits(), k.chunkId(), overhead);
                kept.add(new KeptRagHit(
                        k.chunkId(), k.text(), k.score(), k.locator(),
                        original == null ? "" : original.edition(),
                        original == null ? "" : original.role(),
                        original == null ? "" : original.reason(),
                        k.tokens(), k.truncated()));
            }
            List<RefRagHit> refs = new ArrayList<>(result.refs().size());
            for (RefHit r : result.refs()) {
                refs.add(new RefRagHit(r.chunkId(), r.locator(), r.tokens()));
            }
            return new Allocation(kept, refs, result.beforeTokens(), result.afterTokens(),
                    result.savedTokens(), result.droppedCount(), result.duplicateCount(),
                    result.truncated());
        } catch (RuntimeException e) {
            // 组件级降级：配额失败不影响 RAG 注入与回答主流程；不打印正文。
            log.debug("L2 RAG 配额裁剪跳过（组件级 fail-soft）：kind={}",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 单条命中的元数据 token 开销：把该命中渲染成
     * {@code {"chunkId":…,"text":"","score":…,"locator":…,"edition":…,"role":…,"reason":…}}
     * 后交给<b>生产 TokenCounter</b>计数，
     * 使 {@code Candidate.overheadTokens} 与 worker 真正渲染的字段形状同源。
     * {@code text=""} 是刻意留空：正文 token 由 {@link TextTokens} 单独注入，避免重复计正文。
     */
    private int overheadTokensOf(@NonNull Hit hit) {
        return countSegment(RagHitQuotaPort.hitJson(new KeptRagHit(
                hit.chunkId(), "", hit.score(), hit.locator(),
                hit.edition(), hit.role(), hit.reason(), 0, false)));
    }

    /** 把单段文本投影成 contextBlocks 交给生产 TokenCounter，保证与全窗口预算同源。 */
    private int countSegment(@NonNull String text) {
        return counter.count(new ContextBlocks("", "", "", List.of(), List.of(text), ""));
    }

    /**
     * 仅按算法层结果反推出的 overhead 找回原始元数据；⛔ 不复制去重 / 排序 / 截断逻辑。
     * 同 chunkId 元数据不同的重复命中用 overhead 区分；极端同开销时回落到第一条，
     * 因为重复命中本就是同一知识块，正文由算法层选定，元数据只用于白名单展示。
     */
    private @Nullable Hit originalOf(
            @NonNull List<Hit> hits, @NonNull String chunkId, int overhead) {
        Hit fallback = null;
        for (Hit hit : hits) {
            if (!chunkId.equals(hit.chunkId())) {
                continue;
            }
            if (fallback == null) {
                fallback = hit;
            }
            if (overheadTokensOf(hit) == overhead) {
                return hit;
            }
        }
        return fallback;
    }
}
