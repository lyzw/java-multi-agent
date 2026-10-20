package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.ports.persistence.ApprovalQueryPort;
import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.AuditQueryPort;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.persistence.RunCreationPort;
import cn.studykid.multiagent.ports.persistence.RunListQueryPort;
import cn.studykid.multiagent.ports.persistence.UnknownOperationPort;

/**
 * 治理查询与决策集成测试（P4-D13 / G7~G10 · G-1～G-11）。
 *
 * <p><b>⚠️ 必须跑在真实 PG17 上</b>：本片全部命题都依赖数据库特性——
 * 强制 RLS、唯一约束、CHECK、触发器、{@code timestamptz} 微秒精度。
 * 用内存替身验证等于没验证（P4-D08 R-1 的教训：<b>替身验证的是替身</b>）。
 *
 * <p><b>⚠️ 用非特权角色 app_agent</b>：超级用户绕过 RLS，
 * 会得出「隔离失效」或「写进去读不到」的虚假结论。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class GovernanceQueryIT {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-21T03:00:00Z"), ZoneOffset.UTC);

    private static HikariDataSource appPool;
    private static JdbcRunListQueryRepository runList;
    private static JdbcApprovalQueryRepository approvals;
    private static JdbcUnknownOperationRepository operations;
    private static JdbcAuditQueryRepository audits;
    private static RunCreationPort creationRepo;
    private static String tenantA;
    private static String tenantB;
    private static String snapshotId;

    @BeforeAll
    static void setUp() throws Exception {
        String url = System.getenv("IT_PG_URL");
        String user = System.getenv().getOrDefault("IT_PG_USER", "app_agent");
        String pwd = System.getenv("IT_PG_PASSWORD");

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        if (pwd != null && !pwd.isBlank()) {
            cfg.setPassword(pwd);
        }
        cfg.setMaximumPoolSize(4);
        appPool = new HikariDataSource(cfg);

        // ⚠️ auditPort 写成 no-op：本类只关心查询，审计写入由其它 IT 覆盖。
        //    但**决策必须留痕**，故审批/处置用真实记录端口的等价最小实现替代——
        //    这里用记录到内存的替身，避免递归写审计再查审计。
        AuditPort recording = new NoopAudit();
        runList = new JdbcRunListQueryRepository(appPool);
        approvals = new JdbcApprovalQueryRepository(appPool, recording, CLOCK);
        operations = new JdbcUnknownOperationRepository(appPool, recording);
        audits = new JdbcAuditQueryRepository(appPool);
        creationRepo = new JdbcRunCreationRepository(appPool);

        tenantA = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug='t-a'");
        tenantB = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug='t-b'");
        snapshotId = adminQuery("SELECT snapshot_id::text FROM execution_snapshot LIMIT 1");
    }

    // ── G7：Run 列表 ────────────────────────────────────────────────

    @Test
    @DisplayName("✅ G-1 列表按 (created_at, run_id) 全序返回，同刻多行不重复不遗漏")
    void listIsTotallyOrdered() {
        java.util.stream.IntStream.range(0, 6).forEach(i -> newRun(tenantA));

        Page<RunListQueryPort.RunListItem> p1 = runList.list(tenantA, null, null, null, null, 3);
        Page<RunListQueryPort.RunListItem> p2 =
                runList.list(tenantA, null, null, null, p1.nextCursor(), 3);

        List<String> firstPage = p1.items().stream()
                .map(RunListQueryPort.RunListItem::runId).toList();
        List<String> secondPage = p2.items().stream()
                .map(RunListQueryPort.RunListItem::runId).toList();

        assertThat(firstPage).hasSize(3).doesNotHaveDuplicates();
        assertThat(secondPage).hasSize(3).doesNotHaveDuplicates();
        assertThat(java.util.stream.Stream.concat(firstPage.stream(), secondPage.stream()).toList())
                .as("⛔ 全序缺失时同刻行会在两页中重复或丢失（反证 A-2）")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("✅ G-2 游标翻页不重不漏；并发新插入不影响已翻页的结果")
    void cursorPagingHasNoGapOrOverlap() {
        String marker = newRun(tenantA);
        List<String> all = runList.list(tenantA, null, null, null, null, 100)
                .items().stream().map(RunListQueryPort.RunListItem::runId).toList();
        assertThat(all).contains(marker);

        // 第一页
        Page<RunListQueryPort.RunListItem> p1 = runList.list(tenantA, null, null, null, null, 2);
        assertThat(p1.hasMore()).isTrue();

        // ⛔ 翻页期间插入一条更「新」的 Run（created_at 更晚 = 排序更靠前）
        newRun(tenantA);

        Page<RunListQueryPort.RunListItem> p2 =
                runList.list(tenantA, null, null, null, p1.nextCursor(), 2);

        List<String> seen = java.util.stream.Stream.concat(
                        p1.items().stream().map(RunListQueryPort.RunListItem::runId),
                        p2.items().stream().map(RunListQueryPort.RunListItem::runId))
                .toList();
        assertThat(seen)
                .as("⛔ keyset 分页下并发插入不得让已翻页的行重复出现（offset 分页会）")
                .doesNotHaveDuplicates();
        assertThat(p2.items())
                .as("第二页应全部排在游标之后")
                .allSatisfy(item -> assertThat(item.createdAt())
                        .isBeforeOrEqualTo(p1.items().get(p1.items().size() - 1).createdAt()));
    }

    @Test
    @DisplayName("✅ G-3 坏游标 / 非正 limit → 拒绝（不静默当成第一页）")
    void invalidArgumentsAreRejected() {
        assertThatThrownBy(() -> runList.list(tenantA, null, null, null, "not-base64!!", 10))
                .as("⛔ 坏游标必须报错，不能静默当成第一页")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runList.list(tenantA, null, null, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        // ⚠️ limit 上限 100 是在**端点层**（GovernanceSupport）强制的契约，
        //    仓库层只挡非正数——那条断言在 GovernanceEndpointIT 里。
    }

    @Test
    @DisplayName("✅ G-3b state 过滤生效，且时间范围过滤生效")
    void filtersWork() {
        String runId = newRun(tenantA);

        assertThat(runList.list(tenantA, "QUEUED", null, null, null, 50).items())
                .as("新建的 Run 应处于 QUEUED")
                .anySatisfy(i -> assertThat(i.runId()).isEqualTo(runId));

        Instant future = Instant.now().plusSeconds(3600);
        assertThat(runList.list(tenantA, null, future, null, null, 50).items())
                .as("未来起点不应命中任何行")
                .isEmpty();
        assertThat(runList.list(tenantA, null, null, Instant.parse("2000-01-01T00:00:00Z"), null, 50)
                .items())
                .as("过去终点不应命中任何行")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ G-5 跨租户不可见：B 的列表里不得出现 A 的任何 Run")
    void crossTenantListIsInvisible() {
        String runIdA = newRun(tenantA);
        // ⚠️ **不得断言「B 的列表为空」**：
        //    隔离 IT 库是**跨多次运行累积**的，tenantB 自己也有 Run
        //    （本类的其它用例会为 tenantB 造夹具），
        //    「B 看到自己的数据」是完全正确的行为。
        //    真正的隔离不变式是：**B 看不到 A 的任何一行**。
        //    ——此前写成 isEmpty()，首轮恰好成立，一旦 B 有了自己的数据就转红，
        //    且失败现场指向错误的排查方向（F-9 同形态）。
        List<String> visibleToB = runList.list(tenantB, null, null, null, null, 100)
                .items().stream()
                .map(RunListQueryPort.RunListItem::runId)
                .toList();

        assertThat(visibleToB)
                .as("⛔ 跨租户必须完全不可见（RLS + 显式 tenant_id 双保险）")
                .doesNotContain(runIdA);

        // 且 B 只能看到 B 自己的行——用管理连接对照核对
        List<String> ownRunIdsOfB = adminQueryList(
                "SELECT run_id::text FROM run WHERE tenant_id='" + tenantB + "'::uuid");
        for (String id : visibleToB) {
            assertThat(ownRunIdsOfB)
                    .as("⛔ B 的列表出现了不属于 B 的 Run")
                    .contains(id);
        }
    }

    // ── G8：审批 ────────────────────────────────────────────────────

    /**
     * ⚠️ <b>有效期必须相对 {@link #CLOCK} 而非 {@code Instant.now()}</b>：
     * 被测对象用的是注入的固定时钟，而测试若用真实时钟造夹具，
     * 两边会差出固定偏移 → 「未过期」的夹具被判为过期。
     * 这不是产品缺陷，是夹具与被测对象**不同源时钟**的典型事故。
     */
    private static Instant futureExpiry() {
        return CLOCK.instant().plusSeconds(3600);
    }

    private static Instant pastExpiry() {
        return CLOCK.instant().minusSeconds(10);
    }

    @Test
    @DisplayName("✅ G-6 审批项含参数摘要/目标资源/有效期/服务端过期标志（FR-014）")
    void approvalItemCarriesDecisionContext() {
        String operationId = newUnknownOperation(tenantA, "UNKNOWN");
        String approvalId = newApproval(tenantA, operationId, futureExpiry());

        ApprovalQueryPort.ApprovalItem item = approvals.find(tenantA, approvalId);
        assertThat(item).isNotNull();
        assertThat(item.argumentDigest())
                .as("⛔ FR-014 要求展示参数摘要")
                .isNotBlank();
        assertThat(item.targetResource()).isNotBlank();
        assertThat(item.expiresAt()).isNotNull();
        assertThat(item.expired())
                .as("未过期时服务端标志必须为 false")
                .isFalse();
        assertThat(item.runId()).isNotBlank();
    }

    @Test
    @DisplayName("✅ 过期审批：expired=true，且批准被拒（FE-01：过期须拒绝而非静默通过）")
    void expiredApprovalCannotBeApproved() {
        String operationId = newUnknownOperation(tenantA, "UNKNOWN");
        String approvalId = newApproval(tenantA, operationId, pastExpiry());

        assertThat(approvals.find(tenantA, approvalId).expired())
                .as("⛔ expired 必须由服务端算，前端时钟不可信")
                .isTrue();

        assertThatThrownBy(() -> approvals.decide(
                tenantA, approvalId, true, "approver-1", null))
                .as("⛔⛔ 过期审批不得批准（反证 A-4）")
                .isInstanceOf(ApprovalQueryPort.ApprovalDecisionException.class);

        assertThat(adminQuery("SELECT state::text FROM approval WHERE approval_id='"
                + approvalId + "'"))
                .as("拒绝后状态必须仍是 PENDING")
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("✅ 正常批准 → state=APPROVED；重复决定 → 409 语义的异常")
    void approveWorksAndIsNotRepeatable() {
        String operationId = newUnknownOperation(tenantA, "UNKNOWN");
        String approvalId = newApproval(tenantA, operationId, futureExpiry());

        var decision = approvals.decide(tenantA, approvalId, true, "approver-1", null);
        assertThat(decision.newState()).isEqualTo("APPROVED");
        assertThat(adminQuery("SELECT approver_id FROM approval WHERE approval_id='"
                + approvalId + "'"))
                .as("审批人标识必须落库（来自令牌，不来自请求体）")
                .isEqualTo("approver-1");

        assertThatThrownBy(() -> approvals.decide(tenantA, approvalId, true, "approver-2", null))
                .as("⛔ 已决定的审批不可重复决定")
                .isInstanceOf(ApprovalQueryPort.ApprovalDecisionException.class);
    }

    @Test
    @DisplayName("✅ 拒绝必须带理由；拒绝后 state=REJECTED")
    void denyRequiresReason() {
        String operationId = newUnknownOperation(tenantA, "UNKNOWN");

        String noReason = newApproval(tenantA, operationId, futureExpiry());
        assertThatThrownBy(() -> approvals.decide(tenantA, noReason, false, "a-1", null))
                .as("⛔ 没有理由的拒绝在事后追责时等于没有决定")
                // ⚠️ 必须是 IllegalArgumentException（→ 400）而非 ApprovalDecisionException（→ 409）：
                //    409 会让前端提示「已被别人处理」，而真相是「你少填了理由」
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> approvals.decide(tenantA, noReason, false, "a-1", "  "))
                .isInstanceOf(IllegalArgumentException.class);

        String withReason = newApproval(tenantA, operationId, futureExpiry());
        var d = approvals.decide(tenantA, withReason, false, "a-1", "参数越权，需复核");
        assertThat(d.newState()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("⛔ 审批不存在 / 跨租户 → NotFound（端点转 404，与无权限合并）")
    void approvalNotFoundAcrossTenants() {
        String ghost = UUID.randomUUID().toString();
        assertThatThrownBy(() -> approvals.decide(tenantA, ghost, true, "a-1", null))
                .isInstanceOf(ApprovalQueryPort.ApprovalNotFoundException.class);

        String operationId = newUnknownOperation(tenantA, "UNKNOWN");
        String approvalId = newApproval(tenantA, operationId, futureExpiry());
        assertThatThrownBy(() -> approvals.decide(tenantB, approvalId, true, "a-1", null))
                .as("⛔ 跨租户审批必须与「不存在」同样报错，否则构成存在性探测通道")
                .isInstanceOf(ApprovalQueryPort.ApprovalNotFoundException.class);
    }

    // ── G9：UNKNOWN 处置 ────────────────────────────────────────────

    @Test
    @DisplayName("✅ G-9 三个处置取值各自落到正确 state")
    void eachDispositionMapsToItsState() {
        for (UnknownOperationPort.Disposition d : UnknownOperationPort.Disposition.values()) {
            String operationId = newUnknownOperation(tenantA, "UNKNOWN");
            var result = operations.dispose(tenantA, operationId, d, "operator-1", "人工核实");
            assertThat(result.newState()).isEqualTo(d.name());
            assertThat(adminQuery("SELECT state::text FROM tool_operation WHERE operation_id='"
                    + operationId + "'")).isEqualTo(d.name());
            assertThat(adminQuery("SELECT reconciliation_source FROM tool_operation "
                    + "WHERE operation_id='" + operationId + "'"))
                    .as("⛔ 必须标记为人工处置，与 P3-D03 自动对账可区分")
                    .isEqualTo("MANUAL");
        }
    }

    @Test
    @DisplayName("⛔ G-10 处置枚举中不存在任何会退回 PENDING/IN_FLIGHT 的取值")
    void dispositionEnumHasNoRetry() {
        for (UnknownOperationPort.Disposition d : UnknownOperationPort.Disposition.values()) {
            assertThat(d.name())
                    .as("⛔⛔ 处置取值不得把操作退回执行态——盲目重放 = Sev-0（反证 A-5）")
                    .isNotIn("PENDING", "IN_FLIGHT", "RETRY");
        }
        assertThatThrownBy(() -> UnknownOperationPort.Disposition.valueOf("RETRY"))
                .as("⛔ 连名字都不该存在：RETRY 必须无法被解析")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("✅ 非 UNKNOWN 态 → 拒绝处置（重复处置 / 已被自动对账）")
    void nonUnknownStateCannotBeDisposed() {
        String operationId = newUnknownOperation(tenantA, "SUCCEEDED");

        assertThatThrownBy(() -> operations.dispose(tenantA, operationId,
                UnknownOperationPort.Disposition.MANUAL_DISPOSED, "op-1", null))
                .as("⛔ 已被自动对账接管的操作不得再人工处置")
                .isInstanceOf(UnknownOperationPort.NotInUnknownStateException.class);
        assertThat(adminQuery("SELECT state::text FROM tool_operation WHERE operation_id='"
                + operationId + "'"))
                .as("被拒绝的处置不得留下任何写入")
                .isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("⛔ 处置列表跨租户不可见：B 不得看到 A 的 UNKNOWN 操作")
    void unknownListIsolationAndNotFound() {
        String operationIdA = newUnknownOperation(tenantA, "UNKNOWN");
        String operationIdB = newUnknownOperation(tenantB, "UNKNOWN");

        List<String> visibleToB = operations.list(tenantB, "UNKNOWN", null, 100)
                .items().stream()
                .map(UnknownOperationPort.OperationItem::operationId).toList();

        assertThat(visibleToB)
                .as("⛔⛔ B 不得看到 A 的 UNKNOWN 操作（跨租户泄漏）")
                .doesNotContain(operationIdA);
        assertThat(visibleToB)
                .as("控制组：B 应当能看到自己的操作（否则本用例可能在测「什么都没返回」）")
                .contains(operationIdB);

        assertThatThrownBy(() -> operations.dispose(tenantA, UUID.randomUUID().toString(),
                UnknownOperationPort.Disposition.MANUAL_DISPOSED, "op-1", null))
                .isInstanceOf(UnknownOperationPort.OperationNotFoundException.class);
    }

    // ── G10：审计 ───────────────────────────────────────────────────

    @Test
    @DisplayName("✅ G-11 审计可分页查询；detail 可回读；不返回 tenantId")
    void auditQueryWorks() {
        String auditId = newAuditRecord(tenantA, "APPROVAL_GRANT", "approval", "res-1",
                Map.of("operationId", "op-9"));

        var page = audits.query(tenantA, "APPROVAL_GRANT", "approval", "res-1",
                null, null, null, 50);

        assertThat(page.items())
                .as("⛔ 审计页若查不到刚写的记录，「可追溯」就是空话")
                .anySatisfy(e -> {
                    assertThat(e.auditId()).isEqualTo(auditId);
                    assertThat(e.action()).isEqualTo("APPROVAL_GRANT");
                    assertThat(e.detail())
                            .as("detail 写入时已过 PII 扫描，读出侧应原样返回")
                            .containsEntry("operationId", "op-9");
                });
        assertThat(AuditQueryPort.AuditEntry.class.getRecordComponents())
                .as("⛔ 审计条目不得含 tenantId 字段（避免逐条探测跨租户存在性）")
                .noneMatch(c -> "tenantId".equals(c.getName()));
    }

    @Test
    @DisplayName("✅ 审计过滤条件生效（action/resourceType/resourceId/时间）")
    void auditFiltersWork() {
        newAuditRecord(tenantA, "PROFILE_READ", "profile", "p-1", Map.of("k", "v"));

        assertThat(audits.query(tenantA, "APPROVAL_GRANT", null, null, null, null, null, 50)
                .items())
                .as("按 action 过滤应只返回该 action")
                .allSatisfy(e -> assertThat(e.action()).isEqualTo("APPROVAL_GRANT"));
        assertThat(audits.query(tenantA, null, "profile", null, null, null, null, 50).items())
                .isNotEmpty();
        assertThat(audits.query(tenantA, null, null, "no-such-resource", null, null, null, 50)
                .items())
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 审计查询跨租户不可见：B 不得看到 A 的审计行")
    void auditCrossTenantIsInvisible() {
        String auditIdA = newAuditRecord(tenantA, "RUN_CANCEL", "run", "r-1", Map.of());

        List<String> visibleToB = audits.query(tenantB, null, null, null, null, null, null, 100)
                .items().stream()
                .map(AuditQueryPort.AuditEntry::auditId).toList();

        assertThat(visibleToB)
                .as("⛔ B 不得看到 A 的审计行")
                .doesNotContain(auditIdA);
    }

    // ── 游标单元（不依赖 DB）─────────────────────────────────────────

    @Test
    @DisplayName("✅ 游标编解码往返；坏输入 → 明确异常")
    void cursorRoundTrips() {
        PageCursor c = new PageCursor(Instant.parse("2026-10-21T03:00:00Z"), "abc-123");
        PageCursor back = PageCursor.decode(c.encode());
        assertThat(back).isEqualTo(c);

        assertThatThrownBy(() -> PageCursor.decode("!!!not-base64!!!"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageCursor.decode(
                java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("no-separator".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageCursor.decode(
                java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("not-a-time|x".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    private static String adminQuery(String sql) {
        try (HikariDataSource adminPool = new HikariDataSource(adminConfig());
             Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            assertThat(rs.next()).as("查询应返回一行: " + sql).isTrue();
            return rs.getString(1);
        } catch (Exception e) {
            throw new IllegalStateException("管理员查询失败: " + sql, e);
        }
    }

    /** 管理员查询多行（用于「某租户实际拥有哪些 Run」这类对照断言）。 */
    private static List<String> adminQueryList(String sql) {
        List<String> out = new java.util.ArrayList<>();
        try (HikariDataSource adminPool = new HikariDataSource(adminConfig());
             Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("管理员查询失败: " + sql, e);
        }
    }

    private static HikariConfig adminConfig() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
        cfg.setMaximumPoolSize(1);
        return cfg;
    }

    private static void adminExec(String sql) {
        try (HikariDataSource adminPool = new HikariDataSource(adminConfig());
             Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("管理员写入失败: " + sql, e);
        }
    }

    private static String newRun(String tenantId) {
        // ⚠️ **快照必须按租户取**：execution_snapshot 有 tenant_id，
        //    拿 A 的快照去给 B 建 Run 会撞跨租户约束
        //    （首次表现为「Run 原子提交失败」，指向 Run 代码，实际是夹具造错）。
        String snapshot = snapshotIdFor(tenantId);
        String runId = UUID.randomUUID().toString();
        creationRepo.insertRunAtomically(tenantId, runId, snapshot,
                new IdempotencyKey("run.create", "gov-it-" + runId),
                RequestDigest.ofText("digest-" + runId),
                Map.of("question", "一元二次方程"),
                "corr-" + runId,
                new OutboxMessage(runId, tenantId, 0, 0L, CLOCK.instant()));
        return runId;
    }

    private static java.util.Map<String, String> snapshotCache = new java.util.HashMap<>();

    private static synchronized String snapshotIdFor(String tenantId) {
        return snapshotCache.computeIfAbsent(tenantId, t -> {
            try {
                return adminQuery("SELECT snapshot_id::text FROM execution_snapshot "
                        + "WHERE tenant_id = '" + t + "'::uuid LIMIT 1");
            } catch (Exception e) {
                throw new IllegalStateException("租户 " + t + " 没有执行快照，无法造 Run 夹具", e);
            }
        });
    }

    /** 造一条 tool_operation（超级用户写入：RLS 下 app_agent 无权建任意操作）。 */
    private static String newUnknownOperation(String tenantId, String state) {
        String runId = newRun(tenantId);
        String operationId = UUID.randomUUID().toString();
        adminExec("""
                INSERT INTO tool_operation
                    (operation_id, tenant_id, run_id, tool_name, risk_level, state,
                     idempotency_key, request_digest, tool_arguments, fencing_token,
                     created_at, updated_at)
                VALUES ('%s'::uuid, '%s'::uuid, '%s'::uuid, 'record_weakness',
                        'WRITE_PROFILE', '%s'::tool_operation_state,
                        '%s', 'digest', '{}'::jsonb, 0, now(), now())
                """.formatted(operationId, tenantId, runId, state, "op-" + operationId));
        return operationId;
    }

    private static String newApproval(String tenantId, String operationId, Instant expiresAt) {
        String approvalId = UUID.randomUUID().toString();
        adminExec("""
                INSERT INTO approval
                    (approval_id, tenant_id, operation_id, state, requirement,
                     argument_digest, target_resource, approver_scope, expires_at)
                VALUES ('%s'::uuid, '%s'::uuid, '%s'::uuid, 'PENDING', 'REQUIRED',
                        'sha256:abc123', 'profile/student-1', 'TENANT',
                        '%s'::timestamptz)
                """.formatted(approvalId, tenantId, operationId,
                java.sql.Timestamp.from(expiresAt).toString()));
        return approvalId;
    }

    private static String newAuditRecord(String tenantId, String action, String resourceType,
                                         String resourceId, Map<String, Object> detail) {
        String auditId = UUID.randomUUID().toString();
        adminExec("""
                INSERT INTO audit_record
                    (audit_id, tenant_id, actor_id, action, resource_type, resource_id,
                     detail, created_at)
                VALUES ('%s'::uuid, '%s'::uuid, 'actor-1', '%s', '%s', '%s',
                        '%s'::jsonb, now())
                """.formatted(auditId, tenantId, action, resourceType, resourceId,
                toJson(detail)));
        return auditId;
    }

    private static String toJson(Map<String, Object> map) {
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    /** 只关心查询，故审计写入用 no-op（避免「查审计 → 写审计 → 再查」自递归）。 */
    private record NoopAudit() implements AuditPort {
        @Override
        public void recordRequired(String tenantId, String actorId, String action,
                                   String resourceType, String resourceId,
                                   Map<String, Object> detail) {
        }

        @Override
        public void recordAccess(String tenantId, String actorId, String action,
                                 String resourceType, String resourceId,
                                 Map<String, Object> detail) {
        }

        @Override
        public List<AuditView> findByResource(String tenantId, String resourceType,
                                             String resourceId) {
            return List.of();
        }
    }
}