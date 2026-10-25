package cn.studykid.multiagent.ports.orchestration;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 委派结果契约提供端口（多级 Agent v1.1 · P2-MA-04 · FR-027）。
 *
 * <p>⚠️ <b>委派结果必须按契约校验后才算成功</b>（MA-T16）：
 * 子 Agent 的输出不符合声明契约时，<b>不得</b>作为成功结果交给父 Agent ——
 * 父 Agent 会把它当成可信数据继续推理，错误就此扩散。
 *
 * <p>⚠️ <b>「取不到契约」与「契约为空」不是一回事</b>：
 * 前者（{@code null}）表示契约引用无法解析 → 调用方必须 fail-closed 拒绝；
 * 后者表示契约存在但允许任意结构 → 只能做「不可信输出」处理，不能当结构校验通过。
 */
public interface ResultContractProvider {

    /**
     * 解析契约引用对应的输出 JSON Schema。
     *
     * @param tenantId    租户标识
     * @param contractRef 固定版本契约引用（{@code id@version}）
     * @return 输出 JSON Schema 文本；<b>无法解析时返回 {@code null}</b>（调用方 fail-closed）
     */
    @Nullable String schemaFor(@NonNull String tenantId, @NonNull String contractRef);
}
