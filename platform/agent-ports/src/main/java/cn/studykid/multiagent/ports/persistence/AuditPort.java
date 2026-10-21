package cn.studykid.multiagent.ports.persistence;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 审计写入端口（P3-D07 / R-1 / R-2）。
 *
 * <p><b>为什么存在</b>：P1-D08 §3.3 的 Repudiation（抵赖）三项残余风险中，
 * R-1（无法追溯审批决定）被判为「<b>高</b>」——{@code approval} / {@code audit_record}
 * 表早已建好，<b>但零写入逻辑</b>。本端口把「关键动作留痕」做活。
 *
 * <p><b>⚠️ 两类审计，两种失败语义</b>（D-1）：
 * <ul>
 *   <li><b>关键审计</b>（{@link #recordRequired}）—— 授权类动作（审批授予/撤销/消费、
 *       取消）。失败<b>抛异常中止主流程</b>（fail-closed）：
 *       无审计的授权等于「谁都能批准且无痕迹」，正是 R-1 要防的场景；</li>
 *   <li><b>访问日志</b>（{@link #recordAccess}）—— 读学生画像等。
 *       失败<b>只告警不抛</b>（fail-open）：读日志丢失降低举证能力，
 *       但阻断用户读数据得不偿失，且会被 DoS 放大为拒绝服务。</li>
 * </ul>
 *
 * <p><b>⚠️ detail 是白名单，不是黑名单</b>（D-3）：序列化前跑
 * {@code DataClassification.IdentifyingPattern} 检测，命中身份证/手机/学号/邮箱
 * 即<b>拒绝写入</b>。P2-D05 的 SSE 载荷已因「保留标量透传」被证伪过一次
 * （{@code studentName} / {@code idCard} 都是字符串标量会原样输出）——
 * 审计「拒写」比「写了敏感信息」安全（PIPL）。
 */
public interface AuditPort {

    /** actor_id / action / resource_type 的长度上限（防日志膨胀与超长注入）。 */
    int MAX_FIELD_LENGTH = 200;

    /**
     * 关键审计：失败抛异常（fail-closed）。
     *
     * @param tenantId    租户（RLS 绑定依据，须与 DB 上下文一致）
     * @param actorId     主体（用户 ID / 系统身份 + runId 等可追溯标识）
     * @param action      动作（如 {@code APPROVAL_GRANT}）
     * @param resourceType 资源类型（如 {@code approval}）
     * @param resourceId  资源 ID（可空，如 Run 级别动作）
     * @param detail      明细（<b>写入前检测可识别信息，命中即拒绝</b>）
     */
    void recordRequired(@NonNull String tenantId,
                        @NonNull String actorId,
                        @NonNull String action,
                        @NonNull String resourceType,
                        @Nullable String resourceId,
                        @NonNull Map<String, Object> detail);

    /**
     * 访问日志：失败只告警不抛（fail-open + 可观测）。
     *
     * <p>用于「读学生画像」等高危读路径（R-2）。RLS 只管「能不能看」，
     * 本方法补上「看了什么」。
     *
     * @see #recordRequired 的参数说明
     */
    void recordAccess(@NonNull String tenantId,
                      @NonNull String actorId,
                      @NonNull String action,
                      @NonNull String resourceType,
                      @Nullable String resourceId,
                      @NonNull Map<String, Object> detail);

    /** 审计动作常量（集中定义，避免各处拼写漂移）。 */
    final class Actions {

        private Actions() {
        }

        public static final String APPROVAL_GRANT = "APPROVAL_GRANT";
        public static final String APPROVAL_REVOKE = "APPROVAL_REVOKE";
        public static final String APPROVAL_CONSUME = "APPROVAL_CONSUME";
        public static final String RUN_CANCEL = "RUN_CANCEL";
        public static final String PROFILE_READ = "PROFILE_READ";

        /**
         * 读取审计记录（P4-D13 / G10）。
         *
         * <p>⚠️ <b>与授权动作 {@code audit:read} 同名但不是同一概念</b>：
         * 这里是<b>审计动作字面量</b>（写入 {@code audit_record.action} 列的值），
         * 授权那边是 {@code AuthorizationService.Actions.AUDIT_READ}。
         * 两者碰巧同名，但一个是「被记录的行为」，一个是「检查权限的动作」。
         */
        public static final String AUDIT_READ = "AUDIT_READ";

        /**
         * 人工处置 UNKNOWN 操作（P4-D13 / G9）。
         *
         * <p>⚠️ 与 {@link #APPROVAL_REVOKE} 不同：处置<b>不是撤销</b>，
         * 它把一个「副作用未知」的操作归档为终态，不回退任何状态。
         */
        public static final String UNKNOWN_DISPOSE = "UNKNOWN_DISPOSE";

        /** Agent 创建（P4-D14 / G2 / P18）。 */
        public static final String AGENT_CREATE = "AGENT_CREATE";

        /** Agent 版本发布（P4-D14 / G2 / P18）。定义不可变快照。 */
        public static final String AGENT_PUBLISH = "AGENT_PUBLISH";

        /** 查看配置（P4-D14 / C6 / P19）。fail-open 访问日志。 */
        public static final String CONFIG_VIEW = "CONFIG_VIEW";

        /**
         * 配置变更（P4-D15 / C3+C4 / P15~P17）。
         *
         * <p>⚠️ <b>写入与清除共用同一动作码</b>：靠 {@code detail.tightening}
         * 区分（写入时为方向，清除时为 {@code CLEARED}）。
         * 不分成两个码是因为查询侧按「配置发生了什么变更」聚合，
         * 两个码会让前端多写一次合并逻辑。
         *
         * <p>⚠️ <b>fail-closed</b>：配置变更走 {@link #recordRequired}，
         * 审计写不进去就<b>不生效</b>——无声的高危变更比变更失败危险得多。
         */
        public static final String CONFIG_UPDATE = "CONFIG_UPDATE";
    }

    /** 审计视图（查询用）。 */
    record AuditView(String auditId,
                     String actorId,
                     String action,
                     String resourceType,
                     @Nullable String resourceId,
                     java.time.Instant createdAt) {
    }

    /**
     * 按资源查询审计（对账/追责用）。
     *
     * @return 该资源的相关审计（按时间升序）
     */
    @NonNull List<AuditView> findByResource(@NonNull String tenantId,
                                                     @NonNull String resourceType,
                                                     @Nullable String resourceId);
}