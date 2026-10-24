package cn.studykid.rag.management.core;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.rag.core.source.SourceVerification.VerificationMethod;
import cn.studykid.rag.core.source.SourceVerification.VerificationStatus;
import cn.studykid.rag.core.source.TrustTier;

/**
 * 来源登记命令（新建语料来源）。
 *
 * <p><b>⚠️ 登记态的两条硬约束</b>（RAG-D07 §2.3 阶段①）：
 * <ol>
 *   <li><b>登记不等于准入</b>：新建来源的 {@code trust_tier} 必须是
 *       {@code UNVERIFIED}、{@code ver_status} 必须是 {@code PENDING}。
 *       采集员<b>不得</b>自行提升权威等级或置 VERIFIED
 *       （RAG-D07 §2.2 角色表：「不得自行提升 trust_tier 或置 VERIFIED」）。</li>
 *   <li><b>权威等级与出版标识自洽</b>：V1 的 CHECK 约束要求
 *       教材/教辅等级必须有 ISBN、论文等级必须有 DOI。
 *       本命令在应用层提前校验，避免把错误推迟到 DB 约束报错。</li>
 * </ol>
 *
 * @param sourceType 来源类型
 * @param title 书名/标题
 * @param publisher 出版社/出版方
 * @param isbn ISBN
 * @param doi DOI
 * @param objectRef 正文在对象存储中的引用
 * @param contentHash 整册内容校验和
 */
public record SourceRegistration(
        @NonNull String sourceType,
        @NonNull String title,
        @NonNull String publisher,
        @Nullable String isbn,
        @Nullable String doi,
        @Nullable String objectRef,
        @Nullable String contentHash
) {

    public SourceRegistration {
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType 不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title 不能为空");
        }
        if (publisher == null || publisher.isBlank()) {
            throw new IllegalArgumentException("publisher 不能为空");
        }
        // 标识至少要有一个：教材/教辅给 ISBN，论文给 DOI，两者都空则无法校验。
        boolean hasIsbn = isbn != null && !isbn.isBlank();
        boolean hasDoi = doi != null && !doi.isBlank();
        if (!hasIsbn && !hasDoi) {
            throw new IllegalArgumentException("ISBN 与 DOI 至少提供一个（否则无法进入校验流程）");
        }
        // ⚠️⚠️ 内容校验和必须是 64 位十六进制（SHA-256）。
        //
        //    ⚠️ **为什么在领域层校验而不是只靠 DTO 的 @Pattern**：
        //    批量登记端点**刻意不级联 @Valid**（否则任一行的约束失败会让
        //    整批返回 400，与「逐条独立成败」的治理语义冲突）。
        //    若此处不校验，非法 contentHash 会一路进库 ——
        //    而 {@code rag_source.content_hash} 是 {@code CHAR(64)}：
        //    **短值会被 PostgreSQL 静默空格填充**（不报错），
        //    该列的用途恰恰是「检测语料被静默替换」，
        //    填充后的哈希失去校验能力（且与真实摘要比较时永不相等）。
        //
        //    ⚠️ 单条登记路径由 DTO 的 @Pattern 拦截（400），
        //    批量路径由本处拦截（该行失败、其余行照常）——
        //    两条路径都拒绝非法哈希，只是报告粒度不同（各自符合其语义）。
        if (contentHash != null && !contentHash.isBlank()
                && !contentHash.matches("^[0-9a-fA-F]{64}$")) {
            throw new IllegalArgumentException(
                    "contentHash 必须是 64 位十六进制 SHA-256（实际长度 "
                            + contentHash.length() + "）");
        }
    }

    /**
     * 校验「权威等级 ↔ 出版标识」是否自洽。
     *
     * <p>⚠️ 与 V1 的 {@code rag_source_identifier_chk} 约束同构：
     * 教材/教辅等级必须提供 ISBN，论文等级必须提供 DOI，
     * {@code UNVERIFIED} 等级则两者皆可缺失。
     *
     * @param trustTier 目标权威等级
     * @throws IllegalArgumentException 不自洽时抛出
     */
    public void requireIdentifierConsistent(@NonNull TrustTier trustTier) {
        boolean hasIsbn = isbn != null && !isbn.isBlank();
        boolean hasDoi = doi != null && !doi.isBlank();
        boolean consistent = switch (trustTier) {
            case OFFICIAL_PUBLISHER, OFFICIAL_TUTORIAL -> hasIsbn;
            case PEER_REVIEWED_PAPER, PREPRINT -> hasDoi;
            case UNVERIFIED -> true;
        };
        if (!consistent) {
            throw new IllegalArgumentException(
                    "权威等级 " + trustTier + " 与出版标识不自洽："
                            + "教材/教辅等级需 ISBN，论文等级需 DOI");
        }
    }

    /**
     * @return 登记态的校验状态（恒为 PENDING）
     */
    public VerificationStatus registrationStatus() {
        return VerificationStatus.PENDING;
    }

    /**
     * @return 登记态的校验手段（恒为 NONE）
     */
    public VerificationMethod registrationMethod() {
        return VerificationMethod.NONE;
    }
}
