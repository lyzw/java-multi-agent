package cn.studykid.multiagent.api.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.security.AccessDeniedException;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.ports.statistics.StatisticsPort;
import cn.studykid.multiagent.ports.statistics.StatisticsPort.DailyStatistics;
import cn.studykid.multiagent.ports.statistics.StatisticsPort.KnowledgePointRank;
import cn.studykid.multiagent.ports.statistics.StatisticsPort.PeriodSummary;
import cn.studykid.multiagent.ports.statistics.StatisticsPort.RunDayStat;
import cn.studykid.multiagent.ports.statistics.StatisticsPort.StudentDayStat;
import cn.studykid.multiagent.ports.statistics.StatisticsPort.WeaknessDayStat;

/**
 * 报表中心端点测试（工作台统计 · 每日搜索/导入/错题分析等报表的数据源）。
 *
 * <p><b>核心命题</b>：
 * <ul>
 *   <li><b>租户只来自已验签令牌</b>——端点签名里不得出现 {@code tenantId} 参数，
 *       否则调用方一改 URL 就能看别家租户的报表；</li>
 *   <li><b>执行时授权不可跳过</b>（红线 9）——授权拒绝时<b>绝不能</b>触达统计查询；</li>
 *   <li><b>days 必须被钳制</b>在 1～90——它直接决定聚合查询的扫描范围，
 *       放大到全表会让一个报表页拖垮数据库。</li>
 * </ul>
 */
class StatisticsControllerTest {

    private static final String TENANT = "6f5d33fc-723e-4f64-beda-9e4435c72691";

    /** 记录端口实际收到的 (days, tenantId)——证明租户来源是令牌而非 URL。 */
    private final AtomicReference<Long> receivedDays = new AtomicReference<>();
    private final AtomicReference<String> receivedTenant = new AtomicReference<>();
    private final AtomicBoolean portInvoked = new AtomicBoolean(false);

    private StatisticsPort statistics;
    private AuthorizationService authorization;
    private CurrentPrincipal principal;
    private TenantContext currentContext;
    private boolean authorized;

    @BeforeEach
    void setUp() {
        receivedDays.set(null);
        receivedTenant.set(null);
        portInvoked.set(false);
        authorized = true;
        currentContext = context("user-1", Set.of());
        statistics = (days, tenantId) -> {
            portInvoked.set(true);
            receivedDays.set(days);
            receivedTenant.set(tenantId);
            return sampleStatistics();
        };
        authorization = new AuthorizationService() {
            @Override
            public boolean isAuthorized(TenantContext c, String a, String r) {
                return authorized;
            }

            @Override
            public long currentAuthzVersion(String t, String p) {
                return 0L;
            }

            @Override
            public Set<String> rolesOf(TenantContext c) {
                return c.roles();
            }
        };
        principal = new CurrentPrincipal() {
            @Override
            public TenantContext context() {
                return currentContext;
            }

            @Override
            public String principalId() {
                return "user-1";
            }

            @Override
            public String providerId() {
                return "deepseek";
            }

            @Override
            public String correlationId() {
                return "corr-1";
            }
        };
    }

    private static TenantContext context(String principalId, Set<String> roles) {
        return new TenantContext(TENANT, principalId, roles, 1L);
    }

    private static DailyStatistics sampleStatistics() {
        return new DailyStatistics(
                List.of(new RunDayStat("2026-10-22", 3, 3, 2, 1, 0, 0)),
                List.of(new WeaknessDayStat("2026-10-22", 5)),
                List.of(new StudentDayStat("2026-10-22", 4, 1)),
                List.of(new KnowledgePointRank("一元二次方程", 5,
                        Map.of("公式记错", 3L, "计算失误", 2L))),
                List.of(new KnowledgePointRank("一元二次方程", 2,
                        Map.of("公式记错", 2L))),
                30,
                new PeriodSummary(3, 3, 2, 1, 0, 0, 5, 4, 1),
                new PeriodSummary(2, 2, 2, 0, 0, 0, 3, 2, 0));
    }

    @Nested
    @DisplayName("正向：已授权用户可取本租户聚合")
    class Authorized {

        @Test
        @DisplayName("✅ 返回 200 与聚合数据")
        void returnsAggregates() {
            StatisticsController controller =
                    new StatisticsController(statistics, authorization, principal);

            ResponseEntity<DailyStatistics> resp = controller.daily(30);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().runsByDay()).hasSize(1);
            assertThat(resp.getBody().topKnowledgePoints().get(0).knowledgePoint())
                    .isEqualTo("一元二次方程");
        }

        @Test
        @DisplayName("✅ 租户取自令牌上下文，不是任何外部输入")
        void tenantComesFromTokenNotRequest() {
            StatisticsController controller =
                    new StatisticsController(statistics, authorization, principal);

            controller.daily(7);

            assertThat(receivedTenant.get())
                    .as("⛔ 租户必须来自已验签令牌；取自请求参数等于开放跨租户读取")
                    .isEqualTo(TENANT);
            assertThat(receivedDays.get()).isEqualTo(7L);
        }
    }

    @Nested
    @DisplayName("反向：授权闸门")
    class Authorization {

        @Test
        @DisplayName("⛔ 未授权 → AccessDenied，且统计端口绝不被触达")
        void deniedBeforePortTouched() {
            authorized = false;
            StatisticsController controller =
                    new StatisticsController(statistics, authorization, principal);

            AccessDeniedException ex = catchThrowableOfType(AccessDeniedException.class,
                    () -> controller.daily(30));

            assertThat(ex).isNotNull();
            assertThat(portInvoked.get())
                    .as("⛔ 授权失败仍查库 = 先读取后拒绝的越权窗口")
                    .isFalse();
        }

        @Test
        @DisplayName("⛔ 会话建立后授权被撤 → 执行时重新校验仍然拒绝")
        void revokedAfterSessionStillDenied() {
            // 授权在会话建立之后被撤销；执行时授权（红线 9）必须重新校验
            authorized = false;
            StatisticsController controller =
                    new StatisticsController(statistics, authorization, principal);

            assertThatThrownBy(() -> controller.daily(30))
                    .isInstanceOf(AccessDeniedException.class);
            assertThat(portInvoked.get()).isFalse();
        }
    }

    @Nested
    @DisplayName("入参钳制：days 决定聚合扫描范围")
    class DaysClamping {

        @Test
        @DisplayName("✅ days=0 → 钳为 1（不允许空窗口）")
        void zeroClampedToOne() {
            StatisticsController controller =
                    new StatisticsController(statistics, authorization, principal);

            controller.daily(0);

            assertThat(receivedDays.get()).isEqualTo(1L);
        }

        @Test
        @DisplayName("✅ days=9999 → 钳为 90（防止全表聚合拖垮数据库）")
        void hugeClampedToNinety() {
            StatisticsController controller =
                    new StatisticsController(statistics, authorization, principal);

            controller.daily(9999);

            assertThat(receivedDays.get()).isEqualTo(90L);
        }
    }
}
