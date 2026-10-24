package cn.studykid.rag.management.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.core.source.SourceVerification.VerificationMethod;
import cn.studykid.rag.core.source.SourceVerification.VerificationStatus;
import cn.studykid.rag.core.source.TrustTier;
import cn.studykid.rag.management.core.CorpusDocument;
import cn.studykid.rag.management.core.CorpusIssues;
import cn.studykid.rag.management.core.CorpusSource;
import cn.studykid.rag.management.core.CorpusStatistics;
import cn.studykid.rag.management.core.DocumentVerification;
import cn.studykid.rag.management.core.DocumentVerificationDecision;
import cn.studykid.rag.management.core.RagTenantContext;
import cn.studykid.rag.management.core.SourceQuery;
import cn.studykid.rag.management.core.SourceRegistration;
import cn.studykid.rag.management.core.VerificationDecision;
import cn.studykid.rag.management.core.VerificationTransition;
import cn.studykid.rag.management.ports.CorpusManagementPort;
import cn.studykid.rag.management.ports.CorpusManagementPort.BulkItemResult;
import cn.studykid.rag.management.ports.CorpusManagementPort.BulkRegistrationResult;

/**
 * 语料数据管理的 JDBC 实现（RAG-D07 §2 准入流程 / RAG-D03 §2 数据模型）。
 *
 * <p><b>⚠️ 租户隔离铁律</b>：本类的<b>每个</b>方法都在
 * {@link TransactionTemplate} 内执行「{@code set_config('app.tenant_id', ?, true)}
 * → 业务 SQL」两步，确保：
 * <ol>
 *   <li>租户上下文与业务 SQL 落在<b>同一事务、同一条连接</b>上
 *       （{@code is_local=true} 表示「仅在当前事务有效」，
 *       脱离事务则立即失效，表现为「查不到任何行」且不报错）；</li>
 *   <li>V1 的 RLS FORCE 策略对本次读写生效——<b>真正隔离由 RLS 保证</b>，
 *       SQL 里的 {@code tenant_id = rag_current_tenant()} 只是给优化器的提示。</li>
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

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

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
        if (jdbcTemplate == null || transactionTemplate == null) {
            throw new IllegalArgumentException("依赖不能为空");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    // ---------------------------------------------------------------------
    // 来源登记与查询
    // ---------------------------------------------------------------------

    @Override
    @NonNull
    public CorpusSource registerSource(@NonNull RagTenantContext context,
                                        @NonNull SourceRegistration registration,
                                        @NonNull String principalId) {
        // ⚠️ 登记态固定为 UNVERIFIED / PENDING / NONE：
        //    采集员不得自行提升权威等级或置 VERIFIED（RAG-D07 §2.2 角色表）。
        registration.requireIdentifierConsistent(TrustTier.UNVERIFIED);
        // ⚠️ source_id 在应用侧生成（而非用 gen_random_uuid() 后再回查）：
        //    「插入后按 created_at DESC 取最新一条」在并发登记下会取错行——
        //    两条登记的 created_at 可能相同，取到别人的记录。
        String sourceId = UUID.randomUUID().toString();
        return transactionTemplate.execute(status -> {
            setTenant(context.tenantId());
            jdbcTemplate.update("""
                    INSERT INTO rag_source
                        (source_id, tenant_id, source_type, title, publisher, isbn, doi,
                         trust_tier, ver_status, ver_method, ver_evidence, verified_at,
                         verified_by, ver_valid_days, license_status, object_ref, content_hash)
                    VALUES (?::uuid, rag_current_tenant(), ?, ?, ?, ?, ?,
                            'UNVERIFIED', 'PENDING', 'NONE', NULL, NULL,
                            NULL, 365, 'PENDING'::rag_license_status, ?, ?)
                    """,
                    sourceId, registration.sourceType(), registration.title(),
                    registration.publisher(),
                    blankToNull(registration.isbn()), blankToNull(registration.doi()),
                    blankToNull(registration.objectRef()), blankToNull(registration.contentHash()));
            // ⚠️ 登记也必须留痕（RAG-D07 §2.5：「至少 INGEST（登记）必须落审计」）。
            //    与 INSERT 同事务——审计写不进去则登记整体回滚，
            //    避免出现「库里有来源但无登记审计」的无痕数据。
            appendAuditWithinTenant(context.tenantId(), principalId, "INGEST", sourceId);
            return findSourceWithinTenant(sourceId);
        });
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
    public BulkRegistrationResult registerSources(@NonNull RagTenantContext context,
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
    public List<CorpusSource> listSources(@NonNull RagTenantContext context,
                                             @NonNull SourceQuery query) {
        return transactionTemplate.execute(status -> {
            setTenant(context.tenantId());
            List<Object> args = new ArrayList<>();
            StringBuilder sql = new StringBuilder("""
                    SELECT source_id, source_type, title, publisher, isbn, doi,
                           trust_tier, ver_status, ver_method, ver_evidence, verified_at,
                           verified_by, ver_valid_days, license_status, object_ref,
                           content_hash, created_at, updated_at
                    FROM rag_source
                    WHERE tenant_id = rag_current_tenant()
                    """);
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
    public CorpusSource findSource(@NonNull RagTenantContext context,
                                       @NonNull String sourceId) {
        return transactionTemplate.execute(status -> {
            setTenant(context.tenantId());
            return findSourceWithinTenant(sourceId);
        });
    }

    @Override
    @Nullable
    public CorpusSource applyVerification(@NonNull RagTenantContext context,
                                          @NonNull String sourceId,
                                          @NonNull VerificationDecision decision) {
        return transactionTemplate.execute(status -> {
            setTenant(context.tenantId());
            CorpusSource current = findSourceWithinTenant(sourceId);
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

            jdbcTemplate.update("""
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
                    WHERE tenant_id = rag_current_tenant() AND source_id = ?::uuid
                    """,
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
                jdbcTemplate.update("""
                        UPDATE rag_document
                        SET ver_status = 'REVOKED'::rag_verification_status,
                            retrievable = FALSE,
                            revoked_at = now(),
                            revoked_reason = ?,
                            license_status = 'RESTRICTED'::rag_license_status,
                            updated_at = now()
                        WHERE tenant_id = rag_current_tenant() AND source_id = ?::uuid
                        """, decision.evidenceRef(), sourceId);
            }

            // ⚠️ 审计与状态变更同事务（RAG-D07 §2.5）。
            //    V3 迁移后 rag_access_audit 允许 ADMIT / REJECT 动作，
            //    使「审核员的判定行为」有独立事件流，而不必借道 INGEST
            //    （RAG-D07 §7 记载的缺口）。
            appendAuditWithinTenant(context.tenantId(), decision.decidedBy(),
                    auditActionFor(decision.targetStatus()), sourceId);

            return findSourceWithinTenant(sourceId);
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
    public List<CorpusDocument> listDocuments(@NonNull RagTenantContext context,
                                                @NonNull String sourceId) {
        return transactionTemplate.execute(status -> {
            setTenant(context.tenantId());
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
                    WHERE d.tenant_id = rag_current_tenant() AND d.source_id = ?::uuid
                    ORDER BY d.created_at DESC, d.document_id
                    """,
                    (rs, i) -> mapDocument(rs), sourceId);
        });
    }

    @Override
    @Nullable
    public CorpusDocument applyDocumentVerification(@NonNull RagTenantContext context,
                                                     @NonNull String documentId,
                                                     @NonNull DocumentVerificationDecision decision) {
        return transactionTemplate.execute(status -> {
            setTenant(context.tenantId());
            CorpusDocument current = findDocumentWithinTenant(documentId);
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
            String sourceStatus = querySourceVerificationStatus(current.sourceId());
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

            jdbcTemplate.update("""
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
                    WHERE tenant_id = rag_current_tenant() AND document_id = ?::uuid
                    """,
                    decision.targetStatus().name(), retrievable,
                    decision.targetStatus().name(), decision.targetStatus().name(),
                    decision.evidenceRef(), decision.targetStatus().name(), documentId);

            // ⚠️ 审计与状态变更同事务（RAG-D07 §2.5）。
            //    载体级动作记在**来源**上：审计表没有「文档」维度，
            //    而「这个来源下的语料发生过什么」正是审计要回答的问题。
            appendAuditWithinTenant(context.tenantId(), decision.decidedBy(),
                    DocumentVerification.auditActionFor(decision.targetStatus()),
                    current.sourceId());

            return findDocumentWithinTenant(documentId);
        });
    }

    @Override
    @NonNull
    public CorpusIssues listIssues(@NonNull RagTenantContext context) {
        return transactionTemplate.execute(status -> {
            setTenant(context.tenantId());

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
                    WHERE d.tenant_id = rag_current_tenant()
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
                            rs.getLong("missing_embedding_count")));

            List<CorpusIssues.DocumentParseFailure> failedParse = jdbcTemplate.query("""
                    SELECT d.document_id, d.source_id, d.title, d.carrier, d.parse_status,
                           (SELECT count(*) FROM rag_chunk c
                             WHERE c.tenant_id = d.tenant_id AND c.document_id = d.document_id)
                               AS chunk_count
                    FROM rag_document d
                    WHERE d.tenant_id = rag_current_tenant() AND d.parse_status = 'FAILED'
                    ORDER BY d.created_at DESC, d.document_id
                    """,
                    (rs, i) -> new CorpusIssues.DocumentParseFailure(
                            rs.getString("document_id"),
                            rs.getString("source_id"),
                            rs.getString("title"),
                            rs.getString("carrier"),
                            rs.getString("parse_status"),
                            rs.getLong("chunk_count")));

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
                    WHERE tenant_id = rag_current_tenant()
                      AND ver_status IN ('PENDING', 'IN_REVIEW')
                    ORDER BY created_at DESC, source_id
                    """,
                    (rs, i) -> new CorpusIssues.SourcePendingReview(
                            rs.getString("source_id"),
                            rs.getString("title"),
                            rs.getString("verification_status"),
                            rs.getBoolean("stale"),
                            rs.getString("trust_tier")));

            return new CorpusIssues(missingEmbedding, failedParse, pendingReview);
        });
    }

    @Override
    @NonNull
    public List<ChunkSummary> listChunks(@NonNull RagTenantContext context,
                                            @NonNull String documentId,
                                         int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须 > 0");
        }
        return transactionTemplate.execute(status -> {
            setTenant(context.tenantId());
            return jdbcTemplate.query("""
                    SELECT c.chunk_id, c.role, c.page_number, c.section_path, c.exercise_no,
                           length(c.text) AS char_length, c.content_hash,
                           (e.chunk_id IS NOT NULL) AS has_embedding
                    FROM rag_chunk c
                    LEFT JOIN rag_chunk_embedding e
                           ON c.chunk_id = e.chunk_id AND c.tenant_id = e.tenant_id
                    WHERE c.tenant_id = rag_current_tenant() AND c.document_id = ?::uuid
                    ORDER BY c.page_number, c.char_start, c.chunk_id
                    LIMIT ?
                    """,
                    (rs, i) -> new ChunkSummary(
                            rs.getString("chunk_id"),
                            rs.getString("role"),
                            rs.getInt("page_number"),
                            rs.getString("section_path"),
                            rs.getString("exercise_no"),
                            rs.getInt("char_length"),
                            rs.getBoolean("has_embedding"),
                            rs.getString("content_hash")),
                    documentId, limit);
        });
    }

    // ---------------------------------------------------------------------
    // 统计
    // ---------------------------------------------------------------------

    @Override
    @NonNull
    public CorpusStatistics statistics(@NonNull RagTenantContext context) {
        return transactionTemplate.execute(status -> {
            setTenant(context.tenantId());
            // ⚠️ 租户权威门槛（G-3）**由 platform 下发**（RagTenantContext.minTrustTier）。
            //    RAG 库不存租户信息（V4 迁移已移除 rag_tenant 副本），
            //    门槛的权威源是 platform（RAG-D07 §3.3）。
            TrustTier minTrustTier = context.minTrustTier();

            long totalSources = countLong("SELECT count(*) FROM rag_source"
                    + " WHERE tenant_id = rag_current_tenant()");
            long verifiedSources = countLong("SELECT count(*) FROM rag_source"
                    + " WHERE tenant_id = rag_current_tenant() AND ver_status = 'VERIFIED'");
            long admissibleSources = countLong("""
                    SELECT count(*) FROM rag_source
                    WHERE tenant_id = rag_current_tenant()
                      AND ver_status = 'VERIFIED'
                      AND %s
                      AND license_status IN ('LICENSED', 'FAIR_USE_QUOTATION')
                      AND NOT (verified_at IS NULL
                           OR verified_at + (ver_valid_days || ' days')::interval < now())
                    """.formatted(trustTierInClause(minTrustTier)),
                    trustTierFilterArgs(minTrustTier).toArray());

            long totalDocuments = countLong("SELECT count(*) FROM rag_document"
                    + " WHERE tenant_id = rag_current_tenant()");
            long totalChunks = countLong("SELECT count(*) FROM rag_chunk"
                    + " WHERE tenant_id = rag_current_tenant()");
            long retrievableChunks = countLong("""
                    SELECT count(*)
                    FROM rag_chunk c
                    JOIN rag_document doc
                         ON c.document_id = doc.document_id AND c.tenant_id = doc.tenant_id
                    JOIN rag_source src
                         ON doc.source_id = src.source_id AND c.tenant_id = src.tenant_id
                    WHERE c.tenant_id = rag_current_tenant()
                      AND src.ver_status = 'VERIFIED'
                      AND src.%s
                      AND src.license_status IN ('LICENSED', 'FAIR_USE_QUOTATION')
                      AND NOT (src.verified_at IS NULL
                           OR src.verified_at + (src.ver_valid_days || ' days')::interval < now())
                    """.formatted(trustTierInClause(minTrustTier)),
                    trustTierFilterArgs(minTrustTier).toArray());
            long missingEmbedding = countLong("""
                    SELECT count(*) FROM rag_chunk c
                    WHERE c.tenant_id = rag_current_tenant()
                      AND NOT EXISTS (SELECT 1 FROM rag_chunk_embedding e
                                       WHERE e.chunk_id = c.chunk_id AND e.tenant_id = c.tenant_id)
                    """);

            Map<String, Long> chunksByRole = groupCount("""
                    SELECT role::text AS k, count(*) AS c FROM rag_chunk
                    WHERE tenant_id = rag_current_tenant() GROUP BY role
                    """);
            Map<String, Long> sourcesByStatus = groupCount("""
                    SELECT ver_status::text AS k, count(*) AS c FROM rag_source
                    WHERE tenant_id = rag_current_tenant() GROUP BY ver_status
                    """);
            Map<String, Long> documentsByParseStatus = groupCount("""
                    SELECT parse_status AS k, count(*) AS c FROM rag_document
                    WHERE tenant_id = rag_current_tenant() GROUP BY parse_status
                    """);

            return new CorpusStatistics(totalSources, verifiedSources, admissibleSources,
                    totalDocuments, totalChunks, retrievableChunks, missingEmbedding,
                    chunksByRole, sourcesByStatus, documentsByParseStatus);
        });
    }

    // ---------------------------------------------------------------------
    // 审计
    // ---------------------------------------------------------------------

    @Override
    public void appendAudit(@NonNull RagTenantContext context, @NonNull String principalId,
                             @NonNull String action, @Nullable String sourceId) {
        transactionTemplate.executeWithoutResult(status -> {
            setTenant(context.tenantId());
            appendAuditWithinTenant(context.tenantId(), principalId, action, sourceId);
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
    public DailySearchStatistics dailySearchStatistics(@NonNull RagTenantContext context, int days) {
        int safeDays = clampDays(days);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = today.minusDays(safeDays - 1L);
        Map<String, Long> countsByDay = transactionTemplate.execute(status -> {
            setTenant(context.tenantId());
            return dailyCount("""
                    SELECT to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS k,
                           count(*) AS c
                    FROM rag_access_audit
                    WHERE tenant_id = rag_current_tenant()
                      AND action = 'RETRIEVE'
                      AND occurred_at >= ?
                      AND occurred_at < ?
                    GROUP BY 1
                    """, start, today.plusDays(1));
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
    public DailyImportStatistics dailyImportStatistics(@NonNull RagTenantContext context, int days) {
        int safeDays = clampDays(days);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = today.minusDays(safeDays - 1L);
        Map<String, Long> sourcesByDay = transactionTemplate.execute(status -> {
            setTenant(context.tenantId());
            return dailyCount("""
                    SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS k,
                           count(*) AS c
                    FROM rag_source
                    WHERE tenant_id = rag_current_tenant()
                      AND created_at >= ?
                      AND created_at < ?
                    GROUP BY 1
                    """, start, today.plusDays(1));
        });
        Map<String, Long> documentsByDay = transactionTemplate.execute(status -> {
            setTenant(context.tenantId());
            return dailyCount("""
                    SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS k,
                           count(*) AS c
                    FROM rag_document
                    WHERE tenant_id = rag_current_tenant()
                      AND created_at >= ?
                      AND created_at < ?
                    GROUP BY 1
                    """, start, today.plusDays(1));
        });
        Map<String, Long> chunksByDay = transactionTemplate.execute(status -> {
            setTenant(context.tenantId());
            return dailyCount("""
                    SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS k,
                           count(*) AS c
                    FROM rag_chunk
                    WHERE tenant_id = rag_current_tenant()
                      AND created_at >= ?
                      AND created_at < ?
                    GROUP BY 1
                    """, start, today.plusDays(1));
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
    private Map<String, Long> dailyCount(@NonNull String sql, @NonNull LocalDate fromInclusive,
                                         @NonNull LocalDate toExclusive) {
        Timestamp from = Timestamp.from(fromInclusive.atStartOfDay(ZoneOffset.UTC).toInstant());
        Timestamp to = Timestamp.from(toExclusive.atStartOfDay(ZoneOffset.UTC).toInstant());
        Map<String, Long> result = new LinkedHashMap<>();
        jdbcTemplate.query(sql, ps -> {
            ps.setTimestamp(1, from);
            ps.setTimestamp(2, to);
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
     * 建立租户上下文。
     *
     * <p>⚠️ 必须用 {@code queryForObject()} 而非 {@code update()}：
     * {@code set_config} 是 SELECT（返回一行），{@code update()} 期望「无结果集」，
     * 否则抛 {@code DataIntegrityViolation}（A result was returned when none was expected）。
     */
    private void setTenant(@NonNull String tenantId) {
        jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantId);
    }

    /**
     * 在<b>已建立租户上下文</b>的事务内查询来源。
     *
     * <p>⚠️ 该方法<b>不</b>自己开事务——它假定调用方已在
     * {@code set_config} 之后调用（否则 RLS 会把它过滤成空，
     * 表现为「来源不存在」这类难以定位的静默失败）。
     */
    @Nullable
    private CorpusSource findSourceWithinTenant(@NonNull String sourceId) {
        List<CorpusSource> rows = jdbcTemplate.query("""
                SELECT source_id, source_type, title, publisher, isbn, doi,
                       trust_tier, ver_status, ver_method, ver_evidence, verified_at,
                       verified_by, ver_valid_days, license_status, object_ref,
                       content_hash, created_at, updated_at
                FROM rag_source
                WHERE tenant_id = rag_current_tenant() AND source_id = ?::uuid
                """,
                (rs, i) -> mapSource(rs), sourceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 在<b>已建立租户上下文</b>的事务内查询载体。
     *
     * <p>⚠️ 该方法<b>不</b>自己开事务——它假定调用方已在
     * {@code set_config} 之后调用（否则 RLS 会把它过滤成空，
     * 表现为「载体不存在」这类难以定位的静默失败）。
     */
    @Nullable
    private CorpusDocument findDocumentWithinTenant(@NonNull String documentId) {
        List<CorpusDocument> rows = jdbcTemplate.query("""
                SELECT d.document_id, d.source_id, d.edition_id, d.carrier, d.title,
                       d.page_count, d.object_ref, d.parse_status, d.parser_version,
                       d.content_hash, d.created_at, d.updated_at,
                       d.ver_status, d.retrievable, d.revoked_at, d.revoked_reason,
                       d.license_status,
                       (SELECT count(*) FROM rag_chunk c
                         WHERE c.tenant_id = d.tenant_id AND c.document_id = d.document_id)
                           AS chunk_count
                FROM rag_document d
                WHERE d.tenant_id = rag_current_tenant() AND d.document_id = ?::uuid
                """,
                (rs, i) -> mapDocument(rs), documentId);
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
    private String querySourceVerificationStatus(@NonNull String sourceId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT ver_status::text FROM rag_source"
                        + " WHERE tenant_id = rag_current_tenant() AND source_id = ?::uuid",
                (rs, i) -> rs.getString(1), sourceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void appendAuditWithinTenant(@NonNull String tenantId, @NonNull String principalId,
                                         @NonNull String action, @Nullable String sourceId) {
        // ⚠️ action 受 rag_access_audit_action_chk 约束
        //    （V3 迁移后：RETRIEVE/INGEST/CITE/EXPORT/REVOKE/ADMIT/REJECT），
        //    这里做白名单校验，避免因拼写错误导致整笔审计插入失败
        //    （进而回滚状态变更——那会让「状态已改但无留痕」或「状态与审计不一致」）。
        if (!AUDIT_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("未知的审计动作：" + action);
        }
        jdbcTemplate.update("""
                INSERT INTO rag_access_audit
                    (audit_id, tenant_id, principal_id, action, source_id)
                VALUES (gen_random_uuid(), rag_current_tenant(), ?::uuid, ?, ?::uuid)
                """,
                principalId, action, sourceId);
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

    private Map<String, Long> groupCount(String sql) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            result.put(rs.getString("k"), rs.getLong("c"));
        });
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
                toInstant(rs.getTimestamp("updated_at")));
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
