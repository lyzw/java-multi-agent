package cn.studykid.multiagent.api.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * 请求体未知字段拒收测试（10 号文档 §8 · G-6）。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：{@code CreateRunRequest} 的 javadoc 早就承诺
 * 「传 {@code tenantId} 会被拒」，但实现里只有一个 {@code boolean} 常量 ——
 * 承诺与实现不一致本身就是缺陷，而且这是全链路唯一一个
 * <b>身份字段可从请求体注入</b>的敞口。
 *
 * <p>⚠️ 测试刻意走 <b>Jackson 绑定</b> 而不是直接 {@code new}：
 * 拒收发生在绑定阶段，直接构造根本不经过它（只测构造函数等于没测这条约束）。
 * 实测 Jackson 3 的 {@code @JsonIgnoreProperties(ignoreUnknown = false)}
 * 在默认（{@code FAIL_ON_UNKNOWN_PROPERTIES} 关闭）下<b>不会</b>报错 ——
 * 若哪天有人把 {@code fromJson} 换回注解方案，本测试会立刻变红。
 */
class CreateRunRequestUnknownFieldTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String VALID_BODY =
            "{\"idempotencyKey\":\"idem-1\",\"agentId\":\"cs-router\","
                    + "\"input\":{\"question\":\"q\"}}";

    @Test
    @DisplayName("✅ 只含已声明字段的请求体 → 正常绑定")
    void declaredFieldsBind() {
        var request = JSON.readValue(VALID_BODY, CreateRunRequest.class);

        assertThat(request.agentId()).isEqualTo("cs-router");
        assertThat(request.input()).containsEntry("question", "q");
    }

    @Test
    @DisplayName("🔒 请求体带 tenantId → 拒绝（身份不得由客户端指定）")
    void tenantIdInBodyIsRejected() {
        String body = "{\"idempotencyKey\":\"idem-1\",\"agentId\":\"cs-router\","
                + "\"input\":{\"question\":\"q\"},\"tenantId\":\"victim-tenant\"}";

        assertThatThrownBy(() -> JSON.readValue(body, CreateRunRequest.class))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("不被接受的字段")
                .hasMessageContaining("tenantId");
    }

    @Test
    @DisplayName("🔒 请求体带任意未知字段 → 拒绝（而非静默丢弃）")
    void anyUnknownFieldIsRejected() {
        String body = "{\"idempotencyKey\":\"idem-1\",\"agentId\":\"cs-router\","
                + "\"input\":{\"question\":\"q\"},\"maxDelegationDepth\":99}";

        assertThatThrownBy(() -> JSON.readValue(body, CreateRunRequest.class))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("maxDelegationDepth");
    }

    @Test
    @DisplayName("✅ 全部可选字段齐备 → 拒收逻辑不得误伤声明过的字段")
    void allDeclaredOptionalFieldsBind() {
        String body = "{\"idempotencyKey\":\"idem-1\",\"agentId\":\"cs-router\","
                + "\"input\":{\"question\":\"q\"},\"requestedBy\":\"u-1\","
                + "\"timeoutMs\":30000,\"budgetAmount\":2.50}";

        var request = JSON.readValue(body, CreateRunRequest.class);

        assertThat(request.requestedBy()).isEqualTo("u-1");
        assertThat(request.timeoutMs()).isEqualTo(30_000L);
        assertThat(request.budgetAmount()).isEqualByComparingTo("2.50");
    }

    @Test
    @DisplayName("✅ 权威清单与记录组件一一对应（新增字段必须同步清单）")
    void knownFieldsMatchRecordComponents() {
        var components = java.util.Arrays.stream(CreateRunRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .sorted()
                .toList();

        assertThat(CreateRunRequest.KNOWN_FIELDS.stream().sorted().toList()).isEqualTo(components);
    }
}
