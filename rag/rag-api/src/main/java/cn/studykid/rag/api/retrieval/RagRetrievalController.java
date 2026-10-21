package cn.studykid.rag.api.retrieval;

import java.util.List;
import java.util.function.Predicate;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

/**
 * 检索 REST 端点（RAG-D02 §6 / RAG-D00 §2.8）。
 *
 * <p>作为主平台 {@code search_knowledge_base} 工具 SPI 的 HTTP 实现入口：
 * 主平台在工具执行事务内传入租户上下文（{@code set_config('app.tenant_id', ...)}），
 * 本控制器不解析令牌、不自建用户体系（RAG-D02 §1.1 单向调用约定）。
 *
 * <p>⚠️ 检索期版本/角色硬过滤由 {@link RetrievalOrchestrator} 与
 * {@code JdbcRetrievalRepository} 双重保证；未校验语料（{@code ver_status<>'VERIFIED'}）
 * 在仓库层已被排除（fail-closed，见 V1 设计约束）。
 */
@RestController
@RequestMapping("/api/v1/retrieval")
public class RagRetrievalController {

    private final RetrievalOrchestrator orchestrator;

    public RagRetrievalController(RetrievalOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * 执行检索。
     *
     * @param dto 检索请求（经 Bean Validation 校验）
     * @return 检索结果（含命中列表与召回解释）
     */
    @PostMapping
    public RetrievalResultDto retrieve(@Valid @RequestBody RetrievalRequestDto dto) {
        RetrievalRequest request = toRequest(dto);
        RetrievalResult result = orchestrator.retrieve(request);
        return RetrievalResultDto.from(result);
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
