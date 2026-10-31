package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.AuthzUnavailableException;
import cn.studykid.multiagent.core.cap.AuthzVersion;
import cn.studykid.multiagent.core.monitoring.AuthzWatermark;
import cn.studykid.multiagent.ports.persistence.AuthzVersionPort;

/**
 * P0-1b 回归测试：<b>授权版本记录必须真的有生产写入方</b>。
 *
 * <p><b>缺陷背景</b>：{@code AuthzVersionPort.initialize} 此前<b>零生产调用方</b>，
 * 而 {@code require} 在无行时 fail-closed 抛 {@link AuthzUnavailableException}。
 * 两者叠加的后果：{@code cap_authz_version} 恒为空，
 * 一旦打开能力闭包强制校验，<b>每一次工具调用都会被拒绝</b> ——
 * 那不是「校验生效」，是「全拒绝」。
 *
 * <p><b>本类锁死两条不可让步的性质</b>：
 * <ol>
 *   <li><b>缺行即补建</b>：首次见到的主体必须留下一行，版本为初始值 1；</li>
 *   <li><b>既有行绝不降版</b>：已撤权主体（版本 ≥ 2）重新被读到时，
 *       <b>不得</b>被重置为 1 —— 否则撤权历史被抹掉，撤权静默失效。</li>
 * </ol>
 */
class AuthzVersionProvisioningTest {

    private static final String TENANT = "6f5d33fc-723e-4f64-beda-9e4435c72691";
    private static final String PRINCIPAL = "student-001";

    /** 内存版授权版本端口（行为与 {@code JdbcAuthzVersionRepository} 一致）。 */
    private static final class InMemory implements AuthzVersionPort {
        final Map<String, Long> versions = new HashMap<>();
        int initializeCalls;
        boolean findThrows;

        private String key(String tenantId, String principalId) {
            return tenantId + "/" + principalId;
        }

        @Override public Optional<AuthzVersion> find(String tenantId, String principalId) {
            if (findThrows) {
                throw new AuthzUnavailableException(tenantId, principalId,
                        "版本服务不可用", null);
            }
            Long v = versions.get(key(tenantId, principalId));
            return v == null ? Optional.empty()
                    : Optional.of(new AuthzVersion(tenantId, principalId, v, Instant.EPOCH));
        }

        @Override public AuthzVersion require(String tenantId, String principalId) {
            return find(tenantId, principalId).orElseThrow(() ->
                    new AuthzUnavailableException(tenantId, principalId,
                            "无授权版本记录", null));
        }

        @Override public AuthzVersion initialize(String tenantId, String principalId) {
            initializeCalls++;
            // ⚠️ 与 DB 的 ON CONFLICT DO NOTHING 同语义：既有行原样返回，绝不降版
            Long existing = versions.get(key(tenantId, principalId));
            if (existing != null) {
                return new AuthzVersion(tenantId, principalId, existing, Instant.EPOCH);
            }
            versions.put(key(tenantId, principalId), AuthzVersion.INITIAL_VERSION);
            return new AuthzVersion(tenantId, principalId, AuthzVersion.INITIAL_VERSION,
                    Instant.EPOCH);
        }

        @Override public long bump(String tenantId, String principalId) {
            long next = versions.getOrDefault(key(tenantId, principalId),
                    AuthzVersion.INITIAL_VERSION) + 1;
            versions.put(key(tenantId, principalId), next);
            return next;
        }

        @Override public AuthzWatermark watermark(String tenantId) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    @DisplayName("⚠️⚠️ 首次见到主体：补建一行，版本为初始值 1")
    void missingRecordIsProvisioned() {
        InMemory port = new InMemory();

        AuthzVersion version = port.currentOrInitialize(TENANT, PRINCIPAL);

        assertThat(version.authzVersion()).isEqualTo(AuthzVersion.INITIAL_VERSION);
        assertThat(port.versions).containsEntry(TENANT + "/" + PRINCIPAL, 1L);
        assertThat(port.initializeCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("⚠️⚠️⚠️ 已撤权主体（版本 ≥ 2）：重新读到时**不得**被重置为 1")
    void revokedVersionIsNeverDowngraded() {
        InMemory port = new InMemory();
        // 模拟「该主体已被撤权」：版本已递增到 5
        port.versions.put(TENANT + "/" + PRINCIPAL, 5L);

        AuthzVersion version = port.currentOrInitialize(TENANT, PRINCIPAL);

        assertThat(version.authzVersion())
                .as("撤权历史必须保留 —— 重置为 1 等于让撤权静默失效")
                .isEqualTo(5L);
        assertThat(port.initializeCalls)
                .as("命中既有行时不得再写（避免无谓的写与行锁）")
                .isZero();
    }

    @Test
    @DisplayName("⚠️ 存储不可用：fail-closed（不得因为「要补建」就放行）")
    void storageFailureIsFailClosed() {
        InMemory port = new InMemory();
        port.findThrows = true;

        assertThatThrownBy(() -> port.currentOrInitialize(TENANT, PRINCIPAL))
                .isInstanceOf(AuthzUnavailableException.class);
    }

    @Test
    @DisplayName("✅ 重复调用幂等：版本不递增，行不重复写")
    void repeatedProvisioningIsIdempotent() {
        InMemory port = new InMemory();

        port.currentOrInitialize(TENANT, PRINCIPAL);
        port.currentOrInitialize(TENANT, PRINCIPAL);
        port.currentOrInitialize(TENANT, PRINCIPAL);

        assertThat(port.versions).hasSize(1);
        assertThat(port.versions.get(TENANT + "/" + PRINCIPAL))
                .isEqualTo(AuthzVersion.INITIAL_VERSION);
    }
}
