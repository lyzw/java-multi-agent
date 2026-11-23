package cn.studykid.rag.eval.cases;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 标注纪律与可追溯性断言（RAG-D08 §3.4 D-A~D-G + D-F 孤儿检测）。
 *
 * <p>⚠️ 这些纪律是「评测集不是实现镜像」的唯一保障；CI 直接失败。
 * 本类把断言收敛为纯函数，返回违规清单（空 = 通过），便于测试逐条定位。
 */
public final class CaseDiscipline {

    private CaseDiscipline() {
    }

    /**
     * D08 §1.4 的 AT-RAG 追踪矩阵权威清单。
     *
     * <p>⚠️ 这是 D-F 孤儿检测的「AT 侧」基准：每个 AT 必须至少被一条 case 引用，
     * 否则说明「有验收项但没有可执行用例」。清单变更须与 D08 §1.4 同步。
     * 注意 AT-RAG-HONEST-001 由 D08 §8.2 文档交付（非 case），故单独排除。
     */
    public static final Set<String> KNOWN_AT = Set.of(
            "AT-RAG-STRAT-001", "AT-RAG-EXEC-001", "AT-RAG-ANNOT-001", "AT-RAG-SEP-001",
            "AT-RAG-EXT-001",
            "AT-RAG-TRAP-001", "AT-RAG-TRAP-002", "AT-RAG-TRAP-003", "AT-RAG-TRAP-004",
            "AT-RAG-TRAP-005", "AT-RAG-TRAP-006", "AT-RAG-TRAP-007",
            "AT-RAG-METRIC-001", "AT-RAG-THRESH-001", "AT-RAG-GATE-001",
            "AT-RAG-FALSIFY-001", "AT-RAG-FALSIFY-002", "AT-RAG-FALSIFY-003",
            "AT-RAG-FALSIFY-004", "AT-RAG-FALSIFY-005", "AT-RAG-FALSIFY-006",
            "AT-RAG-FALSIFY-007", "AT-RAG-FALSIFY-008", "AT-RAG-FALSIFY-009",
            "AT-RAG-HUMAN-001", "AT-RAG-REGR-001", "AT-RAG-SCALE-001");

    /** 项目实现方标识（D-A：标注者/复核者不得为这些）。 */
    private static final Set<String> IMPLEMENTER_MARKERS =
            Set.of("impl", "implementation", "developer", "dev-", "ai", "llm");

    private static final Set<String> PLACEHOLDERS = Set.of("tbd", "todo", "待补", "n/a", "na", "");

    /** 已知合法层。 */
    private static final Set<String> LAYERS = Set.of("L1", "L2", "L3", "L4");

    /**
     * 校验全部 case，返回违规清单（空 = 全部通过）。
     *
     * @param caseSets     已加载的 case 集
     * @param knownAt      追踪矩阵权威 AT 清单（传入便于测试注入/扩展）
     * @return 违规描述列表，每条可单独定位到 case
     */
    public static List<String> validate(List<CaseSet> caseSets, Set<String> knownAt) {
        List<String> violations = new ArrayList<>();
        if (caseSets == null || caseSets.isEmpty()) {
            violations.add("D-F: 没有任何 case 文件（评测集为空）");
            return violations;
        }
        Set<String> citedAt = new LinkedHashSet<>();
        Set<String> caseIds = new LinkedHashSet<>();
        for (CaseSet set : caseSets) {
            if (set.caseSetId() == null || set.caseSetId().isBlank()) {
                violations.add("caseSetId 缺失: " + set);
            }
            if (set.version() == null || set.version().isBlank()) {
                violations.add("D-F: caseSet 缺少 version: " + set.caseSetId());
            }
            if (set.cases() == null || set.cases().isEmpty()) {
                violations.add("D-F: caseSet 无任何 case: " + set.caseSetId());
                continue;
            }
            for (EvalCase c : set.cases()) {
                String loc = "[" + set.caseSetId() + "/" + safe(c.id()) + "]";
                checkCase(c, loc, caseIds, citedAt, violations);
            }
        }
        // D-F 孤儿检测（AT 侧）：每个已知 AT 至少被一条 case 引用。
        for (String at : knownAt) {
            if (!citedAt.contains(at)) {
                violations.add("D-F 孤儿: AT " + at + " 未被任何 case 的 acceptance 引用");
            }
        }
        // D-F 孤儿检测（case 侧）：每条 case 的 acceptance 必须是已知 AT。
        for (String at : citedAt) {
            if (!knownAt.contains(at)) {
                violations.add("D-F 孤儿: case 引用了未知 AT " + at);
            }
        }
        return violations;
    }

    private static void checkCase(EvalCase c, String loc, Set<String> caseIds,
                                  Set<String> citedAt, List<String> violations) {
        // 唯一 id
        if (isPlaceholder(c.id())) {
            violations.add("D-C: " + loc + " id 缺失");
        } else if (!caseIds.add(c.id())) {
            violations.add("D-C: " + loc + " id 重复");
        }
        // layer 合法
        if (c.layer() == null || !LAYERS.contains(c.layer().trim().toUpperCase(Locale.ROOT))) {
            violations.add("D-C: " + loc + " layer 非法: " + c.layer());
        }
        // D-D rationale：非空、≥20 字、无占位词
        String rationale = c.rationale() == null ? "" : c.rationale().trim();
        if (isPlaceholder(rationale)) {
            violations.add("D-D: " + loc + " rationale 为空");
        } else if (rationale.length() < 20) {
            violations.add("D-D: " + loc + " rationale 长度 <20: " + rationale.length());
        } else if (containsPlaceholderWord(rationale)) {
            violations.add("D-D: " + loc + " rationale 含 TODO/待补/TBD 占位词");
        }
        // D-E acceptance：非空且每项都进 citedAt
        if (c.acceptance() == null || c.acceptance().isEmpty()) {
            violations.add("D-E: " + loc + " acceptance 为空");
        } else {
            for (String at : c.acceptance()) {
                if (isPlaceholder(at)) {
                    violations.add("D-E: " + loc + " acceptance 含空项");
                } else {
                    citedAt.add(at.trim());
                }
            }
        }
        // D-A 标注者分离
        checkIndependent("annotatorId", c.annotatorId(), loc, violations);
        if (c.reviewedBy() != null && !c.reviewedBy().isBlank()) {
            checkIndependent("reviewedBy", c.reviewedBy(), loc, violations);
            if (c.reviewedBy().trim().equalsIgnoreCase(
                    c.annotatorId() == null ? "" : c.annotatorId().trim())) {
                violations.add("D-A: " + loc + " reviewedBy 与 annotatorId 相同（未独立复核）");
            }
        }
        // expected 必须有断言键（空期望 = 测不出问题）
        if (c.expected() == null || c.expected().isEmpty()) {
            violations.add("D-C: " + loc + " expected 为空");
        }
        // L2/L4 的 query 必填（case 字段规范）
        String layer = c.layer() == null ? "" : c.layer().trim().toUpperCase(Locale.ROOT);
        if (("L2".equals(layer) || "L4".equals(layer)) && isPlaceholder(c.query())) {
            violations.add("D-C: " + loc + " L2/L4 case 缺少 query");
        }
    }

    private static void checkIndependent(String field, String value, String loc,
                                         List<String> violations) {
        if (isPlaceholder(value)) {
            violations.add("D-A: " + loc + " " + field + " 缺失（标注者分离要求具名标识）");
            return;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        for (String marker : IMPLEMENTER_MARKERS) {
            if (lower.contains(marker)) {
                violations.add("D-A: " + loc + " " + field + " 含实现方标记 '" + marker
                        + "'（标注者不得为实现方）: " + value);
                return;
            }
        }
    }

    private static boolean isPlaceholder(String value) {
        if (value == null) {
            return true;
        }
        return PLACEHOLDERS.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean containsPlaceholderWord(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("todo") || lower.contains("tbd") || lower.contains("待补");
    }

    private static String safe(String s) {
        return s == null ? "<null>" : s;
    }
}
