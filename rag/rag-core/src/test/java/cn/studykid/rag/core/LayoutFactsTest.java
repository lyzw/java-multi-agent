package cn.studykid.rag.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.chunk.LayoutFacts;

/**
 * 版面事实的不变式（RAG-D13 §3.2.4 片段校对的判定依据）。
 *
 * <p>⚠️ 本测试证明的核心命题：
 * <b>「未采集」必须与「确认不在答案区」区分开</b>。
 * 前者是"没有这个信息"，后者是"系统确认过"——混为一谈会误导校对员。
 */
class LayoutFactsTest {

    @Nested
    @DisplayName("空事实的判定")
    class EmptyDetection {

        @Test
        @DisplayName("全空即为未采集")
        void allNullIsEmpty() {
            LayoutFacts f = LayoutFacts.none();
            assertThat(f.isEmpty()).isTrue();
            assertThat(f.describe()).contains("无判定依据");
        }

        @Test
        @DisplayName("只要有一个事实非空就不是未采集")
        void anyFactMakesItNonEmpty() {
            assertThat(new LayoutFacts(false, "PARAGRAPH", null).isEmpty()).isFalse();
            assertThat(new LayoutFacts(false, null, "解:").isEmpty()).isFalse();
            assertThat(new LayoutFacts(true, null, null).isEmpty()).isFalse();
        }
    }

    @Nested
    @DisplayName("判定依据的可读描述")
    class Describe {

        @Test
        @DisplayName("答案区 + 有题号的片段描述完整")
        void describesAnswerZone() {
            LayoutFacts f = new LayoutFacts(true, "PARAGRAPH", "解:");
            String d = f.describe();
            assertThat(d).contains("位于答案区");
            assertThat(d).contains("PARAGRAPH");
            assertThat(d).contains("解:");
        }

        @Test
        @DisplayName("不在答案区 + 无前缀的片段如实描述")
        void describesPlainBody() {
            LayoutFacts f = new LayoutFacts(false, "PARAGRAPH", null);
            String d = f.describe();
            assertThat(d).contains("不在答案区");
            assertThat(d).doesNotContain("命中前缀");
        }
    }

    @Nested
    @DisplayName("归一")
    class Normalization {

        @Test
        @DisplayName("空白串归一为 null（避免界面显示空标签）")
        void blankBecomesNull() {
            LayoutFacts f = new LayoutFacts(false, "   ", "  ");
            assertThat(f.blockType()).isNull();
            assertThat(f.matchedPrefix()).isNull();
            assertThat(f.isEmpty()).isTrue();
        }
    }
}
