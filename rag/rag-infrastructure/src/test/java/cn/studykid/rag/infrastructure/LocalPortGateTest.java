package cn.studykid.rag.infrastructure;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 占位端口 fail-closed 闸门测试（评审 P0-6）。
 *
 * <p>钉死语义：{@code rag.local-ports.enabled} 缺省（false）时，
 * 三个占位端口的装配必须<b>抛异常</b>——应用拒绝启动，绝不允许静默把
 * 启发式解析/哈希嵌入/占位带进运行上下文；显式 true 时才放行。
 */
class LocalPortGateTest {

    @Test
    void gateThrowsWhenNotExplicitlyEnabled() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> LocalPortGate.requireEnabled(false, "EmbeddingPort → 占位"));
        assertTrue(ex.getMessage().contains("fail-closed"),
                "错误信息必须点明 fail-closed 语义");
        assertTrue(ex.getMessage().contains("rag.local-ports.enabled"),
                "错误信息必须给出显式启用方式");
        assertTrue(ex.getMessage().contains("不得用于生产"),
                "错误信息必须警示生产风险");
    }

    @Test
    void gateAllowsExplicitOptIn() {
        // 不抛异常即通过
        LocalPortGate.requireEnabled(true, "EmbeddingPort → 占位");
    }

    @Test
    void placeholderBeansRefuseToAssembleByDefault() {
        LocalRagPortsConfig config = new LocalRagPortsConfig(false);
        assertThrows(IllegalStateException.class, config::documentParserPort,
                "解析占位默认必须拒绝装配");
        assertThrows(IllegalStateException.class, config::entailmentPort,
                "蕴含占位默认必须拒绝装配");
    }

    @Test
    void explicitOptInAssemblesPlaceholderBeans() {
        LocalRagPortsConfig config = new LocalRagPortsConfig(true);
        assertNotNull(config.documentParserPort());
        assertNotNull(config.entailmentPort());
        assertNotNull(config.groundingPolicy());
        assertNotNull(config.ingestionPipeline(config.documentParserPort()));
        assertNotNull(config.groundingService(config.entailmentPort(),
                config.groundingPolicy()));
    }
}
