package cn.studykid.rag.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.indexing.IndexGeneration;

/**
 * 索引代际的不变式（RAG-D13 §3.5.2 蓝绿切换）。
 *
 * <p>⚠️ 本测试证明的核心命题：
 * <b>一个只建了一半的代际绝不能被激活</b>——它上线后表现为
 * 「有些片段永远召回不到」，且没有任何报错。
 */
class IndexGenerationTest {

    private static IndexGeneration gen(int no, IndexGeneration.Status status,
                                       long indexed, long total) {
        return new IndexGeneration(no, "BGE-M3", 1024, status, indexed, total,
                null, null, null, null);
    }

    @Nested
    @DisplayName("可激活性")
    class Activatable {

        @Test
        @DisplayName("BUILDING 不可激活（防半成品上线）")
        void buildingIsNotActivatable() {
            IndexGeneration g = gen(2, IndexGeneration.Status.BUILDING, 30, 100);
            assertThat(g.isActivatable()).isFalse();
            assertThat(g.isRetrievable()).isFalse();
        }

        @Test
        @DisplayName("RETIRED 可激活（这是回滚能力的基础）")
        void retiredIsActivatable() {
            IndexGeneration g = gen(1, IndexGeneration.Status.RETIRED, 100, 100);
            assertThat(g.isActivatable()).isTrue();
            assertThat(g.isRetrievable()).isFalse();
        }

        @Test
        @DisplayName("ACTIVE 可激活（幂等切换）且可检索")
        void activeIsActivatableAndRetrievable() {
            IndexGeneration g = gen(1, IndexGeneration.Status.ACTIVE, 100, 100);
            assertThat(g.isActivatable()).isTrue();
            assertThat(g.isRetrievable()).isTrue();
        }
    }

    @Nested
    @DisplayName("进度")
    class Progress {

        @Test
        @DisplayName("进度按已索引/总数计算")
        void computesPercent() {
            assertThat(gen(2, IndexGeneration.Status.BUILDING, 30, 100).progressPercent())
                    .isEqualTo(30);
            assertThat(gen(2, IndexGeneration.Status.BUILDING, 100, 100).progressPercent())
                    .isEqualTo(100);
        }

        @Test
        @DisplayName("总数为 0 时进度为 0（不除零）")
        void zeroTotalDoesNotDivideByZero() {
            assertThat(gen(2, IndexGeneration.Status.BUILDING, 0, 0).progressPercent()).isZero();
        }
    }

    @Nested
    @DisplayName("构造期不变式")
    class Invariants {

        @Test
        @DisplayName("代际号必须 >= 1")
        void generationMustBePositive() {
            assertThatThrownBy(() -> gen(0, IndexGeneration.Status.BUILDING, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("代际号");
        }

        @Test
        @DisplayName("已索引数不得超过总数（进度必须自洽）")
        void indexedCannotExceedTotal() {
            assertThatThrownBy(() -> gen(2, IndexGeneration.Status.BUILDING, 120, 100))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("进度不自洽");
        }

        @Test
        @DisplayName("嵌入模型不能为空")
        void embedModelRequired() {
            assertThatThrownBy(() -> new IndexGeneration(1, "  ", 1024,
                    IndexGeneration.Status.BUILDING, 0, 0, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("embedModel");
        }
    }
}
