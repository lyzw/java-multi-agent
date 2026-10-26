package cn.studykid.multiagent.api.platform;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.infrastructure.security.PlatformAuthorizationServiceImpl;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort.PlatformAuditEvent;
import cn.studykid.multiagent.ports.security.AccessDeniedException;
import cn.studykid.multiagent.ports.security.AuthorizationDecision;
import cn.studykid.multiagent.ports.security.PlatformAuthorizationService;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository;
import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository.DuplicateUserException;
import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository.PlatformUserRecord;
import cn.studykid.multiagent.ports.security.PlatformRoles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台用户 / 角色管理服务单元测试（重规划 §8 · P2）。
 *
 * <p>与 {@link PlatformTenantServiceTest} 同套路：内存假仓储 + 真实
 * {@link PlatformSecurity}（默认拒绝、矩阵驱动的
 * {@link PlatformAuthorizationServiceImpl}），验证
 * <b>授权在执行入口强制</b>、<b>自身保护</b>、<b>超管 SoD</b>、
 * <b>最后一个超管保护</b>、<b>密码策略</b> 与 <b>审计/authz_version 递增</b>。
 */
class PlatformUserManagementServiceTest {

    private static final String PWD_OK = "Str0ngPass!"; // 恰 10 位
    private static final String PWD_SHORT = "short9!";  // 恰 8 位

    private static PlatformPrincipal superAdmin(String id) {
        return new PlatformPrincipal(id, Set.of(PlatformRoles.SUPER_ADMIN), 1L, true);
    }

    private static PlatformPrincipal support() {
        return new PlatformPrincipal("u-support", Set.of(PlatformRoles.SUPPORT), 1L, true);
    }

    private static PlatformUserManagementService service(FakeUserRepository users,
                                                         FakeAuditPort audit) {
        return new PlatformUserManagementService(users,
                new PlatformSecurity(new PlatformAuthorizationServiceImpl()), audit);
    }

    /** 造一条用户记录（与仓储假实现保持一致的主键规则）。 */
    private static PlatformUserRecord record(String id, String username, String status,
                                             Set<String> roles) {
        return new PlatformUserRecord(id, username, null, null, status, "LOCAL",
                false, 1L, null, "hash", Instant.now(), Instant.now());
    }

    // ── 读路径 ─────────────────────────────────────────────

    /** 超管可建号、看自己、列表带回角色摘要；审计记 platform.user.create。 */
    @Test
    void superAdminCanCreateAndGetUser() {
        FakeUserRepository users = new FakeUserRepository();
        FakeAuditPort audit = new FakeAuditPort();
        PlatformUserManagementService svc = service(users, audit);

        PlatformUserManagementService.UserDetail created =
                svc.createUser(superAdmin("actor-1"), "alice", "a@x.com", "Alice",
                        PWD_OK, false, Set.of(PlatformRoles.TENANT_LIFECYCLE_ADMIN));

        assertEquals("alice", created.user().username());
        assertEquals("ACTIVE", created.user().status());
        assertEquals("LOCAL", created.user().authSource());
        assertEquals(Set.of(PlatformRoles.TENANT_LIFECYCLE_ADMIN), created.roles());
        // 响应最小化：哈希仍在记录里（仓储层），但入参/输出没有明文返回
        assertFalse(created.user().passwordHash().isEmpty());
        assertTrue(audit.has("platform.user.create", created.user().id()));

        // 查看自身信息不受限（自身保护只拦写动作）
        assertEquals("alice", svc.getUser(superAdmin("actor-1"), created.user().id())
                .user().username());

        // 列表：批量 join 角色集合（建号时授予的初始角色应在摘要里）
        List<PlatformUserManagementService.UserDetail> all =
                svc.listUsersWithRoles(superAdmin("actor-1"));
        assertEquals(1, all.size());
        assertEquals(Set.of(PlatformRoles.TENANT_LIFECYCLE_ADMIN), all.get(0).roles());
    }

    /** 无权限角色（SUPPORT 只读脱敏）建号被拒：默认拒绝 + AccessDeniedException。 */
    @Test
    void supportCannotCreateUser() {
        PlatformUserManagementService svc = service(new FakeUserRepository(), new FakeAuditPort());
        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> svc.createUser(support(), "bob", null, null, PWD_OK, false, null));
        assertTrue(ex.getMessage().contains("platform.user.manage"));

        // 读同样被拒
        assertThrows(AccessDeniedException.class,
                () -> svc.listUsersWithRoles(support()));
        assertThrows(AccessDeniedException.class, () -> svc.getUser(support(), "u-1"));
    }

    // ── 密码策略 ───────────────────────────────────────────

    /** 建号密码 <10 位拒绝（400），≥10 位通过。 */
    @Test
    void createUserRejectsShortPasswordButAcceptsTen() {
        PlatformUserManagementService svc = service(new FakeUserRepository(), new FakeAuditPort());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> svc.createUser(superAdmin("actor-1"), "bob", null, null,
                        PWD_SHORT, false, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        // 9 位也拒绝（边界以下）
        assertThrows(ResponseStatusException.class,
                () -> svc.createUser(superAdmin("actor-1"), "bob", null, null, "123456789", false, null));
        // 恰 10 位通过
        assertEquals("bob", svc.createUser(superAdmin("actor-1"), "bob", null, null,
                "1234567890", false, null).user().username());
    }

    /** 重置密码 <10 位拒绝（400）；先查不到用户则 404。 */
    @Test
    void resetPasswordPolicyAndNotFound() {
        FakeUserRepository users = new FakeUserRepository();
        FakeAuditPort audit = new FakeAuditPort();
        PlatformUserManagementService svc = service(users, audit);
        users.put(record("u-1", "alice", "ACTIVE", Set.of()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> svc.resetPassword(superAdmin("actor-1"), "u-1", PWD_SHORT));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());

        ResponseStatusException nf = assertThrows(ResponseStatusException.class,
                () -> svc.resetPassword(superAdmin("actor-1"), "no-such-user", PWD_OK));
        assertEquals(HttpStatus.NOT_FOUND, nf.getStatusCode());
    }

    // ── 自身保护 ───────────────────────────────────────────

    /** 对自己执行 变更 / 重置密码 / 授角色 / 撤角色 均应 403（含 profile-only 的 updateUser）。 */
    @Test
    void selfProtectionBlocksAllWriteActions() {
        FakeUserRepository users = new FakeUserRepository();
        FakeAuditPort audit = new FakeAuditPort();
        PlatformUserManagementService svc = service(users, audit);
        PlatformPrincipal self = superAdmin("actor-1");
        users.put(record("actor-1", "root", "ACTIVE", Set.of(PlatformRoles.SUPER_ADMIN)));

        // profile-only 变更也被拒：源码 requireNotSelf 不分「仅改 displayName」分支
        ResponseStatusException e1 = assertThrows(ResponseStatusException.class,
                () -> svc.updateUser(self, "actor-1", "NewName", null, null, null));
        assertEquals(HttpStatus.FORBIDDEN, e1.getStatusCode());

        ResponseStatusException e2 = assertThrows(ResponseStatusException.class,
                () -> svc.resetPassword(self, "actor-1", PWD_OK));
        assertEquals(HttpStatus.FORBIDDEN, e2.getStatusCode());

        ResponseStatusException e3 = assertThrows(ResponseStatusException.class,
                () -> svc.assignRole(self, "actor-1", PlatformRoles.SUPPORT));
        assertEquals(HttpStatus.FORBIDDEN, e3.getStatusCode());

        ResponseStatusException e4 = assertThrows(ResponseStatusException.class,
                () -> svc.revokeRole(self, "actor-1", PlatformRoles.SUPPORT));
        assertEquals(HttpStatus.FORBIDDEN, e4.getStatusCode());

        // 拒绝动作本身落审计（denied 线索）
        assertTrue(audit.has("platform.user.self_operation_denied:用户变更", "actor-1"));
        assertTrue(audit.has("platform.user.self_operation_denied:重置密码", "actor-1"));
        assertTrue(audit.has("platform.user.self_operation_denied:角色授予", "actor-1"));
        assertTrue(audit.has("platform.user.self_operation_denied:角色撤销", "actor-1"));
    }

    // ── updateUser ─────────────────────────────────────────

    /** 状态变化走 setStatus 并落审计；非法状态 400；profile 字段透传仓储。 */
    @Test
    void updateUserProfileAndStatus() {
        FakeUserRepository users = new FakeUserRepository();
        FakeAuditPort audit = new FakeAuditPort();
        PlatformUserManagementService svc = service(users, audit);
        users.put(record("u-1", "alice", "ACTIVE", Set.of()));

        PlatformUserManagementService.UserDetail updated = svc.updateUser(
                superAdmin("actor-1"), "u-1", "Alice改名", "new@x.com", "DISABLED", true);
        assertEquals("Alice改名", updated.user().displayName());
        assertTrue(updated.user().mfaRequired());
        assertEquals("DISABLED", users.map.get("u-1").status());
        assertTrue(audit.has("platform.user.update", "u-1"));

        // DISABLED -> ACTIVE 复活
        svc.updateUser(superAdmin("actor-1"), "u-1", null, null, "ACTIVE", null);
        assertEquals("ACTIVE", users.map.get("u-1").status());

        // 非法状态: LOCKED 由安全机制置位，接口不接受
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> svc.updateUser(superAdmin("actor-1"), "u-1", null, null, "LOCKED", null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());

        // 用户不存在 -> 404
        ResponseStatusException nf = assertThrows(ResponseStatusException.class,
                () -> svc.updateUser(superAdmin("actor-1"), "no-such", null, null, null, null));
        assertEquals(HttpStatus.NOT_FOUND, nf.getStatusCode());
    }

    // ── resetPassword ─────────────────────────────────────

    /** 合规新密码覆盖旧哈希并落审计 platform.user.password.reset。 */
    @Test
    void resetPasswordReplacesHashAndAudits() {
        FakeUserRepository users = new FakeUserRepository();
        FakeAuditPort audit = new FakeAuditPort();
        PlatformUserManagementService svc = service(users, audit);
        users.put(record("u-1", "alice", "ACTIVE", Set.of()));
        String oldHash = users.map.get("u-1").passwordHash();

        svc.resetPassword(superAdmin("actor-1"), "u-1", PWD_OK);

        String newHash = users.map.get("u-1").passwordHash();
        assertNotEquals(oldHash, newHash);
        assertTrue(newHash.startsWith("$pbkdf2-sha256$"));
        assertTrue(audit.has("platform.user.password.reset", "u-1"));
    }

    // ── 角色写 ─────────────────────────────────────────────

    /** 授角色：角色生效、authz_version 递增、审计 platform.role.assign:<role>。 */
    @Test
    void assignRoleGrantsBumpsVersionAndAudits() {
        FakeUserRepository users = new FakeUserRepository();
        FakeAuditPort audit = new FakeAuditPort();
        PlatformUserManagementService svc = service(users, audit);
        users.put(record("u-1", "alice", "ACTIVE", Set.of()));
        long v0 = users.map.get("u-1").authzVersion();

        Set<String> roles = svc.assignRole(superAdmin("actor-1"), "u-1",
                PlatformRoles.TENANT_LIFECYCLE_ADMIN);

        assertEquals(Set.of(PlatformRoles.TENANT_LIFECYCLE_ADMIN), roles);
        assertTrue(users.map.get("u-1").authzVersion() > v0);
        assertTrue(audit.has("platform.role.assign:" + PlatformRoles.TENANT_LIFECYCLE_ADMIN,
                "u-1"));

        // 用户不存在 -> 404
        ResponseStatusException nf = assertThrows(ResponseStatusException.class,
                () -> svc.assignRole(superAdmin("actor-1"), "no-such", PlatformRoles.SUPPORT));
        assertEquals(HttpStatus.NOT_FOUND, nf.getStatusCode());
    }

    /** 建号时未知初始角色拒绝；合法角色逐个另按 platform.role.assign 鉴权。 */
    @Test
    void createUserRejectsUnknownInitialRole() {
        PlatformUserManagementService svc = service(new FakeUserRepository(), new FakeAuditPort());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> svc.createUser(superAdmin("actor-1"), "bob", null, null, PWD_OK,
                        false, Set.of("NOT_A_PLATFORM_ROLE")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("未知平台角色"));
    }

    /** 仓储层「已存在幂等成功」：服务层不报错，仍递增版本/记审计（以源码如实断言）。 */
    @Test
    void assignRoleIsIdempotentAtRepositoryLevel() {
        FakeUserRepository users = new FakeUserRepository();
        FakeAuditPort audit = new FakeAuditPort();
        PlatformUserManagementService svc = service(users, audit);
        users.put(record("u-1", "alice", "ACTIVE", Set.of()));

        svc.assignRole(superAdmin("actor-1"), "u-1", PlatformRoles.SUPPORT);
        long vAfterFirst = users.map.get("u-1").authzVersion();
        // 第二次授予同一角色：仓储返回 false（原已存在），服务层视为幂等成功
        Set<String> roles = svc.assignRole(superAdmin("actor-1"), "u-1", PlatformRoles.SUPPORT);
        assertEquals(Set.of(PlatformRoles.SUPPORT), roles);
        // 源码行为：幂等重复授予仍走 bumpAuthzVersion + 审计（不因已存在而短路）
        assertTrue(users.map.get("u-1").authzVersion() > vAfterFirst);
        assertEquals(2, audit.events.stream()
                .filter(e -> "platform.role.assign:PLATFORM_SUPPORT".equals(e.action())).count());
    }

    /** 撤角色：绑定移除、递增版本、审计；未绑定的角色撤销为无操作（不 bump，仍记审计）。 */
    @Test
    void revokeRoleRemovesBumpsAndAudits() {
        FakeUserRepository users = new FakeUserRepository();
        FakeAuditPort audit = new FakeAuditPort();
        PlatformUserManagementService svc = service(users, audit);
        users.roles.put("u-1", new LinkedHashSet<>(Set.of(PlatformRoles.SUPPORT)));
        users.put(record("u-1", "alice", "ACTIVE", Set.of()));
        long v0 = users.map.get("u-1").authzVersion();

        Set<String> after = svc.revokeRole(superAdmin("actor-1"), "u-1", PlatformRoles.SUPPORT);
        assertEquals(Set.of(), after);
        assertTrue(users.map.get("u-1").authzVersion() > v0);
        assertTrue(audit.has("platform.role.revoke:PLATFORM_SUPPORT", "u-1"));

        // 撤销未绑定角色：removed=false，不递增版本
        long v1 = users.map.get("u-1").authzVersion();
        svc.revokeRole(superAdmin("actor-1"), "u-1", PlatformRoles.SUPPORT);
        assertEquals(v1, users.map.get("u-1").authzVersion());

        // 未知角色 / 未填角色 -> 400
        assertThrows(ResponseStatusException.class,
                () -> svc.revokeRole(superAdmin("actor-1"), "u-1", "NOPE"));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> svc.revokeRole(superAdmin("actor-1"), "u-1", null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    // ── 超管 SoD ───────────────────────────────────────────

    /** 超管本人可授予/撤销 PLATFORM_SUPER_ADMIN（SoD 允许），并递增版本。 */
    @Test
    void superAdminCanGrantAndRevokeSuperAdminRole() {
        FakeUserRepository users = new FakeUserRepository();
        FakeAuditPort audit = new FakeAuditPort();
        PlatformUserManagementService svc = service(users, audit);
        users.put(record("u-2", "bob", "ACTIVE", Set.of()));

        Set<String> after = svc.assignRole(superAdmin("actor-1"), "u-2",
                PlatformRoles.SUPER_ADMIN);
        assertEquals(Set.of(PlatformRoles.SUPER_ADMIN), after);
        assertTrue(audit.has("platform.role.assign:PLATFORM_SUPER_ADMIN", "u-2"));

        users.roles.put("u-2", new LinkedHashSet<>(Set.of(PlatformRoles.SUPER_ADMIN)));
        // 平台上还有另一个超管（actor-1 本人在仓储名单里）时，撤销成功
        users.map.put("actor-1", superAdminRecord("actor-1"));
        users.roles.put("actor-1", new LinkedHashSet<>(Set.of(PlatformRoles.SUPER_ADMIN)));
        Set<String> afterRevoke = svc.revokeRole(superAdmin("actor-1"), "u-2",
                PlatformRoles.SUPER_ADMIN);
        assertFalse(afterRevoke.contains(PlatformRoles.SUPER_ADMIN));
    }

    /** SoD：非超管但具 role.assign 能力的主体授予 PLATFORM_SUPER_ADMIN 被拒。 */
    @Test
    void sodBlocksNonSuperFromAssigningSuperAdmin() {
        FakeUserRepository users = new FakeUserRepository();
        // 模拟「拥有 role.assign 但非超管」的主体：矩阵无此普通角色，
        // 用可注入的授权服务复现 PlatformAuthorizationServiceImpl 的 SoD 分支判定路径
        PlatformAuthorizationService sodOnly = (p, action, t, tid) -> {
            boolean superAdminAssignment =
                    ("platform.role.assign".equals(action) || "platform.role.revoke".equals(action))
                            && "role".equalsIgnoreCase(t)
                            && PlatformRoles.SUPER_ADMIN.equals(tid);
            if (superAdminAssignment) {
                return AuthorizationDecision.deny("SOD_SUPER_ADMIN_PROTECTED", 1L);
            }
            return AuthorizationDecision.allow(1L);
        };
        PlatformUserManagementService svc = new PlatformUserManagementService(users,
                new PlatformSecurity(sodOnly), new FakeAuditPort());
        users.put(record("u-2", "bob", "ACTIVE", Set.of()));

        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> svc.assignRole(
                        new PlatformPrincipal("u-delegate", Set.of(), 1L, true),
                        "u-2", PlatformRoles.SUPER_ADMIN));
        assertTrue(ex.getMessage().contains("PLATFORM_SUPER_ADMIN")
                || ex.getMessage().contains("role"), "应报告被拒的授权目标");
    }

    // ── 最后一个超管保护 ───────────────────────────────────

    /** 撤掉平台上最后一个超管角色：源码抛 CONFLICT，且超管角色被回补（平台不失赋权主体）。 */
    @Test
    void revokingLastSuperAdminRoleIsRejectedAndRestored() {
        FakeUserRepository users = new FakeUserRepository();
        FakeAuditPort audit = new FakeAuditPort();
        PlatformUserManagementService svc = service(users, audit);
        // 仓库里唯一超管是 u-2（actor-1 自身不在仓库名单，countSuperAdmins 只数仓储）
        PlatformUserRecord target = record("u-2", "bob", "ACTIVE", Set.of());
        users.map.put("u-2", target);
        users.roles.put("u-2", new LinkedHashSet<>(Set.of(PlatformRoles.SUPER_ADMIN)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> svc.revokeRole(superAdmin("actor-1"), "u-2", PlatformRoles.SUPER_ADMIN));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        // 角色被回补（平台不至于失去赋权主体）
        assertTrue(users.findRoles("u-2").contains(PlatformRoles.SUPER_ADMIN));
    }

    /** 平台上还有第二个超管时撤销成功。 */
    @Test
    void revokingSuperAdminAllowedWhenAnotherRemains() {
        FakeUserRepository users = new FakeUserRepository();
        PlatformUserManagementService svc = service(users, new FakeAuditPort());
        PlatformUserRecord root = superAdminRecord("actor-1");
        users.map.put("actor-1", root);
        users.roles.put("actor-1", new LinkedHashSet<>(Set.of(PlatformRoles.SUPER_ADMIN)));
        users.map.put("u-2", record("u-2", "bob", "ACTIVE", Set.of()));
        users.roles.put("u-2", new LinkedHashSet<>(Set.of(PlatformRoles.SUPER_ADMIN)));

        Set<String> after = svc.revokeRole(superAdmin("actor-1"), "u-2",
                PlatformRoles.SUPER_ADMIN);
        assertFalse(after.contains(PlatformRoles.SUPER_ADMIN));
    }

    // ── 仓储重复用户兜底 ──────────────────────────────────

    /** 建号用户名重复：仓储 DuplicateUserException → 409。 */
    @Test
    void createUserDuplicatesConflict() {
        FakeUserRepository users = new FakeUserRepository();
        PlatformUserManagementService svc = service(users, new FakeAuditPort());
        svc.createUser(superAdmin("actor-1"), "alice", null, null, PWD_OK, false, null);
        users.duplicateOnNext = true;
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> svc.createUser(superAdmin("actor-1"), "alice", null, null, PWD_OK, false, null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    private static PlatformUserRecord superAdminRecord(String id) {
        return record(id, "root-" + id, "ACTIVE", Set.of(PlatformRoles.SUPER_ADMIN));
    }

    // ── 内存假实现 ────────────────────────────────────────

    static final class FakeUserRepository implements PlatformPrincipalRepository {
        final Map<String, PlatformUserRecord> map = new HashMap<>();
        final Map<String, Set<String>> roles = new HashMap<>();
        int seq;
        boolean duplicateOnNext;

        void put(PlatformUserRecord r) {
            map.put(r.id(), r);
        }

        @Override
        public Optional<PlatformUserRecord> findByUsername(@NonNull String username) {
            return map.values().stream()
                    .filter(u -> u.username().equals(username)).findFirst();
        }

        @Override
        public Optional<PlatformUserRecord> findById(@NonNull String userId) {
            return Optional.ofNullable(map.get(userId));
        }

        @Override
        public List<PlatformUserRecord> listUsers() {
            return new ArrayList<>(map.values());
        }

        @Override
        public Set<String> findRoles(@NonNull String userId) {
            return Set.copyOf(roles.getOrDefault(userId, Set.of()));
        }

        @Override
        public Map<String, Set<String>> rolesByUser() {
            return Map.copyOf(roles);
        }

        @Override
        public PlatformUserRecord insertUser(@NonNull String username, @Nullable String email,
                                             @Nullable String displayName,
                                             @NonNull String passwordHash,
                                             @NonNull String authSource, boolean mfaRequired) {
            if (duplicateOnNext || findByUsername(username).isPresent()) {
                throw new DuplicateUserException("用户名已存在");
            }
            String id = "u-" + (++seq);
            PlatformUserRecord rec = new PlatformUserRecord(id, username, email, displayName,
                    "ACTIVE", authSource, mfaRequired, 1L, null, passwordHash,
                    Instant.now(), Instant.now());
            map.put(id, rec);
            return rec;
        }

        @Override
        public PlatformUserRecord updateProfile(@NonNull String userId, @Nullable String email,
                                                @Nullable String displayName,
                                                @Nullable Boolean mfaRequired) {
            PlatformUserRecord old = map.get(userId);
            if (old == null) {
                throw new java.util.NoSuchElementException("用户不存在");
            }
            PlatformUserRecord rec = new PlatformUserRecord(old.id(), old.username(),
                    email != null ? email : old.email(),
                    displayName != null ? displayName : old.displayName(),
                    old.status(), old.authSource(),
                    mfaRequired != null ? mfaRequired : old.mfaRequired(),
                    old.authzVersion(), old.lastLoginAt(), old.passwordHash(),
                    old.createdAt(), Instant.now());
            map.put(userId, rec);
            return rec;
        }

        @Override
        public void setStatus(@NonNull String userId, @NonNull String status) {
            PlatformUserRecord old = map.get(userId);
            if (old == null) {
                throw new java.util.NoSuchElementException("用户不存在");
            }
            // 契约：状态置位同时递增授权版本
            map.put(userId, withStatus(old, status, old.authzVersion() + 1, old.updatedAt()));
        }

        @Override
        public void updatePasswordHash(@NonNull String userId, @NonNull String passwordHash) {
            PlatformUserRecord old = requireExisting(userId);
            map.put(userId, new PlatformUserRecord(old.id(), old.username(), old.email(),
                    old.displayName(), old.status(), old.authSource(), old.mfaRequired(),
                    old.authzVersion(), old.lastLoginAt(), passwordHash,
                    old.createdAt(), Instant.now()));
        }

        @Override
        public boolean assignRole(@NonNull String userId, @NonNull String roleName,
                                  @NonNull String grantedBy) {
            Set<String> current = new LinkedHashSet<>(roles.getOrDefault(userId, Set.of()));
            boolean added = current.add(roleName);
            if (added) {
                roles.put(userId, current);
            }
            return added;
        }

        @Override
        public boolean revokeRole(@NonNull String userId, @NonNull String roleName) {
            Set<String> current = new LinkedHashSet<>(roles.getOrDefault(userId, Set.of()));
            boolean removed = current.remove(roleName);
            if (removed) {
                roles.put(userId, current);
            }
            return removed;
        }

        @Override
        public void bumpAuthzVersion(@NonNull String userId) {
            PlatformUserRecord old = requireExisting(userId);
            map.put(userId, withStatus(old, old.status(), old.authzVersion() + 1,
                    Instant.now()));
        }

        private PlatformUserRecord requireExisting(@NonNull String userId) {
            PlatformUserRecord old = map.get(userId);
            if (old == null) {
                throw new java.util.NoSuchElementException("用户不存在");
            }
            return old;
        }

        private static PlatformUserRecord withStatus(PlatformUserRecord old, String status,
                                                     long authzVersion, Instant updatedAt) {
            return new PlatformUserRecord(old.id(), old.username(), old.email(),
                    old.displayName(), status, old.authSource(), old.mfaRequired(),
                    authzVersion, old.lastLoginAt(), old.passwordHash(),
                    old.createdAt(), updatedAt);
        }
    }

    static final class FakeAuditPort implements PlatformAuditPort {
        final List<PlatformAuditEvent> events = new ArrayList<>();

        @Override
        public void record(@NonNull PlatformAuditEvent event) {
            events.add(event);
        }

        @Override
        public @NonNull List<PlatformAuditEvent> query(@NonNull PlatformAuditQuery query) {
            return events;
        }

        boolean has(String action, String targetId) {
            return events.stream().anyMatch(e ->
                    action.equals(e.action()) && targetId.equals(e.targetId()));
        }
    }
}
