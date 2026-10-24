package cn.studykid.multiagent.api.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.persistence.RunListQueryPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * Run 列表端点测试（P4-D13 / G7 · FE-01 §P7 检索台）。
 *
 * <p><b>本类验证什么</b>：
 * <ul>
 *   <li>学科筛选参数<b>合法透传</b>、<b>非法即 400</b>（不返回空列表）；</li>
 *   <li>响应体带出学科/年级（前端检索台要按学科筛，列表要显示学科）。</li>
 * </ul>
 *
 * <p>⚠️ 不依赖数据库：仓储与限流均以 Mockito 桩替代，聚焦于<b>契约与闸门</b>逻辑。
 */
@DisplayName("P4-D13 RunListController · 列表与学科筛选")
class RunListControllerTest {

    private static final String TENANT_ID = "tenant-1";

    private final RunListQueryPort runList = mock(RunListQueryPort.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final RateLimitPort rateLimiter = mock(RateLimitPort.class);
    private final CurrentPrincipal currentPrincipal = mock(CurrentPrincipal.class);

    private RunListController controller() {
        when(rateLimiter.check(any(), any())).thenReturn(RateLimitPort.Decision.ALLOWED);
        when(currentPrincipal.context())
                .thenReturn(new TenantContext(TENANT_ID, "principal-1", Set.of("TENANT_ADMIN"), 1L));
        return new RunListController(runList, authorization, rateLimiter, currentPrincipal);
    }

    private RunListQueryPort.RunListItem item(String subject, String grade) {
        return new RunListQueryPort.RunListItem(
                "run-1", "SUCCEEDED", Instant.parse("2026-10-24T02:00:00Z"),
                Instant.parse("2026-10-24T02:05:00Z"), 1, null, subject, grade);
    }

    // ── 学科筛选 ──

    @Test
    @DisplayName("✅ 合法学科 → 透传给仓储，返回体带出学科/年级")
    void validSubjectIsPassedThroughAndRendered() {
        when(runList.list(eq(TENANT_ID), isNull(), eq("数学"), any(), any(), any(), anyInt()))
                .thenReturn(Page.of(List.of(item("数学", "初二")), null));

        var resp = controller().listRuns(20, null, null, "数学", null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().items()).hasSize(1);
        assertThat(resp.getBody().items().get(0).subject())
                .as("前端检索台要显示学科，必须由后端带得出")
                .isEqualTo("数学");
        assertThat(resp.getBody().items().get(0).grade()).isEqualTo("初二");
    }

    @Test
    @DisplayName("⛔ 非法学科 → 400（不是空列表）")
    void invalidSubjectIsRejected() {
        assertThatThrownBy(() -> controller().listRuns(20, null, null, "地理", null, null))
                .as("⛔ 空列表会把「你传错了参数」伪装成「这个租户没有数据」")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject");

        verify(runList, org.mockito.Mockito.never())
                .list(any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("✅ 学科留空 → 不施加学科筛选")
    void blankSubjectAppliesNoFilter() {
        when(runList.list(eq(TENANT_ID), isNull(), isNull(), any(), any(), any(), anyInt()))
                .thenReturn(Page.of(List.of(item(null, null)), null));

        var resp = controller().listRuns(20, null, null, "  ", null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(runList).list(eq(TENANT_ID), isNull(), isNull(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("⛔ 非法状态 → 400（既有契约不得被学科参数挤掉）")
    void invalidStateIsRejected() {
        assertThatThrownBy(() -> controller().listRuns(20, null, "UNKNOWN", null, null, null))
                .as("⛔ 后端枚举里根本没有 UNKNOWN 状态，传它必须报错")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state");
    }

    @Test
    @DisplayName("✅ 空白状态 → 不施加状态筛选（`?state=` 不得 500）")
    void blankStateAppliesNoFilter() {
        when(runList.list(eq(TENANT_ID), isNull(), isNull(), any(), any(), any(), anyInt()))
                .thenReturn(Page.of(List.of(), null));

        var resp = controller().listRuns(20, null, "  ", null, null, null);

        assertThat(resp.getStatusCode())
                .as("⛔ 畸形参数不该换来服务端故障：空串推进 SQL 会让 PG 抛类型转换错 → 500")
                .isEqualTo(HttpStatus.OK);
        verify(runList).list(eq(TENANT_ID), isNull(), isNull(), any(), any(), any(), anyInt());
    }
}
