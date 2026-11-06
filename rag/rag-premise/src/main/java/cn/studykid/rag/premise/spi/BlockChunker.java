package cn.studykid.rag.premise.spi;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.premise.model.ChunkContext;
import cn.studykid.rag.premise.model.ChunkOutcome;
import cn.studykid.rag.premise.model.ChunkPolicy;
import cn.studykid.rag.premise.model.ChunkPolicyConfig;
import cn.studykid.rag.premise.model.PremiseDocument;

/**
 * 切分器 SPI（RAG-D26 §2.3）。
 *
 * <p><b>⚠️ 输入恒为归一化文档</b>：两个切分器（标题层级 / HybridChunker）
 * 看到的都是同一条归一化流水线的产物——这是「换解析器 / 换切分器可正交评测」
 * 的前提（RAG-D26 §2.1 的核心设计点）。
 *
 * <p><b>⚠️ 实现必须确定性</b>：同一输入同一配置 → 同一输出。
 * {@code chunk_id} 由「documentId:页码:字符偏移」派生（name-UUID），
 * 不确定的切分会让重跑产生不同 id，upsert 去重语义失效。
 *
 * <p><b>⚠️ 角色判定不在本 SPI 内</b>：{@code ChunkRole} 由
 * {@code role/BlockRoleResolver}（从既有管道原样搬出的判定表）给出，
 * 切分器只负责<b>边界</b>。对 HybridChunker 这类自身不带角色的策略，
 * 角色回填由实现内部调用 resolver 完成（见 RAG-D26 §2.3 的泄题风险）。
 *
 * <p><b>⚠️ 失败必须抛异常（fail-closed）</b>：例如 HybridChunker 依赖的
 * Python 分块桥不可用时，实现类必须抛异常让 job FAILED，
 * <b>不得</b>静默回退到另一个策略——策略切换是留痕事实，
 * 静默替换会让 {@code chunk_policy} 失真，且两策略 token 边界不同，
 * 检索表现不可比。
 *
 * <p>实现类约束：线程安全、无状态、词汇表顶层声明（RAG-D09 §6）。
 */
public interface BlockChunker {

    /**
     * @return 策略标识（对应 {@link ChunkPolicy} 枚举值）
     */
    @NonNull ChunkPolicy policy();

    /**
     * 切分归一化文档。
     *
     * @param document 归一化文档（含解析留痕）
     * @param config   切分参数
     * @param context  切分上下文（版本坐标 + 知识点表；{@code Chunk} 的领域不变式
     *                 要求完整版本坐标，而它是入库上下文不是解析产物）
     * @return 切分输出（含留痕）
     */
    @NonNull ChunkOutcome chunk(@NonNull PremiseDocument document,
                                @NonNull ChunkPolicyConfig config,
                                @NonNull ChunkContext context);
}
