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

import cn.studykid.rag.application.retrieval.RetrievalOrchestrator;
import cn.studykid.rag.core.retrieval.RetrievalRequest;
import cn.studykid.rag.core.retrieval.RetrievalResult;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.core.version.EditionCoordinate.GradeBand;
import cn.studykid.rag.core.version.EditionCoordinate.Publisher;
import cn.studykid.rag.core.version.EditionCoordinate.Subject;
import cn.studykid.rag.core.version.EditionCoordinate.Term;
import cn.studykid.rag.core.version.EditionScope;
import cn.studykid.rag.core.version.EditionScope.Mode;
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

    public RagRetrievalController(TenantScopedRetrieval tenantScopedRetrieval) {
        this.tenantScopedRetrieval = tenantScopedRetrieval;
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
        EditionScope scope = switch (dto.scopeMode() == null ? Mode.STRICT : dto.scopeMode()) {
            case STRICT -> EditionScope.strict(coordinate);
            case PREFERRED_WITH_FALLBACK -> EditionScope.preferredWithFallback(coordinate);
            case CROSS_EDITION -> EditionScope.crossEdition(coordinate);
        };
        return new RetrievalRequest(dto.query(), dto.mode(), scope,
                dto.knowledgePointId(), dto.subject(), dto.grade(), dto.topK());
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
