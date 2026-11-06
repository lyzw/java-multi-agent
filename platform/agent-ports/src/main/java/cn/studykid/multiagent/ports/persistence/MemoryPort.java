package cn.studykid.multiagent.ports.persistence;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.memory.MemoryEntry;

/**
 * 跨 Run 记忆端口（P1 · {@code docs/Agent记忆机制分析与设计方案.md}）。
 *
 * <p><b>要解决的问题</b>：当前每次 Run 都以
 * {@code messages.add(ModelMessage.user(renderInput(input)))} 为唯一种子
 * （{@code RunExecutionLoop:466–469}），模型对"这个学生以前错过什么"
 * 一无所知。唯一的跨 Run 通道是 {@code get_student_profile} <b>工具</b>，
 * 而工具是"模型决定要不要调"——它不调，记忆就等于不存在。
 * 本端口提供的是<b>不依赖模型主动性的被动记忆</b>。
 *
 * <p>⚠️⚠️ <b>记忆【不得】成为工具</b>：写记忆不经
 * {@code ToolInvocationGate} / {@code CapExecutionFlow} 15 步。
 * 理由：工具调用是<b>有副作用的动作</b>，必须有审批、幂等、预算、
 * 风险分级、审计一整套护栏；而记忆整合是<b>从已落库的证据派生</b>
 * （{@code weakness_record} 已经过闸门写入了），是纯粹的内部状态维护。
 * 若把记忆做成工具，模型就能"主动让系统记住某件事"——
 * 那等于绕过全部证据链，让模型自己给自己下结论（R-2 自我强化）。
 *
 * <h2>四个方法各自的调用时机</h2>
 * <ol>
 *   <li>{@link #recall} —— Run <b>开始</b>时一次（{@code RunExecutionLoop}）。
 *       续跑（{@code resume}）<b>不</b>召回：同一 Run 的上下文已存在，
 *       再召回等于在同一个决策里插入新证据。</li>
 *   <li>{@link #consolidate} —— Run 进入终态<b>之后</b>。
 *       数据来源只能是 {@link ResultQueryPort#findResult} 返回的
 *       {@code weaknesses()}（即 {@code weakness_record}），
 *       <b>不得</b>从模型输出直接派生。</li>
 *   <li>{@link ErasurePort#purgeExpiredMemories} —— 保留期清理（第 5 类）。
 *       与画像清理<b>同期</b>：记忆是从画像派生的学习状态，
 *       画像删了而记忆还活着 = "结论比证据活得久"。
 *       ⚠️ 刻意放在 {@link ErasurePort} 而不是本端口：清理走<b>独立凭据</b>
 *       （{@code agent_retention}），而本端口用应用凭据。</li>
 *   <li>{@link #forget} —— 监护人撤回同意时<b>显式</b>删除。
 *       虽然 {@code ON DELETE CASCADE} 已经会跟着
 *       {@code student_profile} 一起删，但那只是<b>一条</b>路径。
 *       合规删除不能依赖"恰好有 FK"这一个偶然条件。</li>
 * </ol>
 *
 * <h2>实现必须遵守的纪律</h2>
 * <ul>
 *   <li><b>RLS</b>：{@code student_memory} 与 {@code student_profile} 一样是
 *       租户表，必须 {@code ENABLE + FORCE ROW LEVEL SECURITY}，
 *       且每个方法都<b>在事务内先绑定租户</b>再执行业务 SQL
 *       （{@code TransactionalConnectionHolder} / {@code TenantSessionBinder}）。
 *       顺序颠倒或漏绑 → 静默可见 0 行，<b>不报错</b>。</li>
 *   <li><b>幂等</b>：{@code consolidate} 会被 MQ 重投与 {@code resume}
 *       重复触发。<b>只有当 {@code last_run_id} 与本次 Run 不同时</b>
 *       才允许 {@code evidence_count + 1}；否则同一 Run 的重复整合会
 *       把一个知识点从"错 1 次"刷成"错 3 次"，直接跨过召回门槛。</li>
 *   <li><b>不抛异常穿透</b>：记忆是可选增强，失败必须<b>降级为"这次没有记忆"</b>
 *       并打 WARN，绝不能因为记忆不可用让 Run 失败。</li>
 * </ul>
 */
public interface MemoryPort {

    /**
     * 召回该学生的历史记忆（按强度倒序，最多 {@code k} 条）。
     *
     * <p>⚠️ <b>本方法在 P1 不接受 query 参数</b>。理由：P1 是结构化召回
     * （按 {@code strength} 排序 + 知识点前缀匹配），没有可用的语义相似度；
     * 硬塞一个 {@code query} 会让调用方误以为"传了就会做相关性过滤"。
     * P2 引入 pgvector 后才加重载（{@code query} 可为 {@code null}）。
     *
     * <p>⚠️ <b>实现必须自行按"当前时间"重算强度</b>，不能直接用库里的
     * {@code strength} 列排序后原样返回：那一列是<b>写入时</b>的值。
     * 靠"夜里跑批刷新"意味着白天读到的是过期强度。
     *
     * @param tenantId  租户
     * @param studentId 学生标识（{@code stu_ + 16 位小写十六进制}）
     * @param k         条数上限（必须为正数；实现可再按自身上限收紧）
     * @param maxTokens 渲染后的 token 上限（必须为正数；超出则少返回几条）
     * @return 记忆列表，按强度倒序；无记忆返回空列表（<b>恒非 null</b>）
     */
    @NonNull
    List<MemoryEntry> recall(@NonNull String tenantId,
                             @NonNull String studentId,
                             int k,
                             int maxTokens);

    /**
     * 从 {@code weakness_record} 派生/更新该学生的记忆条目。
     *
     * <p>对每个知识点：不存在则新建（{@code evidence_count = 1}）；
     * 已存在且 {@code last_run_id} 与本次 Run <b>不同</b>则
     * {@code evidence_count + 1}、刷新 {@code last_seen_at} 与 {@code strength}；
     * 已存在且 {@code last_run_id} <b>相同</b>则<b>原样返回，不增计数</b>。
     *
     * @param tenantId  租户
     * @param studentId 学生标识
     * @param runId     触发本次整合的 Run
     * @return 受影响（新建或更新）的条目数；无可整合证据返回 0
     */
    int consolidate(@NonNull String tenantId, @NonNull String studentId, @NonNull String runId);

    /**
     * 删除某学生的<b>全部</b>记忆（监护人撤回同意）。
     *
     * <p>⚠️ 这是 {@code ON DELETE CASCADE} 之外的<b>第二道</b>保险：
     * 级联只在"确实删了 profile"时生效，而撤回流程存在
     * {@code markWithdrawalFailed} 等分支 —— 那些分支下 profile 可能还在。
     *
     * <p>⚠️ <b>保留期清理不经过本方法</b>：那条路径走 {@link ErasurePort}
     * （独立清理凭据 + 统一清理报告）。若这里也留一个"按时间删"的方法，
     * 就会出现两个都能删记忆的入口 —— 一个接线了、另一个没接线，
     * 而症状完全相同（"记忆没被清理"）。
     *
     * @param tenantId  租户
     * @param studentId 学生标识
     * @return 实际删除行数
     */
    int forget(@NonNull String tenantId, @NonNull String studentId);

    /**
     * 该学生当前的记忆条数（<b>观测与连通性断言用</b>）。
     *
     * <p>⚠️ 存在的唯一理由：{@code MemoryWiringIT} 需要断言
     * "整合确实落库了"。本项目的既往事故形态正是
     * "机制正确但从未接线"（P4-D08 R-1：三个类零生产实例化 →
     * {@code weakness_record} 永远为空，而全部测试全绿）。
     * 没有这个计数，就无从在集成测试里区分
     * "整合逻辑写对了" 与 "整合逻辑根本没被调用"。
     *
     * @param tenantId  租户
     * @param studentId 学生标识
     * @return 条数（{@code >= 0}）
     */
    int countMemories(@NonNull String tenantId, @NonNull String studentId);

    /**
     * 可选：带查询语义的召回（P2 / pgvector）。
     *
     * <p>⚠️ <b>默认实现直接退化为 {@link #recall}</b>：
     * 未装配向量能力时，"按相似度召回"与"按强度召回"必须给出
     * <b>可用</b>的结果，而不是空列表 —— 空列表会让上层误判
     * "这个学生没有历史记忆"，而真相是"向量索引没建"。
     *
     * @param tenantId  租户
     * @param studentId 学生标识
     * @param query     查询文本；{@code null} = 不做语义过滤
     * @param k         条数上限
     * @param maxTokens token 上限
     * @return 记忆列表
     */
    @NonNull
    default List<MemoryEntry> recall(@NonNull String tenantId,
                                     @NonNull String studentId,
                                     @Nullable String query,
                                     int k,
                                     int maxTokens) {
        return recall(tenantId, studentId, k, maxTokens);
    }
}
