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
 * @param subject 版本五轴·学科（可空：存量与批量导入路径暂不强制）
 * @param grade 版本五轴·年级（可空）
 * @param term 版本五轴·册次（可空；非空时必须在白名单内）
 * @param revisionCode 版本五轴·版次（可空）
 * @param editionPublisher 版本五轴·出版社（可空；缺省回落到 {@code publisher}）
 */
public record SourceRegistration(
        @NonNull String sourceType,
        @NonNull String title,
        @NonNull String publisher,
        @Nullable String isbn,
        @Nullable String doi,
        @Nullable String objectRef,
        @Nullable String contentHash,
        @Nullable String subject,
        @Nullable String grade,
        @Nullable String term,
        @Nullable String revisionCode,
        @Nullable String editionPublisher
) {

    /**
     * 册次取值白名单。
     *
     * <p><b>⚠️ 必须与 {@code rag_edition.term} 的 CHECK 约束、前端下拉同口径</b>：
     * 三层任何一层放宽都会在落库时报约束名，用户看不懂。
     */
    private static final java.util.Set<String> ALLOWED_TERMS =
            java.util.Set.of("上册", "下册", "全一册");

    /** 五轴齐全所需的轴数。 */
    public static final int EDITION_AXIS_COUNT = 5;

    /**
     * 五轴齐全的便捷构造（不含五轴）。
     *
     * <p>⚠️ 保留旧构造签名：批量导入（CSV/ZIP）与既有调用方不走五轴，
     * 存量测试也依赖它。五轴为空即回落到「默认版本坐标」的既有行为。
     */
    public SourceRegistration(@NonNull String sourceType,
                              @NonNull String title,
                              @NonNull String publisher,
                              @Nullable String isbn,
                              @Nullable String doi,
                              @Nullable String objectRef,
                              @Nullable String contentHash) {
        this(sourceType, title, publisher, isbn, doi, objectRef, contentHash,
                null, null, null, null, null);
    }

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

        // ⚠️ 五轴**先归一再去校验**（顺序不能反）：
        //    前端下拉值常带首尾空白，若先校验后归一，合法值会被判非法，
        //    且错误信息显示原样空白让用户找不到问题（MetadataConfirmation 踩过同款）。
        subject = blankToNull(subject);
        grade = blankToNull(grade);
        term = blankToNull(term);
        revisionCode = blankToNull(revisionCode);
        editionPublisher = blankToNull(editionPublisher);

        // 册次非法值提前拦下：DB 的 CHECK 只会报约束名（rag_edition_term_chk），
        // 用户看不懂也不知道该改成什么。
        if (term != null && !ALLOWED_TERMS.contains(term)) {
            throw new IllegalArgumentException(
                    "册次只能是 上册 / 下册 / 全一册，收到：" + term);
        }
    }

    /**
     * 五轴是否**部分**填写（有轴缺轴）。
     *
     * <p><b>⚠️ 为什么「部分填写」要报错而不是静默忽略</b>：
     * 只填了学科却没填年级，说明用户以为自己登记的是「数学七年级」，
     * 而落库会退化成默认坐标（未指定/全学段）——这个偏差不可见、只在检索时暴露。
     * 宁可让登记失败并告诉他缺哪一轴。
     *
     * <p><b>⚠️ 出版社轴不参与「是否填写」的判定</b>：
     * 它缺省回落到来源的 {@code publisher}，而 {@code publisher} 是**必填**的——
     * 若把它算作"已填"，则「五轴全空」会被误判成"只差学科/年级/册次/版次"
     * 从而对**所有存量登记路径**报 400（单测 {@code emptyEditionIsNotFull} 抓到过）。
     * 故这里只用**四个显式轴**判断"用户是否尝试填写五轴"，
     * 出版社轴仅在 {@link #hasFullEdition()} 里作为完整性的一部分参与。
     *
     * @return 部分填写时返回缺失的轴名（逗号分隔），否则 null
     */
    public @Nullable String partiallyFilledEdition() {
        record Axis(String name, String value) { }
        // ⚠️ 只含四个「用户显式填写」的轴（出版社见上面说明）。
        java.util.List<Axis> axes = java.util.List.of(
                new Axis("学科", subject),
                new Axis("年级", grade),
                new Axis("册次", term),
                new Axis("版次", revisionCode));
        java.util.List<String> filled = new java.util.ArrayList<>();
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (Axis a : axes) {
            if (a.value() == null || a.value().isBlank()) {
                missing.add(a.name());
            } else {
                filled.add(a.name());
            }
        }
        // 一个都没填 → 视为「不使用五轴」（存量/批量路径），不是"部分填写"。
        // 全填了 → 也不报错（缺的轴为空）。
        if (filled.isEmpty() || missing.isEmpty()) {
            return null;
        }
        return String.join("、", missing);
    }

    /**
     * 是否提供了完整的版本五轴。
     *
     * <p>⚠️ 判定为「五轴齐全」需同时满足：**每一轴都非空**（出版社轴缺省回落到
     * {@code publisher}）。注意「全空」也算未齐全——{@link #partiallyFilledEdition()}
     * 对「全空」与「齐全」都返回 null，故不能只看它。
     */
    public boolean hasFullEdition() {
        return !isBlank(subject) && !isBlank(grade) && !isBlank(term)
                && !isBlank(revisionCode) && !isBlank(effectiveEditionPublisher());
    }

    /** 生效的版本出版社（五轴专用；缺省回落到来源出版社）。 */
    public @NonNull String effectiveEditionPublisher() {
        return !isBlank(editionPublisher) ? editionPublisher : publisher;
    }

    /**
     * 归一到可被检索期反查的版本出版社值。
     *
     * <p><b>⚠️⚠️ 为什么必须归一</b>（否则会炸整条检索链路）：
     * 检索期 {@code JdbcRetrievalRepository.publisherFromDb} 用
     * {@code Publisher.displayName()} 逐个比对，**比对不上就抛
     * {@code IllegalStateException("未知出版社值")}**。
     * 而 {@code rag_edition.publisher} 是 {@code text} 列，写入不做校验——
     * 于是登记时写进「某某教育出版社」这种**出版机构名**（而非版本体系名），
     * 会让这本教材的**任何一次检索都 500**，且报错点离真因极远。
     *
     * <p>本方法把非枚举值统一收敛到 {@code OTHER（其他）}：宁可版本号略粗，
     * 也不能让一条登记把检索打挂。合法的版本体系名原样保留。
     *
     * @return {@code Publisher} 的合法展示名
     */
    public @NonNull String normalizedEditionPublisher() {
        return normalizeToDisplayName(
                effectiveEditionPublisher(),
                cn.studykid.rag.core.version.EditionCoordinate.Publisher.values(),
                cn.studykid.rag.core.version.EditionCoordinate.Publisher.OTHER.displayName());
    }

    /**
     * 归一到可被检索期反查的版本学科值（非枚举值 → {@code 未指定}）。
     *
     * <p>⚠️ 与出版社同款风险：{@code subjectFromDb} 反查失败会抛异常，
     * 使该校材的任何检索 500。
     */
    public @NonNull String normalizedEditionSubject() {
        return normalizeToDisplayName(
                subject,
                cn.studykid.rag.core.version.EditionCoordinate.Subject.values(),
                cn.studykid.rag.core.version.EditionCoordinate.Subject.UNSPECIFIED.displayName());
    }

    /**
     * 归一到可被检索期反查的版本年级值（非枚举值 → {@code 全学段}）。
     */
    public @NonNull String normalizedEditionGrade() {
        return normalizeToDisplayName(
                grade,
                cn.studykid.rag.core.version.EditionCoordinate.GradeBand.values(),
                cn.studykid.rag.core.version.EditionCoordinate.GradeBand.ALL_GRADES.displayName());
    }

    /**
     * 在枚举展示名中查找；找不到时回落到兜底值。
     *
     * <p>⚠️ 用泛型而非反射：{@code displayName()} 是这三类枚举的公共契约，
     * 泛型约束能把它变成编译期错误，反射只能在运行期炸。
     */
    private static <E extends Enum<E>> String normalizeToDisplayName(
            @Nullable String raw, E[] values, String fallback) {
        if (raw == null) {
            return fallback;
        }
        for (E v : values) {
            if (v.name() != null && displayNameOf(v).equals(raw)) {
                return raw;
            }
        }
        return fallback;
    }

    /** 读取三类版本枚举的展示名（各自的 {@code displayName} 方法签名一致）。 */
    private static String displayNameOf(Enum<?> v) {
        if (v instanceof cn.studykid.rag.core.version.EditionCoordinate.Subject s) {
            return s.displayName();
        }
        if (v instanceof cn.studykid.rag.core.version.EditionCoordinate.GradeBand g) {
            return g.displayName();
        }
        if (v instanceof cn.studykid.rag.core.version.EditionCoordinate.Publisher p) {
            return p.displayName();
        }
        if (v instanceof cn.studykid.rag.core.version.EditionCoordinate.Term t) {
            return t.displayName();
        }
        return v.name();
    }

    private static boolean isBlank(@Nullable String v) {
        return v == null || v.isBlank();
    }

    /** 空白串归一为 null（语义：「没填」只有一种表示）。 */
    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
