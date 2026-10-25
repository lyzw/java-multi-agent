package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.time.Instant;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapResultContractDef;
import cn.studykid.multiagent.ports.persistence.CapResultContractPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 输出契约提供实现测试（多级 Agent v1.1 · FR-027 · MA-T16）。
 *
 * <p>⚠️ <b>「取不到」必须返回 {@code null} 而不是抛异常</b>：
 * 调用方据此 fail-closed 拒绝这次委派；抛异常则可能把整个 Run 带崩，
 * 而「取不到契约」是一个**可预期的业务事实**，不是系统故障。
 */
class JdbcResultContractProviderTest {

    private static final String TENANT = "tenant-1";
    private static final String CONTRACT = "answer-contract";
    private static final String SCHEMA = "{\"type\":\"object\"}";

    @Test
    @DisplayName("已发布的固定版本 → 返回契约定义")
    void publishedContractIsResolved() {
        CapResultContractPort port = portWith(published(CONTRACT, 1));

        String schema = new JdbcResultContractProvider(port)
                .schemaFor(TENANT, CONTRACT + "@1");

        assertThat(schema).isEqualTo(SCHEMA);
    }

    @Test
    @DisplayName("版本不存在 → 取不到（null）")
    void missingVersionResolvesToNull() {
        CapResultContractPort port = portWith(published(CONTRACT, 1));

        assertThat(new JdbcResultContractProvider(port).schemaFor(TENANT, CONTRACT + "@2"))
                .isNull();
    }

    @Test
    @DisplayName("契约未发布 → 取不到（草稿不可被引用）")
    void unpublishedContractResolvesToNull() {
        CapResultContractPort port = portWith(draft(CONTRACT, 1));

        assertThat(new JdbcResultContractProvider(port).schemaFor(TENANT, CONTRACT + "@1"))
                .isNull();
    }

    @Test
    @DisplayName("漂移别名 latest → 取不到（禁止 latest）")
    void driftingAliasResolvesToNull() {
        CapResultContractPort port = portWith(published(CONTRACT, 1));

        assertThat(new JdbcResultContractProvider(port).schemaFor(TENANT, CONTRACT + "@latest"))
                .isNull();
    }

    @Test
    @DisplayName("引用格式非法 → 取不到")
    void malformedRefResolvesToNull() {
        CapResultContractPort port = portWith(published(CONTRACT, 1));

        assertThat(new JdbcResultContractProvider(port).schemaFor(TENANT, CONTRACT)).isNull();
        assertThat(new JdbcResultContractProvider(port).schemaFor(TENANT, "@1")).isNull();
    }

    @Test
    @DisplayName("仓储异常 → 取不到且不外抛（fail-closed 由调用方负责）")
    void repositoryFailureResolvesToNull() {
        CapResultContractPort broken = new CapResultContractPort() {
            @Override
            public boolean insert(@NonNull CapResultContractDef def) {
                return false;
            }

            @Override
            public @NonNull Optional<CapResultContractDef> find(@NonNull String tenantId,
                                                                 @NonNull String contractId,
                                                                 int version) {
                throw new IllegalStateException("模拟存储故障");
            }

            @Override
            public @NonNull Optional<CapResultContractDef> findLatestPublished(
                    @NonNull String tenantId, @NonNull String contractId) {
                throw new IllegalStateException("模拟存储故障");
            }

            @Override
            public @NonNull Optional<CapResultContractDef> findLatest(
                    @NonNull String tenantId, @NonNull String contractId) {
                throw new IllegalStateException("模拟存储故障");
            }
        };

        assertThat(new JdbcResultContractProvider(broken).schemaFor(TENANT, CONTRACT + "@1"))
                .isNull();
    }

    // ── 夹具 ──────────────────────────────────────────────────────

    private static CapResultContractDef published(String id, int version) {
        return new CapResultContractDef(TENANT, id, version, SCHEMA,
                "fp-" + id + "-" + version, 1, Instant.EPOCH, Instant.EPOCH);
    }

    private static CapResultContractDef draft(String id, int version) {
        return new CapResultContractDef(TENANT, id, version, SCHEMA,
                "fp-" + id + "-" + version, 1, Instant.EPOCH, null);
    }

    private static CapResultContractPort portWith(CapResultContractDef def) {
        return new CapResultContractPort() {
            @Override
            public boolean insert(@NonNull CapResultContractDef d) {
                return true;
            }

            @Override
            public @NonNull Optional<CapResultContractDef> find(@NonNull String tenantId,
                                                                 @NonNull String contractId,
                                                                 int version) {
                return TENANT.equals(tenantId) && def.contractId().equals(contractId)
                        && def.version() == version
                        ? Optional.of(def)
                        : Optional.empty();
            }

            @Override
            public @NonNull Optional<CapResultContractDef> findLatestPublished(
                    @NonNull String tenantId, @NonNull String contractId) {
                return Optional.empty();
            }

            @Override
            public @NonNull Optional<CapResultContractDef> findLatest(
                    @NonNull String tenantId, @NonNull String contractId) {
                return Optional.empty();
            }
        };
    }
}
