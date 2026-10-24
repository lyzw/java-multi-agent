package cn.studykid.multiagent.core.reference;

import java.util.List;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 平台辅导业务词表（DEC-6 采集白名单的「学科 / 年级」部分）。
 *
 * <p><b>为什么上提到 core</b>：这份词表有<b>两个</b>消费方，且两者必须永远一致——
 * <ul>
 *   <li>{@code ReferenceController}：前端下拉选项的<b>单一真相源</b>；</li>
 *   <li>{@code JdbcRunListQueryRepository}：Run 列表回填学科/年级时的
 *       <b>fail-closed 校验依据</b>——客户端可控的 {@code input_payload.subject}
 *       只有命中本词表才允许回显。</li>
 * </ul>
 * 两份拷贝必然漂移：前端下拉里出现「地理」、后端校验却不认，
 * 后果是用户<b>选得到、提交得到、列表里却查不出来</b>——
 * 这种「提交成功但结果消失」的故障比直接报错难查得多。
 *
 * <p><b>与 DB 约束的关系</b>：本词表必须与
 * {@code V2__student_profile_and_weakness.sql} 里
 * {@code student_profile_subject_chk} / {@code student_profile_grade_chk}
 * 的取值集合<b>完全一致</b>（由 agent-infrastructure 的漂移守卫测试守住）。
 * DB 约束是画像表的写入边界，本词表是<b>读出边界</b>——两者要合起来才是一整条闭环。
 *
 * <p>⚠️ <b>刻意不含「历史 / 地理 / 道法」</b>：平台当前辅导业务聚焦初中国家课程
 * 理科与语英，词表即业务边界。新增学科须同步两处：本词表、V2 的 CHECK 约束
 * （前端下拉的取值来源已由本词表统一）。
 */
public final class TutoringVocabulary {

    /**
     * 平台辅导业务学科词表。
     *
     * <p>⚠️ 顺序与 {@code V2} 的 CHECK 约束无关（约束是集合语义），
     * 但与前端下拉的展示顺序一致，便于人工比对。
     */
    public static final List<String> SUBJECTS = List.of(
            "数学", "物理", "化学", "生物", "语文", "英语");

    /** 平台辅导业务年级词表。 */
    public static final List<String> GRADES = List.of(
            "初一", "初二", "初三");

    private TutoringVocabulary() {
        // 工具类：不允许实例化
    }

    /**
     * 归一学科取值。
     *
     * <p><b>fail-closed</b>：不在词表内一律返回 {@code null}，
     * <b>绝不原样回显</b>客户端传进来的文本。
     *
     * <p>⚠️ 为什么必须 fail-closed：{@code input_payload} 是<b>客户端可控</b>的
     * JSON——它里面写什么完全由请求方决定。若原样回显，
     * 一个精心构造的 {@code subject="<script>…"} 或
     * {@code subject="李明的数学"} 就会借列表接口流到前端。
     * 「不认识就当没有」是这里唯一安全的默认。
     *
     * @param raw 原始取值；可为 {@code null}
     * @return 词表内的规范取值；不在词表内为 {@code null}
     */
    public static @Nullable String subject(@Nullable String raw) {
        return normalize(raw, SUBJECTS);
    }

    /**
     * 归一年级取值。
     *
     * @param raw 原始取值；可为 {@code null}
     * @return 词表内的规范取值；不在词表内为 {@code null}
     */
    public static @Nullable String grade(@Nullable String raw) {
        return normalize(raw, GRADES);
    }

    /** 学科是否在词表内（筛选参数的合法性判定）。 */
    public static boolean isKnownSubject(@Nullable String raw) {
        return subject(raw) != null;
    }

    /**
     * 归一：去首尾空白后精确匹配词表。
     *
     * <p>⚠️ <b>只去空白，不做模糊匹配</b>：模糊匹配（包含、大小写折叠、
     * 拼音近似）会让「数学分析」「物理化」这类文本被判成「数学」——
     * 那就等于把不可信文本洗成了可信标签，比直接拒绝更危险。
     */
    private static @Nullable String normalize(@Nullable String raw, List<String> vocabulary) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return vocabulary.contains(trimmed) ? trimmed : null;
    }

    /** 学科词表的不可变视图（供需要 {@code Set} 语义的调用方使用）。 */
    public static @NonNull Set<String> subjectSet() {
        return Set.copyOf(SUBJECTS);
    }
}
