package cn.studykid.rag.api.retrieval;

import java.util.List;
import java.util.function.Predicate;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.rag.application.generation.GroundedGenerationService.MissingBasisException;
import cn.studykid.rag.application.retrieval.RetrievalOrchestrator;
import cn.studykid.rag.core.generation.GenerationRequest;
import cn.studykid.rag.core.generation.GenerationResult;
import cn.studykid.rag.core.retrieval.RetrievalRequest;
import cn.studykid.rag.core.retrieval.RetrievalResult;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.core.version.EditionCoordinate.GradeBand;
import cn.studykid.rag.core.version.EditionCoordinate.Publisher;
import cn.studykid.rag.core.version.EditionCoordinate.Subject;
import cn.studykid.rag.core.version.EditionCoordinate.Term;
import cn.studykid.rag.core.version.EditionScope;
import cn.studykid.rag.core.version.EditionScope.Mode;
import cn.studykid.rag.ports.TenantScopedGeneration;
import cn.studykid.rag.ports.TenantScopedRetrieval;

/**
 * 检索 REST 端点（RAG-D02 §6 / RAG-D00 §2.8）。
 *
 * <p>作为主平台 {@code search_knowledge_base} 工具 SPI 的 HTTP 实现入口：
 * 主平台在工具执行事务内传入租户上下文（{@code set_config('app.tenant_id', ...)}），
 * 本控制器不解析令牌、不自建用户体系（RAG-D02 §1.1 单向调用约定）。
 *
 * <p>⚠️ 检索期版本/角色硬过滤由 {@link RetrievalOrchestrator} 与
 * {@code JdbcRetrievalRepository} 双重保证；未校验语料（{@code ver_status&lt;&gt;'VERIFIED'}）
 * 在仓库层已被排除（fail-closed，见 V1 设计约束）。
 *
 * <p><b>端点分工</b>：
 * <ul>
 *   <li>{@code POST /api/v1/retrieval} —— 检索，决定「取哪些语料」；</li>
 *   <li>{@code POST /api/v1/retrieval/generate} —— 生成，把调用方指定的语料
 *       逐字装配成讲解。<b>不自行检索</b>，理由见 {@link GenerationRequestDto}。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/retrieval")
public class RagRetrievalController {

    /**
     * 租户上下文请求头名。
     *
     * <p>⚠️ <b>为什么必须显式携带租户</b>：检索 SQL 以
     * {@code tenant_id = rag_current_tenant()} 收口，而该函数读的是<b>会话变量</b>
     * {@code app.tenant_id}——只在事务内、且只在同一连接上有效。
     * 跨进程 HTTP 调用时主平台的事务上下文<b>不会随之而来</b>；
     * 若不在此重建租户上下文，RLS 会把全部行过滤掉，
     * 表现为「检索永远返回空」且不报任何错。
     */
    public static final String TENANT_HEADER = "X-Tenant-Id";

    private final TenantScopedRetrieval tenantScopedRetrieval;
    private final TenantScopedGeneration tenantScopedGeneration;

    public RagRetrievalController(TenantScopedRetrieval tenantScopedRetrieval,
                                  TenantScopedGeneration tenantScopedGeneration) {
        this.tenantScopedRetrieval = tenantScopedRetrieval;
        this.tenantScopedGeneration = tenantScopedGeneration;
    }

    /**
     * 执行检索。
     *
     * @param tenantId 租户 ID（UUID），来自 {@code X-Tenant-Id} 请求头
     * @param dto      检索请求（经 Bean Validation 校验）
     * @return 检索结果（含命中列表与召回解释）
     */
    @PostMapping
    public RetrievalResultDto retrieve(@RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
                                       @Valid @RequestBody RetrievalRequestDto dto) {
        String tenant = requireTenant(tenantId);
        RetrievalRequest request = toRequest(dto);
        RetrievalResult result = tenantScopedRetrieval.retrieve(tenant, request);
        return RetrievalResultDto.from(result);
    }

    /**
     * 基于调用方指定的依据片段生成讲解（B5）。
     *
     * <p><b>⚠️ 本端点不做检索</b>：{@code hitIds} 必须来自前一次
     * {@link #retrieve} 的命中列表。自行检索会让「依据集合」
     * 退化成服务端黑箱决策，破坏可解释性与可审计性。
     *
     * <p><b>错误语义</b>（运维可区分「调用方错」与「服务坏」）：
     * <ul>
     *   <li>缺/非法 {@code X-Tenant-Id} → 400（调用方配置错误）；</li>
     *   <li>{@code hitIds} 含非法 UUID → 400（入参形态错误）；</li>
     *   <li>{@code hitIds} 取不到（不存在/他租户/来源已撤回）→ 400
     *       （{@link MissingBasisException}）；</li>
     *   <li>依据被闸门滤光 → <b>200 + 空 answer</b>（合法业务结果，
     *       不是故障；调用方应提示学生换种问法）。</li>
     * </ul>
     *
     * @param tenantId 租户 ID（UUID），来自 {@code X-Tenant-Id} 请求头
     * @param dto      生成请求（含依据片段 ID）
     * @return 生成结果（含逐字讲解、依据列表与必须展示的说明）
     */
    @PostMapping("/generate")
    public GenerationResultDto generate(@RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
                                        @Valid @RequestBody GenerationRequestDto dto) {
        String tenant = requireTenant(tenantId);
        GenerationRequest request = toGenerationRequest(dto);
        GenerationResult result;
        try {
            result = tenantScopedGeneration.generate(tenant, request);
        } catch (MissingBasisException e) {
            // ⛔ 映射为 400 而非 500：调用方传了无效依据，
            //    报成 5xx 会让运维去查服务健康而不是查调用方参数。
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return GenerationResultDto.from(result);
    }

    /**
     * 把生成 DTO 映射为领域请求。
     *
     * <p>⚠️ 版本范围与检索端<b>复用同一套</b>映射逻辑（{@link #toScope}），
     * 避免两边对 {@code scopeMode} 的默认值理解不一致。
     */
    private GenerationRequest toGenerationRequest(GenerationRequestDto dto) {
        return new GenerationRequest(
                dto.query(), dto.mode(), toScope(dto.scopeMode(), toCoordinate(dto.edition())),
                dto.hitIds());
    }

    /**
     * 校验并归一租户标识。
     *
     * <p>⚠️ 缺失或非法时**显式报错**而不是静默返回空结果：
     * 「没有租户上下文」是调用方配置错误，必须立刻暴露；
     * 若静默返回空，运维会误判为「语料库里没有相关语料」。
     */
    private String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "缺少租户上下文：请提供 " + TENANT_HEADER + " 请求头（主平台在调用时注入）");
        }
        try {
            return java.util.UUID.fromString(tenantId.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "租户标识不是合法 UUID：" + tenantId);
        }
    }

    private RetrievalRequest toRequest(RetrievalRequestDto dto) {
        EditionCoordinate coordinate = toCoordinate(dto.edition());
        return new RetrievalRequest(dto.query(), dto.mode(),
                toScope(dto.scopeMode(), coordinate),
                dto.knowledgePointId(), dto.subject(), dto.grade(), dto.topK());
    }

    /**
     * 版本范围模式 → {@link EditionScope}。
     *
     * <p>⚠️ 检索端与生成端<b>共用</b>这一个方法：{@code scopeMode} 为 null 时
     * 默认 {@link Mode#STRICT}（严格锁定学生当前版本）。两处各写一份默认值，
     * 就会出现「检索锁版本、生成不锁」的漂移——那正是串版本事故的入口。
     */
    private EditionScope toScope(Mode scopeMode, EditionCoordinate coordinate) {
        Mode mode = scopeMode == null ? Mode.STRICT : scopeMode;
        return switch (mode) {
            case STRICT -> EditionScope.strict(coordinate);
            case PREFERRED_WITH_FALLBACK -> EditionScope.preferredWithFallback(coordinate);
            case CROSS_EDITION -> EditionScope.crossEdition(coordinate);
        };
    }

    /**
     * 把展示名版本轴映射到领域枚举（RAG-D02 §3.1：版本轴可枚举、可比较）。
     * 不匹配展示名即视为非法输入，返回 400（避免「人教版」「人教」被当成不同版本）。
     */
    private EditionCoordinate toCoordinate(EditionDto e) {
        Subject subject = match(Subject.values(), v -> v.displayName().equals(e.subject()), "subject", e.subject());
        GradeBand grade = match(GradeBand.values(), v -> v.displayName().equals(e.grade()), "grade", e.grade());
        Term term = match(Term.values(), v -> v.displayName().equals(e.term()), "term", e.term());
        Publisher publisher = match(Publisher.values(), v -> v.displayName().equals(e.publisher()), "publisher", e.publisher());
        return new EditionCoordinate(subject, grade, term, publisher, e.revisionCode());
    }

    private static <T extends Enum<T>> T match(T[] values, Predicate<T> predicate,
                                              String field, String value) {
        for (T v : values) {
            if (predicate.test(v)) {
                return v;
            }
        }
        throw new ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "未知" + field + "值: " + value);
    }
}
