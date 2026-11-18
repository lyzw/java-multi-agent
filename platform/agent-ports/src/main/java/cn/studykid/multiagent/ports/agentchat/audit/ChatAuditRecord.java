package cn.studykid.multiagent.ports.agentchat.audit;

/**
 * 一条待落盘/落库的审计记录（两种形态）。
 *
 * <p><b>⚠️ 为什么合成一个类型</b>：异步写入器只有一条有界队列，队列元素若分成两种类型，
 * 就要么开两条队列（丢弃策略、计数、停机排空全部翻倍），要么用 {@code Object} + 强转
 * （编译期失去穷尽性）。用 sealed 接口 + 两个 record，写入端对 {@code switch} 是
 * <b>编译期穷尽</b>的：将来新增第三种记录形态，编译器会点名所有未处理的分支。
 *
 * <p><b>两种形态的差别是合规性的，不是形式上的</b>：
 * <ul>
 *   <li>{@link ChatAuditEnvelope} —— 请求已解析出身份，含原文（敏感），落
 *       {@code public.chat_audit_record}（租户级 FORCE RLS）；</li>
 *   <li>{@link ChatAuditRejected} —— 建连前就被拒（401/400/429），身份未成立，
 *       <b>只允许元数据</b>，落 {@code public.chat_audit_unattributed}。</li>
 * </ul>
 */
public sealed interface ChatAuditRecord permits ChatAuditEnvelope, ChatAuditRejected {

    /** 审计标识（= 该请求 traceId）。 */
    String auditId();
}
