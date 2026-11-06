package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.application.cap.CapDefinitionProjector;
import cn.studykid.multiagent.application.cap.ProvisionalFingerprint;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;

/**
 * 双写一致性比对工具（P0-T7 · 06 §3.3 第 4 步）。
 *
 * <p><b>它做什么</b>：只读比对旧模型（{@code agent_version}）与新模型
 * （{@code cap_agent_def}）在<b>同一份内容</b>上的投影结果，输出差异清单。
 * 这是 06 §3.3 迁移流程第 4 步「新旧模型只读比对，确认语义一致」的实现。
 *
 * <p><b>它为什么必须「重算投影」而不是「直接比两列」</b>：
 * 两侧存的不是同一形态的数据（旧侧是自由 JSONB，新侧是结构化列），
 * 直接比列没有意义。唯一有意义的判定是：
 * <b>「由旧侧内容重算出的投影」与「新侧实际存储」是否一致</b>。
 * 这也顺带验证了投影规则本身是确定性的。
 *
 * <p>⚠️ <b>本工具只读</b>：不修复、不删除、不补写。
 * 修复动作必须走「重新双写」或「迁移脚本」，由人工确认后执行——
 * 比对工具自动改数据，会让「漂移到底从哪来」永远查不清。
 */
public class CapDualWriteComparator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SQL_LEGACY = """
            SELECT v.agent_id::text, v.version_number, v.definition::text,
                   a.name, a.description, v.published_at
            FROM agent_version v
            JOIN agent a ON a.agent_id = v.agent_id AND a.tenant_id = v.tenant_id
            WHERE v.tenant_id = ?::uuid
            ORDER BY v.agent_id, v.version_number
            """;

    private static final String SQL_LEGACY_ONE_AGENT = """
            SELECT v.agent_id::text, v.version_number, v.definition::text,
                   a.name, a.description, v.published_at
            FROM agent_version v
            JOIN agent a ON a.agent_id = v.agent_id AND a.tenant_id = v.tenant_id
            WHERE v.tenant_id = ?::uuid AND v.agent_id = ?::uuid
            ORDER BY v.agent_id, v.version_number
            """;

    private static final String SQL_CAP = """
            SELECT agent_id::text, version, name, description,
                   roles::text, enabled_skills::text, skill_bundles::text,
                   capability_tags::text, overrides::text, config_fingerprint
            FROM cap_agent_def
            WHERE tenant_id = ?::uuid
            ORDER BY agent_id, version
            """;

    private static final String SQL_CAP_ONE_AGENT = """
            SELECT agent_id::text, version, name, description,
                   roles::text, enabled_skills::text, skill_bundles::text,
                   capability_tags::text, overrides::text, config_fingerprint
            FROM cap_agent_def
            WHERE tenant_id = ?::uuid AND agent_id = ?::uuid
            ORDER BY agent_id, version
            """;

    private static final String SQL_LEGACY_SNAPSHOT_COUNT = """
            SELECT count(*) FROM execution_snapshot WHERE tenant_id = ?::uuid
            """;

    private static final String SQL_CAP_SNAPSHOT_COUNT = """
            SELECT count(*) FROM execution_capability_snapshot WHERE tenant_id = ?::uuid
            """;

    private final DataSource dataSource;

    public CapDualWriteComparator(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 执行比对（整租户范围）。
     *
     * @param tenantId 租户标识
     * @return 比对报告
     */
    public @NonNull CapDualWriteReport compare(@NonNull String tenantId) {
        return compare(tenantId, null);
    }

    /**
     * 执行比对（<b>限定到单个 Agent</b>）。
     *
     * <p>⚠️ <b>为什么需要限定范围</b>：整租户比对会把该租户<b>所有</b>历史
     * Agent 版本行都纳入判定。只要其中存在一个「未双写过的存量版本」，
     * 整租户报告就必然报漂移 —— 那是<b>存量欠账</b>，不是本次双写的缺陷。
     * 两者混在一起会让报告无法回答「我这次双写做对了吗」。
     *
     * <p>限定到单个 Agent 后，报告才可用于断言「本次双写对该 Agent 无漂移」。
     *
     * @param tenantId 租户标识
     * @param agentId  Agent 标识；{@code null} 表示不限
     * @return 比对报告
     */
    public @NonNull CapDualWriteReport compare(@NonNull String tenantId, String agentId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                conn -> {
                    List<CapDualWriteReport.Difference> missing = new ArrayList<>();
                    List<CapDualWriteReport.Difference> extra = new ArrayList<>();
                    List<CapDualWriteReport.Difference> mismatched = new ArrayList<>();
                    List<CapDualWriteReport.Difference> rejected = new ArrayList<>();

                    Map<String, CapRow> capRows = loadCapRows(conn, tenantId, agentId);
                    Set<String> legacyKeys = new LinkedHashSet<>();

                    int legacyCount = 0;
                    int draftSkipped = 0;
                    int matched = 0;

                    try (PreparedStatement ps = conn.prepareStatement(
                            agentId == null ? SQL_LEGACY : SQL_LEGACY_ONE_AGENT)) {
                        ps.setString(1, tenantId);
                        if (agentId != null) {
                            ps.setString(2, agentId);
                        }
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                String rowAgentId = rs.getString("agent_id");
                                int version = rs.getInt("version_number");
                                String definition = rs.getString("definition");
                                String agentName = rs.getString("name");
                                String agentDescription = rs.getString("description");
                                boolean published = rs.getTimestamp("published_at") != null;

                                // ⚠️ 只比对**已发布**版本：双写只在发布时发生，
                                //    草稿版本本就没有新模型对应行。
                                if (!published) {
                                    draftSkipped++;
                                    continue;
                                }
                                legacyCount++;
                                String key = key(rowAgentId, version);
                                legacyKeys.add(key);

                                CapDefinitionProjector.Projection projection =
                                        CapDefinitionProjector.project(
                                                definition, agentName, agentDescription);
                                if (projection.isRejected()) {
                                    rejected.add(new CapDualWriteReport.Difference(
                                            rowAgentId, version, "-", null, null,
                                            projection.rejectionReason()));
                                    continue;
                                }
                                CapRow actual = capRows.get(key);
                                if (actual == null) {
                                    missing.add(new CapDualWriteReport.Difference(
                                            rowAgentId, version, "-", null, null,
                                            "旧模型已发布但新模型无对应版本行"));
                                    continue;
                                }
                                List<String> fieldDiffs = diff(projection.definition().orElseThrow(), actual);
                                if (fieldDiffs.isEmpty()) {
                                    matched++;
                                } else {
                                    for (String diff : fieldDiffs) {
                                        mismatched.add(new CapDualWriteReport.Difference(
                                                rowAgentId, version, diff, null, null, "字段内容不一致"));
                                    }
                                }
                            }
                        }
                    }

                    for (Map.Entry<String, CapRow> entry : capRows.entrySet()) {
                        if (legacyKeys.contains(entry.getKey())) {
                            continue;
                        }
                        CapRow row = entry.getValue();
                        extra.add(new CapDualWriteReport.Difference(
                                row.agentId(), row.version(), "-", null, null,
                                "新模型有版本行但旧模型无对应已发布版本（孤儿行）"));
                    }

                    int legacySnapshots = count(conn, SQL_LEGACY_SNAPSHOT_COUNT, tenantId);
                    int capSnapshots = count(conn, SQL_CAP_SNAPSHOT_COUNT, tenantId);

                    return new CapDualWriteReport(
                            legacyCount, capRows.size(), draftSkipped, matched,
                            missing, extra, mismatched, rejected,
                            legacySnapshots, capSnapshots);
                });
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private record CapRow(String agentId, int version, String name, String description,
                          String roles, String enabledSkills, String skillBundles,
                          String capabilityTags, String overrides, String configFingerprint) {
    }

    private static Map<String, CapRow> loadCapRows(Connection conn, String tenantId,
                                                   String agentId) throws SQLException {
        Map<String, CapRow> out = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                agentId == null ? SQL_CAP : SQL_CAP_ONE_AGENT)) {
            ps.setString(1, tenantId);
            if (agentId != null) {
                ps.setString(2, agentId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CapRow row = new CapRow(
                            rs.getString("agent_id"), rs.getInt("version"),
                            rs.getString("name"), rs.getString("description"),
                            rs.getString("roles"), rs.getString("enabled_skills"),
                            rs.getString("skill_bundles"), rs.getString("capability_tags"),
                            rs.getString("overrides"), rs.getString("config_fingerprint"));
                    out.put(key(row.agentId(), row.version()), row);
                }
            }
        }
        return out;
    }

    /**
     * 逐字段比对投影结果与存储值。
     *
     * @return 不一致的字段名列表；空列表表示一致
     */
    private static List<String> diff(CapDefinitionProjector.CapAgentDefView expected,
                                      CapRow actual) {
        List<String> diffs = new ArrayList<>();
        compare(diffs, "name", expected.name(), actual.name());
        compare(diffs, "description", expected.description(), actual.description());
        compare(diffs, "roles", canonical(expected.roles()), canonicalFromJson(actual.roles()));
        compare(diffs, "enabled_skills", canonical(expected.enabledSkills()),
                canonicalFromJson(actual.enabledSkills()));
        compare(diffs, "skill_bundles", canonical(expected.skillBundles()),
                canonicalFromJson(actual.skillBundles()));
        compare(diffs, "capability_tags", canonical(expected.capabilityTags()),
                canonicalFromJson(actual.capabilityTags()));
        compareJson(diffs, "overrides", expected.overrides(), actual.overrides());
        compare(diffs, "config_fingerprint", expected.configFingerprint(), actual.configFingerprint());
        return diffs;
    }

    private static void compare(List<String> diffs, String field,
                                @Nullable String expected, @Nullable String actual) {
        if (!Objects.equals(expected, actual)) {
            diffs.add(field);
        }
    }

    /**
     * 比对两个 JSON 文本是否<b>语义等价</b>。
     *
     * <p>⚠️ <b>不能直接比字符串</b>：PostgreSQL 的 {@code jsonb::text} 输出
     * 与应用侧 Jackson 的序列化结果在<b>空白与键序</b>上都可能不同
     * （jsonb 会重排键、并在冒号后加空格），内容却完全一致。
     * 直接比字符串会把「完全一致」判成「不一致」，让比对报告充满假差异。
     *
     * <p>这里改用 {@link tools.jackson.databind.JsonNode} 的相等判定 ——
     * 它对对象字段<b>按名比较、与顺序无关</b>，正是 JSON 语义等价所需。
     */
    private static void compareJson(List<String> diffs, String field,
                                    @Nullable String expected, @Nullable String actual) {
        if (jsonEquals(expected, actual)) {
            return;
        }
        diffs.add(field);
    }

    private static boolean jsonEquals(@Nullable String left, @Nullable String right) {
        String l = (left == null || left.isBlank()) ? "{}" : left;
        String r = (right == null || right.isBlank()) ? "{}" : right;
        if (l.equals(r)) {
            return true;
        }
        try {
            return MAPPER.readTree(l).equals(MAPPER.readTree(r));
        } catch (JacksonException e) {
            // ⚠️ 解析失败**不能**判成相等：那会把「数据损坏」伪装成「内容一致」。
            return false;
        }
    }

    /**
     * 把投影侧的字符串列表规范化为与存储侧一致的表示。
     */
    private static String canonical(@NonNull List<String> values) {
        return ProvisionalFingerprint.canonicalList(values);
    }

    /**
     * 把存储侧的 JSON 数组文本规范化为与投影侧一致的表示。
     *
     * <p>⚠️ <b>必须走同一套规范化</b>：PostgreSQL 的 {@code jsonb::text} 输出形如
     * {@code ["A", "B"]}（带空格），而 Java 的 {@code List.toString()} 形如
     * {@code [A, B]}。直接字符串比较会把<b>完全一致</b>的两侧判成不一致，
     * 产出大量假差异，让真实漂移淹没在噪声里。
     */
    private static String canonicalFromJson(@Nullable String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            return "[]";
        }
        try {
            List<String> parsed = MAPPER.readValue(jsonText, new TypeReference<List<String>>() {
            });
            return canonical(parsed);
        } catch (JacksonException e) {
            // ⚠️ 解析失败**不能**当成空列表：那会把「数据损坏」伪装成「内容一致」。
            //    返回一个不可能等于规范值的标记，让比对把它报成差异。
            return "!!unparsable:" + jsonText;
        }
    }

    private static int count(Connection conn, String sql, String tenantId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static String key(String agentId, int version) {
        return agentId + "#" + version;
    }
}
