package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code args_norm_v1} 参数规范化测试（P3-T2 · §5.3.2）。
 *
 * <p>⚠️ <b>这七条规则每一条都是一条攻击面的封堵</b>：
 * 字段序、Unicode 形式、数字形式、数组序、缺失与 null 的区分、
 * Schema 默认值展开 —— 任何一条放过，都会让「同参数」算出两个摘要，
 * 于是「审批绑定的参数」与「实际执行的参数」对不上，防重放失效。
 */
class ArgsNormalizerTest {

    @Nested
    @DisplayName("§5.3.2 规则 1：字段字典序")
    class FieldOrder {

        @Test
        @DisplayName("⚠️ 键序不同但内容相同 → 同一摘要")
        void keyOrderDoesNotAffectHash() {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("studentId", "stu_1");
            a.put("knowledgePoint", "kp_1");

            Map<String, Object> b = new LinkedHashMap<>();
            b.put("knowledgePoint", "kp_1");
            b.put("studentId", "stu_1");

            assertThat(ArgsNormalizer.hash(a, ArgsNormalizer.Options.all()))
                    .as("§5.3.2：字段字典序 —— 键序不得影响摘要")
                    .isEqualTo(ArgsNormalizer.hash(b, ArgsNormalizer.Options.all()));
        }
    }

    @Nested
    @DisplayName("§5.3.2 规则 2：字符串 UTF-8 NFC")
    class UnicodeNfc {

        @Test
        @DisplayName("⚠️ NFC 等价但字节不同 → 同一摘要")
        void nfcEquivalentsShareHash() {
            // "é" 的组合形式 U+0065 U+0301 与预组合形式 U+00E9
            String decomposed = "é";
            String composed = "é";

            assertThat(ArgsNormalizer.hash(Map.of("name", decomposed), ArgsNormalizer.Options.all()))
                    .as("§5.3.2：字符串 UTF-8 NFC —— Unicode 等价形式必须同摘要")
                    .isEqualTo(ArgsNormalizer.hash(Map.of("name", composed),
                            ArgsNormalizer.Options.all()));
        }
    }

    @Nested
    @DisplayName("§5.3.2 规则 3：数字十进制规范形式")
    class NumberForm {

        @Test
        @DisplayName("⚠️ 1 / 1.0 / 1.00 → 同一摘要")
        void integralAndDecimalShareHash() {
            assertThat(ArgsNormalizer.hash(Map.of("score", 1), ArgsNormalizer.Options.all()))
                    .isEqualTo(ArgsNormalizer.hash(Map.of("score", 1.0d),
                            ArgsNormalizer.Options.all()));
            assertThat(ArgsNormalizer.hash(Map.of("score", 1), ArgsNormalizer.Options.all()))
                    .isEqualTo(ArgsNormalizer.hash(Map.of("score", 1.00d),
                            ArgsNormalizer.Options.all()));
        }

        @Test
        @DisplayName("⚠️ BigDecimal 尾零必须剥离")
        void bigDecimalStripsTrailingZeros() {
            assertThat(ArgsNormalizer.canonicalNumber(new java.math.BigDecimal("1.500")))
                    .isEqualTo("1.5");
            assertThat(ArgsNormalizer.canonicalNumber(new java.math.BigDecimal("1.00")))
                    .isEqualTo("1");
        }

        @Test
        @DisplayName("⚠️⚠️ 数字与同值字符串必须不同摘要（修复前两者同摘要）")
        void numberAndSameTextAreDistinct() {
            // ⚠️ 修复前：Number 被写成**文本节点**（textNode(canonicalNumber(n))），
            //    于是 {"score":1} 与 {"score":"1"} 归一化后**逐字符相同** → 同摘要。
            //    后果：审批绑定的参数是数字、实际执行时换成同值字符串，
            //    审批绑定校验照样通过 —— 类型被悄悄换掉。
            assertThat(ArgsNormalizer.hash(Map.of("score", 1), ArgsNormalizer.Options.all()))
                    .as("⚠️⚠️ 数字 1 与字符串 \"1\" 必须不同摘要"
                            + "（与 ParamPolicyEngine.valueKey 的 n:/s: 前缀口径一致）")
                    .isNotEqualTo(ArgsNormalizer.hash(Map.of("score", "1"),
                            ArgsNormalizer.Options.all()));

            assertThat(ArgsNormalizer.hash(Map.of("score", 1), ArgsNormalizer.Options.all()))
                    .as("⚠️⚠️ 浮点 1.0 与字符串 \"1.0\" 也必须不同摘要")
                    .isNotEqualTo(ArgsNormalizer.hash(Map.of("score", "1.0"),
                            ArgsNormalizer.Options.all()));
        }

        @Test
        @DisplayName("⚠️ 规范化 JSON 保留数字类型（不是字符串）")
        void normalizedJsonKeepsNumberType() {
            assertThat(ArgsNormalizer.normalizedJson(Map.of("score", 1),
                    ArgsNormalizer.Options.all()))
                    .as("⚠️ 数字必须落成 JSON 数字，否则与字符串无法区分")
                    .isEqualTo("{\"score\":1}");
        }
    }

    @Nested
    @DisplayName("§5.3.2 规则 4：布尔")
    class BooleanForm {

        @Test
        @DisplayName("⚠️ true / false 区分，且与字符串 \"true\" 不同")
        void booleansAreDistinct() {
            assertThat(ArgsNormalizer.hash(Map.of("flag", true), ArgsNormalizer.Options.all()))
                    .isNotEqualTo(ArgsNormalizer.hash(Map.of("flag", false),
                            ArgsNormalizer.Options.all()));
            assertThat(ArgsNormalizer.hash(Map.of("flag", true), ArgsNormalizer.Options.all()))
                    .as("布尔与同名字符串必须区分，否则参数可被类型伪装替换")
                    .isNotEqualTo(ArgsNormalizer.hash(Map.of("flag", "true"),
                            ArgsNormalizer.Options.all()));
        }
    }

    @Nested
    @DisplayName("§5.3.2 规则 5：数组序")
    class ArrayOrder {

        @Test
        @DisplayName("⚠️ 默认保持调用序 → 换序即换摘要")
        void defaultKeepsCallOrder() {
            assertThat(ArgsNormalizer.hash(Map.of("tags", List.of("a", "b")),
                    ArgsNormalizer.Options.all()))
                    .isNotEqualTo(ArgsNormalizer.hash(Map.of("tags", List.of("b", "a")),
                            ArgsNormalizer.Options.all()));
        }

        @Test
        @DisplayName("⚠️ 声明为 set → 换序不换摘要")
        void setValuedIgnoresOrder() {
            ArgsNormalizer.Options set = new ArgsNormalizer.Options(null, null, Set.of("tags"));

            assertThat(ArgsNormalizer.hash(Map.of("tags", List.of("a", "b")), set))
                    .as("§5.3.2：元数据声明 set 则字典序排序")
                    .isEqualTo(ArgsNormalizer.hash(Map.of("tags", List.of("b", "a")), set));
        }
    }

    @Nested
    @DisplayName("§5.3.2 规则 6：缺失字段与显式 null 区分")
    class MissingVersusNull {

        @Test
        @DisplayName("⚠️ {} 与 {\"a\": null} → 不同摘要")
        void missingAndExplicitNullDiffer() {
            // ⚠️ Map.of 不允许 null 值 → 必须用可变 Map 造「显式 null」夹具
            Map<String, Object> explicitNull = new LinkedHashMap<>();
            explicitNull.put("a", null);

            assertThat(ArgsNormalizer.hash(Map.of(), ArgsNormalizer.Options.all()))
                    .as("§5.3.2：缺失字段与显式 null 必须区分编码")
                    .isNotEqualTo(ArgsNormalizer.hash(explicitNull, ArgsNormalizer.Options.all()));
        }

        @Test
        @DisplayName("⚠️ 显式 null 是「有效参数」→ 参与摘要")
        void explicitNullParticipatesInEffectiveSet() {
            ArgsNormalizer.Options effectful =
                    ArgsNormalizer.Options.effectful(Set.of("a"));

            Map<String, Object> explicitNull = new LinkedHashMap<>();
            explicitNull.put("a", null);

            assertThat(ArgsNormalizer.effectiveArguments(explicitNull, effectful))
                    .as("显式 null 也在有效参数集内")
                    .containsKey("a");
        }
    }

    @Nested
    @DisplayName("§5.3.2 规则 7：Schema default 先展开")
    class SchemaDefaults {

        @Test
        @DisplayName("⚠️ 省略参数与显式传默认值 → 同一摘要")
        void omittedEqualsExplicitDefault() {
            String schema = """
                    {"type":"object","properties":{"limit":{"type":"integer","default":10}}}
                    """;

            assertThat(ArgsNormalizer.hash(Map.of(), ArgsNormalizer.Options.all()
                            .withDefaults(ArgsNormalizer.defaultsFromSchema(schema))))
                    .as("§5.3.2：Schema default 先展开再哈希")
                    .isEqualTo(ArgsNormalizer.hash(Map.of("limit", 10),
                            ArgsNormalizer.Options.all()
                                    .withDefaults(ArgsNormalizer.defaultsFromSchema(schema))));
        }

        @Test
        @DisplayName("⚠️ 默认值只填补缺失键，绝不覆盖显式 null")
        void defaultsDoNotOverrideExplicitNull() {
            String schema = """
                    {"type":"object","properties":{"limit":{"type":"integer","default":10}}}
                    """;

            Map<String, Object> explicitNull = new LinkedHashMap<>();
            explicitNull.put("limit", null);

            assertThat(ArgsNormalizer.effectiveArguments(
                    explicitNull,
                    ArgsNormalizer.Options.all()
                            .withDefaults(ArgsNormalizer.defaultsFromSchema(schema))))
                    .as("⚠️ 默认值不得覆盖显式传值（否则 null 与默认值无法区分）")
                    .containsEntry("limit", null);
        }
    }

    @Nested
    @DisplayName("摘要形态")
    class DigestShape {

        @Test
        @DisplayName("✅ 前缀为 args_norm_v1: 且为小写 hex")
        void digestHasVersionPrefixAndLowercaseHex() {
            String digest = ArgsNormalizer.hash(Map.of("a", 1), ArgsNormalizer.Options.all());

            assertThat(digest).startsWith("args_norm_v1:");
            assertThat(digest.substring("args_norm_v1:".length()))
                    .matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("⚠️ 内容不同 → 摘要不同")
        void differentArgumentsDiffer() {
            assertThat(ArgsNormalizer.hash(Map.of("to", "a@example.com"),
                    ArgsNormalizer.Options.all()))
                    .isNotEqualTo(ArgsNormalizer.hash(Map.of("to", "b@example.com"),
                            ArgsNormalizer.Options.all()));
        }
    }
}
