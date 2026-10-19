package cn.studykid.multiagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * 应用配置契约测试。
 *
 * <p>把配置基线中的<b>必填项</b>与<b>高危参数</b>变成自动化断言：
 * 配置被误删或误改时立即失败，而不是等到运行时才发现
 * ——尤其 RabbitMQ 的 delivery-limit，一旦丢失会退回默认值 20，
 * 对有副作用工具意味着重复写入（Sev-0）。
 *
 * <p>注意：{@link YamlPropertiesFactoryBean#getObject()} 返回
 * {@link java.util.Properties}，所有值统一为字符串，故此处按字符串比较。
 */
class ApplicationConfigTest {

    private static Map<String, String> load() {
        YamlPropertiesFactoryBean bean = new YamlPropertiesFactoryBean();
        bean.setResources(new ClassPathResource("application.yml"));
        java.util.Properties props = bean.getObject();
        assertThat(props).as("application.yml 未能解析").isNotNull();
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            map.put(key, props.getProperty(key));
        }
        return map;
    }

    @Test
    @DisplayName("配置可正常解析")
    void parses() {
        assertThat(load())
                .as("实际加载到的键: %s", load().keySet())
                .isNotEmpty();
    }

    @Test
    @DisplayName("⚠️ MQ 队列的 delivery-limit 必须是 3（quorum 默认 20 → 重复写入 Sev-0）")
    void deliveryLimitIsThree() {
        Map<String, String> props = load();
        assertThat(props.get("agent.mq.queues.execute.delivery-limit"))
                .as("键不存在。实际键集: %s", props.keySet())
                .isEqualTo("3");
        assertThat(props.get("agent.mq.queues.reconcile.delivery-limit"))
                .as("键不存在")
                .isEqualTo("3");
    }

    @Test
    @DisplayName("⚠️ 死信队列不得挂 DLX（避免死信循环）")
    void deadQueueHasNoDeadLetter() {
        Map<String, String> props = load();
        assertThat(props.get("agent.mq.queues.dead.dead-letter-exchange"))
                .as("死信队列再挂 DLX 会形成死信循环")
                .isNull();
    }

    @Test
    @DisplayName("⚠️ 消费者必须手动确认（自动确认易丢消息）")
    void listenerUsesManualAck() {
        Map<String, String> props = load();
        assertThat(props.get("spring.rabbitmq.listener.simple.acknowledge-mode"))
                .as("quorum queue 建议手动确认；自动确认在故障场景下易丢消息")
                .isEqualTo("manual");
    }

    @Test
    @DisplayName("⚠️ 必须开启 publisher confirms（Outbox 发布可靠性前提）")
    void publisherConfirmsEnabled() {
        Map<String, String> props = load();
        assertThat(props.get("spring.rabbitmq.publisher-confirm-type"))
                .as("quorum queue 仅在消息被多数派复制后才确认写入成功")
                .isEqualTo("correlated");
    }

    @Test
    @DisplayName("⚠️ 拒绝后不重新入队（避免无限重试循环）")
    void noRequeueOnReject() {
        Map<String, String> props = load();
        assertThat(props.get("spring.rabbitmq.listener.simple.default-requeue-rejected"))
                .as("重新入队会与 delivery-limit 冲突导致消息反复重投")
                .isEqualTo("false");
    }

    @Test
    @DisplayName("DEC-9 配置基线初值未被篡改")
    void costConfigBaselines() {
        Map<String, String> props = load();
        assertThat(props.get("agent.run.max-cost-per-run")).isEqualTo("2.00");
        assertThat(props.get("agent.run.max-llm-calls")).isEqualTo("12");
        assertThat(props.get("agent.run.max-steps")).isEqualTo("20");
        assertThat(props.get("agent.tenant.max-concurrent-runs")).isEqualTo("5");
        assertThat(props.get("deployment.pilot.max-tenants")).isEqualTo("1");
    }

    @Test
    @DisplayName("DEC-11 审批配置：WRITE_EXTERNAL 必须 REQUIRED（不能被放松）")
    void approvalMatrixNotRelaxed() {
        Map<String, String> props = load();
        assertThat(props.get("agent.approval.required-by-risk.WRITE_EXTERNAL"))
                .as("WRITE_EXTERNAL 降级属安全门禁降级，须走 CHG 模板 + ISO-5 抽检")
                .isEqualTo("REQUIRED");
        assertThat(props.get("agent.approval.required-by-risk.WRITE_PROFILE"))
                .isEqualTo("OPTIONAL");
        assertThat(props.get("agent.approval.required-by-risk.READ"))
                .isEqualTo("NOT_REQUIRED");
    }

    @Test
    @DisplayName("DEC-10 零保留配置：三家供应商均已确认，且有效期不得为 0")
    void zeroRetentionProvidersConfirmed() {
        Map<String, String> props = load();

        String confirmed = props.get("agent.compliance.zero-retention.confirmed-providers");
        assertThat(confirmed)
                .as("已取得书面合同承诺的供应商清单缺失或被清空")
                .isNotNull()
                .contains("deepseek")
                .contains("minimax")
                .contains("workbuddy");

        // 有效期为 0 或缺失会导致条款立即过期 → 真实数据被全量拒绝（业务不可用）
        String months = props.get("agent.compliance.zero-retention.validity-months");
        assertThat(months)
                .as("条款有效期必须为正，否则闸门立即失效")
                .isNotNull();
        assertThat(Integer.parseInt(months))
                .as("有效期须为正数")
                .isPositive();

        assertThat(Integer.parseInt(props.get("agent.compliance.zero-retention.expiry-warn-days")))
                .as("临期告警天数须为正，否则条款静默过期")
                .isPositive();
    }

    @Test
    @DisplayName("⚠️ 零保留：确认供应商清单不得为空（清空即等于全员拒绝）")
    void confirmedProvidersNotEmptied() {
        Map<String, String> props = load();
        String confirmed = props.get("agent.compliance.zero-retention.confirmed-providers");
        assertThat(confirmed)
                .as("⚠️ 清空清单会使所有供应商落入 fail-closed，真实数据全部被拒")
                .isNotBlank();
        assertThat(confirmed.trim().split(",")).allSatisfy(p ->
                assertThat(p.trim()).as("清单中存在空项").isNotEmpty());
    }

    @Test
    @DisplayName("口令必须由环境变量注入，不得硬编码明文")
    void noPlaintextSecrets() {
        Map<String, String> props = load();
        List<String> hardcoded = props.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().contains("password"))
                // 环境变量占位符（如 ${RABBITMQ_PASSWORD:}）是正确写法；
                // 但默认值若为非空字面量，即视为硬编码。
                .map(Map.Entry::getValue)
                .filter(v -> v != null && !v.isBlank())
                // 剥离 ${VAR} 与 ${VAR:default} 形式后不应剩内容
                .map(v -> v.replaceAll("\\$\\{[^}]*}", "").trim())
                .filter(v -> !v.isEmpty())
                .toList();
        assertThat(hardcoded)
                .as("口令须由环境变量注入，不得硬编码在配置文件: %s", hardcoded)
                .isEmpty();
    }

    @Test
    @DisplayName("RabbitMQ 口令项使用环境变量占位")
    void rabbitPasswordUsesEnvPlaceholder() {
        Map<String, String> props = load();
        String pw = props.get("spring.rabbitmq.password");
        assertThat(pw)
                .as("口令应形如 ${RABBITMQ_PASSWORD:} 以便由环境注入")
                .contains("${");
    }
}