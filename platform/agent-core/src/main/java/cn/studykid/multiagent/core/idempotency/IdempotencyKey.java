package cn.studykid.multiagent.core.idempotency;

import java.time.Duration;
import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 幂等键（ADR-003）。
 *
 * <p><b>键 = 命名空间 + 具体键值</b>。命名空间不可省略，原因：
 * 不同用途的键若混在一起，`run:xxx` 与 `tool:xxx` 可能在同一租户内撞值，
 * 导致「用工具键去查 Run」的错误复用。
 *
 * <p><b>首期键定义</b>（P0-D00 §3.2）：
 * <table border="1">
 *   <caption>幂等键构造规则</caption>
 *   <tr><th>用途</th><th>命名空间</th><th>键值构造</th></tr>
 *   <tr><td>创建 Run</td><td>{@code run.create}</td>
 *       <td>调用方提供（建议 UUID）</td></tr>
 *   <tr><td>{@code record_weakness}</td><td>{@code tool.record_weakness}</td>
 *       <td>{@code student_id + run_id + knowledge_point}</td></tr>
 *   <tr><td>其他工具调用</td><td>{@code tool.invoke}（P4-D08）</td>
 *       <td>{@code run_id + [agent_ref + '/'] + tool_name + call_id}
 *           （{@code agent_ref} 为<b>可省略</b>段，见
 *           {@link #forToolInvoke}）</td></tr>
 * </table>
 *
 * <p><b>分隔符用 {@code '/'} 而非 {@code '|'}</b>：管道符在日志、SQL LIKE、
 * 正则表达式中语义复杂，容易引发转义问题。斜杠更安全，且天然可读。
 *
 * <p>⚠️ <b>键值不得包含学生可识别信息</b>（P0-D00 §4 白名单）：
 * {@code student_id} 必须是随机标识，不能是姓名/学号。
 */
public record IdempotencyKey(@NonNull String namespace, @NonNull String keyValue) {

    /** 分隔符。刻意避开管道符——见类注释。 */
    public static final String SEPARATOR = "/";

    /** 创建 Run 的命名空间。 */
    public static final String NS_RUN_CREATE = "run.create";

    /** 记录薄弱点的命名空间。 */
    public static final String NS_TOOL_RECORD_WEAKNESS = "tool.record_weakness";

    /**
     * 工具调用的命名空间（P4-D08）。
     *
     * <p>⚠️ <b>刻意不与 {@link #NS_RUN_CREATE} 复用</b>：
     * 类注释已说明「命名空间不可省略——不同用途的键若混在一起，
     * {@code run:xxx} 与 {@code tool:xxx} 可能在同一租户内撞值」。
     * 创建 Run 的键落在 {@code run} 表，工具调用的键落在 {@code tool_operation} 表，
     * 两表约束彼此独立，但<b>共用命名空间会让日志与排障无法区分来源</b>，
     * 并违背 ADR-003 的隔离意图。
     */
    public static final String NS_TOOL_INVOKE = "tool.invoke";

    /**
     * 「记录薄弱点」工具名（{@code record_weakness}）。
     *
     * <p>⚠️ <b>只有这个工具才允许使用 {@link #NS_TOOL_RECORD_WEAKNESS} 命名空间</b>。
     * 修复前调用方只看「参数里有没有 {@code studentId} + {@code knowledgePoint}」，
     * 于是<b>任何</b>带这两个参数的工具都被塞进同一个命名空间 ——
     * 两个不同工具算出同一个键，而 {@code tool_operation} 的唯一约束是
     * {@code UNIQUE (tenant_id, idempotency_key)}：
     * <ul>
     *   <li>参数不同 → 第二个工具被判「同键不同参数」→ <b>被拒绝执行</b>；</li>
     *   <li>参数相同 → 第二个工具被判「重放」→ <b>静默不执行</b>。</li>
     * </ul>
     * 两种都是「一个工具的调用把另一个工具的调用吞掉」。
     */
    public static final String RECORD_WEAKNESS_TOOL = "record_weakness";

    /** 默认保留期：24 小时（ADR-003）。 */
    public static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    public IdempotencyKey {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("幂等键命名空间不能为空");
        }
        if (keyValue == null || keyValue.isBlank()) {
            throw new IllegalArgumentException("幂等键值不能为空");
        }
        if (namespace.contains(SEPARATOR)) {
            throw new IllegalArgumentException("命名空间不得包含分隔符 '" + SEPARATOR + "'");
        }
        // 注意：键值<b>允许</b>包含分隔符——复合键（如 record_weakness 的
        // studentId + runId + knowledgePoint）必然要用它拼接各组成部分。
        // 各组成部分的合法性由工厂方法逐个校验，避免拼接后才发现问题。
    }

    /** 创建 Run 的键。 */
    public static @NonNull IdempotencyKey forRunCreate(@NonNull String callerKey) {
        requirePart(callerKey, "callerKey");
        return new IdempotencyKey(NS_RUN_CREATE, callerKey);
    }

    /**
     * {@code record_weakness} 的键。
     *
     * <p>参数取自 DEC-6 白名单（随机 studentId + 薄弱知识点），
     * <b>不含姓名/学校/班级</b>。
     *
     * <p>各组成部分<b>均不得含分隔符</b>，否则拼接后无法唯一分解，
     * 会导致 {@code (a/b, c)} 与 {@code (a, b/c)} 产生相同的键——那将使
     * 不同请求错误地复用同一逻辑操作。
     *
     * @param studentId      随机学生标识（不得为可识别信息）
     * @param runId          所属 Run
     * @param knowledgePoint 薄弱知识点
     */
    public static @NonNull IdempotencyKey forRecordWeakness(@NonNull String studentId,
                                                            @NonNull String runId,
                                                            @NonNull String knowledgePoint) {
        requirePart(studentId, "studentId");
        requirePart(runId, "runId");
        requirePart(knowledgePoint, "knowledgePoint");
        return new IdempotencyKey(NS_TOOL_RECORD_WEAKNESS,
                studentId + SEPARATOR + runId + SEPARATOR + knowledgePoint);
    }

    /**
     * 工具调用的键（P4-D08 · 第十七批增补 Agent 维度）。
     *
     * <p><b>键 = {@code run_id + [agent_ref + '/'] + tool_name + call_id}</b>。
     *
     * <p>⚠️ <b>runId 必须参与</b>：{@code tool_operation} 的唯一约束是
     * {@code UNIQUE (tenant_id, idempotency_key)}——<b>租户级</b>，不含 run 维度。
     * 若键中缺 runId，则<b>同一租户下不同 Run 的同名工具 + 同 callId 会撞键</b>，
     * 而账本命中重复键时**复用既有 operation**（不新建），
     * 于是新 Run 的调用被记到旧 Run 的 operation 上——对账链路与副作用语义同时破裂。
     *
     * <p>⚠️ <b>agent_ref 必须参与（第十七批 · 多级 Agent）</b>：
     * 账本命中重复键时**复用既有 operation、<b>不再次执行工具</b>** ——
     * 而「谁在调用」原本不在键里，于是
     * <b>父 Agent 与子 Agent</b>（或两个兄弟子 Agent）调用
     * <b>同名工具 + 同名 model call id</b>（模型通常都叫 {@code call_1}）时
     * 会算出<b>同一个</b>键 → 后者被当成前者的重放 → <b>那次工具调用根本没发生</b>，
     * 且模型拿到的还是<b>另一个 Agent 的结果</b>。
     * <br>⚠️ {@code agent_ref} 为 {@code null} 表示「单 Agent 路径（无执行作用域）」——
     * 该路径下只有一个 Agent 在跑，不会与谁撞键，**键值保持原样不变**
     * （在途的 {@code tool_operation} 行因此不受影响）。
     *
     * @param runId    所属 Run
     * @param agentRef 当前执行中的 Agent 引用（{@code agentId@version}）；
     *                 {@code null} = 单 Agent 路径
     * @param toolName 工具名
     * @param callId   模型返回的调用标识（缺失时由调用方填占位值）
     */
    public static @NonNull IdempotencyKey forToolInvoke(@NonNull String runId,
                                                         @Nullable String agentRef,
                                                         @NonNull String toolName,
                                                         @NonNull String callId) {
        requirePart(runId, "runId");
        requirePart(toolName, "toolName");
        requirePart(callId, "callId");
        // ⚠️ Agent 段为**可省略**的一段：单 Agent 路径（agentRef == null）不写这一段，
        //    键值与历史完全一致 —— 在途的 tool_operation 行因此不受影响。
        String agentPart = agentRef == null || agentRef.isBlank()
                ? ""
                : requireAgentRef(agentRef) + SEPARATOR;
        return new IdempotencyKey(NS_TOOL_INVOKE,
                runId + SEPARATOR + agentPart + toolName + SEPARATOR + callId);
    }

    /**
     * 渲染「当前执行中的 Agent 引用」（幂等键的 Agent 维度 · 第十七批）。
     *
     * <p>⚠️ <b>格式定义只有这一处</b>：{@code ExecutionScope}（按哪个 release 授权）
     * 与 {@code DelegationContext}（当前 Agent 是谁）都通过本方法渲染 ——
     * 两条链路各自拼一份字符串的话，格式一旦漂移就会撞键，
     * 而撞键的后果是「一次工具调用被静默吞掉」，不是报错。
     *
     * <p>⚠️ <b>版本必须参与</b>：同一个 {@code agent_id} 的不同 release 是
     * <b>不同的执行主体</b>（闭包、工具集都可能不同），键上必须能区分开；
     * 只写 {@code agent_id} 会让「同一 Agent 的新 release」继承旧 release 的幂等记录。
     *
     * @param agentId      当前执行中的 Agent 标识
     * @param agentVersion 当前 Agent 的<b>固定版本号</b>
     * @return Agent 引用（{@code agentId@version}）
     */
    public static @NonNull String agentRefOf(@NonNull String agentId, int agentVersion) {
        requirePart(agentId, "agentId");
        if (agentVersion <= 0) {
            throw new IllegalArgumentException("agentVersion 必须为正数，收到: " + agentVersion);
        }
        return agentId + "@" + agentVersion;
    }

    /**
     * 校验 Agent 引用：非空且不含分隔符。
     *
     * <p>⚠️ 不含分隔符是<b>可分解性</b>要求：{@code (a/b, c)} 与 {@code (a, b/c)}
     * 会产生相同的键 —— 那会让不同 Agent 的调用错误地复用同一逻辑操作。
     */
    private static @NonNull String requireAgentRef(@NonNull String agentRef) {
        requirePart(agentRef, "agentRef");
        return agentRef;
    }

    /**
     * 工具调用的键（<b>全链路唯一的构造入口</b> · P1-9 修复）。
     *
     * <p>⚠️ <b>为什么必须把「按工具分流」收进本类</b>：
     * 调用方（Worker 闸门、CAP 执行流）此前各写一遍分流规则，
     * 而两遍都只看「参数里有没有 {@code studentId} + {@code knowledgePoint}」——
     * 于是<b>任何</b>带这两个参数的工具都被塞进 {@code tool.record_weakness} 命名空间，
     * 两个不同工具算出同一个键：
     * <ul>
     *   <li>参数不同 → 后者被判「同键不同参数」→ <b>被拒绝执行</b>；</li>
     *   <li>参数相同 → 后者被判「重放」→ <b>静默不执行</b>。</li>
     * </ul>
     * 收进本类后，「哪个工具用哪种键形状」只有<b>一处</b>定义。
     *
     * <p>⚠️ <b>只有 {@link #RECORD_WEAKNESS_TOOL} 走 record_weakness 形状</b>：
     * 其余工具一律走 {@link #forToolInvoke}（键里含工具名）。
     *
     * <p>⚠️ <b>record_weakness 的键**不含 Agent 维度</b>（第十七批刻意如此）：
     * 它记的是「某个学生的某个知识点」这条<b>业务事实</b>，
     * 全局<b>唯一</b> —— 父 Agent 与子 Agent 记同一个学生的同一个知识点，
     * 本就该只有一条记录，后写的那次被幂等拦下是<b>正确</b>的。
     * <br>⚠️ 代价：子 Agent 想<b>订正</b>同一知识点（参数相同、语义不同）也会被吞 ——
     * 那是<b>既有</b>的幂等口径（ADR-003），非多级引入 → 登记为缺口，不在本批改。
     *
     * @param runId          所属 Run
     * @param agentRef       当前执行中的 Agent 引用（{@code agentId@version}）；
     *                       {@code null} = 单 Agent 路径
     * @param toolName       工具名
     * @param callId         模型返回的调用标识（可空 → 填占位值）
     * @param studentId      学生标识参数（可空）
     * @param knowledgePoint 知识点参数（可空）
     * @return 幂等键
     */
    public static @NonNull IdempotencyKey forToolCall(@NonNull String runId,
                                                       @Nullable String agentRef,
                                                       @NonNull String toolName,
                                                       @Nullable String callId,
                                                       @Nullable String studentId,
                                                       @Nullable String knowledgePoint) {
        requirePart(runId, "runId");
        requirePart(toolName, "toolName");
        if (RECORD_WEAKNESS_TOOL.equals(toolName)
                && studentId != null && knowledgePoint != null) {
            return forRecordWeakness(studentId, runId, knowledgePoint);
        }
        return forToolInvoke(runId, agentRef, toolName,
                callId == null || callId.isBlank() ? "no-call-id" : callId);
    }

    /** 校验单个组成部分：非空且不含分隔符。 */
    private static void requirePart(@NonNull String part, @NonNull String name) {
        if (part.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        if (part.contains(SEPARATOR)) {
            throw new IllegalArgumentException(
                    name + " 不得包含分隔符 '" + SEPARATOR + "'，否则复合键无法唯一分解: " + part);
        }
    }

    /** 唯一约束比对用的复合形式：{@code namespace:keyValue}。 */
    public @NonNull String composite() {
        return namespace + ":" + keyValue;
    }

    /** @return 该键的过期时刻。 */
    public @NonNull Instant expiresAt(@NonNull Instant createdAt) {
        return createdAt.plus(DEFAULT_RETENTION);
    }

    @Override
    public String toString() {
        return composite();
    }
}