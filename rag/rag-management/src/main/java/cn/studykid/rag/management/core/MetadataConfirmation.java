package cn.studykid.rag.management.core;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 元数据确认命令（交互文档 §4.3「产出确认」）。
 *
 * <p><b>为什么确认要携带一份元数据快照</b>：确认这个动作的意义是
 * 「我以我的名义担保**这一组**元数据是对的」。只记一个布尔值，
 * 事后无法回答「他当时确认的是哪本书名」——而书名恰恰是最常被改的字段。
 * 故快照与确认同事务写入，作为审计留痕的一部分。
 *
 * <p><b>⚠️ 与登记命令的区别</b>：
 * {@link SourceRegistration} 是「我要新录一条」，本命令是「我核对了系统给的值」。
 * 前者创建事实，后者为事实背书。二者不可合并：
 * 否则「确认」会顺带修改数据，掩盖掉「人到底改了什么」。
 *
 * <p><b>⚠️ 可空的字段刻意保留 null 语义</b>：ISBN 对论文类语料本就不适用。
 * 把 null 当成「缺失必填」会逼用户填一个假值，比留空更糟。
 *
 * @param title     确认时的书名
 * @param publisher 确认时的出版社
 * @param isbn      确认时的 ISBN（可空：论文/无 ISBN 语料）
 * @param subject   学科（来自推断或人工选择，可空）
 * @param grade     年级（可空）
 * @param term      册次：上册 / 下册 / 全一册（可空）
 * @param note      确认备注（如「封面磨损，ISBN 由版权页核对」），可空
 */
public record MetadataConfirmation(
        @NonNull String title,
        @NonNull String publisher,
        @Nullable String isbn,
        @Nullable String subject,
        @Nullable String grade,
        @Nullable String term,
        @Nullable String note
) {

    /**
     * 册次取值白名单（与 {@code rag_edition.term} 的 CHECK 约束同口径）。
     *
     * ⚠️ 必须与 DDL 保持一致：前端下拉 / 后端校验 / DB 约束三层同源，
     * 任何一层放宽都会在落库时报错（且错误信息对用户不可读）。
     */
    private static final java.util.Set<String> ALLOWED_TERMS =
            java.util.Set.of("上册", "下册", "全一册");

    public MetadataConfirmation {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("确认时书名不能为空");
        }
        if (publisher == null || publisher.isBlank()) {
            throw new IllegalArgumentException("确认时出版社不能为空");
        }
        // ⚠️ **先归一再去校验**（顺序不能反）：
        //    用户从下拉带过来的值常带首尾空白（" 上册 "）。
        //    若先校验后归一，合法值会被判非法——错误信息还会把原样空白显示出来，
        //    用户看到「上册 不在白名单里」却找不到问题（本类的单测踩到过）。
        isbn = blankToNull(isbn);
        subject = blankToNull(subject);
        grade = blankToNull(grade);
        term = blankToNull(term);
        note = blankToNull(note);

        // 册次非法值提前拦下：DB CHECK 会报一个用户看不懂的约束名
        if (term != null && !ALLOWED_TERMS.contains(term)) {
            throw new IllegalArgumentException(
                    "册次只能是 上册 / 下册 / 全一册，收到：" + term);
        }
    }

    /** 空白串归一为 null（语义：「没填」只有一种表示）。 */
    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
