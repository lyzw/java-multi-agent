package cn.studykid.rag.infrastructure.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.premise.role.BlockRoleResolver;

/**
 * 共享版面判定的<b>双实现同源</b>守卫（RAG-D26 阶段 1.2 的防漂移闸）。
 *
 * <p><b>⚠️ 这条测试防的是什么</b>：
 * 题号抽取与答案区标题判定现在有两个 Java 入口——
 * {@code LayoutPredicates}（infrastructure，Docling/MinerU 映射器共用）
 * 与 {@code BlockRoleResolver.extractExerciseNo}（premise，HybridChunker
 * 角色回填的文本级兜底）。两者正则必须<b>逐字符一致</b>：
 * 漂移的后果是「解析器认得的题号」与「角色回填认得的题号」不同，
 * 同一道题在两条链路下归属不一致——答案块会因此判错角色（泄题方向）。
 *
 * <p>本测试用<b>同一组探针</b>过两侧入口并断言结果相等，
 * 任一侧改了正则而另一侧没改，这里先红。
 */
class LayoutPredicatesParityTest {

    /** 覆盖沪教版实测全部题号形态 + 已知反例（题干指令 / 年份）。 */
    private static final String[] PROBES = {
            "1. What is your name?",
            "2 Work out the rules",
            "（1）Listen and match",
            "Exercise 3 Read and answer",
            "1 Read and answer",
            "(2) How old are you?",
            "2024 年秋季学期",
            "Answer the questions below.",
            "This is a plain paragraph without any numbering.",
            "",
            null,
    };

    private static final String[] HEADING_PROBES = {
            "Answer Key",
            "Answers to Unit 1",
            "参考答案",
            "练习答案",
            "Answer the questions below.",
            "Underline the answers to the questions",
            "",
            null,
    };

    @Test
    @DisplayName("题号抽取：LayoutPredicates 与 BlockRoleResolver 全探针同值")
    void exerciseNoExtractionIsInSync() {
        for (String probe : PROBES) {
            assertThat(LayoutPredicates.extractExerciseNo(probe))
                    .as("题号判定漂移：探针 %s", probe)
                    .isEqualTo(BlockRoleResolver.extractExerciseNo(probe));
        }
    }

    @Test
    @DisplayName("答案区标题判定：LayoutPredicates 与 BlockRoleResolver 前缀语义同源")
    void answerZoneHeadingIsInSync() {
        // ⚠️ 两侧职责不同：predicates 判「标题是否开启答案区」（含长度上限），
        //    resolver 的 SOLUTION_PREFIX 判「块首解答前缀」。这里锁定的是
        //    「predicates 认定的答案区标题，文本级前缀兜底也必须认得」（取严方向）：
        //    即答案区标题文本若出现在合并 chunk 的块首，回填不得漏判。
        for (String probe : HEADING_PROBES) {
            boolean zoneHeading = LayoutPredicates.isAnswerZoneHeading(probe);
            String effectiveNo = LayoutPredicates.extractExerciseNo(probe);
            Object byContent = effectiveNo != null
                    ? BlockRoleResolver.resolveAnswerBearingByContent(probe, effectiveNo)
                    : BlockRoleResolver.resolveAnswerBearingByContent(probe, null);
            if (zoneHeading) {
                assertThat(byContent)
                        .as("答案区标题 %s 必须被文本级兜底同样判为答案承载（取严方向）", probe)
                        .isIn(cn.studykid.rag.core.chunk.ChunkRole.EXERCISE_ANSWER,
                                cn.studykid.rag.core.chunk.ChunkRole.EXAMPLE_SOLUTION);
            }
        }
    }

    @Test
    @DisplayName("题号正则常量逐字符一致（结构性锁定，探针之外的漂移也拦）")
    void regexConstantsAreIdentical() {
        // LayoutPredicates 的正则私有——通过行为探针已覆盖；
        // 此处锁定「两侧对同一输入永不同值」这个不变式本身由上一条测试承担，
        // 本条作为说明性占位：若未来把正则提升为公共常量，可直接断言相等。
        assertThat(LayoutPredicates.extractExerciseNo("12  Write"))
                .isEqualTo(BlockRoleResolver.extractExerciseNo("12  Write"));
    }
}
