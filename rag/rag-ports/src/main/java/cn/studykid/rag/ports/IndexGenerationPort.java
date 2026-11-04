package cn.studykid.rag.ports;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.rag.core.indexing.IndexGeneration;

/**
 * 索引代际端口（RAG-D13 §3.5.2 蓝绿切换）。
 *
 * <p><b>⚠️ 为什么写入侧与检索侧都要它</b>：
 * <ul>
 *   <li><b>写入</b>：新向量必须写进「当前该写的代际」——
 *       日常入库写 ACTIVE 代际；构建新代际时写 BUILDING 代际。
 *       若写错代际，向量就落进了一个检索永远不看的空间，表现为「入库成功但搜不到」。</li>
 *   <li><b>检索</b>：稠密通道只能读 ACTIVE 代际的向量，
 *       否则会把两个不可比的向量空间混在一起算余弦相似度。</li>
 * </ul>
 *
 * <p><b>⚠️ 「当前代际」必须由服务端解析，不接受调用方传入</b>：
 * 让调用方指定代际 = 让调用方决定检索看到哪套索引，
 * 一次传错就会让全租户检索到错误的空间。
 */
public interface IndexGenerationPort {

    /**
     * 解析某租户的 ACTIVE 代际号。
     *
     * <p>⚠️ 返回 {@code null} 表示该租户<b>尚无代际记录</b>（迁移前的空库）。
     * 调用方必须决定如何降级：
     * 检索侧应退回「不按代际过滤」（兼容存量），写入侧应退回代际 1。
     * <b>不得静默当作 1</b>——那会在真有多个代际时才暴露问题。
     *
     * @param tenantId 租户 ID（全局桶传全局哨兵）
     * @return ACTIVE 代际号；无记录时 null
     */
    @Nullable Integer activeGeneration(@NonNull String tenantId);

    /**
     * 列出某租户的全部代际（按代际号降序）。
     *
     * <p>⚠️ 接收 {@code tenantId} 而非作用域上下文：本端口在 rag-ports 层，
     * 不认识 management 模块的 {@code RagManagementContext}。
     * 实现方负责建立 RLS 会话（表受 FORCE RLS 约束）。
     *
     * @param tenantId 租户 ID（全局桶传全局哨兵）
     * @return 代际列表
     */
    @NonNull List<IndexGeneration> listGenerations(@NonNull String tenantId);

    /**
     * 创建新代际（状态 BUILDING），代际号 = 当前最大 + 1。
     *
     * @param tenantId   租户 ID
     * @param embedModel 嵌入模型标识
     * @param dimension  向量维度
     * @param totalChunks 预期片段总数（用于进度）
     * @return 新建的代际
     */
    @NonNull IndexGeneration createGeneration(@NonNull String tenantId,
                                              @NonNull String embedModel,
                                              int dimension,
                                              long totalChunks);

    /**
     * 切换到指定代际（蓝绿切换 / 回滚）。
     *
     * <p>⚠️ 语义：目标代际置 ACTIVE，原 ACTIVE 置 RETIRED。
     * 两者在同一事务内完成——否则中间态会出现「零个 ACTIVE」（检索空转）
     * 或「两个 ACTIVE」（检索不确定读哪套）。
     *
     * @param tenantId   租户 ID
     * @param generation 目标代际号
     * @return 切换后的目标代际
     * @throws IllegalArgumentException 目标代际不存在或处于 BUILDING（不可激活）
     */
    @NonNull IndexGeneration activateGeneration(@NonNull String tenantId, int generation);

    /**
     * 更新构建进度。
     *
     * @param tenantId      租户 ID
     * @param generation    代际号
     * @param indexedChunks 已写入行数
     */
    void updateProgress(@NonNull String tenantId, int generation, long indexedChunks);

    /**
     * 更新构建进度**与游标**（异步分批推进）。
     *
     * <p>⚠️ 两者必须在**同一次更新**里写：若先更新游标再更新进度（或反之），
     * 中途崩溃会让二者不一致——worker 会从一个"进度说有 500、游标还停在第 0 条"
     * 的矛盾状态续跑，结果重复嵌入或跳过片段。
     *
     * @param tenantId      租户 ID
     * @param generation    代际号
     * @param indexedChunks 已处理片段数
     * @param totalChunks   目标总数（0 表示尚未统计；统计后保持不变）
     * @param buildCursor   最后处理的 chunk_id；null 表示尚未开始
     */
    void updateBuildState(@NonNull String tenantId, int generation,
                          long indexedChunks, long totalChunks,
                          @Nullable String buildCursor);

    /**
     * 找出一个「待构建」的代际（RAG-D13 §3.5.2 异步构建）。
     *
     * <p><b>⚠️ 什么算「待构建」</b>：状态为 {@code BUILDING} 且
     * {@code indexed_chunks < total_chunks}——即尚未建完。
     * {@code total_chunks = 0} 表示"刚创建、还没开始"，也算待构建。
     *
     * <p><b>⚠️ 为什么按租户查而不是跨租户扫全表</b>：
     * worker 以非特权角色连接，{@code rag_index_generation} 受 RLS FORCE 约束，
     * 未设会话租户的查询读不到任何行——跨租户扫描会「看起来在跑、实际永远领不到」。
     * 租户清单由 {@code rag.indexer.tenants} 显式给出（与 {@code IngestJobConsumer} 同口径）。
     *
     * @param tenantId 租户 ID
     * @return 待构建的代际；没有则 null
     */
    @Nullable IndexGeneration findPendingBuild(@NonNull String tenantId);

    /**
     * 标记构建已全部完成（异步构建的收尾）。
     *
     * <p><b>⚠️ 为什么需要显式的完成标记，而不是靠 {@code indexed == total} 推断</b>：
     * 计数相等有两种含义——"刚建完但还没扫到末尾确认" 与 "确实建完了"。
     * 若只用计数判断，worker 要么永远不再捡起（构建停在 BUILDING 无法切换），
     * 要么反复捡起已完成的代际（空转刷日志）。
     * 用一个显式标记把两者分开。
     *
     * <p>实现约定：把 {@code total_chunks} 对齐为 {@code indexed_chunks}
     * 并在 {@code note} 写入完成标记；{@code status} 仍保持 BUILDING
     * （是否上线由人决定，构建完成 ≠ 自动切换）。
     *
     * @param tenantId   租户 ID
     * @param generation 代际号
     */
    void markBuildFinished(@NonNull String tenantId, int generation);
}
