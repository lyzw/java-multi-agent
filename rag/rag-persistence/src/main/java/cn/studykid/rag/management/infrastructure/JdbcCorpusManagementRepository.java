package cn.studykid.rag.management.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.core.scope.RagCorpusScope;
import cn.studykid.rag.core.scope.RagScope;
import cn.studykid.rag.core.source.SourceVerification.VerificationMethod;
import cn.studykid.rag.core.source.SourceVerification.VerificationStatus;
import cn.studykid.rag.core.source.TrustTier;
import cn.studykid.rag.infrastructure.ops.RagOpsSessionPort;
import cn.studykid.rag.infrastructure.ops.RagOpsUnavailableException;
import cn.studykid.rag.management.core.CorpusDocument;
import cn.studykid.rag.management.core.CorpusIssues;
import cn.studykid.rag.management.core.CorpusSource;
import cn.studykid.rag.management.core.MetadataConfirmation;
import cn.studykid.rag.management.core.LifecycleEntry;
import cn.studykid.rag.management.core.LifecycleRules;
import cn.studykid.rag.management.core.ReviewQueueEntry;
import cn.studykid.rag.management.core.ReviewerRosterEntry;
import cn.studykid.rag.management.core.ReviewQueueRules;
import cn.studykid.rag.management.core.MetadataConfirmationResult;
import cn.studykid.rag.management.core.CorpusStatistics;
import cn.studykid.rag.management.core.DocumentVerification;
import cn.studykid.rag.management.core.DocumentVerificationDecision;
import cn.studykid.rag.management.core.RagManagementContext;
import cn.studykid.rag.management.core.RagTenantContext;
import cn.studykid.rag.management.core.SourceQuery;
import cn.studykid.rag.management.core.SourceRegistration;
import cn.studykid.rag.management.core.VerificationDecision;
import cn.studykid.rag.management.core.VerificationTransition;
import cn.studykid.rag.management.ports.CorpusManagementPort;
import cn.studykid.rag.management.ports.CorpusManagementPort.BulkItemResult;
import cn.studykid.rag.management.ports.CorpusManagementPort.BulkRegistrationResult;
import cn.studykid.rag.management.ports.CorpusManagementPort.DocumentImportCommand;

/**
 * 语料数据管理的 JDBC 实现（RAG-D07 §2 准入流程 / RAG-D03 §2 数据模型）。
 *
 * <p><b>⚠️ 租户隔离铁律</b>：本类的<b>每个</b>方法都在
 * {@link TransactionTemplate} 内执行「建立作用域会话 → 业务 SQL」两步，确保：
 * <ol>
 *   <li>会话变量与业务 SQL 落在<b>同一事务、同一条连接</b>上
 *       （{@code is_local=true} 表示「仅在当前事务有效」，
 *       脱离事务则立即失效，表现为「查不到任何行」且不报错）；</li>
 *   <li>V8 的 RLS FORCE 策略对本次读写生效——<b>真正隔离由 RLS 保证</b>，
 *       SQL 里的租户谓词是<b>功能性的</b>第二层。读侧按作用域绑定
 *       （{@link RagManagementContext#queryTenantId()}：租户档取该租户、
 *       平台档取全局哨兵），写侧按<b>写通道</b>收口：
 *       租户档仍走应用连接 + {@code rag_current_tenant()}（行为与第一刀逐字节一致），
 *       平台档走独立运维通道 + {@code rag_global_tenant_id()} <b>字面函数</b>。
 *       ⚠️ 平台档的租户不是参数：SQL 文本里根本没有可绑定租户的位置，
 *       所以「把别的桶写进去」这句话在实现层拼不出来——
 *       这是全局写唯一的护栏（{@code rag_ops} 带 BYPASSRLS，RLS 对它不设防）。</li>
 * </ol>
 *
 * <p><b>⚠️ 审计与状态变更必须同事务</b>（RAG-D07 §2.5）：
 * 「转移必须写审计」。若审计写失败而状态已改，会留下无痕的状态变更——
 * 恰好是治理流程最不能接受的情形。因此二者放在<b>同一个</b>事务里，
 * 任一失败整体回滚。
 *
 * <p><b>⚠️ source 与 document 的关联路径</b>：V1 的三层模型是
 * {@code source（作品）→ document（载体）→ chunk（片段）}，
 * {@code rag_chunk} <b>没有 source_id 列</b>，来源须经 {@code rag_document} 中转：
 * {@code JOIN rag_document doc ON c.document_id = doc.document_id}。
 * 写 {@code c.source_id} 会得到 {@code column c.source_id does not exist}。
 */
public class JdbcCorpusManagementRepository implements CorpusManagementPort {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(JdbcCorpusManagementRepository.class);

    /**
     * 片段正文在管理面列表中的预览字符数。
     *
     * <p><b>⚠️ 为什么要截断</b>：一册教材 2400+ 片段，整册正文灌进响应体
     * 既拖慢列表、又把"预览"变成了"全文阅读器"。
     * 512 字足以覆盖一道完整题目或一段讲解的起头，支撑"角色判得对不对"的判断；
     * 需要完整原文时走单片段详情（后续扩展）。
     */
    private static final int CHUNK_TEXT_PREVIEW_CHARS = 512;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * 平台全局语料的写通道（可为 null = 未接入）。
     *
     * <p>⚠️ null 时平台档写动作抛 {@link RagOpsUnavailableException}（503 fail-closed），
     * <b>不</b>回落到应用连接：回落的结果不是「写得差一点」，而是
     * RLS 把全局写静默滤成 0 行、控制台却报告成功。
     */
    private final RagOpsSessionPort opsSession;

    /**
     * 合法审计动作白名单（与 V3 迁移的 {@code rag_access_audit_action_chk} 一致）。
     *
     * <p>⚠️ ADMIT / REJECT 为 V3 新增——此前审核动作被迫借道 INGEST，
     * 使「审核员的判定行为」没有独立事件流（RAG-D07 §7 记载的缺口）。
     */
    private static final List<String> AUDIT_ACTIONS = List.of(
            "RETRIEVE", "INGEST", "CITE", "EXPORT", "REVOKE", "ADMIT", "REJECT");

    public JdbcCorpusManagementRepository(@NonNull JdbcTemplate jdbcTemplate,
                                          @NonNull TransactionTemplate transactionTemplate) {
        this(jdbcTemplate, transactionTemplate, null);
    }

    public JdbcCorpusManagementRepository(@NonNull JdbcTemplate jdbcTemplate,
                                          @NonNull TransactionTemplate transactionTemplate,
                                          @Nullable RagOpsSessionPort opsSession) {
        if (jdbcTemplate == null || transactionTemplate == null) {
            throw new IllegalArgumentException("依赖不能为空");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.opsSession = opsSession;
    }

    // ---------------------------------------------------------------------
    // 写通道
    // ---------------------------------------------------------------------

    /**
     * 一次写动作落在哪条连接、以什么租户表达式收口。
     *
     * <p><b>三个表达式字段是一体的，且都不是参数</b>：{@code tenantExpr} 与
     * {@code scopeExpr} 只可能由通道决定，不由调用方传入——
     * 这正是「平台档结构性写不到租户桶」的实现方式：SQL 文本里
     * 根本没有可以填「另一个桶」的问号位置。
     *
     * <p>⚠️ {@code scopeExpr} 不是冗余的：{@code rag_source}/{@code rag_document}
     * 的 {@code scope} 列默认 {@code 'TENANT'}，且有
     * {@code *_scope_ck} 约束「{@code scope='PLATFORM' ⇔ tenant_id=哨兵}」。
     * 全局写若只换租户列而不换 scope 列，会被该约束<b>直接拒绝</b>
     * （约束不是策略，BYPASSRLS 也绕不过去）。
     *
     * @param tenantExpr SQL 里的租户表达式（{@code rag_current_tenant()} 或
     *                   {@code rag_global_tenant_id()}）
     * @param scopeExpr  SQL 里的作用域字面量（{@code 'TENANT'} 或 {@code 'PLATFORM'}）
     * @param tenantId   回读时绑定的租户（平台档为哨兵常量，非入参）
     * @param global     true = 运维通道（不设置会话租户）
     */
    private record WriteChannel(String tenantExpr,
                                @NonNull String scopeExpr,
                                @NonNull String tenantId,
                                boolean global,
                                JdbcTemplate template,
                                TransactionTemplate tx) {

        /** 应用通道：会话租户由 {@code app.tenant_id} 决定，RLS 同时兜底。 */
        static WriteChannel ofTenant(JdbcTemplate jdbcTemplate, TransactionTemplate tx,
                                     @NonNull String tenantId) {
            return new WriteChannel("rag_current_tenant()", "'TENANT'", tenantId,
                    false, jdbcTemplate, tx);
        }

        /** 运维通道：租户在 SQL 文本里就是全局哨兵函数，没有可替换的位置。 */
        static WriteChannel ofGlobal(JdbcTemplate template, TransactionTemplate tx) {
            return new WriteChannel("rag_global_tenant_id()", "'PLATFORM'",
                    RagScope.GLOBAL_TENANT_ID, true, template, tx);
        }

        /**
         * 事务内的先决步骤。
         *
         * <p>⚠️ 平台档<b>什么都不做</b>，而且<b>不能</b>改成「把哨兵写进会话变量」：
         * {@code rag_current_tenant()} 撞哨兵直接 RAISE（SQLSTATE 42501），
         * 那是迁移 V8 的越权封堵，属承重设计。
         */
        void open() {
            if (global) {
                return;
            }
            // ⚠️ 必须用 queryForObject() 而非 update()：set_config 是 SELECT（返回一行），
            //    update() 期望「无结果集」，否则抛 DataIntegrityViolation
            //    （A result was returned when none was expected）。
            template.queryForObject(
                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);
        }
    }

    /**
     * 按作用域解析写通道，<b>并在通道的事务里</b>执行写动作。
     *
     * <p>事务与「会话窗」都由本方法负责，写站点只写业务 SQL——
     * 因为「忘记开事务」的失败方式是静默的（会话变量立即失效、
     * RLS 滤掉所有行、报告 0 行成功），不能指望每个站点各写一遍。
     *
     * <p>平台档整段动作包在 {@link RagOpsSessionPort#inGlobalCorpusSession} 内：
     * 窗口外取不到连接，所以「忘了开窗口」的失败方式是异常而不是静默写错桶。
     *
     * @param context 管理上下文（作用域 + 可空租户）
     * @param action  以通道为参数的写动作
     * @param <T>     返回值
     */
    private <T> T writeVia(@NonNull RagManagementContext context,
                           @NonNull Function<WriteChannel, T> action) {
        if (context.scope() == RagCorpusScope.TENANT) {
            return inTransaction(WriteChannel.ofTenant(
                    jdbcTemplate, transactionTemplate,
                    context.requireTenant().tenantId()), action);
        }
        if (context.scope() == RagCorpusScope.ALL) {
            throw new IllegalStateException("跨租户盘点档不允许任何写动作（只读视图）");
        }
        if (opsSession == null) {
            throw new RagOpsUnavailableException("rag.ops.datasource",
                    "平台全局语料写通道未接入：需要运维数据源（rag_ops），不回落应用角色");
        }
        return opsSession.inGlobalCorpusSession(
                (opsTemplate, opsTx) -> inTransaction(
                        WriteChannel.ofGlobal(opsTemplate, opsTx), action));
    }

    /**
     * 「开事务 → {@link WriteChannel#open()} → 执行」三步；
     * ⚠️ 三步顺序是承重结构：会话变量只在当前事务内有效。
     */
    private <T> T inTransaction(@NonNull WriteChannel channel,
                                @NonNull Function<WriteChannel, T> action) {
        return channel.tx().execute(status -> {
            channel.open();
            return action.apply(channel);
        });
    }

    // ---------------------------------------------------------------------
    // 来源登记与查询
    // ---------------------------------------------------------------------

    @Override
    @NonNull
    public CorpusSource registerSource(@NonNull RagManagementContext context,
                                        @NonNull SourceRegistration registration,
                                        @NonNull String principalId) {
        // ⚠️ 登记态固定为 UNVERIFIED / PENDING / NONE：
        //    采集员不得自行提升权威等级或置 VERIFIED（RAG-D07 §2.2 角色表）。
        registration.requireIdentifierConsistent(TrustTier.UNVERIFIED);
        // ⚠️ source_id 在应用侧生成（而非用 gen_random_uuid() 后再回查）：
        //    「插入后按 created_at DESC 取最新一条」在并发登记下会取错行——
        //    两条登记的 created_at 可能相同，取到别人的记录。
        String sourceId = UUID.randomUUID().toString();
        return writeVia(context, ch -> {
            ch.template().update("""
                    INSERT INTO rag_source
                        (source_id, tenant_id, scope, source_type, title, publisher, isbn, doi,
                         trust_tier, ver_status, ver_method, ver_evidence, verified_at,
                         verified_by, ver_valid_days, license_status, object_ref, content_hash)
                    VALUES (?::uuid, %s, %s, ?, ?, ?, ?, ?,
                            'UNVERIFIED', 'PENDING', 'NONE', NULL, NULL,
                            NULL, 365, 'PENDING'::rag_license_status, ?, ?)
                    """.formatted(ch.tenantExpr(), ch.scopeExpr()),
                    sourceId, registration.sourceType(), registration.title(),
                    registration.publisher(),
                    blankToNull(registration.isbn()), blankToNull(registration.doi()),
                    blankToNull(registration.objectRef()), blankToNull(registration.contentHash()));
            // ⚠️ 登记也必须留痕（RAG-D07 §2.5：「至少 INGEST（登记）必须落审计」）。
            //    与 INSERT 同事务——审计写不进去则登记整体回滚，
            //    避免出现「库里有来源但无登记审计」的无痕数据。
            appendAuditWithinTenant(ch, principalId, "INGEST", sourceId);

            // ---- 版本五轴落库（RAG-D00 RD-03）----
            //
            // ⚠️ **为什么在同一事务里**：来源行与版本坐标必须同生同灭。
            //    只写来源不写版本，则该来源的载体上传时会退化成「默认版本坐标」
            //    （未指定/全学段），而这个偏差**不可见**——只在检索时表现为
            //    「按七年级数学检索查不到这本」，排查成本极高。
            //
            // ⚠️ **为什么只写完整五轴**：五轴全空的登记（批量导入/存量路径）
            //    维持既有行为（延迟到载体上传时建默认坐标）；**部分填写**在
            //    领域层已报错（partiallyFilledEdition），不会走到这里。
            if (registration.hasFullEdition()) {
                upsertCompleteEdition(ch, sourceId, registration);
            }

            return findSourceWithinTenant(ch.template(), ch.tenantId(), sourceId);
        });
    }

    /**
     * 按登记的完整五轴写入（或复用）版本坐标。
     *
     * <p><b>⚠️ 为什么是 upsert 而不是纯 INSERT</b>：{@code rag_edition_unique}
     * 是 {@code (tenant_id, subject, grade, term, publisher, revision_code)}——
     * **不含 source_id**。若两个来源登记了同一套教材的同一版次（例如同一本书
     * 被两个采集员先后登记），纯 INSERT 会撞唯一约束让登记失败；
     * 而语义上它们本就该共享同一个版本坐标。
     *
     * <p><b>⚠️ 为什么写入的是「归一后」的值</b>：
     * 检索期的 {@code subjectFromDb / gradeFromDb / publisherFromDb} 用枚举
     * {@code displayName()} 逐个比对，**比对不上直接抛异常 → 该校材的任何检索 500**。
     * 而这三列都是 {@code text}，写入不做校验。登记页填的是出版机构名
     * （如「人民教育出版社」）而非版本体系名（「人教版」），直接写入必然踩雷。
     * 故这里统一走 {@code normalizedEdition*}：非枚举值收敛到安全兜底值。
     */
    private void upsertCompleteEdition(@NonNull WriteChannel ch,
                                       @NonNull String sourceId,
                                       @NonNull SourceRegistration registration) {
        ch.template().update(("""
                INSERT INTO rag_edition
                    (tenant_id, source_id, subject, grade, term, publisher, revision_code, scope)
                VALUES (%s, ?::uuid, ?, ?, ?, ?, ?, %s)
                ON CONFLICT (tenant_id, subject, grade, term, publisher, revision_code)
                DO NOTHING
                """).formatted(ch.tenantExpr(), ch.scopeExpr()),
                sourceId,
                registration.normalizedEditionSubject(),
                registration.normalizedEditionGrade(),
                registration.term(),
                registration.normalizedEditionPublisher(),
                registration.revisionCode());
    }

    /**
     * 批量登记来源。
     *
     * <p>⚠️ 逐条调用 {@link #registerSource}（其本身在独立事务内完成
     * 「set_config → INSERT → 审计」），单条失败仅影响该条——
     * 捕获异常后记为失败项，继续下一条。结果与入参<b>同序同长</b>，
     * 调用方按下标即可对齐到原始行。
     */
    @Override
    @NonNull
    public BulkRegistrationResult registerSources(@NonNull RagManagementContext context,
                                                @NonNull List<SourceRegistration> registrations,
                                                @NonNull String principalId) {
        List<BulkItemResult> items = new ArrayList<>(registrations.size());
        for (int i = 0; i < registrations.size(); i++) {
            SourceRegistration registration = registrations.get(i);
            try {
                CorpusSource created = registerSource(context, registration, principalId);
                items.add(new BulkItemResult(i, true, created.sourceId(), null));
            } catch (Exception e) {
                // ⚠️ 单条失败不阻断整批：可能是 ISBN/DOI 自洽校验不过、
                // 内容哈希格式非法、DB 约束冲突或 RLS 过滤等。
                // 把原因原样带回（裁剪掉可能过长的栈信息），让前端逐行展示。
                items.add(new BulkItemResult(i, false, null, shorten(e.getMessage())));
            }
        }
        return new BulkRegistrationResult(items);
    }

    /** 错误信息裁剪，避免把整条堆栈塞进响应体（只保留一线 message）。 */
    private static String shorten(@Nullable String message) {
        if (message == null || message.isBlank()) {
            return "登记失败（无可用错误详情）";
        }
        // ⚠️ 只取第一行：PostgreSQL 的详情常带 "位置" 与 "提示" 多行，
        // 截断到首个换行即可拿到「为什么会失败」的那句话。
        int newline = message.indexOf('\n');
        String first = newline >= 0 ? message.substring(0, newline) : message;
        return first.length() > 400 ? first.substring(0, 397) + "…" : first;
    }

    @Override
    @NonNull
    public List<CorpusSource> listSources(@NonNull RagManagementContext context,
                                             @NonNull SourceQuery query) {
        return transactionTemplate.execute(status -> {
            applyReadScope(context);
            List<Object> args = new ArrayList<>();
            // ⚠️ 租户谓词绑的是「作用域声明的那一批行」而非 rag_current_tenant()：
            //    平台档不建立会话租户，rag_current_tenant() 为 NULL，
            //    等值比较会把全局行一律过滤掉（表现为「管理台看不到平台语料」且不报错）。
            //    RLS 那一层仍按会话租户 ∪ 全局放行，两层同向。
            StringBuilder sql = new StringBuilder("""
                    SELECT source_id, source_type, title, publisher, isbn, doi,
                           trust_tier, ver_status, ver_method, ver_evidence, verified_at,
                           verified_by, ver_valid_days, license_status, object_ref,
                           content_hash, created_at, updated_at,
                           review_started_at, ver_decided_at, metadata_confirmed_at
                    FROM rag_source
                    WHERE tenant_id = ?::uuid
                    """);
            args.add(context.queryTenantId());
            if (query.verificationStatus() != null) {
                sql.append(" AND ver_status = ?::rag_verification_status");
                args.add(query.verificationStatus().name());
            }
            if (query.trustTier() != null) {
                sql.append(" AND trust_tier = ?::rag_trust_tier");
                args.add(query.trustTier().name());
            }
            if (query.keyword() != null && !query.keyword().isBlank()) {
                // ⚠️ 模糊匹配用 ILIKE + 转义 %/_：
                //    否则用户搜索 "50%" 会变成通配符，返回一批无关结果。
                sql.append(" AND (title ILIKE ? ESCAPE '\\' OR isbn ILIKE ? ESCAPE '\\'"
                        + " OR doi ILIKE ? ESCAPE '\\')");
                String pattern = "%" + escapeLike(query.keyword().trim()) + "%";
                args.add(pattern);
                args.add(pattern);
                args.add(pattern);
            }
            if (!query.includeStale()) {
                // ⚠️ 过期是**计算属性**（verified_at + ver_valid_days < now()），
                //    不是状态位（RAG-D07 §3.4），因此必须在 SQL 里算，不能靠枚举过滤。
                sql.append(" AND NOT (ver_status = 'VERIFIED'"
                        + " AND (verified_at IS NULL"
                        + " OR verified_at + (ver_valid_days || ' days')::interval < now()))");
            }
            sql.append(" ORDER BY created_at DESC, source_id LIMIT ? OFFSET ?");
            args.add(query.limit());
            args.add(query.offset());
            return jdbcTemplate.query(sql.toString(), (rs, i) -> mapSource(rs), args.toArray());
        });
    }

    @Override
    @Nullable
    public CorpusSource findSource(@NonNull RagManagementContext context,
                                       @NonNull String sourceId) {
        return transactionTemplate.execute(status -> {
            applyReadScope(context);
            return findSourceWithinTenant(jdbcTemplate, context.queryTenantId(), sourceId);
        });
    }

    @Override
    public java.util.List<ReviewerRosterEntry> listCoSigners(@NonNull RagManagementContext context,
                                                             java.util.Collection<String> excludeIds) {
        String tenantId = context.queryTenantId();
        java.util.Set<String> excluded = excludeIds == null
                ? java.util.Set.of()
                : excludeIds.stream().filter(s -> s != null && !s.isBlank())
                        .map(String::toLowerCase).collect(java.util.stream.Collectors.toSet());
        return transactionTemplate.execute(tx -> {
            RagSessionScope.apply(jdbcTemplate, context);
            java.util.List<ReviewerRosterEntry> rows = jdbcTemplate.query("""
                            SELECT principal_id::text, display_name, can_review, can_co_sign, enabled
                              FROM rag_reviewer_roster
                             WHERE tenant_id = ?::uuid
                               AND enabled = true
                               AND can_co_sign = true
                             ORDER BY display_name
                            """,
                    (rs, i) -> new ReviewerRosterEntry(
                            rs.getString(1), rs.getString(2),
                            rs.getBoolean(3), rs.getBoolean(4), rs.getBoolean(5)),
                    tenantId);
            if (rows == null) {
                return java.util.List.<ReviewerRosterEntry>of();
            }
            // ⚠️ 排除本人与采集人：后端 SoD 会拒绝这些人，
            //    下拉里留着他们等于让用户选一个注定 409 的选项。
            if (excluded.isEmpty()) {
                return rows;
            }
            return rows.stream()
                    .filter(r -> !excluded.contains(r.principalId().toLowerCase()))
                    .toList();
        });
    }

    @Override
    public java.util.@NonNull List<ReviewerRosterEntry> listReviewers(
            @NonNull RagManagementContext context) {
        return transactionTemplate.execute(status -> {
            applyReadScope(context);
            // ⚠️ 不过滤 enabled：停用项也要出现（否则管理员无法"重新启用"一个看不到的人）。
            //    与 co-signers 的差别见端口 javadoc。
            java.util.List<ReviewerRosterEntry> rows = jdbcTemplate.query("""
                            SELECT principal_id, display_name, can_review, can_co_sign, enabled
                              FROM rag_reviewer_roster
                             WHERE tenant_id = ?::uuid
                             ORDER BY enabled DESC, display_name
                            """,
                    (rs, i) -> new ReviewerRosterEntry(
                            rs.getString(1), rs.getString(2),
                            rs.getBoolean(3), rs.getBoolean(4), rs.getBoolean(5)),
                    context.queryTenantId());
            return rows == null ? java.util.List.<ReviewerRosterEntry>of() : rows;
        });
    }

    @Override
    public ReviewerRosterEntry upsertReviewer(@NonNull RagManagementContext context,
                                              @NonNull ReviewerRosterEntry entry) {
        return writeVia(context, ch -> {
            // ⚠️ 幂等覆盖：显示名与权限可改，但 created_at 只在首次插入时写，
            //    updated_at 每次刷新——「什么时候开始能审核」是审计事实。
            ch.template().update("""
                            INSERT INTO rag_reviewer_roster
                                (tenant_id, principal_id, display_name,
                                 can_review, can_co_sign, enabled, scope)
                            VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?)
                            ON CONFLICT (tenant_id, principal_id) DO UPDATE
                               SET display_name = EXCLUDED.display_name,
                                   can_review   = EXCLUDED.can_review,
                                   can_co_sign  = EXCLUDED.can_co_sign,
                                   enabled      = EXCLUDED.enabled,
                                   updated_at   = now()
                            """,
                    ch.tenantId(), entry.principalId(), entry.displayName(),
                    entry.canReview(), entry.canCoSign(), entry.enabled(),
                    ch.tenantId().equals(cn.studykid.rag.core.scope.RagScope.GLOBAL_TENANT_ID)
                            ? "PLATFORM" : "TENANT");
            return entry;
        });
    }

    @Override
    public java.util.List<LifecycleEntry> listLifecycle(@NonNull RagManagementContext context,
                                                        int limit) {
        String tenantId = context.queryTenantId();
        int capped = Math.max(1, Math.min(limit, 500));
        java.time.Instant now = java.time.Instant.now();
        return transactionTemplate.execute(tx -> {
            RagSessionScope.apply(jdbcTemplate, context);
            // ⚠️ 只列「有校验时间」或「已通过」的来源：
            //    从未校验过的来源没有有效期概念，混进来会让清单失去意义。
            java.util.List<LifecycleEntry> rows = jdbcTemplate.query("""
                            SELECT s.source_id::text, s.title, s.ver_status::text,
                                   s.verified_at, s.ver_valid_days
                              FROM rag_source s
                             WHERE s.tenant_id = ?::uuid
                               AND (s.verified_at IS NOT NULL
                                    OR s.ver_status::text = 'VERIFIED')
                             ORDER BY s.verified_at NULLS LAST
                             LIMIT ?
                            """,
                    (rs, i) -> {
                        java.sql.Timestamp vt = rs.getTimestamp(4);
                        java.time.Instant verifiedAt = vt == null ? null : vt.toInstant();
                        int validDays = rs.getInt(5);
                        LifecycleRules.Expiry expiry =
                                LifecycleRules.compute(verifiedAt, validDays, now);
                        boolean admissible = "VERIFIED".equals(rs.getString(3));
                        return new LifecycleEntry(
                                rs.getString(1), rs.getString(2), rs.getString(3),
                                verifiedAt == null ? null : verifiedAt.toString(),
                                validDays,
                                expiry.expiresAt() == null ? null : expiry.expiresAt().toString(),
                                expiry.daysUntilExpiry(),
                                expiry.stale(), expiry.expiringSoon(),
                                admissible,
                                LifecycleRules.describe(expiry));
                    },
                    tenantId, capped);
            if (rows == null) {
                return java.util.List.<LifecycleEntry>of();
            }
            // ⚠️ 已过期的排最前：那是最需要动手的，排在后面等于没有提醒
            java.util.List<LifecycleEntry> sorted = new java.util.ArrayList<>(rows);
            sorted.sort(java.util.Comparator
                    .comparing((LifecycleEntry e) -> !e.stale())
                    .thenComparing(e -> !e.expiringSoon())
                    .thenComparing(e -> e.daysUntilExpiry() == null
                            ? Integer.MAX_VALUE : e.daysUntilExpiry()));
            return sorted;
        });
    }

    @Override
    public java.util.List<ReviewQueueEntry> listReviewQueue(@NonNull RagManagementContext context,
                                                            int limit,
                                                            @Nullable String currentUser) {
        String tenantId = context.queryTenantId();
        int capped = Math.max(1, Math.min(limit, 200));
        return transactionTemplate.execute(tx -> {
            RagSessionScope.apply(jdbcTemplate, context);
            // ⚠️ 一次查询拿齐待办所需的全部信息：
            //    来源状态 + 元数据确认 + 抽检状态 + 载体数 + 片段数 + 答案片段数 + 采集人。
            //    分多次查询会让「待办清单」与「详情页」看到不同的数字（缓存不一致）。
            //
            //    ⚠️ 采集人取自审计表最近一条 INGEST（与 findRegisteredBy 同口径）；
            //    没有审计记录时为 null —— 历史数据不阻断审核（SOD 对 null 不判自审）。
            //
            //    ⚠️ 只取还需要人动手的状态：PENDING / IN_REVIEW / REJECTED。
            //    VERIFIED 与 REVOKED 是终态，混进待办会让待办数永远清不零。
            List<ReviewQueueEntry> rows = jdbcTemplate.query("""
                            SELECT s.source_id::text, s.title, s.publisher::text,
                                   s.ver_status::text, s.metadata_confirmed,
                                   s.inspection_status, s.created_at,
                                   (SELECT count(*) FROM rag_document d
                                     WHERE d.source_id = s.source_id
                                       AND d.tenant_id = s.tenant_id) AS doc_count,
                                   (SELECT count(*) FROM rag_chunk c
                                     JOIN rag_document d2
                                       ON d2.document_id = c.document_id
                                      AND d2.tenant_id = c.tenant_id
                                     WHERE d2.source_id = s.source_id
                                       AND d2.tenant_id = s.tenant_id) AS chunk_count,
                                   (SELECT count(*) FROM rag_chunk c2
                                     JOIN rag_document d3
                                       ON d3.document_id = c2.document_id
                                      AND d3.tenant_id = c2.tenant_id
                                     WHERE d3.source_id = s.source_id
                                       AND d3.tenant_id = s.tenant_id
                                       AND c2.role IN ('EXAMPLE_SOLUTION', 'EXERCISE_ANSWER'))
                                     AS answer_count,
                                   (SELECT a.principal_id::text FROM rag_access_audit a
                                     WHERE a.source_id = s.source_id
                                       AND a.action = 'INGEST'
                                     ORDER BY a.occurred_at DESC LIMIT 1) AS registered_by
                              FROM rag_source s
                             WHERE s.tenant_id = ?::uuid
                               AND s.ver_status::text IN ('PENDING', 'IN_REVIEW', 'REJECTED')
                             ORDER BY s.created_at DESC
                             LIMIT ?
                            """,
                    (rs, i) -> {
                        String verStatus = rs.getString(4);
                        boolean metaConfirmed = rs.getBoolean(5);
                        String inspection = rs.getString(6);
                        ReviewQueueRules.Stage stage =
                                ReviewQueueRules.nextStage(verStatus, metaConfirmed, inspection);
                        ReviewQueueEntry base = new ReviewQueueEntry(
                                rs.getString(1), rs.getString(2), rs.getString(3),
                                verStatus, metaConfirmed,
                                inspection == null ? "NOT_REQUIRED" : inspection,
                                rs.getInt(8), rs.getLong(9), rs.getLong(10),
                                rs.getString(11), stage,
                                false, null,
                                rs.getTimestamp(7) == null ? "" : rs.getTimestamp(7).toInstant().toString());
                        return base.withViewer(currentUser);
                    },
                    tenantId, capped);
            return rows == null ? java.util.List.<ReviewQueueEntry>of() : rows;
        });
    }

    @Override
    @Nullable
    public MetadataConfirmationResult confirmMetadata(@NonNull RagManagementContext context,
                                                      @NonNull String sourceId,
                                                      @NonNull MetadataConfirmation info,
                                                      @NonNull String principalId) {
        return writeVia(context, ch -> {
            // ⚠️ 先确认来源存在且在本作用域内：不存在返回 null（控制器映射 404），
            //    而不是插入一条悬空的确认记录。
            CorpusSource current = findSourceWithinTenant(ch.template(), ch.tenantId(), sourceId);
            if (current == null) {
                return null;
            }

            // ⚠️ 幂等：重复确认不报错，但**不改写首次确认人与时间**。
            //    「谁最先确认」比「最后谁点了按钮」更有审计价值；
            //    若每次都覆盖，责任人会漂移到最后一个点按钮的人。
            ch.template().update("""
                    UPDATE rag_source
                    SET metadata_confirmed = TRUE,
                        metadata_confirmed_at = COALESCE(metadata_confirmed_at, now()),
                        metadata_confirmed_by = COALESCE(metadata_confirmed_by, ?::uuid),
                        updated_at = now()
                    WHERE tenant_id = %s AND source_id = ?::uuid
                    """.formatted(ch.tenantExpr()), principalId, sourceId);

            return ch.template().query("""
                    SELECT metadata_confirmed, metadata_confirmed_at, metadata_confirmed_by
                    FROM rag_source
                    WHERE tenant_id = %s AND source_id = ?::uuid
                    """.formatted(ch.tenantExpr()),
                    rs -> {
                        if (!rs.next()) {
                            return null;
                        }
                        String by = rs.getString("metadata_confirmed_by");
                        java.sql.Timestamp at = rs.getTimestamp("metadata_confirmed_at");
                        return new MetadataConfirmationResult(
                                sourceId,
                                rs.getBoolean("metadata_confirmed"),
                                at == null ? null : at.toInstant(),
                                by);
                    },
                    sourceId);
        });
    }

    @Override
    @Nullable
    public CorpusSource applyVerification(@NonNull RagManagementContext context,
                                          @NonNull String sourceId,
                                          @NonNull VerificationDecision decision) {
        return writeVia(context, ch -> {
            CorpusSource current = findSourceWithinTenant(ch.template(), ch.tenantId(), sourceId);
            if (current == null) {
                return null;
            }
            // ⚠️ 状态机校验在**写库之前**：非法转移必须被拒绝，
            //    而不是「先改了再说」——否则 T-10/T-11/T-12 这三条禁止转移
            //    就会变成「可绕过的建议」。
            VerificationTransition.requireAllowed(
                    current.verificationStatus(), decision.targetStatus());

            // ⚠️ 信任等级的处置：VERIFIED 时用决定中的等级；
            //    REJECTED 时「维持或降为 UNVERIFIED」（RAG-D07 §2.3 阶段③′）——
            //    这里取「降级优先」：决定里给了就用给的，否则降为 UNVERIFIED，
            //    因为被拒绝的来源本就不应保有权威等级。
            TrustTier targetTier = current.trustTier();
            if (decision.targetStatus() == VerificationStatus.VERIFIED) {
                targetTier = decision.trustTier();
            } else if (decision.targetStatus() == VerificationStatus.REJECTED) {
                targetTier = decision.trustTier() == null ? TrustTier.UNVERIFIED : decision.trustTier();
            }
            // ⚠️ 权威等级与出版标识必须自洽（V1 CHECK 约束）：
            //    降级到 UNVERIFIED 时两者可缺失；升到教材/教辅等级则必须有 ISBN。
            if (targetTier != TrustTier.UNVERIFIED
                    && blankToNull(current.isbn()) == null
                    && blankToNull(current.doi()) == null) {
                throw new IllegalStateException(
                        "权威等级 " + targetTier + " 需要 ISBN 或 DOI，但该来源两者皆无");
            }

            // ⚠️ 版权状态：撤回（REVOKED）时强制置 RESTRICTED。
            //    RAG-D07 §2.3.1 G-4 把「版权状态」与「校验状态」作为两个独立轴，
            //    但撤回意味着「权利人要求下架」——此时版权轴必须同时关闭，
            //    否则一旦有别的路径只看版权轴，撤回语料仍可能被检索到。
            String licenseStatus = current.licenseStatus();
            if (decision.targetStatus() == VerificationStatus.REVOKED) {
                licenseStatus = "RESTRICTED";
            }

            ch.template().update("""
                    UPDATE rag_source
                    SET ver_status = ?::rag_verification_status,
                        ver_method = ?::rag_verification_method,
                        ver_evidence = ?,
                        ver_reject_reason = ?,
                        ver_decided_at = now(),
                        verified_at = now(),
                        verified_by = ?::uuid,
                        ver_valid_days = ?,
                        trust_tier = ?::rag_trust_tier,
                        license_status = ?::rag_license_status,
                        reviewer_id = CASE WHEN ?::rag_verification_status = 'IN_REVIEW'
                                           THEN ?::uuid ELSE reviewer_id END,
                        review_started_at = CASE WHEN ?::rag_verification_status = 'IN_REVIEW'
                                                 THEN now() ELSE review_started_at END,
                        updated_at = now()
                    WHERE tenant_id = %s AND source_id = ?::uuid
                    """.formatted(ch.tenantExpr()),
                    decision.targetStatus().name(), decision.verificationMethod().name(),
                    decision.evidenceRef(),
                    decision.targetStatus() == VerificationStatus.REJECTED
                            ? decision.evidenceRef() : null,
                    decision.decidedBy(), decision.validDays(),
                    targetTier.name(), licenseStatus,
                    decision.targetStatus().name(), decision.decidedBy(),
                    decision.targetStatus().name(),
                    sourceId);

            // ⚠️ 撤回必须**连带下架该来源下的所有载体**。
            //    理由：来源级与载体级是两个独立轴（RAG-D07 §2.3.1 G-0 要求两者同时通过）。
            //    若只改来源状态而留下载体 retrievable=TRUE，
            //    任何只看载体级标记的检索路径仍会召回已撤回语料。
            if (decision.targetStatus() == VerificationStatus.REVOKED) {
                ch.template().update("""
                        UPDATE rag_document
                        SET ver_status = 'REVOKED'::rag_verification_status,
                            retrievable = FALSE,
                            revoked_at = now(),
                            revoked_reason = ?,
                            license_status = 'RESTRICTED'::rag_license_status,
                            updated_at = now()
                        WHERE tenant_id = %s AND source_id = ?::uuid
                        """.formatted(ch.tenantExpr()), decision.evidenceRef(), sourceId);
            }

            // ⚠️ 审计与状态变更同事务（RAG-D07 §2.5）。
            //    V3 迁移后 rag_access_audit 允许 ADMIT / REJECT 动作，
            //    使「审核员的判定行为」有独立事件流，而不必借道 INGEST
            //    （RAG-D07 §7 记载的缺口）。
            //
            //    ⚠️⚠️ IN_REVIEW / PENDING 这条流转**刻意不写 INGEST**：
            //    findRegisteredBy（审核职责分离「审核员 ≠ 采集员」的判定依据）
            //    取的是该来源最近一条 action='INGEST' 的 principal_id。而本方法
            //    正是「认领/提交审核」的流转，执行人是审核员而不是登记人——
            //    若它也写成 INGEST，就会把登记人**覆盖成提交人自己**，
            //    自审校验随后比对到的是提交人，等于把「审核员 ≠ 采集员」这条
            //    治理底线悄悄抹掉（实测可复现）。因此：
            //      · 已有审计记录（登记时必然写过一条 INGEST）→ 不再重复写，
            //        登记人与提交人在这里本来就是两个人时，也不该改写登记事实；
            //      · 没有记录（历史数据）→ 才用本次主体补一条，保持留痕完整性。
            //    两种情况下 auditActionFor() 对 VERIFIED/REJECTED/REVOKED 的映射不变。
            if (hasAuditRecord(ch, sourceId)) {
                log.debug("[RAG] 来源 {} 已有审计记录，本轮 {} 流转不再写入 INGEST（保护登记人）",
                        sourceId, decision.targetStatus());
            } else {
                appendAuditWithinTenant(ch, decision.decidedBy(),
                        auditActionFor(decision.targetStatus()), sourceId);
            }

            return findSourceWithinTenant(ch.template(), ch.tenantId(), sourceId);
        });
    }

    /**
     * 校验状态 → 审计动作的映射。
     *
     * <p>⚠️ V3 迁移新增了 {@code ADMIT}（审核通过）与 {@code REJECT}（审核拒绝），
     * 使审核动作有独立事件流；{@code REVOKE} 沿用 V1 既有取值。
     *
     * @param status 目标校验状态
     * @return 审计动作
     */
    private static String auditActionFor(VerificationStatus status) {
        return switch (status) {
            case VERIFIED -> "ADMIT";
            case REJECTED -> "REJECT";
            case REVOKED -> "REVOKE";
            // ⚠️ PENDING / IN_REVIEW 归入 INGEST：
            //    「登记 + 认领」在语义上属于语料入库流程。
            case PENDING, IN_REVIEW -> "INGEST";
        };
    }

    // ---------------------------------------------------------------------
    // 文档与片段
    // ---------------------------------------------------------------------

    @Override
    @NonNull
    public List<CorpusDocument> listDocuments(@NonNull RagManagementContext context,
                                                @NonNull String sourceId) {
        return transactionTemplate.execute(status -> {
            applyReadScope(context);
            // ⚠️ 载体级状态列（ver_status / retrievable / revoked_* / license_status）
            //    必须一并选出：管理台要按载体展示「这份扫描件是否可检索、
            //    是否已被撤回」，缺列会让前端只能看到解析状态而看不到治理结论。
            return jdbcTemplate.query("""
                    SELECT d.document_id, d.source_id, d.edition_id, d.carrier, d.title,
                           d.page_count, d.object_ref, d.parse_status, d.parser_version,
                           d.content_hash, d.created_at, d.updated_at,
                           d.ver_status, d.retrievable, d.revoked_at, d.revoked_reason,
                           d.license_status,
                           (SELECT count(*) FROM rag_chunk c
                             WHERE c.tenant_id = d.tenant_id AND c.document_id = d.document_id)
                               AS chunk_count
                    FROM rag_document d
                    WHERE d.tenant_id = ?::uuid AND d.source_id = ?::uuid
                    ORDER BY d.created_at DESC, d.document_id
                    """,
                    (rs, i) -> mapDocument(rs), context.queryTenantId(), sourceId);
        });
    }

    @Override
    @Nullable
    public CorpusDocument applyDocumentVerification(@NonNull RagManagementContext context,
                                                     @NonNull String documentId,
                                                     @NonNull DocumentVerificationDecision decision) {
        return writeVia(context, ch -> {
            CorpusDocument current = findDocumentWithinTenant(ch.template(),
                    ch.tenantId(), documentId);
            if (current == null) {
                return null;
            }
            // ⚠️ 状态机校验在**写库之前**：非法转移必须被拒绝，
            //    而不是「先改了再说」——否则 T-10（REVOKED 是终态）
            //    这类禁止转移就会变成「可绕过的建议」。
            DocumentVerification.requireAllowed(
                    current.verificationStatus(), decision.targetStatus());

            // ⚠️ 载体不得高于来源：来源没过校验，载体不能自称 VERIFIED。
            //    来源状态必须在**同一事务**内读取——否则并发撤回来源时，
            //    可能读到「来源仍 VERIFIED」的旧快照而放行载体。
            String sourceStatus = querySourceVerificationStatus(ch.template(),
                    ch.tenantId(), current.sourceId());
            if (sourceStatus == null) {
                throw new IllegalStateException("载体所属来源不存在：" + current.sourceId()
                        + "（来源缺失意味着无法判定「载体不得高于来源」）");
            }
            DocumentVerification.requireSourceAllowsVerified(
                    decision.targetStatus(), VerificationStatus.valueOf(sourceStatus));

            // ⚠️ 只有 VERIFIED 才置可检索（fail-closed）：
            //    「被认领」「被拒绝」都不足以让语料进入检索。
            boolean retrievable = DocumentVerification.retrievableAfter(decision.targetStatus());
            boolean revoked = decision.targetStatus() == VerificationStatus.REVOKED;

            ch.template().update("""
                    UPDATE rag_document
                    SET ver_status = ?::rag_verification_status,
                        retrievable = ?,
                        revoked_at = CASE WHEN ?::rag_verification_status = 'REVOKED'
                                          THEN now() ELSE revoked_at END,
                        revoked_reason = CASE WHEN ?::rag_verification_status = 'REVOKED'
                                              THEN ? ELSE revoked_reason END,
                        license_status = CASE WHEN ?::rag_verification_status = 'REVOKED'
                                              THEN 'RESTRICTED'::rag_license_status
                                              ELSE license_status END,
                        updated_at = now()
                    WHERE tenant_id = %s AND document_id = ?::uuid
                    """.formatted(ch.tenantExpr()),
                    decision.targetStatus().name(), retrievable,
                    decision.targetStatus().name(), decision.targetStatus().name(),
                    decision.evidenceRef(), decision.targetStatus().name(), documentId);

            // ⚠️ 审计与状态变更同事务（RAG-D07 §2.5）。
            //    载体级动作记在**来源**上：审计表没有「文档」维度，
            //    而「这个来源下的语料发生过什么」正是审计要回答的问题。
            appendAuditWithinTenant(ch, decision.decidedBy(),
                    DocumentVerification.auditActionFor(decision.targetStatus()),
                    current.sourceId());

            return findDocumentWithinTenant(ch.template(), ch.tenantId(), documentId);
        });
    }

    @Override
    @Nullable
    public String findRegisteredBy(@NonNull RagManagementContext context,
                                   @NonNull String sourceId) {
        // ⚠️⚠️ 这是**只读查询，绝不能走 writeVia**。
        //    writeVia 在 PLATFORM 作用域下要求注入 rag_ops 运维通道，
        //    未接入时会抛 RagOpsUnavailableException——而「读一个登记人」
        //    根本不需要写权限（实测踩到：审核端点因此 500）。
        //
        //    ⚠️ 也**不能**只看租户上下文：本方法的调用方是审核端点，
        //    而全局语料（scope=PLATFORM, tenant_id=全局哨兵）在租户会话下不可见。
        //    故这里用「尽力而为」策略：查不到就返回 null（SOD 规则会把 null 视为
        //    「历史数据无登记人」，不阻断审核，但审计仍记录实际审核人）。
        try {
            String tid = context.scope() == RagCorpusScope.TENANT
                    ? context.requireTenant().tenantId() : null;
            java.util.List<String> rows = transactionTemplate.execute(status -> {
                if (tid != null) {
                    jdbcTemplate.queryForObject(
                            "SELECT set_config('app.tenant_id', ?, true)", String.class, tid);
                }
                String sql = "SELECT principal_id::text FROM rag_access_audit"
                        + " WHERE source_id = ?::uuid AND action = 'INGEST'"
                        + (tid != null ? " AND tenant_id = ?::uuid" : "")
                        // ⚠️ 列名是 occurred_at 而非 created_at（实测表结构）
                        + " ORDER BY occurred_at DESC LIMIT 1";
                return tid != null
                        ? jdbcTemplate.query(sql, (rs, i) -> rs.getString(1), sourceId, tid)
                        : jdbcTemplate.query(sql, (rs, i) -> rs.getString(1), sourceId);
            });
            return rows == null || rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException e) {
            // ⚠️ 读不到登记人不构成阻断理由：SOD 规则对 null 已定义为「不阻断」。
            //    让它冒泡会把「审核」这个核心动作变成 500（比自审风险更糟）。
            log.warn("[RAG] 读取来源 {} 的登记人失败，按「无记录」处理：{}",
                    sourceId, e.getMessage());
            return null;
        }
    }

    @Override
    @Nullable
    public CorpusDocument importDocument(@NonNull RagManagementContext context,
                                        @NonNull String sourceId,
                                        @NonNull DocumentImportCommand cmd,
                                        @NonNull String principalId) {
        return writeVia(context, ch -> {
            // ⚠️ 来源不存在 → 返回 null，由控制器映射为 404（而非让 FK 违约炸成 500）。
            CorpusSource source = findSourceWithinTenant(ch.template(), ch.tenantId(), sourceId);
            if (source == null) {
                return null;
            }
            // ⚠️ 版本坐标强制 NOT NULL：优先复用来源下已有版本，否则建一个「默认版本」。
            String editionId = resolveEdition(ch, sourceId, ch.scopeExpr());
            String documentId = UUID.randomUUID().toString();
            ch.template().update("""
                    INSERT INTO rag_document
                        (document_id, tenant_id, source_id, edition_id, carrier, title,
                         object_ref, parse_status, content_hash, ver_status, retrievable,
                         license_status, scope)
                    VALUES (?::uuid, %s, ?::uuid, ?::uuid, ?, ?, ?,
                            'PENDING'::text, ?,
                            'PENDING'::rag_verification_status, FALSE,
                            'PENDING'::rag_license_status, %s)
                    """.formatted(ch.tenantExpr(), ch.scopeExpr()),
                    documentId, sourceId, editionId,
                    cmd.carrier(), cmd.title(), cmd.objectRef(), cmd.contentHash());
            // ⚠️ 导入动作必须留痕（与状态变更同事务）：记录「谁导入了这份语料」。
            appendAuditWithinTenant(ch, principalId, "INGEST", sourceId);
            return findDocumentWithinTenant(ch.template(), ch.tenantId(), documentId);
        });
    }

    /**
     * 解析一份文档载体应挂到的版本坐标（edition）。
     *
     * <p>⚠️ 优先用<b>来源下第一条</b>版本坐标（同一来源下多载体共享版本是合理的）；
     * 若来源下尚无版本（典型的「只录入文档、未维护版本」），则确保存在一个
     * 「默认版本」并返回其 ID——让录入交互不必强制先填五轴版本信息。
     *
     * @param ch       写通道（已含租户表达式与作用域字面量）
     * @param sourceId 来源 ID
     * @param scopeExpr 作用域字面量（{@code 'TENANT'} / {@code 'PLATFORM'}）
     * @return 版本坐标 ID
     */
    private String resolveEdition(@NonNull WriteChannel ch, @NonNull String sourceId,
                                 @NonNull String scopeExpr) {
        List<String> existing = ch.template().query(
                "SELECT edition_id::text FROM rag_edition"
                        + " WHERE tenant_id = " + ch.tenantExpr() + " AND source_id = ?::uuid"
                        + " ORDER BY created_at ASC, edition_id LIMIT 1",
                (rs, i) -> rs.getString(1), sourceId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return ensureDefaultEdition(ch, sourceId, scopeExpr);
    }

    /**
     * 确保存在一个「默认版本」坐标（幂等）。
     *
     * <p>⚠️ {@code revision_code='src-<sourceId>'} 保证每个来源至多一个默认版本、
     * 且不与真实版本冲突（真实版本的版次码不会长这样）。
     * 用 {@code ON CONFLICT ... DO NOTHING} 后回查，使并发重复导入安全——
     * 无论谁先建，最终都拿到同一个默认版本 ID。
     *
     * <p>⚠️ 占位字段（学科=未指定 / 年级=全学段 / 册次=全一册 / 出版社=未指定）
     * 仅为让 UNIQUE 约束可满足；后续维护版本信息时应由
     * {@code DocumentImportCommand} 的可选版本字段覆盖，或显式补登版本。
     *
     * @param ch       写通道
     * @param sourceId 来源 ID
     * @param scopeExpr 作用域字面量
     * @return 默认版本坐标 ID
     */
    private String ensureDefaultEdition(@NonNull WriteChannel ch, @NonNull String sourceId,
                                       @NonNull String scopeExpr) {
        String revisionCode = "src-" + sourceId;
        ch.template().update(("""
                INSERT INTO rag_edition
                    (tenant_id, source_id, subject, grade, term, publisher, revision_code, scope)
                VALUES (%s, ?::uuid, '未指定', '全学段', '全一册', '未指定', ?, %s)
                ON CONFLICT (tenant_id, subject, grade, term, publisher, revision_code) DO NOTHING
                """).formatted(ch.tenantExpr(), scopeExpr),
                sourceId, revisionCode);
        return ch.template().queryForObject(("""
                SELECT edition_id::text FROM rag_edition
                WHERE tenant_id = %s AND source_id = ?::uuid AND revision_code = ?
                """).formatted(ch.tenantExpr()),
                (rs, i) -> rs.getString(1), sourceId, revisionCode);
    }

    @Override
    @NonNull
    public CorpusIssues listIssues(@NonNull RagManagementContext context) {
        return transactionTemplate.execute(status -> {
            applyReadScope(context);
            String tenant = context.queryTenantId();

            // ⚠️ 缺向量的载体：用 LEFT JOIN + FILTER 一次算出
            //    「片段总数」与「其中缺向量的个数」，再 HAVING 掉缺向量数为 0 的载体。
            //    ⚠️ FILTER 条件里必须同时判 c.chunk_id IS NOT NULL：
            //    否则「一个片段都没有的载体」会因为 LEFT JOIN 产生的空行
            //    被误判成「有 1 个片段缺向量」——那会把解析失败的载体
            //    错误地混进「缺向量」清单，掩盖真正的失败原因。
            List<CorpusIssues.DocumentEmbeddingGap> missingEmbedding = jdbcTemplate.query("""
                    SELECT d.document_id, d.source_id, d.title, d.carrier, d.parse_status,
                           count(c.chunk_id) AS chunk_count,
                           count(*) FILTER (WHERE c.chunk_id IS NOT NULL AND e.chunk_id IS NULL)
                               AS missing_embedding_count
                    FROM rag_document d
                    LEFT JOIN rag_chunk c
                           ON c.document_id = d.document_id AND c.tenant_id = d.tenant_id
                    LEFT JOIN rag_chunk_embedding e
                           ON e.chunk_id = c.chunk_id AND e.tenant_id = c.tenant_id
                    WHERE d.tenant_id = ?::uuid
                    GROUP BY d.document_id, d.source_id, d.title, d.carrier, d.parse_status
                    HAVING count(*) FILTER (WHERE c.chunk_id IS NOT NULL AND e.chunk_id IS NULL) > 0
                    ORDER BY d.created_at DESC, d.document_id
                    """,
                    (rs, i) -> new CorpusIssues.DocumentEmbeddingGap(
                            rs.getString("document_id"),
                            rs.getString("source_id"),
                            rs.getString("title"),
                            rs.getString("carrier"),
                            rs.getString("parse_status"),
                            rs.getLong("chunk_count"),
                            rs.getLong("missing_embedding_count")), tenant);

            List<CorpusIssues.DocumentParseFailure> failedParse = jdbcTemplate.query("""
                    SELECT d.document_id, d.source_id, d.title, d.carrier, d.parse_status,
                           (SELECT count(*) FROM rag_chunk c
                             WHERE c.tenant_id = d.tenant_id AND c.document_id = d.document_id)
                               AS chunk_count
                    FROM rag_document d
                    WHERE d.tenant_id = ?::uuid AND d.parse_status = 'FAILED'
                    ORDER BY d.created_at DESC, d.document_id
                    """,
                    (rs, i) -> new CorpusIssues.DocumentParseFailure(
                            rs.getString("document_id"),
                            rs.getString("source_id"),
                            rs.getString("title"),
                            rs.getString("carrier"),
                            rs.getString("parse_status"),
                            rs.getLong("chunk_count")), tenant);

            // ⚠️ stale 必须在 SQL 里算：它是「结论是否已过期」的计算属性
            //    （verified_at + ver_valid_days < now()），前端没有租户门槛上下文，
            //    自己算会与服务端口径漂移。
            //    ⚠️ 判定式只在 ver_status='VERIFIED' 时才有意义：
            //    未通过校验的来源谈不上「结论过期」（那属于「还没结论」），
            //    故 PENDING / IN_REVIEW 一律为 false。
            List<CorpusIssues.SourcePendingReview> pendingReview = jdbcTemplate.query("""
                    SELECT source_id, title, ver_status::text AS verification_status,
                           trust_tier::text AS trust_tier,
                           (ver_status = 'VERIFIED'
                            AND (verified_at IS NULL
                                 OR verified_at + (ver_valid_days || ' days')::interval < now()))
                               AS stale
                    FROM rag_source
                    WHERE tenant_id = ?::uuid
                      AND ver_status IN ('PENDING', 'IN_REVIEW')
                    ORDER BY created_at DESC, source_id
                    """,
                    (rs, i) -> new CorpusIssues.SourcePendingReview(
                            rs.getString("source_id"),
                            rs.getString("title"),
                            rs.getString("verification_status"),
                            rs.getBoolean("stale"),
                            rs.getString("trust_tier")), tenant);

            return new CorpusIssues(missingEmbedding, failedParse, pendingReview);
        });
    }

    @Override
    @NonNull
    public List<ChunkSummary> listChunks(@NonNull RagManagementContext context,
                                            @NonNull String documentId,
                                         int limit,
                                         java.util.Collection<String> roles) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须 > 0");
        }
        // 角色白名单进 SQL（role = ANY(...)），LIMIT 作用于「筛选后」的结果——
        // 若取回后再过滤，「前 N 条里的答案」几乎恒为空集，筛选就失去了意义。
        java.util.List<String> roleFilter = (roles == null || roles.isEmpty())
                ? java.util.List.of()
                : java.util.List.copyOf(roles);
        return transactionTemplate.execute(status -> {
            applyReadScope(context);
            StringBuilder sql = new StringBuilder("""
                    SELECT c.chunk_id, c.role, c.page_number, c.section_path, c.exercise_no,
                           length(c.text) AS char_length, c.content_hash,
                           (e.chunk_id IS NOT NULL) AS has_embedding,
                           left(c.text, %d) AS chunk_text,
                           c.block_type, c.in_answer_zone, c.matched_prefix
                    FROM rag_chunk c
                    LEFT JOIN rag_chunk_embedding e
                           ON c.chunk_id = e.chunk_id AND c.tenant_id = e.tenant_id
                    WHERE c.tenant_id = ?::uuid AND c.document_id = ?::uuid
                    """.formatted(CHUNK_TEXT_PREVIEW_CHARS));
            if (!roleFilter.isEmpty()) {
                // ⚠️ c.role 是 PG 枚举 rag_chunk_role，与 text[] 比较没有运算符——
                //    列侧转 text 再比（等值语义不变，索引顺序扫描损失可接受，管理面低频）。
                sql.append("  AND c.role::text = ANY(?::text[])");
            }
            sql.append("""
                     ORDER BY c.page_number, c.char_start, c.chunk_id
                    LIMIT ?
                    """);
            Object[] args = roleFilter.isEmpty()
                    ? new Object[] {context.queryTenantId(), documentId, limit}
                    : new Object[] {context.queryTenantId(), documentId,
                                    roleFilter.toArray(new String[0]), limit};
            return jdbcTemplate.query(sql.toString(),
                    (rs, i) -> new ChunkSummary(
                            rs.getString("chunk_id"),
                            rs.getString("role"),
                            rs.getInt("page_number"),
                            rs.getString("section_path"),
                            rs.getString("exercise_no"),
                            rs.getInt("char_length"),
                            rs.getBoolean("has_embedding"),
                            rs.getString("content_hash"),
                            rs.getString("chunk_text"),
                            rs.getString("block_type"),
                            rs.getBoolean("in_answer_zone"),
                            rs.getString("matched_prefix")),
                    args);
        });
    }

    /**
     * 人工重判片段角色（RAG-D13 §3.2.4）。
     *
     * <p><b>⚠️ 只更新 role 一列</b>：text / content_hash / 向量 / 分词都不动。
     * 角色是纯检索期过滤维度（{@code RetrievalPolicy.allowsRole}），
     * 不参与嵌入，因此改完即生效、无需重建索引。
     *
     * <p><b>⚠️ 走 {@link #writeVia}</b>：平台全局语料的片段行 {tenant_id = 全局哨兵}，
     * 用应用连接改会被 RLS 静默滤成 0 行——表面上"成功"，实际什么都没改。
     * 必须与登记/校验同一条写通道。
     *
     * <p><b>⚠️ 0 行受影响即 404</b>：不区分"不存在"与"不属于当前作用域"，
     * 避免通过错误码探测其它租户的片段是否存在。
     */
    @Override
    @NonNull
    public ChunkSummary updateChunkRole(@NonNull RagManagementContext context,
                                        @NonNull String chunkId,
                                        @NonNull String role,
                                        @Nullable String reason) {
        return writeVia(context, channel -> {
            // 先读旧角色（审计要记录 from→to，且借此判断片段是否存在/可见）。
            java.util.List<String> current = channel.template().query(
                    "SELECT role::text FROM rag_chunk WHERE chunk_id = ?::uuid"
                            + " AND tenant_id = ?::uuid",
                    (rs, i) -> rs.getString(1),
                    chunkId, channel.tenantId());
            if (current.isEmpty()) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "片段不存在或不属于当前作用域：" + chunkId);
            }
            String fromRole = current.get(0);
            channel.template().update(
                    "UPDATE rag_chunk SET role = ?::rag.rag_chunk_role"
                            + " WHERE chunk_id = ?::uuid AND tenant_id = ?::uuid",
                    role, chunkId, channel.tenantId());
            log.info("片段角色重判 chunkId={} {} -> {} reason={} tenant={}",
                    chunkId, fromRole, role, reason, channel.tenantId());

            // 返回更新后的摘要（含新角色与正文预览）。
            java.util.List<ChunkSummary> after = channel.template().query("""
                    SELECT c.chunk_id, c.role::text AS role, c.page_number, c.section_path,
                           c.exercise_no, length(c.text) AS char_length, c.content_hash,
                           (e.chunk_id IS NOT NULL) AS has_embedding,
                           left(c.text, %d) AS chunk_text,
                           c.block_type, c.in_answer_zone, c.matched_prefix
                    FROM rag_chunk c
                    LEFT JOIN rag_chunk_embedding e
                           ON c.chunk_id = e.chunk_id AND c.tenant_id = e.tenant_id
                    WHERE c.chunk_id = ?::uuid AND c.tenant_id = ?::uuid
                    """.formatted(CHUNK_TEXT_PREVIEW_CHARS),
                    (rs, i) -> new ChunkSummary(
                            rs.getString("chunk_id"),
                            rs.getString("role"),
                            rs.getInt("page_number"),
                            rs.getString("section_path"),
                            rs.getString("exercise_no"),
                            rs.getInt("char_length"),
                            rs.getBoolean("has_embedding"),
                            rs.getString("content_hash"),
                            rs.getString("chunk_text"),
                            rs.getString("block_type"),
                            rs.getBoolean("in_answer_zone"),
                            rs.getString("matched_prefix")),
                    chunkId, channel.tenantId());
            if (after.isEmpty()) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        "片段已更新但回读失败：" + chunkId);
            }
            return after.get(0);
        });
    }

    @Override
    @NonNull
    public Map<String, Integer> documentCounts(@NonNull RagManagementContext context,
                                                         @NonNull Collection<String> sourceIds) {
        if (sourceIds.isEmpty()) {
            return java.util.Map.of();
        }
        return transactionTemplate.execute(status -> {
            applyReadScope(context);
            String[] ids = sourceIds.toArray(new String[0]);
            return jdbcTemplate.query("""
                    SELECT d.source_id, count(*) AS n
                    FROM rag_document d
                    WHERE d.tenant_id = ?::uuid AND d.source_id = ANY(?::uuid[])
                    GROUP BY d.source_id
                    """,
                    (rs, i) -> Map.entry(rs.getString("source_id"), rs.getInt("n")),
                    context.queryTenantId(), ids).stream()
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        });
    }

    // ---------------------------------------------------------------------
    // 统计
    // ---------------------------------------------------------------------

    @Override
    @NonNull
    public CorpusStatistics statistics(@NonNull RagManagementContext context) {
        return transactionTemplate.execute(status -> {
            applyReadScope(context);
            String tenant = context.queryTenantId();
            // ⚠️ 租户权威门槛（G-3）**由 platform 下发**（RagTenantContext.minTrustTier）。
            //    RAG 库不存租户信息（V4 迁移已移除 rag_tenant 副本），
            //    门槛的权威源是 platform（RAG-D07 §3.3）。
            TrustTier minTrustTier = context.minTrustTier();

            long totalSources = countLong("SELECT count(*) FROM rag_source"
                    + " WHERE tenant_id = ?::uuid", tenant);
            long verifiedSources = countLong("SELECT count(*) FROM rag_source"
                    + " WHERE tenant_id = ?::uuid AND ver_status = 'VERIFIED'", tenant);
            long admissibleSources = countLong("""
                    SELECT count(*) FROM rag_source
                    WHERE tenant_id = ?::uuid
                      AND ver_status = 'VERIFIED'
                      AND %s
                      AND license_status IN ('LICENSED', 'FAIR_USE_QUOTATION')
                      AND NOT (verified_at IS NULL
                           OR verified_at + (ver_valid_days || ' days')::interval < now())
                    """.formatted(trustTierInClause(minTrustTier)),
                    scopedArgs(tenant, trustTierFilterArgs(minTrustTier)));

            long totalDocuments = countLong("SELECT count(*) FROM rag_document"
                    + " WHERE tenant_id = ?::uuid", tenant);
            long totalChunks = countLong("SELECT count(*) FROM rag_chunk"
                    + " WHERE tenant_id = ?::uuid", tenant);
            long retrievableChunks = countLong("""
                    SELECT count(*)
                    FROM rag_chunk c
                    JOIN rag_document doc
                         ON c.document_id = doc.document_id AND c.tenant_id = doc.tenant_id
                    JOIN rag_source src
                         ON doc.source_id = src.source_id AND c.tenant_id = src.tenant_id
                    WHERE c.tenant_id = ?::uuid
                      AND src.ver_status = 'VERIFIED'
                      AND src.%s
                      AND src.license_status IN ('LICENSED', 'FAIR_USE_QUOTATION')
                      AND NOT (src.verified_at IS NULL
                           OR src.verified_at + (src.ver_valid_days || ' days')::interval < now())
                    """.formatted(trustTierInClause(minTrustTier)),
                    scopedArgs(tenant, trustTierFilterArgs(minTrustTier)));
            long missingEmbedding = countLong("""
                    SELECT count(*) FROM rag_chunk c
                    WHERE c.tenant_id = ?::uuid
                      AND NOT EXISTS (SELECT 1 FROM rag_chunk_embedding e
                                       WHERE e.chunk_id = c.chunk_id AND e.tenant_id = c.tenant_id)
                    """, tenant);

            Map<String, Long> chunksByRole = groupCount("""
                    SELECT role::text AS k, count(*) AS c FROM rag_chunk
                    WHERE tenant_id = ?::uuid GROUP BY role
                    """, tenant);
            Map<String, Long> sourcesByStatus = groupCount("""
                    SELECT ver_status::text AS k, count(*) AS c FROM rag_source
                    WHERE tenant_id = ?::uuid GROUP BY ver_status
                    """, tenant);
            Map<String, Long> documentsByParseStatus = groupCount("""
                    SELECT parse_status AS k, count(*) AS c FROM rag_document
                    WHERE tenant_id = ?::uuid GROUP BY parse_status
                    """, tenant);

            return new CorpusStatistics(totalSources, verifiedSources, admissibleSources,
                    totalDocuments, totalChunks, retrievableChunks, missingEmbedding,
                    chunksByRole, sourcesByStatus, documentsByParseStatus);
        });
    }

    // ---------------------------------------------------------------------
    // 审计
    // ---------------------------------------------------------------------

    @Override
    public void appendAudit(@NonNull RagManagementContext context, @NonNull String principalId,
                             @NonNull String action, @Nullable String sourceId) {
        writeVia(context, ch -> {
            appendAuditWithinTenant(ch, principalId, action, sourceId);
            return null;
        });
    }

    // ---------------------------------------------------------------------
    // 每日统计（报表中心）
    // ---------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>⚠️ 时区统一用 <b>UTC</b>：{@code occurred_at} 是 {@code timestamptz}，
     * {@code AT TIME ZONE 'UTC'} 先把它落到 UTC 墙面时刻再截日期，
     * 与应用侧生成连续日期序列用的 {@code LocalDate.now(ZoneOffset.UTC)} 同基准，
     * 否则跨时区部署时「今天的数据落在昨天」。
     */
    @Override
    @NonNull
    public DailySearchStatistics dailySearchStatistics(@NonNull RagManagementContext context, int days) {
        int safeDays = clampDays(days);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = today.minusDays(safeDays - 1L);
        Map<String, Long> countsByDay = transactionTemplate.execute(status -> {
            applyReadScope(context);
            return dailyCount("""
                    SELECT to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS k,
                           count(*) AS c
                    FROM rag_access_audit
                    WHERE tenant_id = ?::uuid
                      AND action = 'RETRIEVE'
                      AND occurred_at >= ?
                      AND occurred_at < ?
                    GROUP BY 1
                    """, context.queryTenantId(), start, today.plusDays(1));
        });
        List<DailySearchCount> byDay = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(today); date = date.plusDays(1)) {
            byDay.add(new DailySearchCount(date.toString(),
                    countsByDay.getOrDefault(date.toString(), 0L)));
        }
        return new DailySearchStatistics(byDay, safeDays);
    }

    /**
     * {@inheritDoc}
     *
     * <p>⚠️ 三个层级各查一次再在应用侧合并，而不是一条 JOIN：
     * {@code rag_source → rag_document → rag_chunk} 是三层不同粒度的表，
     * JOIN 会产生笛卡尔式放大（一个 source 的多个 document 会重复计数 source）。
     * 「连续日期补零」同样在应用侧做——SQL 的 {@code generate_series}
     * 需要显式类型转换且更难单测。
     */
    @Override
    @NonNull
    public DailyImportStatistics dailyImportStatistics(@NonNull RagManagementContext context, int days) {
        int safeDays = clampDays(days);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = today.minusDays(safeDays - 1L);
        Map<String, Long> sourcesByDay = transactionTemplate.execute(status -> {
            applyReadScope(context);
            return dailyCount("""
                    SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS k,
                           count(*) AS c
                    FROM rag_source
                    WHERE tenant_id = ?::uuid
                      AND created_at >= ?
                      AND created_at < ?
                    GROUP BY 1
                    """, context.queryTenantId(), start, today.plusDays(1));
        });
        Map<String, Long> documentsByDay = transactionTemplate.execute(status -> {
            applyReadScope(context);
            return dailyCount("""
                    SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS k,
                           count(*) AS c
                    FROM rag_document
                    WHERE tenant_id = ?::uuid
                      AND created_at >= ?
                      AND created_at < ?
                    GROUP BY 1
                    """, context.queryTenantId(), start, today.plusDays(1));
        });
        Map<String, Long> chunksByDay = transactionTemplate.execute(status -> {
            applyReadScope(context);
            return dailyCount("""
                    SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS k,
                           count(*) AS c
                    FROM rag_chunk
                    WHERE tenant_id = ?::uuid
                      AND created_at >= ?
                      AND created_at < ?
                    GROUP BY 1
                    """, context.queryTenantId(), start, today.plusDays(1));
        });
        List<DailyImportCount> byDay = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(today); date = date.plusDays(1)) {
            String key = date.toString();
            byDay.add(new DailyImportCount(key,
                    sourcesByDay.getOrDefault(key, 0L),
                    documentsByDay.getOrDefault(key, 0L),
                    chunksByDay.getOrDefault(key, 0L)));
        }
        return new DailyImportStatistics(byDay, safeDays);
    }

    /**
     * 按天分组的计数查询（连续日期补零由调用方负责）。
     *
     * <p>⚠️ 必须传入 {@code Timestamp}：{@code created_at} / {@code occurred_at}
     * 是 {@code timestamptz}，pgjdbc 对 {@code LocalDate} 参数会按 {@code date} 类型发送，
     * 与 timestamptz 比较依赖隐式转换，显式转 Timestamp 最稳。
     */
    @NonNull
    private Map<String, Long> dailyCount(@NonNull String sql, @NonNull String tenantId,
                                         @NonNull LocalDate fromInclusive,
                                         @NonNull LocalDate toExclusive) {
        Timestamp from = Timestamp.from(fromInclusive.atStartOfDay(ZoneOffset.UTC).toInstant());
        Timestamp to = Timestamp.from(toExclusive.atStartOfDay(ZoneOffset.UTC).toInstant());
        Map<String, Long> result = new LinkedHashMap<>();
        jdbcTemplate.query(sql, ps -> {
            // ⚠️ 参数序号与 SQL 里的占位符顺序严格对应（租户 → 起 → 止）：
            //    这里用的是显式 setXxx 而非可变参数，错位不会报错、只会静默算错日期区间。
            ps.setString(1, tenantId);
            ps.setTimestamp(2, from);
            ps.setTimestamp(3, to);
        }, rs -> {
            result.put(rs.getString("k"), rs.getLong("c"));
        });
        return result;
    }

    /** 天数钳制在 1～90，防御前端乱传导致的全表扫描。 */
    private static int clampDays(int days) {
        return Math.min(90, Math.max(1, days));
    }

    // ---------------------------------------------------------------------
    // 内部辅助
    // ---------------------------------------------------------------------

    /**
     * 读侧按作用域建立会话上下文（平台全局档见 {@link RagSessionScope}）。
     *
     * <p>⚠️ 会话租户建好之后，租户谓词<b>仍按作用域显式绑定</b>
     * （{@link RagManagementContext#queryTenantId()}）：
     * 平台档的 {@code rag_current_tenant()} 是 NULL，
     * 沿用 {@code tenant_id = rag_current_tenant()} 会把全局行<b>一律滤掉</b>，
     * 表现为「管理台看不到平台语料」且不报错——正是本刀要关的那道静默。
     */
    private void applyReadScope(@NonNull RagManagementContext context) {
        RagSessionScope.apply(jdbcTemplate, context);
    }

    /** 租户谓词在前、其余参数在后的参数表（统计类查询的可读租户维度恒为第一位）。 */
    @NonNull
    private static Object[] scopedArgs(@NonNull String tenantId, @NonNull List<String> rest) {
        Object[] args = new Object[rest.size() + 1];
        args[0] = tenantId;
        for (int i = 0; i < rest.size(); i++) {
            args[i + 1] = rest.get(i);
        }
        return args;
    }

    /**
     * 在<b>已建立作用域会话</b>的事务内查询来源。
     *
     * <p>⚠️ 该方法<b>不</b>自己开事务——它假定调用方已在
     * {@code set_config} 之后调用（否则 RLS 会把它过滤成空，
     * 表现为「来源不存在」这类难以定位的静默失败）。
     *
     * <p>⚠️ {@code template} 必须由调用方给出：全局写回读走的是运维连接，
     * 用字段里的应用连接去回读，会在窗口外取连接而抛错——
     * 「查不到刚写入的那一行」这种表现，比直接报错更难查。
     */
    @Nullable
    private CorpusSource findSourceWithinTenant(@NonNull JdbcTemplate template,
                                                @NonNull String tenantId,
                                                @NonNull String sourceId) {
        List<CorpusSource> rows = template.query("""
                SELECT source_id, source_type, title, publisher, isbn, doi,
                       trust_tier, ver_status, ver_method, ver_evidence, verified_at,
                       verified_by, ver_valid_days, license_status, object_ref,
                       content_hash, created_at, updated_at,
                       review_started_at, ver_decided_at, metadata_confirmed_at
                FROM rag_source
                WHERE tenant_id = ?::uuid AND source_id = ?::uuid
                """,
                (rs, i) -> mapSource(rs), tenantId, sourceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 在<b>已建立作用域会话</b>的事务内查询载体。
     *
     * <p>⚠️ 该方法<b>不</b>自己开事务——它假定调用方已在
     * {@code set_config} 之后调用（否则 RLS 会把它过滤成空，
     * 表现为「载体不存在」这类难以定位的静默失败）。
     */
    @Nullable
    private CorpusDocument findDocumentWithinTenant(@NonNull JdbcTemplate template,
                                                    @NonNull String tenantId,
                                                    @NonNull String documentId) {
        List<CorpusDocument> rows = template.query("""
                SELECT d.document_id, d.source_id, d.edition_id, d.carrier, d.title,
                       d.page_count, d.object_ref, d.parse_status, d.parser_version,
                       d.content_hash, d.created_at, d.updated_at,
                       d.ver_status, d.retrievable, d.revoked_at, d.revoked_reason,
                       d.license_status,
                       (SELECT count(*) FROM rag_chunk c
                         WHERE c.tenant_id = d.tenant_id AND c.document_id = d.document_id)
                           AS chunk_count
                FROM rag_document d
                WHERE d.tenant_id = ?::uuid AND d.document_id = ?::uuid
                """,
                (rs, i) -> mapDocument(rs), tenantId, documentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 在<b>已建立租户上下文</b>的事务内读取来源的校验状态。
     *
     * <p>⚠️ 只取状态位而非整个来源对象：载体级判定只需要「来源过没过校验」，
     * 多取字段既无用途，又会让调用方误以为拿到了可改写的快照。
     *
     * @return 校验状态名；来源不存在返回 {@code null}
     */
    @Nullable
    private String querySourceVerificationStatus(@NonNull JdbcTemplate template,
                                                 @NonNull String tenantId,
                                                 @NonNull String sourceId) {
        List<String> rows = template.query(
                "SELECT ver_status::text FROM rag_source"
                        + " WHERE tenant_id = ?::uuid AND source_id = ?::uuid",
                (rs, i) -> rs.getString(1), tenantId, sourceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 在<b>已建立作用域会话</b>的事务内追加一条审计。
     *
     * <p>⚠️ 租户列取 {@link WriteChannel#tenantExpr()} 而非 {@code rag_current_tenant()}：
     * 运维通道<b>不设置</b>会话租户（设哨兵会 RAISE 42501），
     * 沿用会话函数的结果是 {@code tenant_id = NULL} → NOT NULL 违约，
     * 整笔「状态变更 + 审计」回滚，表现为「全局语料谁都改不动」。
     *
     * <p>⚠️ 全局改动的<b>留痕同样不可省</b>：平台语料被谁放行、谁撤回，
     * 影响的是所有租户，比单租户的审计更该有事件流（RAG-D07 §2.5 不因作用域而豁免）。
     */
    private void appendAuditWithinTenant(@NonNull WriteChannel channel,
                                         @NonNull String principalId,
                                         @NonNull String action, @Nullable String sourceId) {
        // ⚠️ action 受 rag_access_audit_action_chk 约束
        //    （V3 迁移后：RETRIEVE/INGEST/CITE/EXPORT/REVOKE/ADMIT/REJECT），
        //    这里做白名单校验，避免因拼写错误导致整笔审计插入失败
        //    （进而回滚状态变更——那会让「状态已改但无留痕」或「状态与审计不一致」）。
        if (!AUDIT_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("未知的审计动作：" + action);
        }
        channel.template().update("""
                INSERT INTO rag_access_audit
                    (audit_id, tenant_id, principal_id, action, source_id)
                VALUES (gen_random_uuid(), %s, ?::uuid, ?, ?::uuid)
                """.formatted(channel.tenantExpr()),
                principalId, action, sourceId);
    }

    /**
     * 该来源是否已有任何审计记录。
     *
     * <p>用途见 {@code applyVerification} 里的「不覆盖登记人」说明：
     * 登记（以及首次导入）一定会写一条 {@code INGEST}，因此「没有记录」
     * 只可能是历史数据，此时才值得补写。
     *
     * <p>⚠️ 与状态变更同事务、同一通道查询：运维通道下租户列由
     * {@code tenantExpr()} 处理，不能用会话函数（会得到 NULL 而误判为「无记录」）。
     */
    private boolean hasAuditRecord(@NonNull WriteChannel channel, @NonNull String sourceId) {
        Integer n = channel.template().queryForObject(
                "SELECT count(*) FROM rag_access_audit WHERE source_id = ?::uuid",
                Integer.class, sourceId);
        return n != null && n > 0;
    }


    private long countLong(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    /**
     * 按权威门槛过滤的 SQL 片段与参数。
     *
     * <p>⚠️ <b>为什么不能用 {@code trust_tier >= ?}</b>：
     * PostgreSQL 枚举的 {@code >=} 比较按<b>声明顺序</b>，
     * 而 {@code rag_trust_tier} 的声明顺序是
     * {@code OFFICIAL_PUBLISHER → OFFICIAL_TUTORIAL → PEER_REVIEWED_PAPER → PREPRINT → UNVERIFIED}，
     * 恰好与 {@link TrustTier#weight()} 的<b>降序</b>语义<b>相反</b>——
     * 枚举序数上 {@code UNVERIFIED} 最大，而它恰恰是<b>最不权威</b>的等级。
     * 若直接写 {@code trust_tier >= min_trust_tier}，语义会<b>整体反转</b>：
     * 门槛设为 {@code OFFICIAL_TUTORIAL} 时，反而只有 {@code UNVERIFIED} 能通过。
     *
     * <p>因此这里显式枚举每个「达标」等级，用 {@code IN (...)} 表达，
     * 让 SQL 语义与 {@code TrustTier.meets(min)} 的应用层判定<b>完全一致</b>，
     * 不依赖枚举声明顺序这种隐式约定。
     *
     * @param minTrustTier 租户最低权威门槛
     * @return 形如 {@code ["x", "y"]} 的占位参数列表（每项对应一个 {@code ?}）
     */
    private static List<String> trustTierFilterArgs(@NonNull TrustTier minTrustTier) {
        List<String> qualifying = new ArrayList<>();
        for (TrustTier tier : TrustTier.values()) {
            if (tier.meets(minTrustTier)) {
                qualifying.add(tier.name());
            }
        }
        return qualifying;
    }

    /** 构造 {@code trust_tier IN (?, ?, ...)} 片段（占位符个数与参数个数一致）。 */
    private static String trustTierInClause(@NonNull TrustTier minTrustTier) {
        List<String> args = trustTierFilterArgs(minTrustTier);
        StringBuilder sb = new StringBuilder("trust_tier IN (");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("?::rag_trust_tier");
        }
        return sb.append(")").toString();
    }

    private Map<String, Long> groupCount(String sql, Object... args) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            result.put(rs.getString("k"), rs.getLong("c"));
        }, args);
        return result;
    }

    private CorpusSource mapSource(ResultSet rs) throws SQLException {
        return new CorpusSource(
                rs.getString("source_id"),
                rs.getString("source_type"),
                rs.getString("title"),
                rs.getString("publisher"),
                rs.getString("isbn"),
                rs.getString("doi"),
                TrustTier.valueOf(rs.getString("trust_tier")),
                VerificationStatus.valueOf(rs.getString("ver_status")),
                VerificationMethod.valueOf(rs.getString("ver_method")),
                rs.getString("ver_evidence"),
                toInstant(rs.getTimestamp("verified_at")),
                rs.getString("verified_by"),
                rs.getInt("ver_valid_days"),
                rs.getString("license_status"),
                rs.getString("object_ref"),
                rs.getString("content_hash"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                // ⚠️ 三个时间列可空且**语义各自独立**（见 CorpusSource 的 Javadoc）：
                //    未认领 → review_started_at 为 null；未判定 → ver_decided_at 为 null；
                //    未确认元数据 → metadata_confirmed_at 为 null。
                //    一律如实映射为 null，**不得**回落到 createdAt/updatedAt ——
                //    那会让「还没开始审核」看起来像「已经审核过了」。
                toInstant(rs.getTimestamp("review_started_at")),
                toInstant(rs.getTimestamp("ver_decided_at")),
                toInstant(rs.getTimestamp("metadata_confirmed_at")));
    }

    private CorpusDocument mapDocument(ResultSet rs) throws SQLException {
        int pageCount = rs.getInt("page_count");
        Integer pageCountBoxed = rs.wasNull() ? null : pageCount;
        return new CorpusDocument(
                rs.getString("document_id"),
                rs.getString("source_id"),
                rs.getString("edition_id"),
                rs.getString("carrier"),
                rs.getString("title"),
                pageCountBoxed,
                rs.getString("object_ref"),
                rs.getString("parse_status"),
                rs.getString("parser_version"),
                rs.getString("content_hash"),
                rs.getLong("chunk_count"),
                // ⚠️ ver_status / retrievable 是载体级治理结论，
                //    必须随读模型一起返回——管理台要按载体展示
                //    「这份扫描件是否可检索、是否已被撤回」。
                VerificationStatus.valueOf(rs.getString("ver_status")),
                rs.getBoolean("retrievable"),
                toInstant(rs.getTimestamp("revoked_at")),
                rs.getString("revoked_reason"),
                rs.getString("license_status"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(@Nullable Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String blankToNull(@Nullable String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** 转义 LIKE 通配符，避免用户输入的 {@code %} / {@code _} 被当作通配符。 */
    private static String escapeLike(@NonNull String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
