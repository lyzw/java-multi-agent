package cn.studykid.multiagent.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import tools.jackson.databind.ObjectMapper;

/** 本地题库文件源：精确关联 + 加载期 fail-closed + 不保留学生原文。 */
@DisplayName("L2 · 本地题库文件源")
class LocalFileReferenceAnswerSourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BANK = "agent-eval/reference-answers-test.json";

    private static LocalFileReferenceAnswerSource from(Resource resource) {
        return new LocalFileReferenceAnswerSource(resource, MAPPER);
    }

    private static Resource file(Path dir, String name, String json) throws IOException {
        Path path = dir.resolve(name);
        Files.writeString(path, json, StandardCharsets.UTF_8);
        return new FileSystemResource(path.toFile());
    }

    @Test
    @DisplayName("✅ 按 questionId 精确命中；未收录返回 empty，不按题面猜")
    void resolvesByExactQuestionId() {
        LocalFileReferenceAnswerSource source = from(new ClassPathResource(BANK));

        assertThat(source.size()).isEqualTo(2);
        assertThat(source.resolve("Q-MATH-001")).isPresent();
        assertThat(source.resolve("Q-MATH-001").orElseThrow().standardAnalysis()).contains("移项");
        assertThat(source.resolve(" Q-MATH-002 ")).isPresent();
        assertThat(source.resolve("Q-NOT-IN-BANK")).isEmpty();
        assertThat(source.resolve("请帮我解方程 2x-4=0")).isEmpty();
    }

    @Test
    @DisplayName("⛔ 题库文件里夹带的学生题面原文不会被保留进内存对象")
    void doesNotRetainStudentQuestionText() {
        var answer = from(new ClassPathResource(BANK)).resolve("Q-MATH-001").orElseThrow();

        assertThat(answer.questionId()).isEqualTo("Q-MATH-001");
        assertThat(answer.toString()).doesNotContain("学生原题")
                .as("ReferenceAnswer 只允许 questionId + standardAnalysis；"
                        + "题面原文进内存就等于多存一份不可保留数据");
    }

    @Test
    @DisplayName("✅ 默认 classpath 题库为空：开关开了也不凭空造参考答案")
    void defaultClasspathBankIsEmpty() {
        LocalFileReferenceAnswerSource source =
                from(new ClassPathResource("agent-eval/reference-answers.json"));

        assertThat(source.size()).isZero();
        assertThat(source.resolve("Q-MATH-001")).isEmpty();
    }

    @Test
    @DisplayName("✅ file: 外部路径可覆盖默认题库（不依赖外部接口 / 不改表）")
    void externalFileCanOverrideDefault(@TempDir Path dir) throws IOException {
        LocalFileReferenceAnswerSource source = from(file(dir, "bank.json", """
                {"version":1,"questions":[
                  {"questionId":"EXT-1","standardAnalysis":"外部题库解析"}
                ]}
                """));

        assertThat(source.resolve("EXT-1")).isPresent();
        assertThat(source.resolve("Q-MATH-001")).isEmpty();
    }

    @Test
    @DisplayName("⛔ 重复 questionId 必须拒绝启动（否则判分随加载顺序漂移）")
    void duplicateQuestionIdFailsClosed(@TempDir Path dir) throws IOException {
        Resource resource = file(dir, "dup.json", """
                {"version":1,"questions":[
                  {"questionId":"Q-DUP","standardAnalysis":"解析 A"},
                  {"questionId":"Q-DUP","standardAnalysis":"解析 B"}
                ]}
                """);

        assertThatThrownBy(() -> from(resource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复 questionId");
    }

    @Test
    @DisplayName("⛔ 题面原文当题号 / 中文题号：加载期拒绝，不静默收下")
    void invalidQuestionIdFailsClosed(@TempDir Path dir) throws IOException {
        Resource resource = file(dir, "invalid-id.json", """
                {"version":1,"questions":[
                  {"questionId":"请解方程 2x-4=0","standardAnalysis":"解析"}
                ]}
                """);

        assertThatThrownBy(() -> from(resource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("questionId 非法");
    }

    @Test
    @DisplayName("⛔ 空解析 / 非字符串解析：加载期拒绝（没有解析就不该有参考答案）")
    void emptyAnalysisFailsClosed(@TempDir Path dir) throws IOException {
        Resource blank = file(dir, "blank-analysis.json", """
                {"version":1,"questions":[
                  {"questionId":"Q-1","standardAnalysis":"   "}
                ]}
                """);
        Resource wrongType = file(dir, "wrong-type.json", """
                {"version":1,"questions":[
                  {"questionId":"Q-1","standardAnalysis":{"text":"解析"}}
                ]}
                """);

        assertThatThrownBy(() -> from(blank)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> from(wrongType)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("⛔ 未知版本 / 空文件：拒绝启动，不「尽力解析」出一个看似正常的题库")
    void malformedBankFailsClosed(@TempDir Path dir) throws IOException {
        Resource future = file(dir, "future.json", """
                {"version":2,"questions":[]}
                """);
        Resource empty = file(dir, "empty.json", "");
        Resource missingQuestions = file(dir, "missing.json", """
                {"version":1}
                """);

        assertThatThrownBy(() -> from(future))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> from(empty)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> from(missingQuestions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("questions");
    }
}
