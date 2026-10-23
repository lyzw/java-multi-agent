package cn.studykid.rag.application.generation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.generation.GenerationRequest;
import cn.studykid.rag.core.generation.GenerationResult;
import cn.studykid.rag.core.retrieval.RetrievalPolicy;
import cn.studykid.rag.ports.RetrievalPort;

/**
 * 接地生成用例（RAG-D00 §2.7 决策 D-R09 / B5）。
 *
 * <p><b>⚠️ 这个类刻意不做「生成」</b>。它做的是三件确定性的事：
 * <ol>
 *   <li><b>校验依据</b>：调用方给的 {@code hitIds} 是否真的存在、
 *       是否属于本租户、是否是合法 UUID；</li>
 *   <li><b>应用闸门</b>：与检索期<b>同源</b>的角色过滤（泄题）与版本过滤（串版本）；</li>
 *   <li><b>逐字装配</b>：把通过闸门的片段<b>原文</b>连同版本页码呈现出来。</li>
 * </ol>
 *
 * <p><b>为什么这样设计而不是接一个大模型</b>：
 * 见 {@link cn.studykid.rag.core.grounding.GroundingPolicy} 的核心立场——
 * 本系统的正确行为不是「总是给出答案」，而是「只给出可验证的答案」。
 * 在真实模型接入前（RAG-D09 G3 条件，受 A-008 阻断），
 * 逐字摘录是<b>唯一</b>不可能产生幻觉的形态；它不够「聪明」，
 * 但它的每一句话学生都能翻书核对。
 *
 * <p><b>⚠️ 过滤规则不得在本类另写一套</b>：
 * 角色过滤必须复用 {@link RetrievalPolicy#allowsRole}——那是全项目
 * <b>唯一</b>的角色规则来源。在生成期复制一份规则，
 * 就是制造「检索期不泄题但生成期泄题」的漂移窗口。
 */
public class GroundedGenerationService {

    private final RetrievalPort retrievalPort;

    /**
     * @param retrievalPort 检索端口（复用一个读取方法，见
     *                      {@link RetrievalPort#loadByIds}）
     */
    public GroundedGenerationService(@NonNull RetrievalPort retrievalPort) {
        if (retrievalPort == null) {
            throw new IllegalArgumentException("retrievalPort 不能为空");
        }
        this.retrievalPort = retrievalPort;
    }

    /**
     * 生成讲解。
     *
     * <p><b>执行顺序不可调换</b>（与 {@code RetrievalOrchestrator} 同构的理由）：
     * <pre>
     *   ① 入参形态校验   → 非法 UUID 直接拒（400），不进 SQL
     *   ② 依据取回       → 租户 + VERIFIED 双条件收口
     *   ③ 存在性校验     → 取不到的 ID 显式报错，不静默丢弃
     *   ④ 角色过滤       → 泄题闸门（与检索期同源）
     *   ⑤ 版本过滤       → 串版本闸门（与检索期同源）
     *   ⑥ 逐字装配       → 只引用原文，不产生新陈述
     * </pre>
     *
     * @param request 生成请求
     * @return 生成结果
     * @throws IllegalArgumentException 入参含非法 UUID（调用方错误，映射 400）
     * @throws MissingBasisException   依据片段不存在或不属于本租户（映射 400）
     */
    public @NonNull GenerationResult generate(@NonNull GenerationRequest request) {
        // ① 入参形态校验——非法 UUID 必须在进 SQL 前拦住，
        //    否则 Postgres 会抛 22P02，被包装成 500（把调用方错误报成服务故障）。
        List<String> validIds = new ArrayList<>(request.hitIds().size());
        for (String id : request.hitIds()) {
            validIds.add(parseUuid(id));
        }

        // ② 依据取回（租户上下文由外层 TenantScopedGeneration 建立）
        List<Chunk> loaded = retrievalPort.loadByIds(validIds);

        // ③ 存在性校验
        //    ⛔ 绝不静默丢弃：调用方传了 5 个 ID 只取回 3 个，
        //       若照常装配，学生看到的「依据」比实际引用的少，
        //       而调用方以为全部都用上了——这是最坏的一类静默故障。
        Set<String> found = new LinkedHashSet<>();
        for (Chunk c : loaded) {
            found.add(c.id());
        }
        List<String> missing = validIds.stream().filter(id -> !found.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new MissingBasisException(missing);
        }

        // ④ 角色过滤——泄题闸门（与检索期同源，不另写规则）
        List<Chunk> roleAllowed = RetrievalPolicy.applyRoleFilter(loaded, request.mode());
        boolean suppressed = roleAllowed.size() < loaded.size();
        if (roleAllowed.isEmpty()) {
            return GenerationResult.insufficient(
                    "指定的 " + loaded.size() + " 处依据在当前「" + modeLabel(request.mode())
                            + "」模式下不可引用（答案类内容已被策略排除）。");
        }

        // ⑤ 版本过滤——串版本闸门
        List<Chunk> versionAllowed = RetrievalPolicy.applyEditionFilter(
                roleAllowed, request.editionScope());
        boolean versionMismatch = RetrievalPolicy.detectVersionMismatch(
                roleAllowed, request.editionScope());
        if (versionAllowed.isEmpty()) {
            return GenerationResult.insufficient(
                    "指定的依据与学生当前的教材版本不一致。请确认版本五轴是否填对，"
                            + "或把版本范围改成「优先本版，可回退同册」。");
        }

        // ⑥ 逐字装配
        return new GenerationResult(
                assemble(request, versionAllowed),
                versionAllowed,
                GenerationResult.GENERATOR_EXCERPT_ASSEMBLY,
                buildDisclaimer(versionMismatch, suppressed),
                versionMismatch,
                suppressed);
    }

    /**
     * 校验并归一 UUID。
     *
     * <p>⚠️ 归一为 {@code UUID.toString()} 的小写规范形式：
     * 数据库按 {@code uuid} 类型比较，大小写不影响相等性，
     * 但归一后可在内存里用字符串集合做存在性比对（见 ③）。
     */
    private static String parseUuid(String raw) {
        try {
            return UUID.fromString(raw.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("片段 ID 不是合法 UUID：" + raw);
        }
    }

    /**
     * 装配讲解正文。
     *
     * <p><b>⚠️ 只允许两类内容进入正文</b>：
     * <ul>
     *   <li>片段的<b>逐字原文</b>（引号内，一个字都不改）；</li>
     *   <li>由片段派生的事实性陈述（条数、版本名、页码）。</li>
     * </ul>
     * 任何「因为……所以……」式的推演都不得出现——那需要蕴含判定，
     * 而当前 {@code EntailmentPort} 是本地确定性占位（RAG-D09 诚实边界）。
     */
    private static String assemble(GenerationRequest request, List<Chunk> basis) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下内容逐字摘自你正在使用的教材，未作改写，可对照原书核对。\n\n");
        for (int i = 0; i < basis.size(); i++) {
            Chunk c = basis.get(i);
            sb.append("【依据 ").append(i + 1).append("】")
                    .append(c.edition().display())
                    .append(" · ").append(c.locator().display()).append('\n');
            sb.append(c.text().strip()).append('\n');
            if (i < basis.size() - 1) {
                sb.append('\n');
            }
        }
        // ⚠️ 结尾不写「综上可知……」——没有蕴含判定就没有结论。
        //    空 query 也不构造反问句，避免把系统的局限包装成对话。
        if (request.query() != null && !request.query().isBlank()) {
            sb.append("\n（以上为与「").append(request.query().strip()).append("」相关的教材原文。）");
        }
        return sb.toString();
    }

    /**
     * 构造必须随回答展示的说明。
     *
     * <p>⚠️ 空串表示「无附加说明」；非空时前端<b>必须</b>展示，
     * 否则学生无法知道「答案被策略藏起来了」或「这段来自其他版本」。
     */
    private static String buildDisclaimer(boolean versionMismatch, boolean suppressed) {
        List<String> parts = new ArrayList<>(2);
        if (suppressed) {
            parts.add("部分依据属于答案类内容，已按当前讲解策略排除，未纳入上文。");
        }
        if (versionMismatch) {
            parts.add("部分依据来自你当前版本以外的教材，引用前请与手上课本核对。");
        }
        return String.join(" ", parts);
    }

    private static String modeLabel(cn.studykid.rag.core.retrieval.RetrievalRequest.RetrievalMode mode) {
        return switch (mode) {
            case EXPLAIN -> "讲解";
            case SOLVE -> "解题";
            case VERIFY -> "核对";
            case OVERVIEW -> "概览";
        };
    }

    /**
     * 依据缺失异常。
     *
     * <p>⚠️ 与「依据不足」{@link GenerationResult#insufficient} 是两回事：
     * <ul>
     *   <li><b>缺失</b> = 调用方传了取不到的 ID → <b>调用方错误</b>，映射 400；</li>
     *   <li><b>不足</b> = ID 都取到了，但被闸门滤光 → <b>合法业务结果</b>，返回 200。</li>
     * </ul>
     * 把前者也返回 200 会让调用方无法区分「我填错了」与「语料里没有」。
     */
    public static final class MissingBasisException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final List<String> missingIds;

        public MissingBasisException(@NonNull List<String> missingIds) {
            super("依据片段不存在或不属于当前租户：" + String.join(", ", missingIds));
            this.missingIds = List.copyOf(missingIds);
        }

        public @NonNull List<String> missingIds() {
            return missingIds;
        }
    }
}
