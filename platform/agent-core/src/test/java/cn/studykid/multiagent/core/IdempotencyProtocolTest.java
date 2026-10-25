package cn.studykid.multiagent.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.idempotency.IdempotencyConflictException;
import cn.studykid.multiagent.core.idempotency.IdempotencyDecision;
import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;

/**
 * 幂等协议测试（P1-03 / ADR-003）。
 *
 * <p>重点验证三条语义：
 * <ol>
 *   <li>同键<b>同摘要</b> → 复用首次结果（正常重试不会重复副作用）；</li>
 *   <li>同键<b>异摘要</b> → 冲突（换参数重试不会被误判为成功）；</li>
 *   <li>摘要对<b>键顺序不敏感</b>（否则不同序列化顺序产生假冲突）。</li>
 * </ol>
 */
class IdempotencyProtocolTest {

    @Nested
    @DisplayName("请求摘要：对参数顺序不敏感")
    class DigestCanonicalization {

        @Test
        @DisplayName("同一参数、不同书写顺序 → 同一摘要")
        void keyOrderDoesNotAffectDigest() {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("knowledgePoint", "一元二次方程");
            a.put("confidence", 0.8);

            Map<String, Object> b = new LinkedHashMap<>();
            b.put("confidence", 0.8);
            b.put("knowledgePoint", "一元二次方程");

            assertThat(RequestDigest.of(a))
                    .as("字段顺序不同不应产生假冲突")
                    .isEqualTo(RequestDigest.of(b));
        }

        @Test
        @DisplayName("嵌套 Map 顺序不同 → 同一摘要")
        void nestedOrderDoesNotAffectDigest() {
            Map<String, Object> inner1 = new LinkedHashMap<>(Map.of("b", 2, "a", 1));
            Map<String, Object> inner2 = new LinkedHashMap<>(Map.of("a", 1, "b", 2));

            Map<String, Object> outer1 = new LinkedHashMap<>(Map.of("meta", inner1));
            Map<String, Object> outer2 = new LinkedHashMap<>(Map.of("meta", inner2));

            assertThat(RequestDigest.of(outer1)).isEqualTo(RequestDigest.of(outer2));
        }

        @Test
        @DisplayName("值不同 → 摘要不同（这正是要检出的冲突）")
        void differentValuesDifferentDigest() {
            Map<String, Object> a = Map.of("confidence", 0.8);
            Map<String, Object> b = Map.of("confidence", 0.9);

            assertThat(RequestDigest.of(a).matches(RequestDigest.of(b)))
                    .as("参数变更必须被检出")
                    .isFalse();
        }

        @Test
        @DisplayName("列表顺序影响摘要（列表是有序的）")
        void listOrderMatters() {
            assertThat(RequestDigest.of(Map.of("items", java.util.List.of(1, 2)))
                    .matches(RequestDigest.of(Map.of("items", java.util.List.of(2, 1)))))
                    .isFalse();
        }

        @Test
        @DisplayName("摘要为 64 位十六进制（SHA-256）")
        void digestFormat() {
            String v = RequestDigest.of(Map.of("a", 1)).value();
            assertThat(v).hasSize(64).matches("[0-9a-f]{64}");
        }
    }

    @Nested
    @DisplayName("幂等键：命名空间隔离")
    class KeyNamespacing {

        @Test
        @DisplayName("record_weakness 键 = studentId + runId + knowledgePoint")
        void recordWeaknessKey() {
            IdempotencyKey k = IdempotencyKey.forRecordWeakness("stu-1", "run-1", "一元二次方程");
            assertThat(k.namespace()).isEqualTo("tool.record_weakness");
            assertThat(k.keyValue()).isEqualTo("stu-1/run-1/一元二次方程");
            assertThat(k.composite()).isEqualTo("tool.record_weakness:stu-1/run-1/一元二次方程");
        }

        @Test
        @DisplayName("⚠️ 不同命名空间的相同键值不相撞")
        void namespacesIsolate() {
            IdempotencyKey runKey = new IdempotencyKey("run.create", "shared-value");
            IdempotencyKey toolKey = new IdempotencyKey("tool.record_weakness", "shared-value");

            assertThat(runKey.composite())
                    .as("若命名空间缺失，跨用途会撞值导致错误复用")
                    .isNotEqualTo(toolKey.composite());
        }

        @Test
        @DisplayName("三要素任一不同 → 键不同")
        void keyVariesWithEachPart() {
            IdempotencyKey base = IdempotencyKey.forRecordWeakness("stu-1", "run-1", "KP");
            assertThat(base.keyValue())
                    .isNotEqualTo(IdempotencyKey.forRecordWeakness("stu-2", "run-1", "KP").keyValue())
                    .isNotEqualTo(IdempotencyKey.forRecordWeakness("stu-1", "run-2", "KP").keyValue())
                    .isNotEqualTo(IdempotencyKey.forRecordWeakness("stu-1", "run-1", "OTHER").keyValue());
        }

        @Test
        @DisplayName("空命名空间/空键值被拒绝")
        void rejectsBlank() {
            assertThatThrownBy(() -> new IdempotencyKey("", "k"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new IdempotencyKey("ns", " "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("含分隔符的键值被拒绝（避免 composite 歧义）")
        void rejectsSeparatorInKey() {
            // 命名空间禁止含分隔符；复合键的<b>组成部分</b>也禁止（否则无法唯一分解）
            assertThatThrownBy(() -> new IdempotencyKey("bad/ns", "k"))
                    .as("命名空间含分隔符会破坏 composite 结构")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> IdempotencyKey.forRecordWeakness("stu/1", "r1", "KP"))
                    .as("组成部分含分隔符会使 (a/b,c) 与 (a,b/c) 撞键")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("studentId");
        }

        @Test
        @DisplayName("保留期默认 24 小时")
        void retention() {
            assertThat(IdempotencyKey.DEFAULT_RETENTION).hasHours(24);
        }
    }

    // ── P1-9：幂等键必须含工具身份 ───────────────────

    @Nested
    @DisplayName("P1-9 幂等键含工具身份")
    class ToolIdentityInKey {

        @Test
        @DisplayName("✅ record_weakness 自己仍用 record_weakness 命名空间")
        void recordWeaknessKeepsItsOwnShape() {
            IdempotencyKey k = IdempotencyKey.forToolCall(
                    "run-1", null, IdempotencyKey.RECORD_WEAKNESS_TOOL, "call-1",
                    "stu-1", "一元二次方程");
            assertThat(k.namespace()).isEqualTo("tool.record_weakness");
            assertThat(k.keyValue()).isEqualTo("stu-1/run-1/一元二次方程");
        }

        @Test
        @DisplayName("⚠️⚠️ 其他工具不得占用 record_weakness 命名空间（P1-9 反证）")
        void otherToolMustNotReuseRecordWeaknessNamespace() {
            // ⚠️ 修复前：调用方只看「参数里有没有 studentId + knowledgePoint」，
            //    于是任意带这两个参数的工具都被塞进 tool.record_weakness 命名空间 ——
            //    两个不同工具算出同一个键（UNIQUE(tenant_id, idempotency_key)）。
            IdempotencyKey k = IdempotencyKey.forToolCall(
                    "run-1", null, "generate_explanation", "call-1",
                    "stu-1", "一元二次方程");

            assertThat(k.namespace())
                    .as("⚠️⚠️ 非 record_weakness 工具必须用 tool.invoke 命名空间；"
                            + "否则它与 record_weakness 的调用拉同一个键")
                    .isEqualTo("tool.invoke");
            assertThat(k.keyValue())
                    .as("⚠️⚠️ 键里必须含工具名")
                    .isEqualTo("run-1/generate_explanation/call-1");
        }

        @Test
        @DisplayName("⚠️⚠️ 两个不同工具 + 相同参数 → 不同键（P1-9 反证）")
        void differentToolsWithSameArgumentsProduceDifferentKeys() {
            IdempotencyKey a = IdempotencyKey.forToolCall(
                    "run-1", null, "record_weakness", "call-1", "stu-1", "KP");
            IdempotencyKey b = IdempotencyKey.forToolCall(
                    "run-1", null, "generate_explanation", "call-1", "stu-1", "KP");

            assertThat(a.composite())
                    .as("⚠️⚠️ 工具不同必须产生不同键 —— "
                            + "相同键会让后者被判「重放」（静默不执行）"
                            + "或被判「冲突」（被拒绝执行）")
                    .isNotEqualTo(b.composite());
        }

        @Test
        @DisplayName("✅ 缺 callId 时仍产生带工具名的键")
        void missingCallIdStillCarriesToolName() {
            IdempotencyKey k = IdempotencyKey.forToolCall(
                    "run-1", null, "get_student_profile", null, "stu-1", null);
            assertThat(k.keyValue()).isEqualTo("run-1/get_student_profile/no-call-id");
        }
    }

    // ── P4-D08 R-2：幂等键必须含 run 维度 ──────────────────────────

    @Nested
    @DisplayName("P4-D08 R-2 幂等键含 run 维度")
    class RunDimensionPresent {

        @Test
        @DisplayName("⚠️⚠️ record_weakness 键含真实 runId（修复前是硬编码占位）")
        void recordWeaknessCarriesRealRunId() {
            IdempotencyKey k = IdempotencyKey.forRecordWeakness(
                    "stu-1", "run-abc123", "一元二次方程");
            assertThat(k.keyValue())
                    .as("⚠️⚠️ 键必须含真实 runId——"
                            + "tool_operation 的 UNIQUE(tenant_id, idempotency_key) 是**租户级**，"
                            + "缺 run 维度会让跨 Run 撞键并复用旧 operation")
                    .isEqualTo("stu-1/run-abc123/一元二次方程");
            assertThat(k.keyValue())
                    .as("⚠️⚠️ 绝不能出现占位符 runId（修复前为 \"pending-run\"）")
                    .doesNotContain("pending-run");
        }

        @Test
        @DisplayName("⚠️⚠️ 跨 Run 不撞键（同 student + 同 knowledgePoint）")
        void differentRunsProduceDifferentKeys() {
            IdempotencyKey runA = IdempotencyKey.forRecordWeakness(
                    "stu-1", "run-a", "一元二次方程");
            IdempotencyKey runB = IdempotencyKey.forRecordWeakness(
                    "stu-1", "run-b", "一元二次方程");
            assertThat(runA.composite())
                    .as("⚠️⚠️ 跨 Run 撞键 = 新 Run 的调用被记到旧 Run 的 operation 上"
                            + " = 副作用语义破裂 = Sev-0")
                    .isNotEqualTo(runB.composite());
        }

        @Test
        @DisplayName("✅ 工具调用键 = runId + toolName + callId，命名空间 tool.invoke")
        void toolInvokeKeyShape() {
            IdempotencyKey k = IdempotencyKey.forToolInvoke(
                    "run-1", null, "get_student_profile", "call-7");
            assertThat(k.namespace())
                    .as("⚠️ 工具调用不得复用 run.create 命名空间"
                            + "（否则日志与排障无法区分来源，且违背 ADR-003 隔离意图）")
                    .isEqualTo("tool.invoke");
            assertThat(k.keyValue()).isEqualTo("run-1/get_student_profile/call-7");
        }

        @Test
        @DisplayName("⚠️⚠️ 工具调用键跨 Run 不撞键（同工具名 + 同 callId）")
        void toolInvokeDoesNotCollideAcrossRuns() {
            IdempotencyKey runA = IdempotencyKey.forToolInvoke("run-a", null, "t", "call-1");
            IdempotencyKey runB = IdempotencyKey.forToolInvoke("run-b", null, "t", "call-1");
            assertThat(runA.composite())
                    .as("⚠️⚠️ 同工具名 + 同 callId 跨 Run 必须产生不同键")
                    .isNotEqualTo(runB.composite());
        }

        @Test
        @DisplayName("⚠️ 工具调用键与 Run 创建键不同命名空间")
        void toolInvokeNamespaceIsolated() {
            IdempotencyKey runKey = IdempotencyKey.forRunCreate("shared");
            IdempotencyKey toolKey = IdempotencyKey.forToolInvoke("shared", null, "shared", "shared");
            assertThat(runKey.composite())
                    .as("⚠️ 跨用途撞值会导致错误复用")
                    .isNotEqualTo(toolKey.composite());
        }

        @Test
        @DisplayName("⚠️ 组成部分含分隔符被拒（同 record_weakness 规则）")
        void toolInvokeRejectsSeparator() {
            assertThatThrownBy(() -> IdempotencyKey.forToolInvoke("run/1", null, "t", "c"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("runId");
            assertThatThrownBy(() -> IdempotencyKey.forToolInvoke("run-1", null, "t", ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("callId");
        }
    }

    // ── 第十七批：幂等键必须按「当前执行中的 Agent」分域 ──────────

    @Nested
    @DisplayName("第十七批 幂等键含 Agent 维度（多级 Agent）")
    class AgentDimensionPresent {

        @Test
        @DisplayName("⚠️⚠️ 父子 Agent 同名工具 + 同名 call id → 不同键")
        void parentAndChildDoNotCollide() {
            // ⚠️ 修复前键里没有 Agent 维度 → 父子算出同一个键 →
            //    账本命中重复键 → **复用既有 operation、不再次执行工具** →
            //    子 Agent 那次调用**根本没发生**，模型拿到的还是父的结果。
            IdempotencyKey parent = IdempotencyKey.forToolInvoke(
                    "run-1", "agent-parent@1", "get_student_profile", "call-1");
            IdempotencyKey child = IdempotencyKey.forToolInvoke(
                    "run-1", "agent-child@1", "get_student_profile", "call-1");

            assertThat(parent.composite())
                    .as("⚠️⚠️ 父子撞键 = 子 Agent 的调用被静默吞掉 = Sev-0")
                    .isNotEqualTo(child.composite());
        }

        @Test
        @DisplayName("⚠️⚠️ 兄弟子 Agent 同名工具 + 同名 call id → 不同键")
        void siblingAgentsDoNotCollide() {
            IdempotencyKey left = IdempotencyKey.forToolInvoke(
                    "run-1", "agent-left@1", "get_student_profile", "call-1");
            IdempotencyKey right = IdempotencyKey.forToolInvoke(
                    "run-1", "agent-right@1", "get_student_profile", "call-1");

            assertThat(left.composite())
                    .as("⚠️⚠️ 兄弟撞键 = 第二个子 Agent 的调用被第一个的记录吞掉")
                    .isNotEqualTo(right.composite());
        }

        @Test
        @DisplayName("✅ 同一 Agent 重复投递 → 键相同（幂等语义未被破坏）")
        void sameAgentRepeatDeliveryStaysIdempotent() {
            // ⚠️ 这是本次修复的**反面**：加了 Agent 维度之后，
            //    同一 Agent 的同一次调用重投**必须**仍是同一个键，
            //    否则「重复投递」会变成「重复副作用」——那是比吞调用更糟的失效方向。
            IdempotencyKey first = IdempotencyKey.forToolInvoke(
                    "run-1", "agent-child@1", "get_student_profile", "call-1");
            IdempotencyKey replay = IdempotencyKey.forToolInvoke(
                    "run-1", "agent-child@1", "get_student_profile", "call-1");

            assertThat(replay.composite())
                    .as("⚠️⚠️ 同一 Agent 重复投递必须仍是同一个键 —— "
                            + "键若每次都变，幂等就退化成「每次都执行一遍」")
                    .isEqualTo(first.composite());
        }

        @Test
        @DisplayName("✅ 无 Agent 维度（单 Agent 路径）→ 键值与历史完全一致")
        void nullAgentRefKeepsHistoricalKeyShape() {
            // ⚠️ 在途的 tool_operation 行是按旧键写的：
            //    单 Agent 路径若改了键形状，那些行就再也匹配不上 →
            //    重放会被当成新调用 → **重复副作用**。
            IdempotencyKey k = IdempotencyKey.forToolInvoke(
                    "run-1", null, "get_student_profile", "call-7");

            assertThat(k.keyValue())
                    .as("⚠️⚠️ 单 Agent 路径的键必须与历史逐字符一致")
                    .isEqualTo("run-1/get_student_profile/call-7");
            assertThat(k.namespace()).isEqualTo("tool.invoke");
        }

        @Test
        @DisplayName("✅ 同一 agentId 的不同 release → 不同键")
        void differentVersionsOfSameAgentDoNotCollide() {
            IdempotencyKey v1 = IdempotencyKey.forToolInvoke(
                    "run-1", "agent-child@1", "get_student_profile", "call-1");
            IdempotencyKey v2 = IdempotencyKey.forToolInvoke(
                    "run-1", "agent-child@2", "get_student_profile", "call-1");

            assertThat(v1.composite())
                    .as("⚠️⚠️ 不同 release 是不同的执行主体（闭包/工具集可能不同），"
                            + "键若不区分版本，新 release 会继承旧 release 的幂等记录")
                    .isNotEqualTo(v2.composite());
        }

        @Test
        @DisplayName("✅ record_weakness 的键不含 Agent 维度（业务事实全局唯一）")
        void recordWeaknessIgnoresAgentDimension() {
            // ⚠️ record_weakness 记的是「某学生的某知识点」这条**业务事实**，
            //    全局唯一：父 Agent 与子 Agent 记同一条，后写的那次被幂等拦下是**正确**的。
            //    因此这一形状**刻意**不加 Agent 维度。
            IdempotencyKey withAgent = IdempotencyKey.forToolCall(
                    "run-1", "agent-child@1", IdempotencyKey.RECORD_WEAKNESS_TOOL, "call-1",
                    "stu-1", "一元二次方程");
            IdempotencyKey withoutAgent = IdempotencyKey.forToolCall(
                    "run-1", null, IdempotencyKey.RECORD_WEAKNESS_TOOL, "call-1",
                    "stu-1", "一元二次方程");

            assertThat(withAgent.composite())
                    .as("✅ record_weakness 是业务事实，不按 Agent 分域")
                    .isEqualTo(withoutAgent.composite());
            assertThat(withAgent.keyValue()).isEqualTo("stu-1/run-1/一元二次方程");
        }

        @Test
        @DisplayName("⚠️ Agent 引用含分隔符被拒（键必须可唯一分解）")
        void agentRefWithSeparatorIsRejected() {
            // ⚠️ (a/b, c) 与 (a, b/c) 会算出同一个键 →
            //    不同 Agent 的调用互相吞掉 —— 那正是本次要修的缺陷换个方向复发。
            assertThatThrownBy(() -> IdempotencyKey.forToolInvoke(
                    "run-1", "agent/child@1", "t", "call-1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("agentRef");
        }

        @Test
        @DisplayName("✅ Agent 引用格式 = agentId@version（唯一一处定义）")
        void agentRefFormat() {
            assertThat(IdempotencyKey.agentRefOf("agent-child", 3))
                    .as("⚠️ 格式定义必须唯一：执行作用域与委派上下文都从这里渲染，"
                            + "各自拼一份的话格式一旦漂移就会撞键")
                    .isEqualTo("agent-child@3");
        }

        @Test
        @DisplayName("⚠️ Agent 引用的版本必须为正数")
        void agentRefRejectsNonPositiveVersion() {
            assertThatThrownBy(() -> IdempotencyKey.agentRefOf("agent-child", 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("agentVersion");
        }
    }

    @Nested
    @DisplayName("⚠️ 三态判定不可混淆")
    class DecisionSemantics {

        @Test
        @DisplayName("首次执行 → 可继续")
        void firstExecution() {
            IdempotencyDecision d = IdempotencyDecision.firstExecution("run-1");
            assertThat(d.kind()).isEqualTo(IdempotencyDecision.Kind.FIRST_EXECUTION);
            assertThat(d.shouldExecute()).isTrue();
            assertThat(d.isConflict()).isFalse();
            assertThat(d.originalResourceId()).isEqualTo("run-1");
        }

        @Test
        @DisplayName("重复且同摘要 → 复用首次结果，不得重复执行")
        void replayDoesNotExecute() {
            IdempotencyDecision d = IdempotencyDecision.replay("run-original");
            assertThat(d.kind()).isEqualTo(IdempotencyDecision.Kind.REPLAY);
            assertThat(d.shouldExecute())
                    .as("重复请求绝不可再次执行副作用")
                    .isFalse();
            assertThat(d.originalResourceId())
                    .as("须返回首次创建的资源 ID")
                    .isEqualTo("run-original");
        }

        @Test
        @DisplayName("异摘要 → 冲突并抛异常")
        void conflictThrows() {
            IdempotencyDecision d = IdempotencyDecision.conflict();
            assertThat(d.isConflict()).isTrue();
            assertThat(d.shouldExecute()).isFalse();
            assertThatThrownBy(d::throwIfConflict)
                    .isInstanceOf(IdempotencyConflictException.class);
        }

        @Test
        @DisplayName("非冲突时 throwIfConflict 不抛异常")
        void nonConflictDoesNotThrow() {
            IdempotencyDecision.firstExecution("r1").throwIfConflict();
            IdempotencyDecision.replay("r1").throwIfConflict();
        }

        @Test
        @DisplayName("⚠️ 异常消息不含键值与摘要（脱敏）")
        void conflictMessageIsSanitized() {
            String msg = new IdempotencyConflictException().getMessage();
            assertThat(msg).doesNotContain("stu-");
        }

        @Test
        @DisplayName("构造校验：非冲突场景必须带原始资源 ID")
        void constructorValidation() {
            assertThatThrownBy(() -> new IdempotencyDecision(
                    IdempotencyDecision.Kind.REPLAY, null))
                    .isInstanceOf(IllegalArgumentException.class);
            // 冲突场景不要求资源 ID
            IdempotencyDecision.conflict();
        }
    }

    @Nested
    @DisplayName("端到端：重试语义")
    class RetrySemantics {

        @Test
        @DisplayName("网络超时后原样重试 → 复用首次结果")
        void identicalRetryReplays() {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("knowledgePoint", "一元二次方程");
            args.put("confidence", 0.8);

            RequestDigest first = RequestDigest.of(args);
            RequestDigest retry = RequestDigest.of(args);

            assertThat(first.matches(retry))
                    .as("原样重试必须被判为同一请求")
                    .isTrue();
        }

        @Test
        @DisplayName("改参数重试 → 冲突而非静默返回旧结果")
        void changedArgsConflicts() {
            RequestDigest first = RequestDigest.of(Map.of("confidence", 0.8));
            RequestDigest changed = RequestDigest.of(Map.of("confidence", 0.5));

            assertThat(first.matches(changed))
                    .as("改参数后必须检出差异，否则用户改了参数却被告知已成功")
                    .isFalse();
        }
    }
}