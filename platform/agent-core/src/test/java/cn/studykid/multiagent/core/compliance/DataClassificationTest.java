package cn.studykid.multiagent.core.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P0-3 回归：{@link DataClassification#fromContent} 必须按内容推导分级，
 * 杜绝硬编码 SYNTHETIC 导致零保留闸门真实数据分支永不触发。
 */
class DataClassificationTest {

    @Test
    @DisplayName("无内容无声明 → SYNTHETIC（兼容 P2 合成模式）")
    void defaultsToSynthetic() {
        assertThat(DataClassification.fromContent(null, null)).isEqualTo(DataClassification.SYNTHETIC);
        assertThat(DataClassification.fromContent("解方程 x²-5x+6=0", null))
                .isEqualTo(DataClassification.SYNTHETIC);
    }

    @Test
    @DisplayName("内容含身份证号 → REAL_STUDENT（即便声明 SYNTHETIC 也不降级）")
    void identifyingContentWinsOverDeclaredSynthetic() {
        DataClassification c = DataClassification.fromContent(
                "学生 110101199003071234 的错题", DataClassification.SYNTHETIC);
        assertThat(c).isEqualTo(DataClassification.REAL_STUDENT);
        assertThat(c.containsRealStudentData()).isTrue();
    }

    @Test
    @DisplayName("内容含手机号 → REAL_STUDENT")
    void mobilePatternTriggersReal() {
        assertThat(DataClassification.fromContent("家长电话 13800138000", null))
                .isEqualTo(DataClassification.REAL_STUDENT);
    }

    @Test
    @DisplayName("显式声明 REAL_STUDENT（无识别信息）→ REAL_STUDENT")
    void declaredRealHonored() {
        assertThat(DataClassification.fromContent(
                "学生张三的数学作业", DataClassification.REAL_STUDENT))
                .isEqualTo(DataClassification.REAL_STUDENT);
    }

    @Test
    @DisplayName("声明 SYNTHETIC 且无识别信息 → SYNTHETIC")
    void declaredSyntheticHonored() {
        assertThat(DataClassification.fromContent(
                "解方程", DataClassification.SYNTHETIC))
                .isEqualTo(DataClassification.SYNTHETIC);
    }
}
