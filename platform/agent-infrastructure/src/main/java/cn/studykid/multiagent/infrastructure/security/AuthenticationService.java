package cn.studykid.multiagent.infrastructure.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.tenant.TenantContext;

/**
 * 认证服务（D-001）：口令 → 令牌，以及令牌 → 可信上下文。
 *
 * <p><b>设计原则（决定于「客户端不可信」）</b>：
 * <ol>
 *   <li><b>不区分失败原因</b> —— 「账号不存在」与「密码错误」返回完全相同的结果，
 *       否则可被用于枚举账号。</li>
 *   <li><b>恒定耗时</b> —— 两条路径都执行一次 PBKDF2。</li>
 *   <li><b>失败计数持久化</b> —— 内存计数重启即失效，攻击者只需等重启。</li>
 * </ol>
 */
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private final PrincipalRepository principals;
    private final JwtTokenService tokens;
    private final Duration lockDuration;
    private final int maxFailedAttempts;
    private final Set<String> knownRoles;

    public AuthenticationService(PrincipalRepository principals,
                                 JwtTokenService tokens,
                                 Duration lockDuration,
                                 int maxFailedAttempts,
                                 Set<String> knownRoles) {
        this.principals = principals;
        this.tokens = tokens;
        if (lockDuration == null || lockDuration.isNegative() || lockDuration.isZero()) {
            throw new IllegalArgumentException("锁定时长必须为正");
        }
        if (maxFailedAttempts <= 0) {
            throw new IllegalArgumentException("最大失败次数必须为正");
        }
        if (knownRoles == null || knownRoles.isEmpty()) {
            // ⚠️ fail-closed：角色白名单为空会让所有令牌角色被忽略，
            //    导致全站403。这属于配置错误，必须在启动时暴露而非运行期。
            throw new IllegalArgumentException("角色白名单不得为空");
        }
        this.lockDuration = lockDuration;
        this.maxFailedAttempts = maxFailedAttempts;
        this.knownRoles = Set.copyOf(knownRoles);
    }

    /** 认证结果。仅两种状态，<b>不区分失败原因</b>。 */
    public record AuthResult(boolean success, String token, String principalId, String tenantId,
                             Set<String> roles) {

        public static AuthResult success(String token, String principalId, String tenantId,
                                        Set<String> roles) {
            return new AuthResult(true, token, principalId, tenantId,
                    roles == null ? Set.of() : Set.copyOf(roles));
        }

        /** 统一失败结果：消息与耗时均不可区分。 */
        public static AuthResult failure() {
            return new AuthResult(false, null, null, null, Set.of());
        }
    }

    /**
     * 口令登录。
     *
     * @param tenantId 租户（由调用方从可信来源确定，<b>不接受客户端直接传参</b>）
     */
    public AuthResult authenticate(String tenantId, String loginName,
                                   String rawPassword, Instant now) {
        if (tenantId == null || loginName == null || rawPassword == null) {
            return AuthResult.failure();
        }

        Optional<PrincipalRepository.PrincipalRecord> found =
                principals.findByLogin(tenantId, loginName);
        PrincipalRepository.PrincipalRecord record = found.orElse(null);

        // ⚠️ 无条件校验：record 为 null 时走 dummy 哈希路径，耗时一致
        boolean passwordOk = principals.verifyPassword(record, rawPassword, now);

        if (record == null || !passwordOk) {
            if (record != null) {
                principals.recordFailure(record, now, maxFailedAttempts, lockDuration);
            }
            return AuthResult.failure();
        }

        if (!"ACTIVE".equals(record.status()) && !"LOCKED".equals(record.status())) {
            // DISABLED：账号被管理员停用。与密码错误同样返回统一失败，
            // 但<b>不计入失败次数</b> —— 否则停用状态会被锁定计数掩盖。
            return AuthResult.failure();
        }

        principals.recordSuccess(record, now);

        Set<String> roles = rolesOf(record);
        TenantContext context = new TenantContext(
                record.tenantId(), record.principalId(), roles, record.authzVersion());

        String token = tokens.issue(context, now);
        log.info("登录成功: tenant={}, principal={}", record.tenantId(), record.principalId());
        // ⚠️ 回带角色（B1：登录返角色）：角色来自库中登记值（rolesOf），
        //    与 JWT 声明同源但此处直接给前端，免去一次 /api/auth/roles 往返。
        //    前端仅用于**导航显隐**（非安全边界），安全判定仍以执行时重授权为准。
        return AuthResult.success(token, record.principalId(), record.tenantId(), roles);
    }

    /**
     * 校验访问令牌并构造可信上下文。
     *
     * <p>⚠️ 角色取交集：令牌声明的角色必须在<b>库中登记</b>才生效。
     * 否则任何能拿到签发能力的一方都能签发带 {@code TENANT_ADMIN} 的令牌
     * <b>自封管理员</b>。
     */
    public TenantContext authenticateToken(String token, Instant now) {
        return tokens.verify(token, now, knownRoles);
    }

    public JwtTokenService tokenService() {
        return tokens;
    }

    /**
     * 账号的角色集合——<b>以数据库为准</b>。
     *
     * <p>⚠️ JWT 里的 roles 只是<b>声明</b>，实际生效的角色来自这里。
     * 若改为信任 JWT 声明，攻击者自签一个带 {@code TENANT_ADMIN}
     * 的令牌即可提权 —— 因为签发能力一旦泄露就等于完全失守。
     */
    private Set<String> rolesOf(PrincipalRepository.PrincipalRecord record) {
        return principals.findRoles(record.tenantId(), record.principalId());
    }
}