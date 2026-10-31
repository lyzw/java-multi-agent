package cn.studykid.multiagent.core.compliance;

import java.util.regex.Pattern;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 数据分级（DEC-10 GATE-1 / P0-D00 §4 白名单）。
 *
 * <p><b>为什么必须显式分级</b>：若不要求调用方声明，闸门就无从判断。
 * 本类型的设计遵循 <b>fail-closed</b>：未声明者按最严格处理。
 */
public enum DataClassification {

    /**
     * 合成/模拟数据。P1 阶段的唯一合法输入（DEC-10 GATE-1）。
     *
     * <p>可用示例：「解方程 x²-5x+6=0」——不含任何真实学生信息。
     */
    SYNTHETIC(false),

    /**
     * 含真实学生数据（即便已脱敏为随机标识，仍视为真实）。
     *
     * <p>⚠️ 判据：数据源自真实学生即算真实，与是否可识别无关。
     * 零保留条款针对的是「供应商不存储」，因此脱敏后仍受闸门约束。
     */
    REAL_STUDENT(true);

    private final boolean containsRealStudentData;

    DataClassification(boolean containsRealStudentData) {
        this.containsRealStudentData = containsRealStudentData;
    }

    /** @return 是否包含真实学生数据。 */
    public boolean containsRealStudentData() {
        return containsRealStudentData;
    }

    /**
     * 从请求内容推导数据分级（P0-3 修复：杜绝硬编码 {@code SYNTHETIC} 导致
     * 零保留闸门的真实数据分支永不触发、供应商确认检查形同虚设）。
     *
     * <p><b>判定优先级（fail-closed）</b>：
     * <ol>
     *   <li>若内容命中任一可识别学生信息模式 → {@link #REAL_STUDENT}
     *       （<b>内容优先</b>，调用方不得降级绕过）；</li>
     *   <li>否则若调用方显式声明 → 采用其声明；</li>
     *   <li>否则 → {@link #SYNTHETIC}（兼容 P2 合成数据工作模式）。</li>
     * </ol>
     *
     * <p>⚠️ <b>内容优先</b>：即便调用方声明为 {@code SYNTHETIC}，只要内容含可识别信息，
     * 仍按真实数据处置——否则「声明 SYNTHETIC + 夹带身份证号」即可绕过闸门
     * （{@code ZeroRetentionGate} 对 SYNTHETIC 提前 return，不再检查可识别内容）。
     *
     * @param text     将进入 Prompt 的内容（可为 null）
     * @param declared 调用方显式声明（可为 null）
     * @return 推导后的分级
     */
    public static @NonNull DataClassification fromContent(
            @Nullable String text, @Nullable DataClassification declared) {
        if (text != null && containsIdentifyingContent(text)) {
            return REAL_STUDENT;
        }
        if (declared != null) {
            return declared;
        }
        return SYNTHETIC;
    }

    /** 内容是否含可识别学生信息（任一模式命中即 true）。 */
    private static boolean containsIdentifyingContent(@NonNull String text) {
        for (IdentifyingPattern pattern : IdentifyingPattern.values()) {
            if (pattern.detect(text)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 可识别学生信息的常见模式（P0-D00 §4 黑名单）。
     *
     * <p>用于在 Prompt 中检测明显的可识别信息。
     * <b>这是纵深防御的一层</b>，不能替代数据分级——
     * 正则无法穷尽所有编码/隐写形式。
     */
    public enum IdentifyingPattern {

        /**
         * 中国大陆身份证号（18 位，含校验位）。
         *
         * <p><b>⚠️ 必须用词边界而非 {@code ^$} 锚定</b>：实际 Prompt 是
         * 「学生 110101… 的错题」这样的句子，整串匹配会完全失效——
         * 这是一个被契约测试证伪出来的真实缺陷。
         */
        ID_CARD("身份证号", "(?<![0-9A-Za-z])[1-9]\\d{5}(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx](?![0-9A-Za-z])"),

        /**
         * 手机号（中国大陆 11 位）。
         *
         * <p>要求前后不接数字，避免匹配「订单号后 11 位恰好是手机号」之类误伤。
         */
        MOBILE("手机号", "(?<![0-9])1[3-9]\\d{9}(?![0-9])"),

        /**
         * 学号（8～12 位数字）。
         *
         * <p>⚠️ 下限取 8 位：短于此的数字串在数学题中极常见
         * （如「计算 5 × 6 = 30」），收紧以避免把正常题目判成学号。
         */
        STUDENT_ID("学号", "(?<![0-9])[0-9]{8,12}(?![0-9])"),

        /** 电子邮箱。 */
        EMAIL("邮箱", "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(?![A-Za-z0-9.-])");

        private final String label;
        private final String regex;
        /** 编译结果缓存（构造期一次性编译，避免每次检测重复编译）。 */
        private final Pattern compiled;

        IdentifyingPattern(String label, String regex) {
            this.label = label;
            this.regex = regex;
            this.compiled = Pattern.compile(regex);
        }

        public @NonNull String label() {
            return label;
        }

        /**
         * 编译后的正则（唯一判据来源）。
         *
         * <p>⚠️ <b>检测与脱敏必须共用同一份正则</b>：若输出侧的掩码各自再写一遍，
         * 两边迟早漂移成「检测到 A 模式、却按 B 模式去抹」——
         * 而这份黑名单本身就是为使 §4 的判断与处置<b>逐字一致</b>而存在的。
         */
        public @NonNull Pattern pattern() {
            return compiled;
        }

        /** 在文本中检测本模式。 */
        public boolean detect(@NonNull String text) {
            return compiled.matcher(text).find();
        }
    }
}