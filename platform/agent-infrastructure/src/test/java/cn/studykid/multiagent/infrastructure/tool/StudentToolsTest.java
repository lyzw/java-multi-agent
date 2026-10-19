package cn.studykid.multiagent.infrastructure.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.ports.tool.ToolExecutionException;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * D4 测试：两个工具的实现。
 *
 * <p><b>核心命题</b>（P2-D04 §5）：摘要只对**工具参数**计算。
 * 若把 runId/tenantId 算进去，同一逻辑操作在不同 Run 中摘要不同
 * → <b>幂等失效</b> → 重复写入 = Sev-0。
 *
 * <p>这是「看起来对」但实际危险的代码，必须有专门测试。
 */
class StudentToolsTest {

    private static final String TENANT = "6f5d33fc-723e-4f64-beda-9e4435c72691";
    private static final String STUDENT = "stu_a1b2c3d4e5f6g7h8";
    private static final String RUN_ID = UUID.randomUUID().toString();

    private static ToolHandler.ToolExecutionContext ctx() {
        return new ToolHandler.ToolExecutionContext(
                TENANT, RUN_ID, "worker", "key-1", 1);
    }

    private static Map<String, Object> weaknessArgs() {
        Map<String, Object> m = new HashMap<>();
        m.put("studentId", STUDENT);
        m.put("knowledgePoint", "一元二次方程");
        m.put("errorType", "概念不清");
        m.put("confidence", 0.8);
        return m;
    }

    // ── 仓储替身 ────────────────────────────────────────────────────

    private static class FakeRepo extends StudentDataRepositoryStub {
        StudentDataRepository.ProfileView profile = StudentDataRepository.ProfileView.empty(STUDENT);
        StudentDataRepository.WriteOutcome writeOutcome = new StudentDataRepository.WriteOutcome("wk-1", false);
        boolean writeThrows;
        boolean writeThrowsDuplicate;
        Optional<String> reconcileResult = Optional.empty();
        int insertCount;

        @Override
        public StudentDataRepository.ProfileView loadProfile(String tenantId, String studentId) {
            return profile;
        }

        @Override
        public StudentDataRepository.WriteOutcome insertWeakness(String t, String s, String r,
                                                                 String kp, String et, Double c) {
            insertCount++;
            if (writeThrowsDuplicate) {
                throw new StudentDataRepository.DuplicateWeaknessException("冲突");
            }
            if (writeThrows) {
                throw new IllegalStateException("连接中断");
            }
            return writeOutcome;
        }

        @Override
        public Optional<String> reconcileWeakness(String t, String s, String r, String kp) {
            return reconcileResult;
        }
    }

    /** 仓储替身基类（避免实现真实 JDBC）。 */
    private abstract static class StudentDataRepositoryStub {
        abstract StudentDataRepository.ProfileView loadProfile(String tenantId, String studentId);

        abstract StudentDataRepository.WriteOutcome insertWeakness(String t, String s, String r,
                                                                     String kp, String et, Double c);

        abstract Optional<String> reconcileWeakness(String t, String s, String r, String kp);
    }

    // ── 测试 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Schema 校验（§3.2 / §4）")
    class SchemaValidation {

        @Test
        @DisplayName("✅ 合法参数通过")
        void validArgsPass() {
            new GetStudentProfileTool(null).validate(Map.of("studentId", STUDENT));
            new RecordWeaknessTool(null).validate(weaknessArgs());
        }

        @Test
        @DisplayName("⚠️ studentId 格式非法（含姓名）被拒")
        void invalidStudentIdRejected() {
            var tool = new GetStudentProfileTool(null);
            // 姓名不是合法 studentId —— DEC-6 禁止可识别信息
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> tool.validate(Map.of("studentId", "张三")));
        }

        @Test
        @DisplayName("⚠️⚠️ 多传字段被拒（additionalProperties: false）")
        void extraFieldsRejected() {
            var profileTool = new GetStudentProfileTool(null);
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> profileTool.validate(Map.of(
                            "studentId", STUDENT, "studentName", "张三")));

            var weaknessTool = new RecordWeaknessTool(null);
            Map<String, Object> args = weaknessArgs();
            args.put("school", "实验中学");
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> weaknessTool.validate(args));
        }

        @Test
        @DisplayName("⚠️ errorType 非法枚举被拒（防知识点发散）")
        void invalidErrorTypeRejected() {
            var tool = new RecordWeaknessTool(null);
            Map<String, Object> args = weaknessArgs();
            args.put("errorType", "随便写的类型");
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> tool.validate(args));
        }

        @Test
        @DisplayName("⚠️ confidence 越界被拒")
        void confidenceOutOfRangeRejected() {
            var tool = new RecordWeaknessTool(null);
            Map<String, Object> args = weaknessArgs();
            args.put("confidence", 1.5);
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> tool.validate(args));
        }

        @Test
        @DisplayName("⚠️ 缺必填字段被拒")
        void missingRequiredRejected() {
            var tool = new RecordWeaknessTool(null);
            Map<String, Object> args = weaknessArgs();
            args.remove("knowledgePoint");
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> tool.validate(args));
        }

        @Test
        @DisplayName("✅ 工具名与风险级别符合 D-007 契约")
        void toolMetadataMatchesContract() {
            var read = new GetStudentProfileTool(null);
            assertThat(read.toolName()).isEqualTo("get_student_profile");
            assertThat(read.riskLevel()).isEqualTo(ToolRiskLevel.READ);

            var write = new RecordWeaknessTool(null);
            assertThat(write.toolName()).isEqualTo("record_weakness");
            assertThat(write.riskLevel())
                    .as("DEC-11：record_weakness 为 WRITE_PROFILE")
                    .isEqualTo(ToolRiskLevel.WRITE_PROFILE);
        }
    }

    @Nested
    @DisplayName("⚠️⚠️ 摘要范围（§5）—— 决定幂等是否成立")
    class DigestScope {

        @Test
        @DisplayName("⚠️⚠️ 摘要只含工具参数，不含 runId/tenantId")
        void digestExcludesContext() {
            Map<String, Object> args = weaknessArgs();

            // 只对 arguments 计算
            RequestDigest fromArgs = RequestDigest.of(args);

            // 若把上下文混入，摘要会不同
            Map<String, Object> polluted = new HashMap<>(args);
            polluted.put("runId", RUN_ID);
            polluted.put("tenantId", TENANT);
            RequestDigest fromPolluted = RequestDigest.of(polluted);

            assertThat(fromArgs.matches(fromPolluted))
                    .as("⚠️ 若相等，说明实现把上下文算进了摘要 —— "
                            + "同一逻辑操作在不同 Run 中摘要不同 → 幂等失效")
                    .isFalse();
        }

        @Test
        @DisplayName("✅ 参数顺序不同 → 摘要相同（归一化生效）")
        void digestOrderIndependent() {
            Map<String, Object> a = new HashMap<>();
            a.put("studentId", STUDENT);
            a.put("knowledgePoint", "一元二次方程");
            a.put("errorType", "概念不清");

            Map<String, Object> b = new HashMap<>();
            b.put("errorType", "概念不清");
            b.put("studentId", STUDENT);
            b.put("knowledgePoint", "一元二次方程");

            assertThat(RequestDigest.of(a).matches(RequestDigest.of(b))).isTrue();
        }

        @Test
        @DisplayName("✅ 值不同 → 摘要不同（这正是要检出的冲突）")
        void digestDetectsValueChange() {
            Map<String, Object> a = weaknessArgs();
            Map<String, Object> b = weaknessArgs();
            b.put("errorType", "计算失误");

            assertThat(RequestDigest.of(a).matches(RequestDigest.of(b)))
                    .as("内容变化必须检出，否则换参数重试会被误判为重复")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("画像读取（§3.1）")
    class ProfileRead {

        @Test
        @DisplayName("✅ 学生不存在 → 空画像而非报错")
        void missingStudentReturnsEmpty() {
            FakeRepo repo = new FakeRepo();
            repo.profile = StudentDataRepository.ProfileView.empty(STUDENT);

            var tool = new GetStudentProfileTool(toRepo(repo));
            var result = tool.execute(ctx(), Map.of("studentId", STUDENT));

            assertThat(result.output())
                    .as("⚠️ 不报错是刻意的：报错/有数据可让攻击者枚举 studentId")
                    .contains("notFound")
                    .doesNotContain("error");
        }

        @Test
        @DisplayName("✅ 存在画像 → 返回白名单字段")
        void existingProfileReturnsData() {
            FakeRepo repo = new FakeRepo();
            repo.profile = new StudentDataRepository.ProfileView(
                    STUDENT, "初二", "数学",
                    java.util.List.of(new StudentDataRepository.WeaknessView(
                            "一元二次方程", "概念不清", 0.8,
                            java.time.Instant.now())),
                    java.time.Instant.now());

            var tool = new GetStudentProfileTool(toRepo(repo));
            var result = tool.execute(ctx(), Map.of("studentId", STUDENT));

            assertThat(result.output())
                    .contains("grade").contains("初二")
                    .contains("knowledgePoint");
            assertThat(result.output())
                    .as("⚠️ 输出只含 DEC-6 白名单字段")
                    .doesNotContain("name").doesNotContain("school");
        }
    }

    @Nested
    @DisplayName("⚠️⚠️ UNKNOWN 语义（§4.2）—— 最关键路径")
    class UnknownSemantics {

        @Test
        @DisplayName("⚠️⚠️ 写入过程异常 → unknownResult（不可重试）")
        void writeFailureIsUnknownResult() {
            FakeRepo repo = new FakeRepo();
            repo.writeThrows = true;

            var tool = new RecordWeaknessTool(toRepo(repo));

            var e = catchThrowableOfType(
                    () -> tool.execute(ctx(), weaknessArgs()),
                    ToolExecutionException.class);

            assertThat(e).isNotNull();
            assertThat(e.isResultUnknown())
                    .as("⚠️⚠️ 写入异常必须按 UNKNOWN 处理 —— "
                            + "若按 definitelyFailed 处理而实际已落库，重试即重复写入 = Sev-0")
                    .isTrue();
        }

        @Test
        @DisplayName("✅ 幂等冲突 → definitelyFailed（明确无副作用）")
        void duplicateIsDefinitelyFailed() {
            FakeRepo repo = new FakeRepo();
            repo.writeThrowsDuplicate = true;

            var tool = new RecordWeaknessTool(toRepo(repo));

            var e = catchThrowableOfType(
                    () -> tool.execute(ctx(), weaknessArgs()),
                    ToolExecutionException.class);

            assertThat(e).isNotNull();
            assertThat(e.isResultUnknown())
                    .as("冲突是明确的无副作用失败，可重试（用新键）")
                    .isFalse();
        }

        @Test
        @DisplayName("✅ 正常写入返回 recorded + replayed")
        void normalWriteReturnsOutcome() {
            FakeRepo repo = new FakeRepo();
            repo.writeOutcome = new StudentDataRepository.WriteOutcome("wk-1", false);

            var tool = new RecordWeaknessTool(toRepo(repo));
            var result = tool.execute(ctx(), weaknessArgs());

            assertThat(result.output())
                    .contains("recorded").contains("wk-1")
                    .contains("replayed");
        }

        @Test
        @DisplayName("✅ 幂等重放 → replayed=true 且不重复写")
        void replayDoesNotInsertAgain() {
            FakeRepo repo = new FakeRepo();
            repo.writeOutcome = new StudentDataRepository.WriteOutcome("wk-1", true);

            var tool = new RecordWeaknessTool(toRepo(repo));
            var result = tool.execute(ctx(), weaknessArgs());

            assertThat(result.output()).contains("replayed").contains("\"replayed\":true");
        }
    }

    @Nested
    @DisplayName("对账（§4.2）")
    class Reconcile {

        @Test
        @DisplayName("✅ 查到记录 → SUCCEEDED")
        void reconcileFound() {
            FakeRepo repo = new FakeRepo();
            repo.reconcileResult = Optional.of("wk-1");

            var tool = new RecordWeaknessTool(toRepo(repo));
            var result = tool.reconcile(ctx(),
                    STUDENT + "/" + RUN_ID + "/一元二次方程");

            assertThat(result).isNotNull();
            assertThat(result.output()).contains("SUCCEEDED").contains("wk-1");
        }

        @Test
        @DisplayName("✅ 确认不存在 → ABSENT（不是未知）")
        void reconcileAbsent() {
            FakeRepo repo = new FakeRepo();
            repo.reconcileResult = Optional.empty();

            var tool = new RecordWeaknessTool(toRepo(repo));
            var result = tool.reconcile(ctx(),
                    STUDENT + "/" + RUN_ID + "/一元二次方程");

            assertThat(result.output()).contains("ABSENT");
        }

        @Test
        @DisplayName("⚠️⚠️ 键格式不符 → 返回 null（不可查，须转人工）")
        void unparseableKeyReturnsNull() {
            FakeRepo repo = new FakeRepo();
            var tool = new RecordWeaknessTool(toRepo(repo));

            var result = tool.reconcile(ctx(), "malformed-key");

            assertThat(result)
                    .as("⚠️ null = 下游不可查 → 调用方须 MANUAL_DISPOSED，"
                            + "绝不重试（盲重试即重复写入 = Sev-0）")
                    .isNull();
        }
    }

    /** 把替身适配为真实仓储类型（测试专用）。 */
    private static StudentDataRepository toRepo(StudentDataRepositoryStub stub) {
        return new StudentDataRepository(null) {
            @Override
            public ProfileView loadProfile(String tenantId, String studentId) {
                return stub.loadProfile(tenantId, studentId);
            }

            @Override
            public WriteOutcome insertWeakness(String t, String s, String r, String kp,
                                               String et, Double c) {
                return stub.insertWeakness(t, s, r, kp, et, c);
            }

            @Override
            public Optional<String> reconcileWeakness(String t, String s, String r, String kp) {
                return stub.reconcileWeakness(t, s, r, kp);
            }
        };
    }
}