package cn.studykid.rag.management.ports;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.rag.management.core.CorpusDocument;
import cn.studykid.rag.management.core.CorpusIssues;
import cn.studykid.rag.management.core.CorpusSource;
import cn.studykid.rag.management.core.CorpusStatistics;
import cn.studykid.rag.management.core.DocumentVerificationDecision;
import cn.studykid.rag.management.core.MetadataConfirmation;
import cn.studykid.rag.management.core.MetadataConfirmationResult;
import cn.studykid.rag.management.core.RagManagementContext;
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
 * <p><b>⚠️ 租户上下文的来源</b>：本端口接收的租户信息（身份、G-3 权威门槛）
 * 的<b>权威源是 platform</b>（RAG-D00 §4），RAG 库里<b>不存</b>租户清单（V4 迁移已移除
 * {@code rag_tenant}）。实现方在事务内 {@code set_config('app.tenant_id', ..., true)}，
 * 使 V1 的 RLS FORCE 策略生效。
 *
 * <p><b>⚠️ 写入禁令</b>：本端口<b>不提供</b>物理删除语料的方法。
 * 撤回的唯一手段是 {@code ver_status='REVOKED'}
 * （RAG-D07 §8 INV-4-a：禁止把物理 DELETE 当作撤回手段，
 * 因为 DELETE 会级联抹除历史引用，破坏可溯源性）。
 *
 * <p><b>⚠️ 读写方法都收 {@link RagManagementContext}</b>（RAG-D11 第四刀·第一刀读、
 * 第二刀写）：作用域是「这次动作落在哪一批行」的唯一答案，
 * 租户档与平台档的区别<b>不在端口签名上表达成两个方法</b>，
 * 而是由实现侧的<b>写通道</b>收口——
 * 租户档走应用连接（{@code rag_current_tenant()}，RLS 兜底），
 * 平台档走独立运维数据源（{@code rag_global_tenant_id()} 字面函数，
 * 见 {@code RagOpsSessionPort}）。
 *
 * <p><b>⚠️ 跨租户盘点档（ALL）在任何写方法上都是错误</b>：
 * 「同时往 N 个租户里写」不是本端口的能力，实现侧直接拒绝。
 * 该拒绝的位置在实现层而不是控制器，因为控制器之外还有批量与工单入口。
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
     * @param context 作用域上下文（租户身份与门槛由 platform 下发；盘点档拒绝写）
     * @param registration 登记信息
     * @param principalId 登记人（UUID）
     * @return 登记后的来源视图
     */
    @NonNull CorpusSource registerSource(@NonNull RagManagementContext context,
                                         @NonNull SourceRegistration registration,
                                         @NonNull String principalId);

    /**
     * 按查询条件列出来源。
     *
     * @param context 作用域上下文（租户档／平台全局档）
     * @param query 查询条件
     * @return 匹配的来源列表
     */
    @NonNull List<CorpusSource> listSources(@NonNull RagManagementContext context,
                                             @NonNull SourceQuery query);

    /**
     * 按 ID 查询单个来源。
     *
     * @param context 作用域上下文（租户档／平台全局档）
     * @param sourceId 来源 ID
     * @return 来源视图；不存在返回 {@code null}
     */
    @Nullable CorpusSource findSource(@NonNull RagManagementContext context,
                                       @NonNull String sourceId);

    /**
     * 执行一次校验状态转移（审核/拒绝/撤回/重新认领）。
     *
     * <p>⚠️ 实现方<b>必须</b>先校验转移合法性
     * （{@code VerificationTransition.requireAllowed}），
     * 非法转移必须拒绝而非静默执行。
     *
     * @param context 作用域上下文（租户档／平台全局档；盘点档拒绝写）
     * @param sourceId 来源 ID
     * @param decision 校验决定（含证据与决定人）
     * @return 转移后的来源视图；来源不存在返回 {@code null}
     */
    @Nullable CorpusSource applyVerification(@NonNull RagManagementContext context,
                                             @NonNull String sourceId,
                                             @NonNull VerificationDecision decision);

    /**
     * 确认来源元数据（交互文档 §4.3「产出确认」/ V1.0.2 §5 R1）。
     *
     * <p><b>为什么需要这一步</b>：文件上传后书名/出版社/ISBN 等由系统**推断**得出
     * （见 {@code MetadataInference}）。推断值只是**待确认的建议**，不是权威值——
     * 未经人确认就送审，等于让审核员审一份机器猜出来的元数据。
     *
     * <p><b>⚠️ 这是「确认」不是「修改」</b>：本方法只记录
     * 「谁在何时确认了什么」这一事实。若人改了字段，那是一次普通的元数据更新，
     * 由调用方先走更新再确认——两者**分开**，否则「确认」会掩盖掉改动本身。
     *
     * <p><b>⚠️ 幂等</b>：重复确认不报错（返回当前视图），
     * 但**不改写首次确认人**——「谁最先确认」比「最后谁点了按钮」更有意义。
     *
     * @param context 作用域上下文（租户档／平台全局档；盘点档拒绝写）
     * @param sourceId  来源 ID
     * @param info      确认时的元数据快照（记录被确认的值，便于追溯）
     * @param principalId 确认人（UUID，必填：不能出现无主的确认）
     * @return 确认后的来源视图；来源不存在返回 {@code null}
     */
    @Nullable MetadataConfirmationResult confirmMetadata(@NonNull RagManagementContext context,
                                                         @NonNull String sourceId,
                                                         @NonNull MetadataConfirmation info,
                                                         @NonNull String principalId);


    /**
     * 列出审核待办（交互文档 §4.6「审核收件箱」）。
     *
     * <p><b>它解决什么</b>：审核员当前要「搜索 → 找人 → 读上下文」才知道
     * 今天该审哪几本。本方法把「该不该我审、卡在哪一步」一次性算好。
     *
     * <p>⚠️ 只返回**还需要人动手**的来源（已完成 / 已撤回的不进队列）：
     * 把终态混进待办里，待办数就永远清不零，人会逐渐不再看它。
     *
     * @param context 作用域上下文
     * @param limit   条数上限
     * @param currentUser 当前调用者主体 ID（用于「审核员 ≠ 采集人」的界面提示），可空
     * @return 待办列表（按创建时间倒序）
     */
    java.util.List<cn.studykid.rag.management.core.ReviewQueueEntry> listReviewQueue(
            @NonNull RagManagementContext context,
            int limit,
            @Nullable String currentUser);


    /**
     * 列出语料生命周期状态（交互文档 §4.7）。
     *
     * <p>⚠️ 过期与「即将到期」一律由服务端计算（{@link LifecycleRules}）：
     * 前端自行算会与检索侧 {@code admissiblePredicate} 的边界口径漂移。
     *
     * @param context 作用域上下文
     * @param limit   条数上限
     * @return 生命周期列表（已过期的排前面）
     */
    java.util.List<cn.studykid.rag.management.core.LifecycleEntry> listLifecycle(
            @NonNull RagManagementContext context, int limit);


    /**
     * 列出可做复核的审核员（签发表单的下拉数据源）。
     *
     * <p><b>⚠️ 必须排除「本人」与「采集人」</b>：
     * 后端 SoD 会拒绝「复核人 = 决定人」与「复核人 = 采集人」，
     * 若下拉里仍列出这些人，用户选下去必然 409——
     * 让用户点一个注定被拒的选项是产品缺陷。
     *
     * <p>⚠️ 只返回 {@code enabled = true} 且 {@code can_co_sign = true} 的条目：
     * 可执行审核 ≠ 可做复核（复核是双人签署的第二签，通常只授予少数资深审核员）。
     *
     * @param context     作用域上下文
     * @param excludeIds  需要排除的主体 ID（通常是「本人」与「采集人」）
     * @return 可复核的审核员列表（按显示名排序）
     */
    java.util.List<cn.studykid.rag.management.core.ReviewerRosterEntry> listCoSigners(
            @NonNull RagManagementContext context, java.util.Collection<String> excludeIds);

    /**
     * 登记/更新一名审核员（管理面维护名录）。
     *
     * <p>⚠️ 幂等：同一主体重复登记为覆盖更新（显示名与权限可变），
     * 但不改写 {@code created_at}——「什么时候开始能审核」是有审计意义的事实。
     *
     * @param context 作用域上下文
     * @param entry   名录条目
     * @return 登记后的条目
     */
    cn.studykid.rag.management.core.ReviewerRosterEntry upsertReviewer(
            @NonNull RagManagementContext context,
            cn.studykid.rag.management.core.ReviewerRosterEntry entry);

    /**
     * 列出某来源下的文档载体。
     *
     * @param context 作用域上下文（租户档／平台全局档）
     * @param sourceId 来源 ID
     * @return 文档列表
     */
    @NonNull List<CorpusDocument> listDocuments(@NonNull RagManagementContext context,
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
     * @param context 作用域上下文（租户档／平台全局档；盘点档拒绝写）
     * @param documentId 载体 ID
     * @param decision 校验决定（含证据与决定人）
     * @return 转移后的载体视图；载体不存在返回 {@code null}
     */
    @Nullable CorpusDocument applyDocumentVerification(@NonNull RagManagementContext context,
                                                         @NonNull String documentId,
                                                         @NonNull DocumentVerificationDecision decision);

    /**
     * 导入一份物理文档载体（上传落桶后的登记）。
     *
     * <p><b>⚠️ 这条路径与「手填 objectRef 的登记」是两件不同的事</b>（RAG-D03 §4）：
     * 来源（{@code rag_source}）是书目级元数据，文档载体（{@code rag_document}）是物理文件——
     * 同一来源下可有多份载体（官方电子版 + 第三方扫描件）。本方法在<b>已有来源</b>下
     * 新建一份载体，并把已落桶的 {@code objectRef} 与<b>服务端重算</b>的 {@code contentHash} 落下。
     *
     * <p><b>⚠️ 新建载体恒为「待解析 · 待校验 · 不可检索」</b>：
     * {@code parse_status='PENDING'}、{@code ver_status='PENDING'}、{@code retrievable=false}、
     * {@code license_status='PENDING'}。摄入（Docling + BGE-M3）与审核是后续独立步骤，
     * 不在此处发生（A-008 硬阻断下摄入链为占位，强行解析只会产出糙结构）。
     *
     * <p><b>⚠️ 版本坐标（edition）的处理</b>：{@code rag_document.edition_id} 强制 NOT NULL。
     * 若来源下已存在版本坐标则用其第一条；否则自动建一个「默认版本」坐标
     * （{@code revision_code='src-<sourceId>'} 以保证唯一），让「只录入文档、暂未维护版本」
     * 的来源也能挂上载体。{@code DocumentImportCommand} 携带的可选版本字段可覆盖默认值。
     *
     * <p><b>⚠️ 必须留痕</b>：导入动作写 {@code rag_access_audit}（动作 {@code INGEST}），
     * 与状态变更同事务——「谁导入了这份语料」是后续争议追溯的第一问。
     *
     * @param context  作用域上下文（租户档／平台全局档；盘点档拒绝写）
     * @param sourceId 所属来源 ID（不存在返回 {@code null}）
     * @param command  导入命令（含 carrier / title / objectRef / contentHash / 可选版本字段）
     * @param principalId 操作人（UUID）
     * @return 新建的载体视图；来源不存在返回 {@code null}
     */
    /**
     * 读取来源的登记人（{@code rag_source.registered_by}）。
     *
     * <p><b>⚠️ V1.0.2 S3-2 新增</b>：用于「审核员 ≠ 采集员」自审拦截。
     * 返回 null 表示该来源没有登记人记录（历史数据或平台档），
     * 此时<b>不阻断</b>审核——但审计记录仍会保留实际审核人。
     *
     * @param context  作用域上下文
     * @param sourceId 来源 ID
     * @return 登记人主体 ID；无记录返回 null
     */
    @Nullable
    String findRegisteredBy(@NonNull RagManagementContext context, @NonNull String sourceId);

    @Nullable CorpusDocument importDocument(@NonNull RagManagementContext context,
                                           @NonNull String sourceId,
                                           @NonNull DocumentImportCommand command,
                                           @NonNull String principalId);

    /**
     * 列出维护问题清单（缺向量 / 解析失败 / 待审核）。
     *
     * <p><b>⚠️ 计算属性在 SQL 里算</b>：结果中的 {@code stale}
     * 依赖校验有效期与当前时刻，前端没有租户权威门槛上下文，
     * 推算出来的结论与服务端口径必然不一致。
     *
     * @param context 作用域上下文（租户档／平台全局档）
     * @return 问题清单
     */
    @NonNull CorpusIssues listIssues(@NonNull RagManagementContext context);

    /**
     * 列出某文档下的片段摘要（管理面浏览用）。
     *
     * <p><b>roles 筛选</b>：非空时只返回命中任一角色的片段（SQL 端
     * {@code role = ANY(...)}，不是取回后再过滤——否则 LIMIT 语义会错：
     * 「前 N 条里的答案」几乎恒为空集，抽检员依然够不到答案片段）。
     *
     * @param context 作用域上下文（租户档／平台全局档）
     * @param documentId 文档 ID
     * @param limit 返回条数上限
     * @param roles 角色白名单；null/空 = 不过滤
     * @return 片段摘要列表
     */
    @NonNull List<ChunkSummary> listChunks(@NonNull RagManagementContext context,
                                            @NonNull String documentId,
                                            int limit,
                                            java.util.Collection<String> roles);

    /**
     * 批量统计来源的载体（文档）数量（列表页区分「仅元数据」来源用）。
     *
     * <p><b>为什么是批量</b>：列表页一页最多几十个来源，逐条 COUNT 是
     * N+1；一条 {@code GROUP BY source_id} 就够。查不到的 sourceId
     * 由调用方按 0 处理（0 = 仅元数据登记，语义等同）。
     *
     * @param context 作用域上下文（租户档／平台全局档）
     * @param sourceIds 来源 ID 集合（空集合返回空 Map）
     * @return sourceId → 载体数量（只含 count>0 的条目）
     */
    @NonNull Map<String, Integer> documentCounts(@NonNull RagManagementContext context,
                                                           @NonNull Collection<String> sourceIds);

    /**
     * 统计租户语料与索引状况。
     *
     * @param context 作用域上下文（租户档／平台全局档）
     * @return 统计视图
     */
    @NonNull CorpusStatistics statistics(@NonNull RagManagementContext context);

    /**
     * 每日检索次数（报表中心 · 每日搜索的问题数）。
     *
     * <p>⚠️ 数据来源是 {@code rag_access_audit} 的 {@code RETRIEVE} 动作计数——
     * 检索链路在每次检索后落审计（见 {@code JdbcTenantScopedRetrieval}）。
     * 系统发起的检索无人类主体（principal_id 为 NULL），不计入任何个人。
     *
     * @param context 作用域上下文（租户档／平台全局档）
     * @param days    最近天数（1～90）
     * @return 连续日期的每日检索次数
     */
    @NonNull DailySearchStatistics dailySearchStatistics(@NonNull RagManagementContext context, int days);

    /**
     * 每日导入语料量（报表中心 · 每日导入 RAG 的资料数）。
     *
     * <p>⚠️ 分三个维度：资料来源（册 / 作品）、文档载体（物理文件）、片段。
     * 三者是 V1 三层模型的不同层级，分别反映「登记了多少资料」「导入了多少文件」
     * 「切出了多少可检索片段」。
     *
     * @param context 作用域上下文（租户档／平台全局档）
     * @param days    最近天数（1～90）
     * @return 连续日期的每日导入量
     */
    @NonNull DailyImportStatistics dailyImportStatistics(@NonNull RagManagementContext context, int days);

    /**
     * 写入语料访问审计（追加写，不可改）。
     *
     * <p>⚠️ RAG-D07 §2.5：「转移必须写审计」。审计表由 DB 触发器强制只追加。
     *
     * @param context 作用域上下文（租户档／平台全局档；盘点档拒绝写）
     * @param principalId 操作人
     * @param action 动作（RETRIEVE / INGEST / CITE / EXPORT / REVOKE / ADMIT / REJECT）
     * @param sourceId 关联来源（可为 null）
     */
    void appendAudit(@NonNull RagManagementContext context, @NonNull String principalId,
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
     * @param context 作用域上下文（租户档／平台全局档；盘点档拒绝写）
     * @param registrations 登记命令列表（已是合法领域对象，构造期校验通过）
     * @param principalId 登记人（UUID），对整批统一留痕
     * @return 逐条结果（与入参同序同长）
     */
    @NonNull
    BulkRegistrationResult registerSources(@NonNull RagManagementContext context,
                                           @NonNull List<SourceRegistration> registrations,
                                           @NonNull String principalId);

    /**
     * 文档载体导入命令（上传落桶后的登记）。
     *
     * <p><b>⚠️ {@code objectRef} / {@code contentHash} 由服务端落桶与重算后填入</b>，
     * 不来自前端手填（前端算的哈希只用于即时展示，落库以服务端为准）。
     * 版本坐标字段（edition*）全部可空：留空时仓储自动建/复用「默认版本」。
     *
     * <p><b>⚠️ carrier 受 {@code rag_document_carrier_chk} 约束</b>（PDF/SCAN/EPUB/HTML/TXT），
     * 构造期即校验，不把非法值拖到 DB 的 CHECK 报错（那会变成 500 而非 400）。
     *
     * @param carrier       载体类型（PDF/SCAN/EPUB/HTML/TXT），大写
     * @param title         文档标题（非空）
     * @param objectRef     对象存储定位符（落桶后由服务端产出，非空）
     * @param contentHash   服务端重算的 SHA-256（64 位十六进制，非空）
     * @param editionSubject      可选版本坐标：学科
     * @param editionGrade        可选版本坐标：年级
     * @param editionTerm         可选版本坐标：册次（上册/下册/全一册）
     * @param editionPublisher    可选版本坐标：出版社
     * @param editionRevisionCode 可选版本坐标：版次代码
     */
    record DocumentImportCommand(
            @NonNull String carrier,
            @NonNull String title,
            @NonNull String objectRef,
            @NonNull String contentHash,
            @Nullable String editionSubject,
            @Nullable String editionGrade,
            @Nullable String editionTerm,
            @Nullable String editionPublisher,
            @Nullable String editionRevisionCode
    ) {
        public DocumentImportCommand {
            if (carrier == null || carrier.isBlank()) {
                throw new IllegalArgumentException("carrier 不能为空");
            }
            // ⚠️ 大小写归一：前端下拉可能给小写，落库前统一大写，避免 CHECK 拒收。
            carrier = carrier.toUpperCase();
            if (!java.util.Set.of("PDF", "SCAN", "EPUB", "HTML", "TXT").contains(carrier)) {
                throw new IllegalArgumentException(
                        "carrier 必须是 PDF / SCAN / EPUB / HTML / TXT，实际：" + carrier);
            }
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("title 不能为空");
            }
            if (objectRef == null || objectRef.isBlank()) {
                throw new IllegalArgumentException("objectRef 不能为空（应由服务端落桶后产出）");
            }
            // ⚠️ 与 SourceRegistration 同源的校验：CHAR(64) 短值会被 PG 静默空格填充，
            // 失去「检测语料被静默替换」能力。
            if (contentHash == null || !contentHash.matches("^[0-9a-fA-F]{64}$")) {
                throw new IllegalArgumentException("contentHash 必须是 64 位十六进制 SHA-256");
            }
        }
    }
}
