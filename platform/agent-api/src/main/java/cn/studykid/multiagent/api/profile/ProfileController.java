package cn.studykid.multiagent.api.profile;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.ProfileQueryPort;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.api.run.RunController;

/**
 * 学习画像查询端点（P4-D09 / G6）。
 *
 * <p>⚠️ <b>白名单纪律（DEC-6 / PIPL 未成年人）</b>：
 * 只返回四类信息——随机 {@code studentId}、年级、学科、薄弱点。
 * <b>不得</b>返回姓名、学校、班级、身份证、手机、定位、生物识别。
 * 前端亦有「拒绝渲染」纵深防御（{@code pickWhitelisted}），
 * 本端点是<b>第一道</b>防线，前端那层是第二道。
 *
 * <p>⚠️ <b>「学生不存在」返回 200 + {@code notFound:true}，不返回 404</b>：
 * 若不存在报错、存在返回数据，攻击者可据此<b>枚举有效 studentId</b>。
 * 画像页应显示<b>空态</b>（还没被辅导过），而非「系统故障」。
 *
 * <p>⚠️ <b>本端点在真实模型接入前返回空画像</b>（P4-D09 §1.2）：
 * {@code FakeModelGateway} 的 {@code toolCalls} 为空 → 模型永不发起工具调用
 * → {@code record_weakness} 不执行 → {@code weakness_record} 永远为空。
 * 这是<b>真实状态</b>，端点如实返回，**不得**伪造数据。
 */
@RestController
public class ProfileController {

    private static final Logger log = LoggerFactory.getLogger(ProfileController.class);

    private final ProfileQueryPort profileQuery;
    private final AuthorizationService authorization;
    private final RateLimitPort rateLimiter;
    private final RunController.CurrentPrincipal currentPrincipal;

    public ProfileController(ProfileQueryPort profileQuery,
                             AuthorizationService authorization,
                             RateLimitPort rateLimiter,
                             RunController.CurrentPrincipal currentPrincipal) {
        this.profileQuery = profileQuery;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
    }

    /**
     * 查询学习画像。
     *
     * @param studentId 随机学生标识
     */
    @GetMapping("/api/profiles/{studentId}")
    public ResponseEntity<ProfileResponse> getProfile(@PathVariable String studentId) {
        // ⚠️ 租户来自已验签令牌，绝不从请求参数取
        TenantContext context = currentPrincipal.context();

        // ⚠️ 执行时授权（红线 9）：画像是学生维度数据，单独用 profile:read 动作
        authorization.requireAuthorized(
                context, AuthorizationService.Actions.PROFILE_READ, studentId);

        // ⚠️ 限流：复用只读轮询桶
        RateLimitPort.Decision limit = rateLimiter.check(
                context.tenantId(), RateLimitPort.Buckets.EVENT_QUERY);
        if (limit != RateLimitPort.Decision.ALLOWED) {
            return ResponseEntity.status(429).header("Retry-After", "60").build();
        }

        ProfileQueryPort.ProfileView view = profileQuery.loadProfile(
                context.tenantId(), studentId);

        // ⚠️ 不存在 → 200 + notFound:true（不返回 404，避免枚举 studentId）
        boolean notFound = !view.exists();
        if (notFound) {
            log.info("学习画像查询未命中: tenant={}, studentId={}",
                    context.tenantId(), studentId);
        }

        List<WeaknessResponse> weaknesses = view.weaknesses().stream()
                .map(w -> new WeaknessResponse(
                        w.knowledgePoint(), w.errorType(), w.confidence(), w.createdAt()))
                .toList();

        return ResponseEntity.ok(new ProfileResponse(
                view.studentId(), view.grade(), view.subject(),
                weaknesses, notFound));
    }

    /**
     * 画像响应。
     *
     * <p>⚠️ <b>字段名一律 camelCase</b>（对齐 {@code CreateRunResponse} / {@code ConfigResponse}）。
     *
     * @param studentId  随机学生标识
     * @param grade      年级；学生不存在时为 {@code null}
     * @param subject    学科；学生不存在时为 {@code null}
     * @param weaknesses 薄弱点列表（结构化，<b>非扁平字符串</b>）
     * @param notFound   学生是否存在；{@code true} 表示该学生尚无记录
     */
    public record ProfileResponse(
            @NonNull String studentId,
            @Nullable String grade,
            @Nullable String subject,
            @NonNull List<WeaknessResponse> weaknesses,
            boolean notFound
    ) {
        public ProfileResponse {
            weaknesses = weaknesses == null ? List.of() : List.copyOf(weaknesses);
        }
    }

    /**
     * 单条薄弱点。
     *
     * @param knowledgePoint 知识点
     * @param errorType      错因分类
     * @param confidence     置信度 {@code [0,1]}；可为空
     * @param createdAt      记录时刻
     */
    public record WeaknessResponse(
            @NonNull String knowledgePoint,
            @NonNull String errorType,
            @Nullable Double confidence,
            @NonNull Instant createdAt
    ) {
    }
}
