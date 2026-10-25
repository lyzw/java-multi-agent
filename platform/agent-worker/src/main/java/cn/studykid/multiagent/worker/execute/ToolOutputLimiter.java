package cn.studykid.multiagent.worker.execute;

import org.jspecify.annotations.NonNull;

/**
 * 工具输出的<b>回灌长度封顶</b>。
 *
 * <p>⚠️ <b>为什么必须封顶</b>：工具输出会被送进模型上下文。
 * 一个返回上万行记录的查询工具若原样回灌，会直接顶爆上下文窗口 ——
 * 那时候模型看到的不是「结果」，而是「一堆噪声 + 报错」，
 * 而且失败原因（上下文超限）在日志里长得像供应商故障，极难排查。
 *
 * <p>⚠️ <b>截断必须留痕</b>：悄悄截断会让模型以为「工具只返回了这些」，
 * 从而基于残缺数据给出**看起来正常**的错误结论。
 * 故截断处显式标注「已截断 + 原始长度」。
 */
public final class ToolOutputLimiter {

    /** 默认上限（字符）。 */
    public static final int DEFAULT_MAX_CHARS = 8_000;

    private ToolOutputLimiter() {
    }

    /**
     * 按上限截断工具输出。
     *
     * @param output   工具原始输出
     * @param maxChars 上限（字符数）
     * @return 截断后的输出（超限时带「已截断」标注）
     */
    public static @NonNull String limit(@NonNull String output, int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("上限必须为正数，收到: " + maxChars);
        }
        if (output.length() <= maxChars) {
            return output;
        }
        return output.substring(0, maxChars)
                + "\n…（输出已截断：原始长度 " + output.length() + " 字符，仅保留前 "
                + maxChars + " 字符）";
    }

    /**
     * 按默认上限截断。
     *
     * @param output 工具原始输出
     * @return 截断后的输出
     */
    public static @NonNull String limit(@NonNull String output) {
        return limit(output, DEFAULT_MAX_CHARS);
    }
}
