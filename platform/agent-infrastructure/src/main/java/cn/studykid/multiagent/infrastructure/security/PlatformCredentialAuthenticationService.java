package cn.studykid.multiagent.infrastructure.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository;

/**
 * 平台登录认证（重规划 §7）。
 *
 * <p>校验平台用户口令 → 构造可信 {@link PlatformPrincipal} → 签发平台令牌。
 * 与租户侧 {@link AuthenticationService} 独立：只查 {@code platform} schema，
 * 不触碰租户用户表，口令哈希算法与租户侧一致（PBKDF2-HMAC-SHA256）。
 *
 * <p><b>防枚举纪律（与租户侧同构）</b>：
 * <ol>
 *   <li><b>统一失败</b> —— 用户不存在 / 口令错 / 状态禁用 返回完全相同的异常，
 *       不区分原因，避免账号枚举。</li>
 *   <li><b>常量耗时</b> —— 用户不存在时也跑一次哈希校验（假哈希），
 *       使「不存在」与「口令错」的响应时间不可区分。</li>
 *   <li><b>不回显</b> —— 异常消息固定为 {@code invalid_credentials}，不含任何内部细节。</li>
 * </ol>
 */
public class PlatformCredentialAuthenticationService {

    private static final String DUMMY_HASH =
            PrincipalRepository.PasswordHasher.hash("dummy-platform-timing-equalization");

    private final PlatformPrincipalRepository users;
    private final PlatformJwtTokenService tokens;
    private final Clock clock;

    public PlatformCredentialAuthenticationService(PlatformPrincipalRepository users,
                                                   PlatformJwtTokenService tokens,
                                                   Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.clock = clock;
    }

    /**
     * 用户名 + 口令登录。
     *
     * @return 平台令牌与可信主体
     * @throws PlatformLoginException 任意失败（原因对客户不可辨）
     */
    public @NonNull LoginResult login(@NonNull String username, @NonNull String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new PlatformLoginException();
        }

        PlatformPrincipalRepository.PlatformUserRecord record =
                users.findByUsername(username).orElse(null);

        // ⚠️ 常量耗时：无论用户是否存在都跑一次校验（假哈希或真实哈希）
        String hash = record == null ? DUMMY_HASH : record.passwordHash();
        boolean matched = PrincipalRepository.PasswordHasher.verify(password, hash);

        // 用户不存在或口令错：统一失败（不区分）
        if (record == null || !matched) {
            throw new PlatformLoginException();
        }
        // 状态禁用/锁定：同样统一失败（不暴露「用户存在但被锁」）
        if (!"ACTIVE".equals(record.status())) {
            throw new PlatformLoginException();
        }

        Set<String> roles = users.findRoles(record.id());
        boolean mfaVerified = !record.mfaRequired();

        PlatformPrincipal principal = new PlatformPrincipal(
                record.id(), roles, record.authzVersion(), mfaVerified);

        String token = tokens.issue(principal, clock.instant());
        return new LoginResult(token, principal);
    }

    /** 登录结果。 */
    public record LoginResult(@NonNull String token, @NonNull PlatformPrincipal principal) {
    }

    /** 登录失败（原因对客户不可辨）。 */
    public static class PlatformLoginException extends RuntimeException {
        public PlatformLoginException() {
            super("invalid_credentials");
        }
    }
}
