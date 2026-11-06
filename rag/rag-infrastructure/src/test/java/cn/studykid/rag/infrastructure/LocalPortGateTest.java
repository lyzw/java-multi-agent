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
        // RAG-D26 阶段 3.1：ingestionPipeline 增加 premise 配置参数，
        // 默认值（enabled=false）下仍走旧内置切分——行为与迁移前一致。
        assertNotNull(config.ingestionPipeline(config.documentParserPort(),
                false, "", 512, 0.15));
        assertNotNull(config.groundingService(config.entailmentPort(),
                config.groundingPolicy()));
    }

    @Test
    void premiseDefaultsToLegacyMode() {
        LocalRagPortsConfig config = new LocalRagPortsConfig(true);
        var pipeline = config.ingestionPipeline(config.documentParserPort(),
                false, "", 512, 0.15);
        org.junit.jupiter.api.Assertions.assertFalse(pipeline.premiseModeEnabled(),
                "默认必须关闭 premise 切分（阶段 3 灰度未开启）");
    }

    @Test
    void premiseEnabledAssemblesHeadingChunker() {
        LocalRagPortsConfig config = new LocalRagPortsConfig(true);
        var pipeline = config.ingestionPipeline(config.documentParserPort(),
                true, "heading", 512, 0.15);
        assertTrue(pipeline.premiseModeEnabled(), "显式开启后应注入 premise 切分器");
    }

    @Test
    void premiseRejectsUnknownPolicyAtAssemblyTime() {
        LocalRagPortsConfig config = new LocalRagPortsConfig(true);
        // ⚠️ fail-closed：未知策略值在装配期抛出（应用拒绝启动），不留到运行期。
        assertThrows(IllegalArgumentException.class, () ->
                        config.ingestionPipeline(config.documentParserPort(),
                                true, "not-a-policy", 512, 0.15),
                "未知切分策略必须在装配期失败");
    }
}
