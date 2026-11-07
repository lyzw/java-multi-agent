package cn.studykid.multiagent.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.modelcredential.ProviderCredentialPort;
import cn.studykid.multiagent.ports.modelcredential.ProviderCredentialPort.ProviderCredentialView;
import cn.studykid.multiagent.ports.security.AccessDeniedException;
import cn.studykid.multiagent.ports.security.AuthorizationService;

@DisplayName("AdminModelCredentialController · 密钥带外管理端点")
class AdminModelCredentialControllerTest {

    /** 内存密钥桩。 */
    static final class StubCredentialPort implements ProviderCredentialPort {
        final Map<String, String> store = new java.util.LinkedHashMap<>();

        @Override
        public Optional<String> resolveApiKey(String provider) {
            return Optional.ofNullable(store.get(provider));
        }

        @Override
        public void save(String provider, String apiKey) {
            store.put(provider, apiKey);
        }

        @Override
        public void delete(String provider) {
            store.remove(provider);
        }

        @Override
        public List<ProviderCredentialView> listMasked() {
            return store.entrySet().stream()
                    .map(e -> new ProviderCredentialView(e.getKey(),
                            ProviderCredentialView.mask(e.getValue()), null))
                    .toList();
        }

        @Override
        public Optional<ProviderCredentialView> masked(String provider) {
            return Optional.ofNullable(store.get(provider))
                    .map(k -> new ProviderCredentialView(provider,
                            ProviderCredentialView.mask(k), null));
        }
    }

    /** 授权桩：allow=false 时默认 requireAuthorized 抛 AccessDeniedException。 */
    static final class StubAuthz implements AuthorizationService {
        final boolean allow;

        StubAuthz(boolean allow) {
            this.allow = allow;
        }

        @Override
        public boolean isAuthorized(TenantContext context, String action, String resourceId) {
            return allow;
        }

        @Override
        public long currentAuthzVersion(String tenantId, String principalId) {
            return 0;
        }

        @Override
        public Set<String> rolesOf(TenantContext context) {
            return context.roles();
        }
    }

    record StubPrincipal(TenantContext ctx) implements CurrentPrincipal {
        @Override
        public TenantContext context() {
            return ctx;
        }

        @Override
        public String principalId() {
            return ctx.principalId();
        }

        @Override
        public String providerId() {
            return "prov";
        }

        @Override
        public String correlationId() {
            return "corr";
        }
    }

    private static final TenantContext ADMIN = new TenantContext("t-a", "u-admin",
            Set.of("PLATFORM_ADMIN"), 1);
    private static final TenantContext USER = new TenantContext("t-a", "u-user",
            Set.of("AGENT_MANAGE"), 1);

    private AdminModelCredentialController controller(boolean allow, StubCredentialPort port) {
        return new AdminModelCredentialController(port, new StubAuthz(allow),
                new StubPrincipal(allow ? ADMIN : USER));
    }

    @Test
    @DisplayName("✅ 管理员可列出脱敏密钥视图（无明文泄漏）")
    void listMasked() {
        StubCredentialPort port = new StubCredentialPort();
        port.save("openai", "sk-super-secret-1234");
        var resp = controller(true, port).list();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<ProviderCredentialView> body = resp.getBody();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).provider()).isEqualTo("openai");
        // 脱敏：末 4 位可见，但绝不等于明文
        assertThat(body.get(0).maskedKey()).startsWith("****").endsWith("1234");
        assertThat(body.get(0).maskedKey()).doesNotContain("super-secret");
    }

    @Test
    @DisplayName("✅ 管理员写入密钥 → 返回脱敏视图，明文不回显")
    void saveReturnsMasked() {
        StubCredentialPort port = new StubCredentialPort();
        var resp = controller(true, port).save(
                new AdminModelCredentialController.ProviderCredentialRequest(
                        "deepseek", "sk-deepseek-9876"));
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody().maskedKey()).isEqualTo("****9876");
        // 端口内落的是明文（运行时用），但响应绝不带回显
        assertThat(port.store.get("deepseek")).isEqualTo("sk-deepseek-9876");
        assertThat(resp.getBody().maskedKey()).doesNotContain("deepseek-9876");
    }

    @Test
    @DisplayName("✅ 管理员删除密钥 → 调用端口 delete")
    void deleteCallsPort() {
        StubCredentialPort port = new StubCredentialPort();
        port.save("minimax", "sk-minimax-0000");
        var resp = controller(true, port).delete("minimax");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(port.store).doesNotContainKey("minimax");
    }

    @Test
    @DisplayName("✅ 非管理员 → 403 AccessDenied")
    void unauthorized() {
        StubCredentialPort port = new StubCredentialPort();
        assertThatThrownBy(() -> controller(false, port).list())
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller(false, port).save(
                new AdminModelCredentialController.ProviderCredentialRequest("x", "y")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("⚠️ 写入缺少 apiKey → 400")
    void saveMissingKey() {
        StubCredentialPort port = new StubCredentialPort();
        assertThatThrownBy(() -> controller(true, port).save(
                new AdminModelCredentialController.ProviderCredentialRequest("x", "  ")))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }
}
