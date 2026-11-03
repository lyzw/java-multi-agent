package cn.studykid.rag.core.ingest;

import org.jspecify.annotations.Nullable;

/**
 * 去重判定规则（方案 §4 Z3 / 设计 §2.2.2 / N1）。
 *
 * <p><b>⚠️ 本类只定义「什么算重复」这一条语义，不碰数据库</b>：
 * 应用层预检（{@code IngestDedupPrecheck}）与数据库部分唯一索引
 * （{@code rag_document_tenant_content_hash_uq} 等）必须<b>共用同一口径</b>，
 * 否则会出现两类事故：
 * <ul>
 *   <li>预检放行、索引拒绝 → 用户看到 500；</li>
 *   <li>预检拒收、索引可入 → 合法数据永远录不进来。</li>
 * </ul>
 *
 * <p><b>「重复」的显式定义</b>：存在<b>活跃</b>的同坐标 / 同哈希对象。
 * 「活跃」= 排除以下两类（它们重新入库是合法路径）：
 * <ul>
 *   <li>载体 {@code ver_status='REVOKED'}：载体撤回后经新来源重传同文件；</li>
 *   <li>载体 {@code parse_status='FAILED'}：解析失败后同文件重传重跑，是失败处置的唯一活口。</li>
 * </ul>
 * 来源侧同理排除 {@code ver_status='REVOKED'}——RAG-D07 T-10 要求撤回后
 * <b>新建 source_id</b> 重新登记，同 ISBN 必然重现。
 */
public final class IngestDedupRules {

    private IngestDedupRules() {
    }

    /**
     * 载体去重判定所需的「是否活跃」谓词（SQL 片段）。
     *
     * <p><b>⚠️ 必须与 {@code rag_document_tenant_content_hash_uq} 的 WHERE 逐字一致</b>。
     * 修改此处时，务必同步修改 01_rag_ddl.sql 与 05_v1_0_2_s1_alter.sql。
     */
    public static final String ACTIVE_DOCUMENT_PREDICATE =
            "ver_status <> 'REVOKED'::rag_verification_status AND parse_status <> 'FAILED'";

    /**
     * 来源去重判定所需的「是否活跃」谓词（SQL 片段）。
     *
     * <p><b>⚠️ 必须与 {@code rag_source_tenant_isbn_uq} / {@code rag_source_tenant_doi_uq}
     * 的 WHERE 逐字一致</b>。
     */
    public static final String ACTIVE_SOURCE_PREDICATE =
            "ver_status <> 'REVOKED'::rag_verification_status";

    /**
     * 去重命中的层级。
     */
    public enum Layer {
        /** 来源级：ISBN/DOI 精确命中。 */
        SOURCE("DUP_SOURCE"),
        /** 版本级：五轴坐标命中 {@code rag_edition_unique}。 */
        EDITION("DUP_EDITION"),
        /** 载体级：{@code content_hash} 相同。 */
        CARRIER("DUP_CARRIER");

        private final String errorCode;

        Layer(String errorCode) {
            this.errorCode = errorCode;
        }

        /**
         * 对应对外错误码（方案 §4 Z3）。
         *
         * @return 错误码字面量
         */
        public String errorCode() {
            return errorCode;
        }
    }

    /**
     * 去重命中结果。
     *
     * @param layer     命中的层级
     * @param hitId     命中对象 ID（来源 ID / 版本 ID / 载体 ID），供前端定位
     * @param hitTitle  命中对象标题（便于提示「已存在哪一本」），可为 null
     */
    public record DuplicateHit(
            Layer layer,
            String hitId,
            @Nullable String hitTitle
    ) {
        /**
         * 人类可读的拒收说明（写进 400 响应，前端就地展示）。
         *
         * @return 说明文案
         */
        public String describe() {
            String what = switch (layer) {
                case SOURCE -> "同 ISBN/DOI 的来源";
                case EDITION -> "同五轴坐标的版本";
                case CARRIER -> "内容完全相同的载体";
            };
            String suffix = (hitTitle == null || hitTitle.isBlank())
                    ? ""
                    : "（" + hitTitle + "）";
            return "已存在" + what + suffix + "，ID=" + hitId;
        }
    }
}
