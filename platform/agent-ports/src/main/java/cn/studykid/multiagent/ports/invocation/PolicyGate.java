package cn.studykid.multiagent.ports.invocation;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 统一能力调用闸门（PolicyGate）：两条入口共用的唯一裁决点。
 *
 * <p><b>为什么是它</b>：双入口兼容而不泄露的结构性保证——平台直录与 Agent 编排归一为
 * {@link InvocationContext} 后，都经过本闸门用同一套规则裁决。任何"Agent 那条路少了
 * 一道检查"的可能都被消除，因为根本没有第二条判定路径。
 *
 * <p><b>闸门顺序（fail-closed，任一不过即 DENY）</b>：
 * <ol>
 *   <li>能力必须在注册表中（未知能力一律拒，防绕过）；</li>
 *   <li>入口必须在能力 allowedChannels 内；</li>
 *   <li>作用域必须在能力 allowedScopes 内；</li>
 *   <li>ALL 档写恒拒（红线：不出正文）；</li>
 *   <li>作用域非显式（默认租户兜底）时写恒拒（R1：关闭隐式作用域写泄露）；</li>
 *   <li>需要票据的能力必须有票，且票据三元组匹配本次调用（R3：防交叉误用/提权）；</li>
 *   <li>敏感能力在 Agent 编排入口下不得访问平台全局桶（Q1：G 档写只留平台直录）。</li>
 * </ol>
 *
 * <p><b>纯逻辑</b>：不依赖 Spring / DB / 时钟源（now 由调用方注入），可穷举单测；
 * 返回 {@link Decision} 含稳定 reasonCode，调用方归一为 403/400 并写审计。
 */
public final class PolicyGate {

    private final Map<String, Capability> registry;
    private final java.time.Clock clock;

    public PolicyGate(@NonNull Map<String, Capability> registry) {
        this(registry, java.time.Clock.systemUTC());
    }

    public PolicyGate(@NonNull Map<String, Capability> registry, @NonNull Clock clock) {
        this.registry = Objects.requireNonNull(Map.copyOf(registry), "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 裁决结果：允许 / 拒绝 + 稳定原因码（与审计 reason_code 同源）。 */
    public record Decision(boolean allowed, @NonNull String reasonCode) {
        public static final Decision ALLOW = new Decision(true, "ALLOW");
        public static @NonNull Decision deny(@NonNull String code) {
            return new Decision(false, Objects.requireNonNull(code, "code"));
        }
        public boolean denied() {
            return !allowed;
        }
    }

    /** 对一次能力调用做统一裁决。 */
    public @NonNull Decision decide(@NonNull InvocationContext ctx) {
        // 1. 能力必须在册
        Optional<Capability> found = Capability.find(registry, ctx.capability());
        if (found.isEmpty()) {
            return Decision.deny("CAPABILITY_UNKNOWN");
        }
        Capability cap = found.get();

        // 2. 入口白名单
        if (!cap.allowedChannels().contains(ctx.channel())) {
            return Decision.deny("CHANNEL_NOT_ALLOWED");
        }

        // 3. 作用域白名单
        if (!cap.allowedScopes().contains(ctx.scope())) {
            return Decision.deny("SCOPE_NOT_ALLOWED");
        }

        // 4. ALL 档恒只读
        if (ctx.isWrite() && !ctx.scope().allowsWrite()) {
            return Decision.deny("SCOPE_READ_ONLY");
        }

        // 5. 隐式作用域（默认租户兜底）禁止写 —— 关闭 R1
        if (ctx.isWrite() && !ctx.scopeExplicit()) {
            return Decision.deny("SCOPE_IMPLICIT_WRITE_DENIED");
        }

        // 6. 敏感能力 + Agent 编排 + 平台全局桶 → 只留平台直录（Q1）
        if (cap.sensitiveContent()
                && ctx.channel() == Channel.AGENT_ORCH
                && ctx.scope() == Scope.PLATFORM) {
            return Decision.deny("AGENT_GLOBAL_WRITE_DENIED");
        }

        // 7. 票据闸门
        if (cap.requiresTicket()) {
            TicketClaims ticket = ctx.ticket();
            if (ticket == null) {
                return Decision.deny("TICKET_REQUIRED");
            }
            String mismatch = ticket.mismatch(ctx, clock.instant());
            if (mismatch != null) {
                return Decision.deny(mismatch);
            }
        } else if (ctx.ticket() != null) {
            // 不需要票据却带了票：按票的严格声明校验，防止把票当"万能凭证"夹带
            String mismatch = ctx.ticket().mismatch(ctx, clock.instant());
            if (mismatch != null) {
                return Decision.deny(mismatch);
            }
        }

        return Decision.ALLOW;
    }

    /** 便捷裁决：拒绝即抛（供控制器 fail-fast，消息只含 reasonCode 不泄内部）。 */
    public void require(@NonNull InvocationContext ctx) {
        Decision d = decide(ctx);
        if (d.denied()) {
            throw new InvocationDeniedException(d.reasonCode());
        }
    }

    /** 调用闸门拒绝异常：控制器映射 403 + reasonCode 审计。 */
    public static final class InvocationDeniedException extends RuntimeException {
        private final @NonNull String reasonCode;

        public InvocationDeniedException(@NonNull String reasonCode) {
            super("能力调用被拒绝: " + reasonCode);
            this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        }

        public @NonNull String reasonCode() {
            return reasonCode;
        }
    }
}
