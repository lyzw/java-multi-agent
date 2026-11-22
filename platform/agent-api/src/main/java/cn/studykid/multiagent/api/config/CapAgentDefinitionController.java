package cn.studykid.multiagent.api.config;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.governance.GovernanceSupport;
import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 多级 Agent 定义的结构化读取端点（第三十四批 · C34-2）。
 *
 * <p>⚠️ <b>为什么需要它</b>：管理台的 Agent 详情页此前只能从
 * {@code GET /api/agents/{id}} 拿到一个 {@code latestDefinition} 字符串 ——
 * 那是<b>旧体系</b>（{@code agent} / {@code agent_version}）的不可变快照，
 * 与多级 Agent 的权威定义（{@code cap_agent_def}）<b>不是同一份东西</b>。
 * 结果是详情页的「关系字段」（启用哪些 Skill / 声明哪些子 Agent /
 * 路由策略引用 / 输出契约引用）**取不到**，
 * 编辑表单只能靠人工重填 —— 而「取不到就当空」意味着
 * <b>改一下就把关系静默清空</b>。
 *
 * <p>⚠️ <b>本端点返回的是权威定义（{@code cap_agent_def}）</b>，
 * 不是旧体系的快照 —— 详情页要回填、要编辑、要提交的对象是它。
 *
 * <p>⚠️ <b>编排声明按<b>原文</b>返回</b>（与 {@code overrides} 同口径）：
 * 编排的<b>结构化解析</b>由前端已有的解析器完成
 * （与后端 {@code AgentOrchestrationParser} 对齐），
 * 后端在此处**不重复解析一遍** —— 两份解析器迟早漂移，
 * 而漂移出来的差异只会出现在「回填」这一环。
 * ⚠️ <b>原文优先</b>：把原文交给前端，前端解析失败时能**显式报错**；
 * 若后端先解析再序列化，解析失败就只剩一个「500」，前端无从判断
 * 「是我读错了」还是「配置本身非法」。
 */
@RestController
@RequestMapping("/api/cap/agents")
public class CapAgentDefinitionController {

    private final CapAgentDefPort agentDefs;
    private final AuthorizationService authorization;
    private final RateLimitPort rateLimiter;
    private final CurrentPrincipal currentPrincipal;

    public CapAgentDefinitionController(CapAgentDefPort agentDefs,
                                         AuthorizationService authorization,
                                         RateLimitPort rateLimiter,
                                         CurrentPrincipal currentPrincipal) {
        this.agentDefs = agentDefs;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
    }

    /**
     * 读取某个 Agent 的权威定义（{@code cap_agent_def} 的最新版本）。
     *
     * @param agentId Agent 标识
     * @return 结构化定义
     */
    @GetMapping("/{agentId}/definition")
    public ResponseEntity<AgentDefinitionResponse> getDefinition(@PathVariable String agentId) {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.AGENT_MANAGE, null);
        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁");
        }
        CapAgentDef def = agentDefs.findLatest(context.tenantId(), agentId)
                .or(() -> agentDefs.findLatestPublished(context.tenantId(), agentId))
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "资源不存在"));
        return ResponseEntity.ok(AgentDefinitionResponse.of(def));
    }

    /**
     * 读取某个 Agent 的<b>全部历史版本</b>（{@code cap_agent_def} 不可变版本行，版本号降序）。
     *
     * <p>⚠️ <b>为什么需要它</b>：变更记录（{@code /platform/v1/capabilities/agents/{id}/changes}）
     * 只回管理元数据、<b>不含定义正文</b>（平台边界纪律）。而「某条变更前后的配置差异」
     * 必须拿到两侧的完整配置才说得清 —— 正文只能从版本快照里取。
     * 本端点按版本返回<b>完整结构化定义</b>，供变更对比（Diff）使用。
     *
     * <p>⚠️ <b>来源是权威表 {@code cap_agent_def}，不是 {@code agent_version}</b>：
     * 后者只是历史快照归档，运行期语义（提示词 / 模型 / 生成参数 / 编排）以本表为准；
     * 拿旧快照做 diff 会对比出「看起来变了、其实运行期没变」的假差异。
     *
     * <p>⚠️ <b>平台边界</b>：本端点走 {@code AGENT_MANAGE} 鉴权（与
     * {@code /agents/{id}/definition} 同口径）；Agent 可尚无租户绑定，
     * 故用 {@link cn.studykid.multiagent.ports.persistence.CapAgentDefPort#listVersionsGlobal}
     * 的全局口径读取（按绑定过滤会读不到未绑定租户的 Agent）。
     *
     * @param agentId Agent 标识
     * @return 全部版本行，按版本号降序；无任何版本时 404（与单对象读取一致）
     */
    @GetMapping("/{agentId}/versions")
    public ResponseEntity<List<AgentDefinitionResponse>> listDefinitions(
            @PathVariable String agentId) {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.AGENT_MANAGE, null);
        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁");
        }
        var versions = agentDefs.listVersionsGlobal(agentId);
        if (versions.isEmpty()) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "资源不存在");
        }
        return ResponseEntity.ok(versions.stream().map(AgentDefinitionResponse::of).toList());
    }

    /**
     * 多级 Agent 的结构化定义视图。
     *
     * @param agentId        Agent 标识
     * @param version        定义版本号
     * @param name           名称
     * @param description    描述
     * @param roles          角色集
     * @param enabledSkills  启用的 Skill 集
     * @param skillBundles   Skill 包集
     * @param capabilityTags 能力标签集
     * @param overrides      收紧覆盖（<b>JSON 原文</b>）
     * @param orchestration  编排声明（<b>JSON 原文</b>）
     * @param instructions   提示词声明（<b>JSON 原文</b>，缺省 {@code "{}"} = 未声明）
     * @param modelRef       绑定的模型引用（{@code null} = 未绑定，运行期 fail-closed）
     * @param generationParams 生成参数（<b>JSON 原文</b>，缺省 {@code "{}"} = 运行期默认值）
     * @param published      该版本行是否已发布
     * @param createdAt      版本行创建时间（= 该次配置保存的变更时间，供版本时间线展示）
     * @param publishedAt    发布时间（{@code null} = 草稿）
     */
    public record AgentDefinitionResponse(
            @NonNull String agentId,
            int version,
            @Nullable String name,
            @Nullable String description,
            @NonNull List<String> roles,
            @NonNull List<String> enabledSkills,
            @NonNull List<String> skillBundles,
            @NonNull List<String> capabilityTags,
            @NonNull String overrides,
            @NonNull String orchestration,
            @NonNull String instructions,
            @Nullable String modelRef,
            @NonNull String generationParams,
            boolean published,
            /** 版本行创建时间（版本变更时间）；老数据可能为空，故可空。 */
            @Nullable Instant createdAt,
            /** 发布时间；未发布为 null。 */
            @Nullable Instant publishedAt
    ) {
        static AgentDefinitionResponse of(CapAgentDef def) {
            // ⚠️ 提示词 / 模型 / 生成参数必须回显：编辑既有 Agent 时前端靠这个 DTO
            //    还原草稿，缺了它们会在「读 → 改 → 存」时把三个字段静默覆盖成空值。
            return new AgentDefinitionResponse(
                    def.agentId(), def.version(), def.name(), def.description(),
                    def.roles(), def.enabledSkills(), def.skillBundles(), def.capabilityTags(),
                    def.overrides(), def.orchestration(),
                    def.instructions(), def.modelRef(), def.generationParams(),
                    def.isPublished(), def.createdAt(), def.publishedAt());
        }
    }
}
