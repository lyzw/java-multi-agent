package cn.studykid.rag.management.core;

import org.jspecify.annotations.Nullable;

/**
 * 语料版权状态（对齐数据库枚举 {@code rag_license_status}）。
 *
 * <p><b>⚠️ 为什么领域层也要有这个枚举</b>：数据库枚举挡得住拼写错误，
 * 却挡不住「调用方根本不传」——改造前校验决定命令里压根没有版权轴，
 * 登记 SQL 又硬编码 {@code PENDING}，结果 7 本教材技术导入全部完成、
 * 来源/载体却永远停在「未取得授权」，既不能 VERIFIED 也不该被检索到。
 * 把版权状态收进命令对象，才能让 VERIFIED 时<b>必须显式声明</b>成为编译期/构造期事实，
 * 而不是靠调用方记得填某个 HTTP 字段。
 *
 * <p><b>⚠️ 只有 {@link #LICENSED} / {@link #FAIR_USE_QUOTATION} 支持检索放行</b>：
 * {@link #PENDING} 表示尚未取得授权，{@link #RESTRICTED} 表示禁止使用。
 * 与 {@link CorpusSource#licensePermitsRetrieval()} 的口径完全一致——
 * 管理面计算属性与检索期 SQL 不得各说各话。
 */
public enum LicenseStatus {
    LICENSED,
    FAIR_USE_QUOTATION,
    PENDING,
    RESTRICTED;

    /** 是否允许进入可检索索引（RAG-D07 §2.3.1 G-4）。 */
    public boolean permitsRetrieval() {
        return this == LICENSED || this == FAIR_USE_QUOTATION;
    }

    /**
     * 宽松解析 HTTP 字符串值；未知值返回 {@code null}，由调用方决定如何拒绝。
     *
     * <p>刻意不做「猜默认值」：空串、拼错、旧版 PENDING 都必须显式失败，
     * 否则一次静默回落就会把「未声明版权」呈现成「已声明合理引用」。
     */
    @Nullable
    public static LicenseStatus parse(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        for (LicenseStatus status : values()) {
            if (status.name().equalsIgnoreCase(normalized)) {
                return status;
            }
        }
        return null;
    }

    /**
     * VERIFIED 决定专用的严格解析：只接受两种可放行版权状态。
     *
     * @throws IllegalArgumentException 未声明、未知取值，或声明了 PENDING/RESTRICTED
     */
    public static LicenseStatus requireForVerified(@Nullable String value) {
        LicenseStatus parsed = parse(value);
        if (parsed == null) {
            throw new IllegalArgumentException(
                    "判定 VERIFIED 必须显式声明版权状态：LICENSED 或 FAIR_USE_QUOTATION");
        }
        if (!parsed.permitsRetrieval()) {
            throw new IllegalArgumentException(
                    "版权状态 " + parsed.name() + " 不支持 VERIFIED 放行；"
                            + "只允许 LICENSED 或 FAIR_USE_QUOTATION");
        }
        return parsed;
    }
}
