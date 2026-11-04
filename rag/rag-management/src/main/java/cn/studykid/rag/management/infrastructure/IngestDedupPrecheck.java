package cn.studykid.rag.management.infrastructure;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.core.ingest.IngestDedupRules;
import cn.studykid.rag.core.ingest.IngestDedupRules.DuplicateHit;
import cn.studykid.rag.core.ingest.IngestDedupRules.Layer;
import cn.studykid.rag.core.scope.RagCorpusScope;
import cn.studykid.rag.management.core.RagManagementContext;

/**
 * 上传前的三层去重预检（方案 §4 Z3 / 设计 §2.2.2 / N1）。
 *
 * <p><b>⚠️ 本类只做「读侧预检」，唯一性的最终保证在数据库部分唯一索引</b>：
 * 预检通过后的两个并发请求仍可能同时落库，此时由
 * {@code rag_document_tenant_content_hash_uq} 等索引拒绝，
 * 调用方须把 {@code DuplicateKeyException} 映射回同一组错误码。
 * 「预检 + 约束」是两层防线，不是替代关系。
 *
 * <p><b>⚠️ 预检谓词必须与索引 WHERE 逐字同口径</b>：本类复用
 * {@link IngestDedupRules#ACTIVE_DOCUMENT_PREDICATE} 与
 * {@link IngestDedupRules#ACTIVE_SOURCE_PREDICATE}，<b>不在此处另写一份条件</b>——
 * 两份条件一旦漂移，就会出现「预检说重复、约束说可入」或反之的两层打架。
 *
 * <p><b>⚠️ 会话租户由本类按作用域建立</b>（{@link RagSessionScope}）：
 * 租户档绑该租户；平台档<b>显式置空</b>（不是跳过——池化连接可能带着上一位的租户），
 * 此时 RLS 读策略 {@code tenant_id = ANY (ARRAY[rag_current_tenant(), rag_global_tenant_id()])}
 * 只剩「全局行」一支，谓词再绑定全局哨兵，正好是平台语料那一批行。
 * 不建会话的后果是查询恒返回空、预检形同虚设（静默失效，比报错更危险）。
 *
 * <p><b>⚠️ 平台档的租户值是 {@code queryTenantId()} 而不是 {@code requireTenant()}</b>：
 * 后者对平台档必然抛异常，会让「平台语料抽检时的注入校验」一律 500（实测踩到）。
 * 换言之：预检是<b>读</b>，读的租户列就是哨兵；写通道的选择不归本类管。
 */
public class IngestDedupPrecheck {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * 不绑定事务模板的构造器：每次预检自开事务（会话变量只在事务内有效）。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public IngestDedupPrecheck(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null);
    }

    /**
     * @param jdbcTemplate        JDBC 模板
     * @param transactionTemplate 事务模板（可为 null = 自建一个仅用于本类的模板）
     */
    public IngestDedupPrecheck(JdbcTemplate jdbcTemplate,
                               @Nullable TransactionTemplate transactionTemplate) {
        if (jdbcTemplate == null) {
            throw new IllegalArgumentException("jdbcTemplate 不能为空");
        }
        this.jdbcTemplate = jdbcTemplate;
        // ⚠️ 缺省自建：会话变量必须在一个事务里设置才有效，
        //    而预检的调用方（控制器）不持有事务边界。
        this.transactionTemplate = transactionTemplate != null
                ? transactionTemplate
                : new TransactionTemplate(new DataSourceTransactionManager(
                        Objects.requireNonNull(jdbcTemplate.getDataSource(),
                                "JdbcTemplate 未绑定 DataSource，无法为预检建立会话")));
    }

    /**
     * 按作用域预检「同内容哈希的活跃载体」是否存在。
     *
     * <p>⚠️ 平台档必须走这里而不是自己拼租户 ID：租户列与<b>会话</b>是两件事
     * （会话置空 + 谓词绑哨兵），后者由 {@link RagSessionScope} 负责。
     *
     * @param context     作用域上下文
     * @param contentHash 服务端 SHA-256
     * @return 命中结果；未命中返回 {@code null}
     */
    @Nullable
    public DuplicateHit checkCarrier(@NonNull RagManagementContext context,
                                     @NonNull String contentHash) {
        if (context.scope() == RagCorpusScope.ALL) {
            // 与读侧同一语义：rag_app 角色看不到别的租户，给不出正确口径的答案。
            throw new IllegalStateException("跨租户盘点档不支持去重预检（需平台运维数据源）");
        }
        String tenantId = context.queryTenantId();
        return transactionTemplate.execute(status -> {
            RagSessionScope.apply(jdbcTemplate, context);
            return checkCarrier(tenantId, contentHash);
        });
    }

    /**
     * 按作用域预检「同 ISBN / DOI 的活跃来源」是否存在。
     *
     * @param context 作用域上下文
     * @param isbn    ISBN（可为 null）
     * @param doi     DOI（可为 null）
     * @return 命中结果；未命中返回 {@code null}
     */
    @Nullable
    public DuplicateHit checkSource(@NonNull RagManagementContext context,
                                    @Nullable String isbn,
                                    @Nullable String doi) {
        if (context.scope() == RagCorpusScope.ALL) {
            throw new IllegalStateException("跨租户盘点档不支持去重预检（需平台运维数据源）");
        }
        String tenantId = context.queryTenantId();
        return transactionTemplate.execute(status -> {
            RagSessionScope.apply(jdbcTemplate, context);
            return checkSource(tenantId, isbn, doi);
        });
    }

    /**
     * 来源级预检：同 ISBN / DOI 的<b>活跃</b>来源是否已存在。
     *
     * <p>⚠️ 排除 REVOKED 来源：RAG-D07 T-10 要求撤回后新建 source_id 重登记，
     * 同 ISBN 必然重现，不得误判为重复（N1）。
     *
     * @param tenantId 租户 ID
     * @param isbn     ISBN（可为 null）
     * @param doi      DOI（可为 null）
     * @return 命中结果；未命中返回 {@code null}
     */
    @Nullable
    public DuplicateHit checkSource(@NonNull String tenantId,
                                                      @Nullable String isbn,
                                                      @Nullable String doi) {
        String active = IngestDedupRules.ACTIVE_SOURCE_PREDICATE;
        if (isbn != null && !isbn.isBlank()) {
            List<String[]> hits = jdbcTemplate.query(
                    "SELECT source_id::text, title FROM rag_source"
                            + " WHERE tenant_id = ?::uuid AND isbn = ?"
                            + "   AND " + active + " LIMIT 1",
                    (rs, i) -> new String[]{rs.getString(1), rs.getString(2)},
                    tenantId, isbn.trim());
            if (!hits.isEmpty()) {
                return new DuplicateHit(
                        Layer.SOURCE, hits.get(0)[0], hits.get(0)[1]);
            }
        }
        if (doi != null && !doi.isBlank()) {
            List<String[]> hits = jdbcTemplate.query(
                    "SELECT source_id::text, title FROM rag_source"
                            + " WHERE tenant_id = ?::uuid AND doi = ?"
                            + "   AND " + active + " LIMIT 1",
                    (rs, i) -> new String[]{rs.getString(1), rs.getString(2)},
                    tenantId, doi.trim());
            if (!hits.isEmpty()) {
                return new DuplicateHit(
                        Layer.SOURCE, hits.get(0)[0], hits.get(0)[1]);
            }
        }
        return null;
    }

    /**
     * 载体级预检：同 {@code content_hash} 的<b>活跃</b>载体是否已存在。
     *
     * <p>⚠️ 排除两类载体（N1）：
     * ① {@code ver_status='REVOKED'}——载体撤回后经新来源重传同文件是合法路径；
     * ② {@code parse_status='FAILED'}——解析失败后同文件重传重跑，是失败处置的唯一活口。
     *
     * @param tenantId    租户 ID
     * @param contentHash 服务端 SHA-256（64 位十六进制）
     * @return 命中结果；未命中返回 {@code null}
     */
    @Nullable
    public DuplicateHit checkCarrier(@NonNull String tenantId,
                                                       @NonNull String contentHash) {
        String active = IngestDedupRules.ACTIVE_DOCUMENT_PREDICATE;
        List<String[]> hits = jdbcTemplate.query(
                "SELECT document_id::text, title FROM rag_document"
                        + " WHERE tenant_id = ?::uuid AND content_hash = ?"
                        + "   AND " + active + " LIMIT 1",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2)},
                tenantId, contentHash);
        if (hits.isEmpty()) {
            return null;
        }
        return new DuplicateHit(
                Layer.CARRIER, hits.get(0)[0], hits.get(0)[1]);
    }

    /**
     * 版本级预检：五轴坐标是否已存在（对应 {@code rag_edition_unique}）。
     *
     * <p>⚠️ 版本级不是「拒收」而是「复用」：同来源下多载体共享版本是正常的
     * （官方电子版 + 扫描版挂同一版本坐标）。故本方法只用于提示，
     * 真正的唯一性由 {@code rag_edition_unique} 保证，且
     * {@code importDocument} 已实现「优先复用来源下已有版本」。
     *
     * @param tenantId     租户 ID
     * @param subject      学科
     * @param grade        年级
     * @param term         册次
     * @param publisher    出版社
     * @param revisionCode 版次
     * @return 已存在的版本 ID；不存在返回 {@code null}
     */
    @Nullable
    public String findEditionId(@NonNull String tenantId,
                                @NonNull String subject,
                                @NonNull String grade,
                                @NonNull String term,
                                @NonNull String publisher,
                                @NonNull String revisionCode) {
        return jdbcTemplate.query(
                        "SELECT edition_id::text FROM rag_edition"
                                + " WHERE tenant_id = ?::uuid AND subject = ? AND grade = ?"
                                + "   AND term = ? AND publisher = ? AND revision_code = ?"
                                + " LIMIT 1",
                        (rs, i) -> rs.getString(1),
                        tenantId, subject, grade, term, publisher, revisionCode)
                .stream().findFirst().orElse(null);
    }
}
