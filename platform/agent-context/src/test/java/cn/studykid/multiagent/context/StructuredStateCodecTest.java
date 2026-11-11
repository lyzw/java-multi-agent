package cn.studykid.multiagent.context;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.studykid.multiagent.context.StructuredState.Artifact;
import cn.studykid.multiagent.context.StructuredState.Fact;
import cn.studykid.multiagent.context.StructuredState.ToolCall;

/**
 * M5-c 结构化状态的<b>确定性 JSON 编解码 + digest</b>守卫（《方案》§5.6 / §4.4）。
 *
 * <p>⚠️ 状态是 SSE carry-over 的不可信载荷与 DB checkpoint 的 {@code jsonb} 真值，
 * 任何「宽松解析」都会把脏状态当真值续写，属于「不报错、数字悄悄错」失真。
 * 每条 reject 断言都必须 fail-closed。
 */
class StructuredStateCodecTest {

    /** 固定 schema 的最小状态（用于脏 JSON 注入）。 */
    private static String canonical() {
        return StructuredStateCodec.toJson(new S().goal("g").cursor(0, 3).build());
    }

    private static void assertRejected(String json) {
        assertThatThrownBy(() -> StructuredStateCodec.fromJson(json))
                .as("⛔ 脏 checkpoint 被宽松接受：%s", json)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fail-closed");
    }

    @Test
    @DisplayName("✅ RV-36：确定性 JSON，字段顺序固定 + facts 按 key 排序 + 同状态同字节")
    void toJsonIsCanonicalAndFactsSorted() {
        StructuredState a = new S().facts(new Fact("z", "最后"), new Fact("a", "最先"),
                new Fact("m", "中间")).build();
        StructuredState b = new S().facts(new Fact("a", "最先"), new Fact("m", "中间"),
                new Fact("z", "最后")).build();

        String json = StructuredStateCodec.toJson(a);
        assertThat(json).as("⛔ facts 输入顺序影响了规范 JSON：digest 会漂移").isEqualTo(
                StructuredStateCodec.toJson(b));
        assertThat(StructuredStateCodec.digest(a)).isEqualTo(StructuredStateCodec.digest(b));

        List<String> fields = List.of("goal", "constraints", "userCorrections", "decisions",
                "rejectedApproaches", "openItems", "artifacts", "facts", "toolCalls",
                "fromSeq", "upToSeq", "policyVersion");
        int previous = -1;
        for (String field : fields) {
            int at = json.indexOf("\"" + field + "\":");
            assertThat(at).as("⛔ 字段 %s 缺失或顺序漂移（prompt cache / digest 前提）", field)
                    .isGreaterThan(previous);
            previous = at;
        }
        assertThat(json.indexOf("\"key\":\"a\""))
                .as("⛔ facts 未按 key 排序").isLessThan(json.indexOf("\"key\":\"m\""));
        assertThat(json.indexOf("\"key\":\"m\""))
                .isLessThan(json.indexOf("\"key\":\"z\""));
    }

    @Test
    @DisplayName("✅ digest 是规范 JSON 的 SHA-256：任一字段变化必变，空状态有 golden 指纹")
    void digestIsStableAndSchemaSensitive() {
        S base = new S().goal("目标").constraints("约束").corrections("纠正").decisions("决定")
                .rejected("否决").open("待办").artifacts(new Artifact("r", "kind", "一行"))
                .facts(new Fact("k", "v")).toolCalls(new ToolCall("grade", "t", "一行"))
                .cursor(1, 5).policy("pv1");
        String golden = StructuredStateCodec.digest(base.build());
        assertThat(StructuredStateCodec.digest(base.build())).isEqualTo(golden);

        assertThat(StructuredStateCodec.digest(base.copy().goal("目标2").build())).isNotEqualTo(golden);
        assertThat(StructuredStateCodec.digest(base.copy().constraints("约束2").build())).isNotEqualTo(golden);
        assertThat(StructuredStateCodec.digest(base.copy().corrections("纠正2").build())).isNotEqualTo(golden);
        assertThat(StructuredStateCodec.digest(base.copy().decisions("决定2").build())).isNotEqualTo(golden);
        assertThat(StructuredStateCodec.digest(base.copy().rejected("否决2").build())).isNotEqualTo(golden);
        assertThat(StructuredStateCodec.digest(base.copy().open("待办2").build())).isNotEqualTo(golden);
        assertThat(StructuredStateCodec.digest(base.copy()
                .artifacts(new Artifact("r", "kind", "另一行")).build())).isNotEqualTo(golden);
        assertThat(StructuredStateCodec.digest(base.copy()
                .facts(new Fact("k", "v2")).build())).isNotEqualTo(golden);
        assertThat(StructuredStateCodec.digest(base.copy()
                .toolCalls(new ToolCall("grade", "t", "另一行")).build())).isNotEqualTo(golden);
        assertThat(StructuredStateCodec.digest(base.copy().cursor(2, 5).build())).isNotEqualTo(golden);
        assertThat(StructuredStateCodec.digest(base.copy().cursor(1, 6).build())).isNotEqualTo(golden);
        assertThat(StructuredStateCodec.digest(base.copy().policy("pv2").build())).isNotEqualTo(golden);

        // schema 或字段顺序无意变更 ⇒ 必须显式改这个 golden，不能悄悄漂移。
        assertThat(StructuredStateCodec.digest(StructuredState.empty())).isEqualTo(
                "sha256:03938b308ad8e32216fc2c829bfce9b70e1617d31b955c5ce036df840170a529");
    }

    @Test
    @DisplayName("✅ round-trip：CJK / 换行 / 引号 / 反斜杠 / 控制字符逐字保真")
    void roundTripPreservesCjkEscapesAndExactSpans() {
        String tricky = "中文\n下一行\t制表\"引号\"\\反斜杠" + (char) 0x1F + "结束";
        StructuredState state = new S().goal(tricky).constraints(tricky).corrections(tricky)
                .artifacts(new Artifact("ref-1", "片段", tricky))
                .facts(new Fact("精确值", tricky))
                .toolCalls(new ToolCall("grade", "chunk-1", tricky))
                .cursor(0, 42).policy("pv-中文").build();

        String json = StructuredStateCodec.toJson(state);
        assertThat(json).contains("\\n").contains("\\t").contains("\\\"").contains("\\\\");
        String unicodeEscape = "\\" + "u001f";
        assertThat(json).contains(unicodeEscape).contains("中文");
        assertThat(json).as("⛔ 裸控制字符进了 JSON").doesNotContain(String.valueOf((char) 0x1F));
        assertThat(StructuredStateCodec.fromJson(json)).isEqualTo(state);
    }

    @Test
    @DisplayName("✅ RV-29：缺字段 / 多字段 / 重复字段一律拒（重复键不得静默覆盖）")
    void fromJsonRejectsMissingExtraAndDuplicateFields() {
        String json = canonical();
        assertRejected(json.replace("\"goal\":\"g\",", ""));
        assertRejected(json.substring(0, json.length() - 1) + ",\"extra\":1}");
        assertRejected(json.replace("\"goal\":\"g\"", "\"goal\":\"g\",\"goal\":\"h\""));

        String withFact = StructuredStateCodec.toJson(new S().facts(new Fact("k", "v")).build());
        assertRejected(withFact.replace("\"key\":\"k\"", "\"key\":\"k\",\"key\":\"k2\""));
    }

    @Test
    @DisplayName("✅ 类型不符 / null 字面量占位一律拒，绝不把脏状态续写 checkpoint")
    void fromJsonRejectsTypeMismatchesIncludingNullLiteral() {
        String json = canonical();
        assertRejected(json.replace("\"goal\":\"g\"", "\"goal\":1"));
        assertRejected(json.replace("\"goal\":\"g\"", "\"goal\":null"));
        assertRejected(json.replace("\"constraints\":[]", "\"constraints\":[\"ok\",1]"));
        assertRejected(json.replace("\"constraints\":[]", "\"constraints\":[null]"));
        assertRejected(json.replace("\"artifacts\":[]", "\"artifacts\":{}"));
        assertRejected(json.replace("\"fromSeq\":0", "\"fromSeq\":\"0\""));
        assertRejected(json.replace("\"upToSeq\":3", "\"upToSeq\":true"));
    }

    @Test
    @DisplayName("✅ RV-35：只接受 toJson 会写出的规范整数，前导零 / -0 / 浮点 / 指数 / 溢出全拒")
    void fromJsonRejectsNonCanonicalIntegers() {
        String json = canonical();
        for (String raw : List.of("01", "-0", "1.0", "1e2", "1x", "", "-",
                "9223372036854775808", "null", "true")) {
            assertRejected(json.replace("\"fromSeq\":0", "\"fromSeq\":" + raw));
        }
    }

    @Test
    @DisplayName("✅ RV-31：结构性非法的 checkpoint 游标必拒（否则后续增量被静默全跳过）")
    void fromJsonRejectsCorruptCursor() {
        String json = canonical();
        String pair = "\"fromSeq\":0,\"upToSeq\":3";
        assertRejected(json.replace(pair, "\"fromSeq\":-1,\"upToSeq\":3"));
        assertRejected(json.replace(pair, "\"fromSeq\":0,\"upToSeq\":-2"));
        assertRejected(json.replace(pair, "\"fromSeq\":10,\"upToSeq\":5"));

        // 空状态的合法游标组合（fromSeq=0 / upToSeq=-1）必须仍能往返。
        StructuredState odd = StructuredStateCodec.fromJson(
                json.replace(pair, "\"fromSeq\":0,\"upToSeq\":-1"));
        assertThat(odd.upToSeq()).isEqualTo(-1);
        assertThat(odd.fromSeq()).isZero();
    }

    @Test
    @DisplayName("✅ 非法转义 / 裸控制字符 / 尾随垃圾 / 未闭合一律拒")
    void fromJsonRejectsBrokenJsonSyntax() {
        String json = canonical();
        assertRejected("");
        assertRejected("{");
        assertRejected("{\"goal\":\"unterminated");
        assertRejected(json + "x");
        assertRejected(json.replace("\"goal\":\"g\"", "\"goal\":\"\\q\""));
        assertRejected(json.replace("\"goal\":\"g\"", "\"goal\":\"g" + (char) 0x01 + "\""));
        assertRejected(json.replace("\"goal\":\"g\"", "\"goal\":\"\\u00\""));
    }

    @Test
    @DisplayName("✅ 空状态 JSON / golden digest / 往返三口径一致")
    void emptyStateRoundTrips() {
        String json = StructuredStateCodec.toJson(StructuredState.empty());
        assertThat(json).isEqualTo("{\"goal\":\"\",\"constraints\":[],\"userCorrections\":[],"
                + "\"decisions\":[],\"rejectedApproaches\":[],\"openItems\":[],\"artifacts\":[],"
                + "\"facts\":[],\"toolCalls\":[],\"fromSeq\":0,\"upToSeq\":-1,\"policyVersion\":\"\"}");
        assertThat(StructuredStateCodec.fromJson(json)).isEqualTo(StructuredState.empty());
    }

    /** 12 字段显式构造器 + copy：加字段时必须在 copy 里逐字段带过（铁律 #10）。 */
    private static final class S {
        private String goal = "";
        private List<String> constraints = List.of();
        private List<String> userCorrections = List.of();
        private List<String> decisions = List.of();
        private List<String> rejectedApproaches = List.of();
        private List<String> openItems = List.of();
        private List<Artifact> artifacts = List.of();
        private List<Fact> facts = List.of();
        private List<ToolCall> toolCalls = List.of();
        private long fromSeq;
        private long upToSeq = -1;
        private String policyVersion = "pv1";

        S goal(String v) { goal = v; return this; }
        S constraints(String... v) { constraints = List.of(v); return this; }
        S corrections(String... v) { userCorrections = List.of(v); return this; }
        S decisions(String... v) { decisions = List.of(v); return this; }
        S rejected(String... v) { rejectedApproaches = List.of(v); return this; }
        S open(String... v) { openItems = List.of(v); return this; }
        S artifacts(Artifact... v) { artifacts = List.of(v); return this; }
        S facts(Fact... v) { facts = List.of(v); return this; }
        S toolCalls(ToolCall... v) { toolCalls = List.of(v); return this; }
        S cursor(long from, long upTo) { fromSeq = from; upToSeq = upTo; return this; }
        S policy(String v) { policyVersion = v; return this; }

        S copy() {
            S n = new S();
            n.goal = goal;
            n.constraints = constraints;
            n.userCorrections = userCorrections;
            n.decisions = decisions;
            n.rejectedApproaches = rejectedApproaches;
            n.openItems = openItems;
            n.artifacts = artifacts;
            n.facts = facts;
            n.toolCalls = toolCalls;
            n.fromSeq = fromSeq;
            n.upToSeq = upToSeq;
            n.policyVersion = policyVersion;
            return n;
        }

        StructuredState build() {
            return new StructuredState(goal, constraints, userCorrections, decisions,
                    rejectedApproaches, openItems, artifacts, facts, toolCalls,
                    fromSeq, upToSeq, policyVersion);
        }
    }
}
