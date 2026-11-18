package cn.studykid.multiagent.ports.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 第 3 刀：平台审计扩 capability / channel 两列的兼容性守卫。
 *
 * <p>反向验证锚点：删掉旧 12 参构造器 → 全部既有审计调用点编译失败；
 * 把 14 参顺序写反 → round-trip 断言红。
 */
class PlatformAuditEventCapabilityTest {

    private static final Instant NOW = Instant.parse("2026-11-18T00:00:00Z");

    @Test
    @DisplayName("✅ 旧 12 参构造器仍可用，capability/channel 默认 null（既有调用点零回归）")
    void legacyConstructorDefaultsNewFieldsToNull() {
        PlatformAuditPort.PlatformAuditEvent event = new PlatformAuditPort.PlatformAuditEvent(
                UUID.randomUUID(), "actor-1", "PLATFORM_USER", "platform.user.manage",
                "user", null, "ALLOW", "jti-1", null, null, NOW, "PLATFORM");

        assertThat(event.capability()).isNull();
        assertThat(event.channel()).isNull();
        assertThat(event.ticketScope()).isEqualTo("PLATFORM");
    }

    @Test
    @DisplayName("✅ 14 参构造器：capability/channel 按位置写入，不串行")
    void fullConstructorKeepsFieldOrder() {
        PlatformAuditPort.PlatformAuditEvent event = new PlatformAuditPort.PlatformAuditEvent(
                UUID.randomUUID(), "actor-1", "PLATFORM_USER", "platform.rag.governance_ticket",
                "rag_governance_ticket", null, "ALLOW", "jti-2", null, null, NOW, "PLATFORM",
                "rag.corpus.ingest", "PLATFORM_DIRECT");

        assertThat(event.capability()).isEqualTo("rag.corpus.ingest");
        assertThat(event.channel()).isEqualTo("PLATFORM_DIRECT");
        assertThat(event.ticketScope()).isEqualTo("PLATFORM");
        assertThat(event.decision()).isEqualTo("ALLOW");
    }
}
