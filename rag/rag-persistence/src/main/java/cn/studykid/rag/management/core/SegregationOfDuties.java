package cn.studykid.rag.management.core;

import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 审核职责分离校验（V1.0.2 §5 R4 / §6.1 硬规则 / S3-2）。
 *
 * <p><b>⚠️⚠️ 这是治理从「制度」变为「机制」的关键一环</b>：
 * 在 IAM 交付之前，「审核员不得审核自己采集的语料」「VERIFIED 须双人复核」
 * 只能靠流程约定；一旦有人赶进度，这两个约束就形同虚设。
 * 而**自审 = 一次泄题责任不可追溯**，双人复核缺失 = 单人可签发任意语料。
 *
 * <p><b>⚠️ 本类只做判定，不负责取主体</b>：
 * 主体来自令牌（{@code RagJwtAuthFilter} 验签后写入的 {@code sub}），
 * 由控制器传入。这样本类可以在无 Spring 上下文下单测，
 * 也避免「令牌解析逻辑」与「治理规则」纠缠在一起。
 *
 * <p><b>⚠️ 与 D-001 的关系</b>：本类<b>不依赖</b> IAM 的角色定义——
 * 它只要求「两个不同的主体 ID」。IAM 交付后，角色校验在其上叠加，
 * 本类无需改动。
 */
public final class SegregationOfDuties {

    private SegregationOfDuties() {
    }

    /**
     * 主体类型（来自令牌声明 {@code principal_type}）。
     *
     * <p>⚠️ 平台用户与租户用户是两套身份体系，<b>不能互相冒充</b>。
     */
    public enum PrincipalType {
        /** 租户域用户（{@code aud=agent-platform}）。 */
        TENANT_USER,
        /** 平台域用户（{@code aud=admin-console-api}）。 */
        PLATFORM_USER,
        /** 无法识别（缺声明）。 */
        UNKNOWN
    }

    /**
     * 一次签发动作的参与者。
     *
     * @param actorId      本次动作的执行主体（令牌 sub）
     * @param actorType    主体类型
     * @param registeredBy 该来源的登记人（{@code rag_source.registered_by}），可为 null
     * @param verifiedBy   复核主体（第二个人），仅 VERIFIED 时要求
     */
    public record Actors(
            @NonNull String actorId,
            @NonNull PrincipalType actorType,
            @Nullable String registeredBy,
            @Nullable String verifiedBy
    ) {
    }

    /**
     * 校验结果。
     *
     * @param allowed 是否允许
     * @param reason  拒绝原因（允许时为 null）
     */
    public record Decision(boolean allowed, String reason) {
    }

    /**
     * 校验「审核员 ≠ 采集员」+「双人复核」。
     *
     * <p>适用于 <b>VERIFIED</b> 这一步；其余状态（IN_REVIEW / REJECTED / REVOKED）
     * 只需主体非空，不要求双人。
     *
     * @param actors    参与者
     * @param isVerified 目标状态是否为 VERIFIED
     * @return 判定结果
     */
    public static Decision check(@NonNull Actors actors, boolean isVerified) {
        if (actors.actorId() == null || actors.actorId().isBlank()) {
            return new Decision(false, "缺少操作人主体（令牌未提供 sub），拒绝以匿名身份签发");
        }
        if (actors.actorType() == PrincipalType.UNKNOWN) {
            // ⚠️ 缺 principal_type 说明令牌来源不可识别，不应放行治理动作
            return new Decision(false, "令牌缺少 principal_type 声明，无法确认身份域");
        }
        if (!isVerified) {
            // 非 VERIFIED 的流转不要求双人复核
            return new Decision(true, null);
        }

        // ① 审核员 ≠ 采集员
        String registeredBy = actors.registeredBy();
        if (registeredBy != null && !registeredBy.isBlank()
                && registeredBy.equalsIgnoreCase(actors.actorId())) {
            return new Decision(false,
                    "审核员不得审核自己采集的来源（当前主体 " + actors.actorId()
                            + " 即该来源的登记人）。请由其他审核员处理");
        }

        // ② 双人复核：必须给出第二个不同主体
        String verifiedBy = actors.verifiedBy();
        if (verifiedBy == null || verifiedBy.isBlank()) {
            return new Decision(false,
                    "签发 VERIFIED 需要双人复核：缺少复核人主体（verifiedBy）");
        }
        if (verifiedBy.equalsIgnoreCase(actors.actorId())) {
            return new Decision(false,
                    "双人复核要求两个不同主体，当前执行人与复核人为同一人（"
                            + actors.actorId() + "）");
        }
        return new Decision(true, null);
    }

    /**
     * 解析令牌的 {@code principal_type} 声明。
     *
     * @param raw 声明值（如 {@code PLATFORM_USER}）
     * @return 主体类型；无法识别返回 {@link PrincipalType#UNKNOWN}
     */
    public static PrincipalType parsePrincipalType(String raw) {
        if (raw == null || raw.isBlank()) {
            return PrincipalType.UNKNOWN;
        }
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (v.equals("TENANT_USER") || v.equals("TENANT")) {
            return PrincipalType.TENANT_USER;
        }
        if (v.equals("PLATFORM_USER") || v.equals("PLATFORM")) {
            return PrincipalType.PLATFORM_USER;
        }
        return PrincipalType.UNKNOWN;
    }

    /**
     * 治理票据专用角色：<b>只表示「这张票可签发全局语料的审核结论」</b>。
     *
     * <p>⚠️ 与平台侧 {@code PlatformJwtTokenService.RAG_GOVERNANCE_ROLE} <b>同名同义、各存一份</b>：
     * 两个 reactor 之间没有共享编译产物（RAG 不依赖平台 jar），
     * 契约只能靠命名与注释钉住；改一处不改另一处的表现是「票据签出来了，RAG 说不认识」。
     *
     * <p>它不是平台角色（平台授权矩阵里没有这一项），只在这一道闸门里被识别。
     */
    public static final String RAG_GOVERNANCE_ROLE = "RAG_CORPUS_GOVERNANCE";

    /**
     * 角色授权校验（S3-2 的补充项）。
     *
     * <p>⚠️ 当前 RAG 尚无独立的角色枚举，本方法用<b>角色名约定</b>判断：
     * 含 {@code REVIEWER} / {@code AUDITOR} 的角色，或治理票据专用角色
     * （{@link #RAG_GOVERNANCE_ROLE}）方可签发 VERIFIED。
     * IAM 交付后这里替换为正式的权限点校验。
     *
     * <p><b>⚠️⚠️ 为什么必须认票据角色</b>（实测踩到的死锁）：
     * 平台全局档的写动作要求「平台域令牌 + 票据声明 {@code rag_scope}」，
     * 而治理票据按设计<b>不携带持有者真实角色</b>（否则票据就是一枚登录态）。
     * 只认 REAL 角色时会出现：能签出票据的人（{@code PLATFORM_SUPER_ADMIN} /
     * {@code PLATFORM_RESOURCE_ADMIN}）用票据请求 → 角色为空 → 403；
     * 而带真实角色的登录令牌又缺 {@code rag_scope} → 同样 403。
     * <b>两个方向都堵死 = 平台上任何角色都签不出全局语料的 VERIFIED。</b>
     * 平台侧因此改为在票据里签入本角色——它不代表任何平台权限，
     * 只证明「持票者已被平台授权到这一步」。
     *
     * @param roles      令牌 roles 声明
     * @param isVerified 目标状态是否为 VERIFIED
     * @return 判定结果
     */
    public static Decision checkRole(@NonNull Set<String> roles, boolean isVerified) {
        if (!isVerified) {
            return new Decision(true, null);
        }
        // ⚠️ 匹配时必须**去掉下划线/连字符**再比较：
        //    实测平台角色是 PLATFORM_SUPER_ADMIN（有下划线），
        //    而首版规则写的是 SUPERADMIN —— 直接 contains 匹配不上，
        //    导致超管被 403 拒绝（实测踩到）。
        boolean canVerify = roles.stream()
                .filter(r -> r != null)
                .map(r -> r.toUpperCase(java.util.Locale.ROOT).replace("_", "").replace("-", ""))
                .anyMatch(r -> r.contains("REVIEWER") || r.contains("AUDITOR")
                        || r.contains("SUPERADMIN")
                        // 治理票据专用角色（规范化后为 RAGCORPUSGOVERNANCE）
                        || r.contains("RAGCORPUSGOVERNANCE"));
        if (!canVerify) {
            return new Decision(false,
                    "当前角色 " + roles + " 无权签发 VERIFIED（需 REVIEWER / AUDITOR 类角色，"
                            + "或持平台治理票据）");
        }
        return new Decision(true, null);
    }
}
