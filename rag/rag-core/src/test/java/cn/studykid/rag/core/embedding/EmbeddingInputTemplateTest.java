package cn.studykid.rag.core.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Embedding 输入模板 v1 的不变式（RAG-D28 P3-1 / D27 §6.1 / D29 DEC-P7 M5）。
 *
 * <p>覆盖：字段完整性、query/doc 模板不对称、PII fail-closed、版本留痕、超长可见截断。
 */
class EmbeddingInputTemplateTest {

    private static EmbeddingInput fullInput() {
        return new EmbeddingInput("沪教版", "六年级", "下册", "2024",
                "Module 1 > Unit 2 > 二次函数", 12, 13, "Q003", "EXERCISE", "EXERCISE",
                List.of("二次函数顶点", "抛物线对称轴"), List.of("顶点式", "轴对称性"),
                "求抛物线的顶点坐标并说明开口方向。",
                "二次函数 y=ax^2+bx+c 顶点横坐标 x=-b/2a。",
                "图 2-3：抛物线图像");
    }

    @Test
    @DisplayName("doc 模板含教材五轴/章节/位置/角色/知识点/正文/公式/图注八类语义")
    void documentTemplateCoversAllSemanticFields() {
        String rendered = EmbeddingInputTemplate.renderDocument(fullInput());
        assertThat(rendered)
                .contains("[教材: 沪教版-六年级-下册-2024]")
                .contains("[章节: Module 1 > Unit 2 > 二次函数]")
                .contains("[位置: P12-P13 / 题号 Q003]")
                .contains("[类型: EXERCISE / 角色: EXERCISE]")
                .contains("二次函数顶点")
                .contains("顶点式")
                .contains("求抛物线的顶点坐标")
                .contains("顶点横坐标")
                .contains("图 2-3");
    }

    @Test
    @DisplayName("query 模板拼 instruction、doc 模板不拼（D29 DEC-P7 M5）")
    void queryAndDocumentTemplatesAreAsymmetric() {
        String doc = EmbeddingInputTemplate.renderDocument(fullInput());
        String query = EmbeddingInputTemplate.renderQuery("二次函数的顶点怎么求");
        assertThat(query).contains(EmbeddingInputTemplate.QUERY_INSTRUCTION);
        assertThat(doc).doesNotContain(EmbeddingInputTemplate.QUERY_INSTRUCTION);
        // 撤点反证要点：若两侧共用同一模板，上面两条断言中至少一条必红。
    }

    @Test
    @DisplayName("PII fail-closed：8–12 位连续数字（学号形态）命中即拒收且不回显命中串")
    void piiFailClosed() {
        String secret = "2024113456";
        EmbeddingInput withPii = new EmbeddingInput("沪教版", "六年级", "下册", "2024",
                "", 1, 1, null, null, "EXPLANATION", List.of(), List.of(),
                "学号是 " + secret + " 的同学请留下", null, null);
        assertThatThrownBy(() -> EmbeddingInputTemplate.renderDocument(withPii))
                .isInstanceOf(EmbeddingPiiException.class)
                .hasMessageContaining("学号")
                // 异常消息不得回显具体号码（避免日志二次泄露）。
                .hasMessageNotContaining(secret);

        assertThatThrownBy(() -> EmbeddingInputTemplate.renderQuery("查一下 " + secret))
                .isInstanceOf(EmbeddingPiiException.class);
    }

    @Test
    @DisplayName("合法短数字（年份/页码/题号）不误判为 PII")
    void shortNumbersAreNotPii() {
        assertThat(EmbeddingInputTemplate.renderDocument(fullInput()))
                .contains("2024", "P12", "Q003");
    }

    @Test
    @DisplayName("模板版本片段确定性，供 chunk_policy_config 留痕")
    void configTraceIsDeterministic() {
        assertThat(EmbeddingInputTemplate.configTrace())
                .isEqualTo("embeddingInput=embedding-input-v1")
                .isEqualTo(EmbeddingInputTemplate.configTrace());
    }

    @Test
    @DisplayName("超长输入按可见 [TRUNCATED] 标记截断，不静默丢尾")
    void overlengthIsTruncatedWithVisibleMarker() {
        String huge = " extensively".repeat(2_000);
        String rendered = EmbeddingInputTemplate.renderDocument(
                EmbeddingInput.minimal("EXPLANATION", "顶点定义" + huge));
        assertThat(rendered).contains("[TRUNCATED]");
        assertThat(rendered.length()).isLessThanOrEqualTo(EmbeddingInputTemplate.MAX_TEMPLATE_CHARS);
    }

    @Test
    @DisplayName("无元数据的最小输入仍可渲染（兼容旧夹具），不伪造章节/知识点")
    void minimalInputRendersWithoutFabrication() {
        String rendered = EmbeddingInputTemplate.renderDocument(
                EmbeddingInput.minimal("EXPLANATION", "平面内三角形内角和为 180 度"));
        assertThat(rendered)
                .contains("[章节: ]")
                .contains("[知识点: ]")
                .contains("平面内三角形内角和");
    }
}
