package cn.studykid.multiagent.ports.agentchat;

import java.util.Objects;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.model.ModelRequest;

/**
 * L3 ConsoleChat「直接对话」的<b>请求级 W1/W2 预算保护</b>中性端口（ADR-013 D52 /
 * 开发计划 §4.1 M9）。
 *
 * <p>⚠️ <b>为什么不是 M6/M7 的 {@code RequestAssemblyPort}</b>：那个端口携带
 * {@code tenantId / agentId / agentVersion / fencingToken / cap_agent_def} 等
 * <b>Run 专属身份与冻结策略</b>，而 L3 直连是<b>平台/租户前台管理员对话</b>——无 Agent 定义、
 * 无 Run、无租约、无 checkpoint、无 carry-over（D49④「独立协议、不复用 L2 checkpoint 收发、
 * 不引入会话态」）。G1 A.5 守卫精确禁止 L3 文件出现 {@code RequestAssemblyPort} 令牌。
 * 本端口因此只收一条「已按 legacy 路径构造好的 {@link #legacyRequest()}」+
 * 平台级 W1/W2 裁决所需的中性字段，关闭态原样返回同一引用（逐位等价）。
 *
 * <p>⚠️ <b>只跑 W1/W2，零 LLM</b>：实现必须用 {@code TRIM_ONLY} 策略档（老 TOOL 结果可逆外置 +
 * 整轮裁剪），⛔ 不触发 W3 摘要、不调模型、不接观测链（D49④）。请求体自带的
 * {@code systemPrompt} 按<b>稳定前缀</b>逐字保留，⛔ 不追加 notice / 摘要 / 记忆。
 *
 * <p>⚠️ <b>无会话态</b>：每次 HTTP 请求都从请求体 {@code messages} 全量历史独立装配，
 * 端口不缓存、不跨请求持有状态、不读写 checkpoint。
 *
 * <p>⚠️ <b>关闭等价桥是构造级保证</b>：全局开关 {@code agent.context.enabled=false}
 * 时实现必须<b>短路</b>，直接返回 {@link BudgetResult#passthrough(ModelRequest)} 同一引用，
 * 零策略解析、零预算、零管线、零计数。
 *
 * <p>⚠️ <b>fail-closed</b>：策略 / 预算 / 装配链路异常时抛
 * {@link ConsoleBudgetException}（带对外稳定原因码），调用方必须以明确错误拒绝该请求，
 * ⛔ 绝不静默回退 legacy 让超窗请求直接进入模型（那会「不报错、只是悄悄超窗」）。
 *
 * <p>⚠️ <b>中性端口</b>：签名只使用 {@code agent-ports} 已有类型，不泄漏
 * {@code agent-context} 算法层类型（ADR-011）；实现由组合根提供。
 */
public interface ConsoleBudgetPort {

    /**
     * L3 请求级作用域哨兵：<b>不是租户 id</b>，只用于日志 / 诊断与
     * {@code ContextSpec} 的必填身份占位；不进请求正文、不写 RLS 表。
     */
    String DEFAULT_SCOPE = "console:direct";

    /**
     * 按请求级平台策略装配一次「预算保护后的唯一送模型请求」。
     *
     * @return 唯一送模型的请求；关闭态返回 {@link BudgetResult#passthrough}（同一引用）
     * @throws ConsoleBudgetException 策略 / 预算 / 装配失败（fail-closed）
     */
    @NonNull BudgetResult protect(@NonNull BudgetInput input);

    /**
     * 一次预算保护的中立输入（纯值对象，全参构造唯一，铁律 #10）。
     *
     * <p>⚠️ <b>数值合法性由唯一 adapter 在启用态裁决</b>：本记录只 fail-closed 身份 /
     * 基线引用，⛔ 不在构造期校验 {@code maxOutputTokens}。否则总开关关闭时也会因非法
     * 输出上限抛错，导致「没有压缩功能」与「压缩关闭」行为不等价（[D11]）。
     *
     * @param legacyRequest   调用方按 legacy 路径完整构造的请求：关闭等价基线 +
     *                        非上下文字段（modelId / temperature / maxTokens / timeout /
     *                        toolSchemas / userContent）唯一真源
     * @param scopeKey        请求级作用域键（日志 / 诊断用稳定哨兵；
     *                        <b>不是租户 id</b>，不进请求正文，也绝不写入 RLS 表）
     * @param maxOutputTokens 输出上限（参与总窗口溢出判定；仅启用态校验为正）
     */
    record BudgetInput(
            @NonNull ModelRequest legacyRequest,
            @NonNull String scopeKey,
            int maxOutputTokens) {

        public BudgetInput {
            Objects.requireNonNull(legacyRequest,
                    "ConsoleBudgetPort.BudgetInput.legacyRequest 不得为 null（关闭等价桥基线）");
            scopeKey = scopeKey == null || scopeKey.isBlank()
                    ? DEFAULT_SCOPE : scopeKey;
        }
    }

    /**
     * 预算保护的中立产出（纯值对象，全参构造唯一，铁律 #10）。
     *
     * <p>⚠️ <b>{@link #request()} 是唯一送模型的请求</b>：调用方⛔ 不得再拼 systemPrompt、
     * 不得再替换 messages。
     *
     * <p>⚠️ <b>{@link #budgeted()} 是行为分流的唯一判据</b>，由 adapter 用
     * {@code out.request() != input.legacyRequest()} 的<b>引用身份</b>推导，
     * ⛔ 调用方不复制 enabled / strategy 判定（两份判定漂移会让关闭态偷偷改字节）。
     *
     * @param request      唯一送往模型的请求
     * @param budgeted     预算保护是否真正改写过请求（引用身份判定）
     * @param beforeTokens 保护前全窗口估算；关闭态恒 0
     * @param afterTokens  保护后全窗口估算；关闭态恒 0
     * @param tier         触发档位 0/1/2；关闭态恒 0（L3 只 W1/W2，绝不 W3）
     */
    record BudgetResult(
            @NonNull ModelRequest request,
            boolean budgeted,
            int beforeTokens,
            int afterTokens,
            int tier) {

        public BudgetResult {
            Objects.requireNonNull(request, "ConsoleBudgetPort.BudgetResult.request 不得为 null");
            if (tier < 0 || tier > 2) {
                throw new IllegalArgumentException(
                        "ConsoleBudgetPort.BudgetResult.tier 必须是 0/1/2（L3 不跑 W3/HARD）");
            }
            if (beforeTokens < 0 || afterTokens < 0) {
                throw new IllegalArgumentException(
                        "ConsoleBudgetPort.BudgetResult token 计数不得为负");
            }
            if (!budgeted && (tier != 0 || beforeTokens != 0 || afterTokens != 0)) {
                throw new IllegalArgumentException(
                        "关闭等价态不得携带预算行为数据（tier/tokens）——"
                                + "那会让 enabled=false 多报数字 [D11]");
            }
            if (budgeted && afterTokens <= 0) {
                throw new IllegalArgumentException(
                        "预算保护态 afterTokens 必须为正（全窗口估算为 0 意味着预算口径空转）");
            }
            if (budgeted && afterTokens > beforeTokens) {
                throw new IllegalArgumentException(
                        "预算保护不得反向膨胀（after > before）：W1/W2 若净增 token 属功能失真");
            }
        }

        /** 未装配端口 / 关闭等价桥：request 就是传入同一引用，零行为数据。 */
        public static @NonNull BudgetResult passthrough(@NonNull ModelRequest legacy) {
            return new BudgetResult(legacy, false, 0, 0, 0);
        }
    }
}
