package cn.studykid.rag.management.ports;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.rag.management.core.CorpusDocument;
import cn.studykid.rag.management.core.CorpusIssues;
import cn.studykid.rag.management.core.CorpusSource;
import cn.studykid.rag.management.core.CorpusStatistics;
import cn.studykid.rag.management.core.DocumentVerificationDecision;
import cn.studykid.rag.management.core.RagTenantContext;
import cn.studykid.rag.management.core.SourceQuery;
import cn.studykid.rag.management.core.SourceRegistration;
import cn.studykid.rag.management.core.VerificationDecision;

/**
 * 语料数据管理端口（RAG-D07 §2 准入流程的管理面出口）。
 *
 * <p><b>⚠️ 本端口是「管理面」，与 {@code RetrievalPort} 是两个方向</b>：
 * 检索端口回答「能召回什么」，管理端口回答「库里有什么、哪些还没审、能不能放行」。
 * 二者<b>不能合并</b>——检索面是 fail-closed 的自动闸门，
 * 管理面是带留痕与双人复核的<b>人工闸门</b>。
 *
 * <p><b>⚠️ 租户上下文的来源</b>：本端口的所有方法接收 {@link RagTenantContext}
 * 而非裸 {@code tenantId}——租户信息（含 G-3 权威门槛）的<b>权威源是 platform</b>
 * （RAG-D00 §4），RAG 库里<b>不存</b>租户清单（V4 迁移已移除 {@code rag_tenant}）。
 * 实现方在事务内 {@code set_config('app.tenant_id', ..., true)}，
 * 使 V1 的 RLS FORCE 策略生效。
 *
 * <p><b>⚠️ 写入禁令</b>：本端口<b>不提供</b>物理删除语料的方法。
 * 撤回的唯一手段是 {@code ver_status='REVOKED'}
 * （RAG-D07 §8 INV-4-a：禁止把物理 DELETE 当作撤回手段，
 * 因为 DELETE 会级联抹除历史引用，破坏可溯源性）。
 */
public interface CorpusManagementPort {

    /**
     * 登记新的语料来源（阶段① PENDING）。
     *
     * <p>⚠️ 登记结果恒为 {@code trust_tier=UNVERIFIED}、{@code ver_status=PENDING}：
     * 采集员不得自行提升等级或置 VERIFIED（RAG-D07 §2.2）。
     *
     * <p>⚠️ 登记必须留痕（RAG-D07 §2.5：「至少 INGEST（登记）必须落审计」），
     * 故本方法要求显式传入登记人——{@code rag_access_audit.principal_id}
     * 是 NOT NULL，且「谁采集的」是后续争议追溯的第一问。
     *
     * @param context 租户上下文（由 platform 下发）
     * @param registration 登记信息
     * @param principalId 登记人（UUID）
     * @return 登记后的来源视图
     */
    @NonNull CorpusSource registerSource(@NonNull RagTenantContext context,
                                         @NonNull SourceRegistration registration,
                                         @NonNull String principalId);

    /**
     * 按查询条件列出来源。
     *
     * @param context 租户上下文
     * @param query 查询条件
     * @return 匹配的来源列表
     */
    @NonNull List<CorpusSource> listSources(@NonNull RagTenantContext context,
                                             @NonNull SourceQuery query);

    /**
     * 按 ID 查询单个来源。
     *
     * @param context 租户上下文
     * @param sourceId 来源 ID
     * @return 来源视图；不存在返回 {@code null}
     */
    @Nullable CorpusSource findSource(@NonNull RagTenantContext context,
                                       @NonNull String sourceId);

    /**
     * 执行一次校验状态转移（审核/拒绝/撤回/重新认领）。
     *
     * <p>⚠️ 实现方<b>必须</b>先校验转移合法性
     * （{@code VerificationTransition.requireAllowed}），
     * 非法转移必须拒绝而非静默执行。
     *
     * @param context 租户上下文
     * @param sourceId 来源 ID
     * @param decision 校验决定（含证据与决定人）
     * @return 转移后的来源视图；来源不存在返回 {@code null}
     */
    @Nullable CorpusSource applyVerification(@NonNull RagTenantContext context,
                                             @NonNull String sourceId,
                                             @NonNull VerificationDecision decision);

    /**
     * 列出某来源下的文档载体。
     *
     * @param context 租户上下文
     * @param sourceId 来源 ID
     * @return 文档列表
     */
    @NonNull List<CorpusDocument> listDocuments(@NonNull RagTenantContext context,
                                                @NonNull String sourceId);

    /**
     * 执行一次<b>载体级</b>校验状态转移（审核 / 拒绝 / 撤回某个物理载体）。
     *
     * <p><b>⚠️ 载体级与来源级是两个独立轴</b>（RAG-D03 §4）：
     * 同一版本下可只撤回其中一个载体（第三方扫描版）而不动整部教材。
     * 这正是 {@code rag_document.ver_status} 存在的意义。
     *
     * <p><b>⚠️ 实现方必须保证「载体不得高于来源」</b>：
     * 来源当前不是 VERIFIED 时，载体不允许置为 VERIFIED
     * （{@code DocumentVerification.requireSourceAllowsVerified}）。
     * 否则会留下「来源 PENDING、载体 VERIFIED」的自相矛盾记录，
     * 而只看载体标记的检索路径会召回未审核语料。
     *
     * <p><b>⚠️ 实现方必须先校验转移合法性</b>
     * （{@code VerificationTransition.requireAllowed}），
     * 非法转移必须拒绝而非静默执行。
     *
     * @param context 租户上下文
     * @param documentId 载体 ID
     * @param decision 校验决定（含证据与决定人）
     * @return 转移后的载体视图；载体不存在返回 {@code null}
     */
    @Nullable CorpusDocument applyDocumentVerification(@NonNull RagTenantContext context,
                                                         @NonNull String documentId,
                                                         @NonNull DocumentVerificationDecision decision);

    /**
     * 列出维护问题清单（缺向量 / 解析失败 / 待审核）。
     *
     * <p><b>⚠️ 计算属性在 SQL 里算</b>：结果中的 {@code stale}
     * 依赖校验有效期与当前时刻，前端没有租户权威门槛上下文，
     * 推算出来的结论与服务端口径必然不一致。
     *
     * @param context 租户上下文
     * @return 问题清单
     */
    @NonNull CorpusIssues listIssues(@NonNull RagTenantContext context);

    /**
     * 列出某文档下的片段摘要（管理面浏览用）。
     *
     * @param context 租户上下文
     * @param documentId 文档 ID
     * @param limit 返回条数上限
     * @return 片段摘要列表
     */
    @NonNull List<ChunkSummary> listChunks(@NonNull RagTenantContext context,
                                            @NonNull String documentId,
                                            int limit);

    /**
     * 统计租户语料与索引状况。
     *
     * @param context 租户上下文
     * @return 统计视图
     */
    @NonNull CorpusStatistics statistics(@NonNull RagTenantContext context);

    /**
     * 每日检索次数（报表中心 · 每日搜索的问题数）。
     *
     * <p>⚠️ 数据来源是 {@code rag_access_audit} 的 {@code RETRIEVE} 动作计数——
     * 检索链路在每次检索后落审计（见 {@code JdbcTenantScopedRetrieval}）。
     * 系统发起的检索无人类主体（principal_id 为 NULL），不计入任何个人。
     *
     * @param context 租户上下文
     * @param days    最近天数（1～90）
     * @return 连续日期的每日检索次数
     */
    @NonNull DailySearchStatistics dailySearchStatistics(@NonNull RagTenantContext context, int days);

    /**
     * 每日导入语料量（报表中心 · 每日导入 RAG 的资料数）。
     *
     * <p>⚠️ 分三个维度：资料来源（册 / 作品）、文档载体（物理文件）、片段。
     * 三者是 V1 三层模型的不同层级，分别反映「登记了多少资料」「导入了多少文件」
     * 「切出了多少可检索片段」。
     *
     * @param context 租户上下文
     * @param days    最近天数（1～90）
     * @return 连续日期的每日导入量
     */
    @NonNull DailyImportStatistics dailyImportStatistics(@NonNull RagTenantContext context, int days);

    /**
     * 写入语料访问审计（追加写，不可改）。
     *
     * <p>⚠️ RAG-D07 §2.5：「转移必须写审计」。审计表由 DB 触发器强制只追加。
     *
     * @param context 租户上下文
     * @param principalId 操作人
     * @param action 动作（RETRIEVE / INGEST / CITE / EXPORT / REVOKE / ADMIT / REJECT）
     * @param sourceId 关联来源（可为 null）
     */
    void appendAudit(@NonNull RagTenantContext context, @NonNull String principalId,
                     @NonNull String action, @Nullable String sourceId);

    /** 单日检索次数。 */
    record DailySearchCount(@NonNull String date, long searches) {
    }

    /** 每日检索次数聚合（连续日期，缺数据填 0）。 */
    record DailySearchStatistics(
            @NonNull List<DailySearchCount> searchesByDay,
            int generatedDays
    ) {
    }

    /** 单日导入语料量（三层级）。 */
    record DailyImportCount(
            @NonNull String date,
            long sources,
            long documents,
            long chunks
    ) {
    }

    /** 每日导入语料量聚合（连续日期，缺数据填 0）。 */
    record DailyImportStatistics(
            @NonNull List<DailyImportCount> importsByDay,
            int generatedDays
    ) {
    }

    /**
     * 片段摘要（管理面列表项）。
     *
     * @param chunkId 片段 ID
     * @param role 片段角色
     * @param pageNumber 页码
     * @param sectionPath 章节路径
     * @param exerciseNo 题号
     * @param charLength 正文字符数
     * @param hasEmbedding 是否已写入向量
     * @param contentHash 内容校验和
     */
    record ChunkSummary(
            @NonNull String chunkId,
            @NonNull String role,
            int pageNumber,
            @NonNull String sectionPath,
            @Nullable String exerciseNo,
            int charLength,
            boolean hasEmbedding,
            @Nullable String contentHash
    ) {
    }

    /**
     * 批量登记来源的单条结果。
     *
     * <p><b>⚠️ 每条独立成败</b>：批量登记是「尽力而为」——
     * 一行失败（缺 ISBN/DOI、DB 约束冲突、RLS 过滤等）不影响其它行。
     * 故结果逐条返回，而不是「任一行失败即整体回滚」（那会丢入整批已校验通过的来源）。
     *
     * @param index 该行在请求数组中的下标（从 0 开始）
     * @param ok 是否登记成功
     * @param sourceId 成功时的来源 ID（失败为 null）
     * @param error 失败时的原因（成功为 null）
     */
    record BulkItemResult(
            int index,
            boolean ok,
            @Nullable String sourceId,
            @Nullable String error
    ) {
    }

    /**
     * 批量登记来源的结果。
     *
     * <p>⚠️ {@code items} 与入参同序同长：第 i 项对应请求数组第 i 行，
     * 调用方可直接按下标对齐到原始输入，无需二次匹配。
     *
     * @param items 逐条结果（与入参顺序一致）
     */
    record BulkRegistrationResult(@NonNull List<BulkItemResult> items) {
        /** 成功条数（派生）。 */
        public int succeeded() {
            int n = 0;
            for (BulkItemResult r : items) {
                if (r.ok()) n++;
            }
            return n;
        }

        /** 失败条数（派生）。 */
        public int failed() {
            return items.size() - succeeded();
        }
    }

    /**
     * 批量登记语料来源（阶段① PENDING）。
     *
     * <p><b>⚠️ 逐条独立事务</b>：每条来源在自己的事务内登记
     * （复用 {@link #registerSource} 的「set_config → INSERT → 审计」两步），
     * 任一失败整体回滚该条、不影响其它条。
     * 登记结果与 {@link #registerSource} 同语义：恒为
     * {@code trust_tier=UNVERIFIED}、{@code ver_status=PENDING}。
     *
     * <p><b>⚠️ 为什么不在端口层做批量 SQL</b>：登记必须逐条留痕
     * （RAG-D07 §2.5），且 ISBN/DOI 自洽校验、内容哈希格式等前置校验
     * 都发生在领域命令构造期——逐条调用能精确到「第几行为什么失败」，
     * 否则整批失败只会给出一个无指向的错误。
     *
     * @param context 租户上下文
     * @param registrations 登记命令列表（已是合法领域对象，构造期校验通过）
     * @param principalId 登记人（UUID），对整批统一留痕
     * @return 逐条结果（与入参同序同长）
     */
    @NonNull
    BulkRegistrationResult registerSources(@NonNull RagTenantContext context,
                                           @NonNull List<SourceRegistration> registrations,
                                           @NonNull String principalId);
}
