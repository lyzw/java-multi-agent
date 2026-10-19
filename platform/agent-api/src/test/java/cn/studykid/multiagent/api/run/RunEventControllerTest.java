package cn.studykid.multiagent.api.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RunEventQueryPort;
import cn.studykid.multiagent.ports.security.AccessDeniedException;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * {@link RunEventController} 契约测试（P2-D05 §5.2）。
 *
 * <p>⚠️ <b>本测试断言的是「不可绕过的性质」</b>，不是实现细节。
 * 例如「跨租户必须 404」断言的是<b>状态码不可区分</b>，
 * 而非「调用了某个方法」——后者可被无意义的重构破坏。
 */
class RunEventControllerTest {

    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
    private static final String RUN_ID = "33333333-3333-3333-3333-333333333333";

    // ── S1/S2：续传语义 ────────────────────────────────────────────────

    @Test
    @DisplayName("S1 按 sequence_no 升序返回事件")
    void eventsAreOrderedBySequence() {
        RecordingQueryPort query = new RecordingQueryPort("QUEUED");
        query.events = List.of(
                event(1L, "RUN_CREATED"),
                event(2L, "RUN_CLAIMED"),
                event(3L, "RUN_FINISHED"));

        ResponseEntity<String> response = controller(TENANT_A, query, ALLOW_ALL)
                .streamEvents(RUN_ID, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().indexOf("id: 1") < response.getBody().indexOf("id: 2"),
                "事件必须按序号升序");
        assertTrue(response.getBody().indexOf("id: 2") < response.getBody().indexOf("id: 3"),
                "事件必须按序号升序");
    }

    @Test
    @DisplayName("S2 Last-Event-ID 只返回其后的事件（续传不重复）")
    void resumesAfterCursor() {
        RecordingQueryPort query = new RecordingQueryPort("QUEUED");
        query.events = List.of(event(4L, "RUN_FINISHED"));

        controller(TENANT_A, query, ALLOW_ALL).streamEvents(RUN_ID, "3");

        assertEquals(3L, query.lastCursor, "游标必须传给查询端口");
        assertEquals(4L, query.events.get(0).sequenceNo());
    }

    @Test
    @DisplayName("S2b 未提供 Last-Event-ID 时从头读取（游标为 0）")
    void defaultsToZeroCursor() {
        RecordingQueryPort query = new RecordingQueryPort("QUEUED");

        controller(TENANT_A, query, ALLOW_ALL).streamEvents(RUN_ID, null);

        assertEquals(0L, query.lastCursor, "首次订阅须从头读取");
    }

    // ── S3：跨租户拒绝（本端点最关键的安全性质）────────────────────────

    @Test
    @DisplayName("S3 Run 不存在 → 404")
    void missingRunYields404() {
        RecordingQueryPort query = new RecordingQueryPort(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller(TENANT_A, query, ALLOW_ALL).streamEvents(RUN_ID, null));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    @DisplayName("S3b ⚠️ 跨租户 → 404 而非 403（防存在性探测）")
    void crossTenantYields404Not403() {
        RecordingQueryPort query = new RecordingQueryPort("QUEUED");
        // ⚠️ 该 Run 归属租户 A，租户 B 查询时 RLS 使其不可见
        query.visibleTo = TENANT_A;

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller(TENANT_B, query, ALLOW_ALL).streamEvents(RUN_ID, null));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode(),
                "跨租户必须 404。若返回 403，攻击者可用随机 runId "
                        + "通过状态码差异枚举真实存在的 Run —— "
                        + "这是租户存在性探测通道（P2-D05 §2 D-3）");
    }

    @Test
    @DisplayName("S3c ⚠️ 授权拒绝 → 403 而非 404（必须可区分）")
    void authorizationDeniedYields403() {
        RecordingQueryPort query = new RecordingQueryPort("QUEUED");

        // ⚠️ 授权失败与资源不存在必须可区分：
        // 都返回 404 会让运维无法判断是权限问题还是数据缺失。
        // 实际抛出 AccessDeniedException，由 ApiExceptionHandler 映射为 403。
        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> controller(TENANT_A, query, DENY_ALL).streamEvents(RUN_ID, null));

        assertEquals(AuthorizationService.Actions.EVENT_SUBSCRIBE, ex.action());
    }

    @Test
    @DisplayName("S3d 授权检查在查询之前 —— 未授权不得触碰数据")
    void authorizationPrecedesQuery() {
        RecordingQueryPort query = new RecordingQueryPort("QUEUED");

        assertThrows(AccessDeniedException.class,
                () -> controller(TENANT_A, query, DENY_ALL).streamEvents(RUN_ID, null));

        assertTrue(query.findStateCalled == false,
                "授权失败时不得查询数据库 —— "
                        + "否则构成「用查询副作用探测资源存在性」的旁路");
    }

    // ── S4：游标校验 ──────────────────────────────────────────────────

    @Test
    @DisplayName("S4 非法 Last-Event-ID → 400")
    void malformedCursorRejected() {
        // ⚠️ 纯空白与 null 属「未提供」，不在这组断言内（见 validCursorAccepted）。
        // "+3" 是 Long.parseLong 接受的标准整数写法，不属非法输入。
        for (String bad : List.of("abc", "1.5", "-1", "1e5", "0x10", "3 4", "99999999999999999999")) {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> RunEventController.parseCursor(bad),
                    "非法游标必须拒绝: '" + bad + "'");
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode(),
                    "非法游标必须 400：" + bad);
        }
    }

    @Test
    @DisplayName("S4b 合法游标被接受；null / 空 → 0")
    void validCursorAccepted() {
        assertEquals(0L, RunEventController.parseCursor(null));
        assertEquals(0L, RunEventController.parseCursor(""));
        assertEquals(0L, RunEventController.parseCursor("   "));
        assertEquals(42L, RunEventController.parseCursor("42"));
        assertEquals(42L, RunEventController.parseCursor(" 42 "));
        assertEquals(3L, RunEventController.parseCursor("+3"),
                "\"+3\" 是标准整数写法，应被接受");
        // ⚠️ 超出 long 范围必须拒绝，不得静默截断为某个序号
        assertThrows(ResponseStatusException.class,
                () -> RunEventController.parseCursor("99999999999999999999"));
    }

    @Test
    @DisplayName("S4c ⚠️ 超前游标视为无新事件，不报错")
    void cursorBeyondMaxYieldsEmpty() {
        RecordingQueryPort query = new RecordingQueryPort("SUCCEEDED");
        query.events = List.of();

        ResponseEntity<String> response = controller(TENANT_A, query, ALLOW_ALL)
                .streamEvents(RUN_ID, "99999");

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "客户端可能领先于库（如读到过已回滚数据），不应报错");
    }

    // ── S5：流终止 ────────────────────────────────────────────────────

    @Test
    @DisplayName("S5 终态 Run 发送完成标记后关闭流")
    void terminalRunMarksStreamComplete() {
        for (String terminal : List.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT")) {
            RecordingQueryPort query = new RecordingQueryPort(terminal);
            query.events = List.of(event(1L, "RUN_CREATED"));

            ResponseEntity<String> response = controller(TENANT_A, query, ALLOW_ALL)
                    .streamEvents(RUN_ID, null);

            assertTrue(response.getBody().contains("run-terminal"),
                    "终态 " + terminal + " 必须发送结束标记");
        }
    }

    @Test
    @DisplayName("S5b ⚠️ TIMED_OUT 也算终态（初稿曾漏判）")
    void timedOutIsTerminal() {
        RecordingQueryPort query = new RecordingQueryPort("TIMED_OUT");
        query.events = List.of();

        ResponseEntity<String> response = controller(TENANT_A, query, ALLOW_ALL)
                .streamEvents(RUN_ID, null);

        assertTrue(response.getBody().contains("run-terminal"),
                "TIMED_OUT 是终态。漏判会导致连接永不关闭");
    }

    @Test
    @DisplayName("S5c 非终态不发结束标记（等待续传）")
    void nonTerminalKeepsStreamOpen() {
        for (String running : List.of("QUEUED", "RUNNING")) {
            RecordingQueryPort query = new RecordingQueryPort(running);
            query.events = List.of();

            ResponseEntity<String> response = controller(TENANT_A, query, ALLOW_ALL)
                    .streamEvents(RUN_ID, null);

            assertTrue(!response.getBody().contains("run-terminal"),
                    "非终态不得发结束标记");
        }
    }

    // ── S6：载荷最小化 ────────────────────────────────────────────────

    @Test
    @DisplayName("S6 响应不含 Prompt 与学生可识别信息")
    void responseCarriesNoSensitiveData() {
        RecordingQueryPort query = new RecordingQueryPort("RUNNING");
        // ⚠️ 替身刻意含敏感字段：若序列化时被透传，测试即失败。
        //    这是「测试替身应让防护可被证伪」的做法。
        query.events = List.of(new RunEventQueryPort.RunEvent(
                1L, "RUN_STATE_CHANGED",
                Map.of("state", "RUNNING",
                        "studentName", "张三",
                        "idCard", "110101199001011234"),
                Instant.now()));

        ResponseEntity<String> response = controller(TENANT_A, query, ALLOW_ALL)
                .streamEvents(RUN_ID, null);

        String body = response.getBody();
        assertTrue(body.contains("RUNNING"), "状态字段应保留");
        assertTrue(!body.contains("张三"), "不得输出学生姓名（PIPL 红线）");
        assertTrue(!body.contains("110101199001011234"), "不得输出身份证号（PIPL 红线）");
    }

    @Test
    @DisplayName("S6b 白名单内字段的引号被转义，不会破坏 SSE 帧结构")
    void quotesAreEscaped() {
        RecordingQueryPort query = new RecordingQueryPort("RUNNING");
        // ⚠️ 必须用白名单内字段（errorCode）才能走到序列化路径 ——
        //    用 note 等非白名单字段会被整体丢弃，测试就成了空断言
        query.events = List.of(new RunEventQueryPort.RunEvent(
                1L, "X", Map.of("errorCode", "he said \"hi\""), Instant.now()));

        String body = controller(TENANT_A, query, ALLOW_ALL)
                .streamEvents(RUN_ID, null).getBody();

        assertTrue(body.contains("errorCode"), "白名单字段应被透传");
        assertTrue(body.contains("\\\""), "引号必须转义，否则破坏帧结构使客户端丢事件");
        // ⚠️ 断言帧结构完整：data 行后紧跟空行分隔
        assertTrue(body.contains("data: {"), "data 帧结构必须完整");
    }

    @Test
    @DisplayName("S6d ⚠️ 非白名单字段一律丢弃（含标量字符串）")
    void nonWhitelistedFieldsAreDropped() {
        RecordingQueryPort query = new RecordingQueryPort("RUNNING");
        query.events = List.of(new RunEventQueryPort.RunEvent(
                1L, "X",
                Map.of("state", "RUNNING",
                        "note", "普通文本",
                        "nested", Map.of("a", "b")),
                Instant.now()));

        String body = controller(TENANT_A, query, ALLOW_ALL)
                .streamEvents(RUN_ID, null).getBody();

        assertTrue(body.contains("RUNNING"), "白名单字段应保留");
        assertTrue(!body.contains("普通文本"), "非白名单字段必须丢弃");
        assertTrue(!body.contains("\"a\""), "嵌套结构必须丢弃");
    }

    @Test
    @DisplayName("S6c 响应头禁用代理缓冲与缓存")
    void headersDisableProxyBuffering() {
        RecordingQueryPort query = new RecordingQueryPort("RUNNING");

        ResponseEntity<String> response = controller(TENANT_A, query, ALLOW_ALL)
                .streamEvents(RUN_ID, null);

        assertEquals("no", response.getHeaders().getFirst("X-Accel-Buffering"),
                "Nginx 默认缓冲 SSE，不禁用会导致事件迟迟不到");
        assertTrue(response.getHeaders().getFirst("Cache-Control").contains("no-cache"));
    }

    // ── 辅助 ──────────────────────────────────────────────────────────

    private static RunEventQueryPort.RunEvent event(long seq, String type) {
        return new RunEventQueryPort.RunEvent(seq, type, Map.of(), Instant.now());
    }

    private RunEventController controller(String tenantId, RunEventQueryPort query,
                                          AuthorizationService authz) {
        TenantContext context = new TenantContext(tenantId, "p-1", java.util.Set.of("USER"), 1L);
        RunController.CurrentPrincipal principal = new RunController.CurrentPrincipal() {
            @Override
            public TenantContext context() {
                return context;
            }

            @Override
            public String principalId() {
                return "p-1";
            }

            @Override
            public String providerId() {
                return "deepseek";
            }

            @Override
            public String snapshotId() {
                return "snap-1";
            }

            @Override
            public String correlationId() {
                return "corr-1";
            }
        };
        return new RunEventController(query, authz, principal);
    }

    private static final AuthorizationService ALLOW_ALL =
            new AuthorizationService() {
                @Override
                public boolean isAuthorized(TenantContext c, String a, String r) {
                    return true;
                }

                @Override
                public long currentAuthzVersion(String t, String p) {
                    return 1L;
                }

                @Override
                public java.util.Set<String> rolesOf(TenantContext c) {
                    return c.roles();
                }
            };

    private static final AuthorizationService DENY_ALL =
            new AuthorizationService() {
                @Override
                public boolean isAuthorized(TenantContext c, String a, String r) {
                    return false;
                }

                @Override
                public long currentAuthzVersion(String t, String p) {
                    return 1L;
                }

                @Override
                public java.util.Set<String> rolesOf(TenantContext c) {
                    return java.util.Set.of();
                }
            };

    /** 记录调用参数的仓储替身。 */
    private static final class RecordingQueryPort implements RunEventQueryPort {

        private final String state;
        List<RunEvent> events = List.of();
        long lastCursor = -1;
        boolean findStateCalled;
        String visibleTo = "";

        RecordingQueryPort(String state) {
            this.state = state;
        }

        @Override
        public List<RunEvent> findAfter(String tenantId, String runId,
                                        long afterSequenceNo, int limit) {
            lastCursor = afterSequenceNo;
            return events;
        }

        @Override
        public String findState(String tenantId, String runId) {
            findStateCalled = true;
            // ⚠️ 模拟 RLS：仅归属租户可见该状态
            if (state == null) {
                return null;
            }
            if (!visibleTo.isEmpty() && !visibleTo.equals(tenantId)) {
                return null;
            }
            return state;
        }
    }
}