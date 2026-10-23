package cn.studykid.multiagent.testsupport.acceptance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.ReasonCode;

/**
 * 设计文档 §9 的解析器（验收矩阵的唯一真相来源 · W-PERM-P6-T1/T5）。
 *
 * <p>⚠️ <b>为什么必须解析文档，而不是在代码里另抄一份</b>：
 * 若注册表自带一份「§9 有哪些场景、期望什么四字段」，
 * 它与设计文档之间就出现<b>两份真相</b> —— 改了文档忘了改注册表（或反之），
 * 冻结判定就建立在过期数据上，而<b>没有任何测试会红</b>。
 * 故本类直接从
 * {@code docs/Agent - Skill- Tool -MCP 统一配置与权限管控体系.md}
 * 的 §9.1 / §9.2 表格里读出场景与期望，
 * 由 {@code Section9ScenarioCoverageTest} 与注册表逐字段比对。
 *
 * <p>⚠️ <b>「权威文档」与「实现」的关系是被本类固定的</b>：
 * 文档改场景 → 覆盖测试立刻红 → 必须同步改实现或改注册表。
 * 这就是 P6 §6 要求的「禁止手工勾选代替」的技术形态。
 */
public final class Section9ScenarioSpec {

    /** 设计文档文件名（相对仓库根）。 */
    private static final String DOC_RELATIVE =
            "docs/Agent - Skill- Tool -MCP 统一配置与权限管控体系.md";

    /** 原因码 token（形如 {@code AUTHZ_STALE}）。 */
    private static final Pattern REASON_CODE_TOKEN = Pattern.compile("\\b([A-Z][A-Z_]{3,})\\b");

    /** §9.1 的行为场景。 */
    public record BehaviorScenario(String key,
                                   String id,
                                   String title,
                                   Set<Section9ScenarioRegistry.Expectation> expectations) {
    }

    /** §9.2 的不变量 / 故障注入场景。 */
    public record InvariantScenario(String key, String id, String kind, String assertion) {
    }

    private Section9ScenarioSpec() {
    }

    /**
     * @return 设计文档路径
     */
    public static Path designDoc() {
        Path root = TestSourceIndex.platformRoot().getParent();
        if (root == null) {
            throw new IllegalStateException("无法定位仓库根目录");
        }
        Path doc = root.resolve(DOC_RELATIVE);
        if (!Files.isRegularFile(doc)) {
            throw new IllegalStateException("未找到设计文档: " + doc
                    + "（验收矩阵的比对依赖权威文档；缺文档时必须失败而不是跳过）");
        }
        return doc;
    }

    /**
     * 解析 §9.1 行为测试表。
     *
     * @return 场景键 → 场景规格
     */
    public static Map<String, BehaviorScenario> behaviorScenarios() {
        List<String> lines = readLines();
        Map<String, BehaviorScenario> result = new LinkedHashMap<>();
        boolean inSection = false;
        for (String line : lines) {
            if (line.startsWith("### 9.1")) {
                inSection = true;
                continue;
            }
            if (line.startsWith("### 9.2")) {
                break;
            }
            if (!inSection || !line.startsWith("|")) {
                continue;
            }
            List<String> cells = cells(line);
            if (cells.size() < 6) {
                continue;
            }
            String id = cells.get(0).trim();
            String title = cells.get(1).trim();
            if (id.isEmpty() || id.equals("---") || id.startsWith("#")) {
                continue;
            }
            // 表头行：`| # | 场景 | authz | exec | status | reasonCode |`
            if (id.equals("#")) {
                continue;
            }
            String key = id.equals("—") ? title : id;
            result.put(key, new BehaviorScenario(key, id, title,
                    buildExpectations(cells.get(2), cells.get(3), cells.get(4), cells.get(5))));
        }
        return result;
    }

    /**
     * 由 §9.1 的四个单元格构造期望的四字段集合。
     *
     * <p>⚠️ <b>复合取值的配对规则</b>：文档里 S3f 的状态是
     * 「{@code SUCCEEDED/FAILED/UNKNOWN}」而原因码是
     * 「{@code SUCCESS / 稳定失败码 / EXEC_UNKNOWN}」——
     * 两者<b>按位置一一对应</b>，不是笛卡尔积。
     * 而 S5c 的状态只有一个（NOT_STARTED）、原因码有两个，
     * 那是<b>笛卡尔积</b>（同一状态下的两种可能码）。
     *
     * <p>若不区分这两种，期望集合会多出无意义的组合
     * （如「FAILED + SUCCESS」），覆盖测试就会要求一条根本不存在的用例。
     */
    private static Set<Section9ScenarioRegistry.Expectation> buildExpectations(
            String authzCell, String execCell, String statusCell, String reasonCell) {
        List<AuthorizationDecision.Authorization> authz =
                parseEnumList(authzCell, AuthorizationDecision.Authorization.class);
        List<AuthorizationDecision.Execution> exec =
                parseEnumList(execCell, AuthorizationDecision.Execution.class);
        List<AuthorizationDecision.Status> status =
                parseEnumList(statusCell, AuthorizationDecision.Status.class);
        List<List<ReasonCode>> reasonParts = parseReasonParts(reasonCell);

        Set<Section9ScenarioRegistry.Expectation> expectations = new LinkedHashSet<>();

        boolean positional = status.size() > 1
                && status.size() == reasonParts.size()
                && reasonParts.stream().allMatch(part -> part.size() == 1);
        if (positional) {
            for (int i = 0; i < status.size(); i++) {
                for (AuthorizationDecision.Authorization a : authz) {
                    for (AuthorizationDecision.Execution e : exec) {
                        expectations.add(new Section9ScenarioRegistry.Expectation(
                                a, e, status.get(i), reasonParts.get(i).get(0)));
                    }
                }
            }
            return expectations;
        }

        List<ReasonCode> reasons = new ArrayList<>();
        reasonParts.forEach(reasons::addAll);
        for (AuthorizationDecision.Authorization a : authz) {
            for (AuthorizationDecision.Execution e : exec) {
                for (AuthorizationDecision.Status s : status) {
                    for (ReasonCode r : reasons) {
                        expectations.add(new Section9ScenarioRegistry.Expectation(a, e, s, r));
                    }
                }
            }
        }
        return expectations;
    }

    /**
     * 解析「{@code A / B / C}」形态的枚举列表。
     */
    private static <E extends Enum<E>> List<E> parseEnumList(String raw, Class<E> type) {
        List<E> values = new ArrayList<>();
        for (String part : raw.split("/")) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            values.add(parseEnum(type, value));
        }
        if (values.isEmpty()) {
            throw new IllegalStateException("枚举单元格为空: " + raw);
        }
        return values;
    }

    /**
     * 解析原因码单元格：按 {@code /} 切成若干「组」，每组含 1 个或多个码。
     *
     * <p>⚠️ <b>散文占位在这里被显式映射</b>：
     * 文档写「稳定失败码」时，必须由人指明它对应平台上的哪一个码
     * （见 {@link Section9ScenarioRegistry#PROSE_PLACEHOLDER_CODES}）。
     */
    private static List<List<ReasonCode>> parseReasonParts(String raw) {
        List<List<ReasonCode>> parts = new ArrayList<>();
        for (String rawPart : raw.split("/")) {
            String part = rawPart.trim();
            if (part.isEmpty()) {
                continue;
            }
            ReasonCode prose = Section9ScenarioRegistry.PROSE_PLACEHOLDER_CODES.get(part);
            if (prose != null) {
                parts.add(List.of(prose));
                continue;
            }
            Set<ReasonCode> codes = new LinkedHashSet<>();
            Matcher matcher = REASON_CODE_TOKEN.matcher(part);
            while (matcher.find()) {
                try {
                    codes.add(ReasonCode.valueOf(matcher.group(1)));
                } catch (IllegalArgumentException ignored) {
                    // 非平台原因码的 token 忽略
                }
            }
            if (!codes.isEmpty()) {
                parts.add(new ArrayList<>(codes));
            }
        }
        return parts;
    }

    /**
     * 解析 §9.2 不变量与故障注入表。
     *
     * @return 场景键 → 场景规格
     */
    public static Map<String, InvariantScenario> invariantScenarios() {
        List<String> lines = readLines();
        Map<String, InvariantScenario> result = new LinkedHashMap<>();
        boolean inSection = false;
        for (String line : lines) {
            if (line.startsWith("### 9.2")) {
                inSection = true;
                continue;
            }
            if (line.startsWith("## 10.")) {
                break;
            }
            if (!inSection || !line.startsWith("|")) {
                continue;
            }
            List<String> cells = cells(line);
            if (cells.size() < 3) {
                continue;
            }
            String id = cells.get(0).trim();
            if (id.isEmpty() || id.equals("---") || id.equals("ID")) {
                continue;
            }
            result.put(id, new InvariantScenario(id, id, cells.get(1).trim(), cells.get(2).trim()));
        }
        return result;
    }

    /**
     * 全部 §9 场景键（§9.1 + §9.2）。
     *
     * @return 场景键集合
     */
    public static Set<String> allKeys() {
        Set<String> keys = new LinkedHashSet<>(behaviorScenarios().keySet());
        keys.addAll(invariantScenarios().keySet());
        return keys;
    }

    // ── 内部实现 ──────────────────────────────────────────────────

    private static List<String> readLines() {
        try {
            return Files.readAllLines(designDoc(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取设计文档失败: " + designDoc(), e);
        }
    }

    /**
     * 拆分表格行。
     *
     * @param line 表格行
     * @return 单元格（去掉首尾空项）
     */
    private static List<String> cells(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        String value = raw.trim();
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(value)) {
                return constant;
            }
        }
        throw new IllegalStateException("无法解析枚举 " + type.getSimpleName() + " 的取值: " + raw);
    }

    /**
     * 供诊断使用：文档里无法解析为平台原因码的 token。
     *
     * @param raw 单元格文本
     * @return 无法解析的 token
     */
    public static Set<String> unknownTokens(String raw) {
        Set<String> unknown = new LinkedHashSet<>();
        Matcher matcher = REASON_CODE_TOKEN.matcher(raw);
        while (matcher.find()) {
            String token = matcher.group(1);
            try {
                ReasonCode.valueOf(token);
            } catch (IllegalArgumentException e) {
                unknown.add(token);
            }
        }
        return unknown;
    }

    /**
     * 读取文档中某个章节的原始行（供诊断输出用）。
     *
     * @param sectionPrefix 章节标题前缀
     * @return 章节内的行
     */
    public static List<String> sectionLines(String sectionPrefix) {
        List<String> result = new ArrayList<>();
        boolean inSection = false;
        for (String line : readLines()) {
            if (line.startsWith(sectionPrefix)) {
                inSection = true;
                continue;
            }
            if (inSection && line.startsWith("### ") && !line.startsWith(sectionPrefix)) {
                break;
            }
            if (inSection) {
                result.add(line);
            }
        }
        return result;
    }

    /**
     * @param key 场景键
     * @return 该场景在文档中的原始表格行（诊断用）
     */
    public static Optional<String> rawRow(String key) {
        for (String line : readLines()) {
            if (!line.startsWith("|")) {
                continue;
            }
            List<String> cells = cells(line);
            if (cells.isEmpty()) {
                continue;
            }
            String id = cells.get(0);
            if (id.equals(key) || (cells.size() > 1 && cells.get(1).equals(key))) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }
}
