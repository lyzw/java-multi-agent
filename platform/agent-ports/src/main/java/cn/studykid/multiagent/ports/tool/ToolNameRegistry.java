package cn.studykid.multiagent.ports.tool;

import java.util.Set;

import org.jspecify.annotations.NonNull;

/**
 * 已注册工具名注册表（P7-2 采集口径修正）。
 *
 * <p><b>为什么需要这个端口</b>：指标里的「未知工具数」要判定
 * 「模型请求的工具名是否在平台已定义的工具全集里」。
 * 这个全集有<b>两个来源</b>，各在一侧：
 * <ul>
 *   <li><b>能力目录</b>（{@code public.cap_tool_def.id}）—— 平台级对象，
 *       存 HTTP / MCP / 目录内 native 工具的<b>逻辑 id</b>；由事实端口读取。</li>
 *   <li><b>代码注册表</b>（本端口）—— 进程内 {@link ToolHandler} 实现的
 *       {@link ToolHandler#toolName()}，如 {@code get_student_profile} /
 *       {@code record_weakness}。<b>它们不在 {@code cap_tool_def} 里</b>
 *       （目录里是目录 id，不是代码注册名）。</li>
 * </ul>
 *
 * <p><b>⚠️ 漏掉代码注册表的后果</b>：native 工具的每一次调用都会被判成
 * 「模型幻觉出的工具」→ {@code unknown_tool} 虚高 → 无效调用率失真。
 * 这类错误<b>不报错</b>，只是让看板上的数字悄悄变错——
 * 比采集直接失败更难发现。
 *
 * <p><b>⚠️ 口径边界</b>：本端口只回答「本进程注册了哪些工具名」，
 * <b>不回答</b>「该租户的该 Agent 当前能不能调用它」——
 * 后者是能力闭包（{@code execution_snapshot} 版本树）的职责。
 * 二者不可混用：前者用于「这个名字是否荒谬」，后者用于「这次调用是否合法」。
 */
public interface ToolNameRegistry {

    /**
     * 本进程已注册的工具名全集。
     *
     * @return 工具名集合（不可为 null；无已注册工具时返回空集合）
     */
    @NonNull Set<String> knownToolNames();
}
