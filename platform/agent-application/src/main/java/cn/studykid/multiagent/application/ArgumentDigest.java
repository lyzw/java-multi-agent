package cn.studykid.multiagent.application;

import java.util.Map;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.application.cap.ArgsNormalizer;

/**
 * 调用参数摘要（M0-3 · G1/G2 共用）。
 *
 * <p><b>为什么存在</b>：闸门（{@code ToolInvocationGate}）与 CAP 审批链路
 * （{@code ArgsNormalizer}）此前用两套口径计算「参数是什么」——
 * 前者用 {@code String.valueOf(call.arguments())}（依赖 {@code HashMap} 遍历顺序、
 * 并把<b>参数原文</b>送进审批台，属学生信息泄漏面），
 * 后者用 {@code ArgsNormalizer.hash}（稳定、与 CAP 同源）。
 * 二者不一致会导致「审批绑定的参数」与「实际执行的参数」算出两个值，
 * 防篡改/防重放形同虚设。
 *
 * <p><b>本类是唯一的参数摘要入口</b>：闸门与 CAP 都调它，
 * 保证全链路同源（设计文档 §06「数据模型变更」与 M0-3）。
 *
 * <p>⚠️ <b>只存摘要、不存原文</b>：摘要由 {@link ArgsNormalizer#hash} 产出
 * （{@code args_norm_v1:} + SHA-256 小写 hex），审批行 {@code argument_digest}
 * 只保留摘要，绝不保留参数明文——合规红线不变。
 *
 * <p>⚠️ <b>稳定</b>：同参同摘要（字典序规范化），顺序不同的 {@code Map} 同摘要，
 * 数字与字符串不同摘要（§5.3.2）。
 */
public final class ArgumentDigest {

    private ArgumentDigest() {
    }

    /**
     * 计算参数摘要。
     *
     * @param arguments 调用参数（{@code null} 视为空参数集）
     * @return {@code args_norm_v1:} 前缀 + SHA-256 小写 hex；与 CAP 同源
     */
    public static @NonNull String of(@NonNull Map<String, Object> arguments) {
        return ArgsNormalizer.hash(arguments, ArgsNormalizer.Options.all());
    }
}
